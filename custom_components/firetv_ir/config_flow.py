"""Config flow for Fire TV IR (androidtv-style ADB + profile picker)."""

from __future__ import annotations

import logging
from typing import Any

import voluptuous as vol
from homeassistant import config_entries
from homeassistant.core import callback
from homeassistant.data_entry_flow import FlowResult
from homeassistant.helpers import selector

from .adb import AdbError, FireStickAdb
from .agent import AgentError, StickAgent
from .const import (
    CONF_ADB_SERVER_IP,
    CONF_ADB_SERVER_PORT,
    CONF_ADBKEY,
    CONF_BLAST_COUNT,
    CONF_BRAND_NAME,
    CONF_CODES,
    CONF_CODESET_ID,
    CONF_DEVICE_NAME,
    CONF_DUTY_CYCLE,
    CONF_HOST,
    CONF_PORT,
    CONF_POWER_MODE,
    CONF_PROFILE_ID,
    CONF_PROFILE_NAME,
    CONF_REGION,
    CONF_REMOTE_MAC,
    CONF_STATE_ENTITY,
    CONF_STATE_SOURCE,
    CONF_TV_IP,
    DEFAULT_ADB_SERVER_PORT,
    DEFAULT_PORT,
    DEFAULT_REGION,
    DOMAIN,
    POWER_MODE_DISCRETE,
    POWER_MODE_TOGGLE_ONLY,
    POWER_MODE_TOGGLE_STATE,
    STATE_ASSUMED,
    STATE_DEVICECONTROL,
    STATE_ENTITY,
    STATE_HDMI,
    STATE_PING,
    STATE_STICK_AWAKE,
)

from .naming import device_title, profile_option_label

_LOGGER = logging.getLogger(__name__)


class FireTvIrConfigFlow(config_entries.ConfigFlow, domain=DOMAIN):
    """Handle a config flow."""

    VERSION = 1

    def __init__(self) -> None:
        self._adb: FireStickAdb | None = None
        self._agent: StickAgent | None = None
        self._data: dict[str, Any] = {}
        self._brands: list[dict[str, Any]] = []
        self._profiles: list[dict[str, Any]] = []
        self._remotes: list[dict[str, Any]] = []

    async def async_step_user(self, user_input: dict[str, Any] | None = None) -> FlowResult:
        errors: dict[str, str] = {}
        if user_input is not None:
            adb = FireStickAdb(
                user_input[CONF_HOST],
                user_input.get(CONF_PORT, DEFAULT_PORT),
                adbkey=user_input.get(CONF_ADBKEY) or None,
                adb_server_ip=user_input.get(CONF_ADB_SERVER_IP) or None,
                adb_server_port=user_input.get(CONF_ADB_SERVER_PORT, DEFAULT_ADB_SERVER_PORT),
            )
            try:
                await adb.connect()
                agent = StickAgent(
                    adb, region=user_input.get(CONF_REGION, DEFAULT_REGION)
                )
                ok, msg = await agent.check_helper()
                if not ok:
                    errors["base"] = "helper_missing"
                    _LOGGER.error(msg)
                else:
                    self._adb = adb
                    self._agent = agent
                    self._data.update(user_input)
                    await self.async_set_unique_id(f"{user_input[CONF_HOST]}:{user_input.get(CONF_PORT, DEFAULT_PORT)}")
                    self._abort_if_unique_id_configured()
                    return await self.async_step_remote()
            except (AdbError, AgentError) as exc:
                _LOGGER.exception("ADB connect failed: %s", exc)
                errors["base"] = "cannot_connect"

        schema = vol.Schema(
            {
                vol.Required(CONF_HOST): str,
                vol.Required(CONF_PORT, default=DEFAULT_PORT): int,
                vol.Optional(CONF_ADBKEY): str,
                vol.Optional(CONF_ADB_SERVER_IP): str,
                vol.Optional(CONF_ADB_SERVER_PORT, default=DEFAULT_ADB_SERVER_PORT): int,
                vol.Required(CONF_REGION, default=DEFAULT_REGION): vol.In(["na", "eu", "fe"]),
            }
        )
        return self.async_show_form(step_id="user", data_schema=schema, errors=errors)

    async def async_step_remote(self, user_input: dict[str, Any] | None = None) -> FlowResult:
        assert self._agent
        errors: dict[str, str] = {}
        if user_input is not None:
            self._data[CONF_REMOTE_MAC] = user_input[CONF_REMOTE_MAC]
            return await self.async_step_brand()

        try:
            self._remotes = await self._agent.list_remotes()
        except AgentError as exc:
            errors["base"] = "remotes_failed"
            _LOGGER.error("remotes: %s", exc)
            self._remotes = []

        options = {
            r["address"]: f"{r.get('name') or 'remote'} ({r['address']})"
            for r in self._remotes
        }
        # Prefer likely Fire remotes as default
        default = None
        for r in self._remotes:
            if r.get("likely_fire_remote"):
                default = r["address"]
                break
        if default is None and self._remotes:
            default = self._remotes[0]["address"]

        if not options:
            options = {"manual": "Enter MAC manually below"}
            schema = vol.Schema(
                {
                    vol.Required(CONF_REMOTE_MAC): str,
                }
            )
        else:
            schema = vol.Schema(
                {
                    vol.Required(CONF_REMOTE_MAC, default=default): vol.In(options),
                }
            )
        return self.async_show_form(step_id="remote", data_schema=schema, errors=errors)

    async def async_step_brand(self, user_input: dict[str, Any] | None = None) -> FlowResult:
        assert self._agent
        errors: dict[str, str] = {}
        if user_input is not None:
            query = (user_input.get("brand_search") or "").strip().lower()
            brand_id = user_input.get("brand_id")
            if brand_id:
                self._data["_brand_id"] = int(brand_id)
                # resolve name
                for b in self._brands:
                    if int(b["id"]) == int(brand_id):
                        self._data[CONF_BRAND_NAME] = b["name"]
                        break
                return await self.async_step_profile()
            if query:
                try:
                    if not self._brands:
                        self._brands = await self._agent.brands()
                except AgentError as exc:
                    errors["base"] = "idc_failed"
                    _LOGGER.error("brands: %s", exc)
                matches = [b for b in self._brands if query in b["name"].lower()]
                if not matches:
                    errors["base"] = "no_brand"
                elif len(matches) == 1:
                    self._data["_brand_id"] = int(matches[0]["id"])
                    self._data[CONF_BRAND_NAME] = matches[0]["name"]
                    return await self.async_step_profile()
                else:
                    self._data["_brand_matches"] = matches
                    return await self.async_step_brand_pick()

        # Prefetch brands for nicer UX (may fail without MAP)
        if not self._brands:
            try:
                self._brands = await self._agent.brands()
            except AgentError as exc:
                _LOGGER.warning("Could not prefetch brands: %s", exc)

        schema = vol.Schema(
            {
                vol.Optional("brand_search"): str,
                vol.Optional("brand_id"): str,
            }
        )
        return self.async_show_form(
            step_id="brand",
            data_schema=schema,
            errors=errors,
            description_placeholders={
                "hint": f"{len(self._brands)} brands loaded" if self._brands else "Search brand (e.g. Vizio)"
            },
        )

    async def async_step_brand_pick(self, user_input: dict[str, Any] | None = None) -> FlowResult:
        matches = self._data.get("_brand_matches") or []
        options = {str(b["id"]): b["name"] for b in matches}
        if user_input is not None:
            bid = user_input["brand_id"]
            self._data["_brand_id"] = int(bid)
            self._data[CONF_BRAND_NAME] = options.get(bid, bid)
            return await self.async_step_profile()
        return self.async_show_form(
            step_id="brand_pick",
            data_schema=vol.Schema({vol.Required("brand_id"): vol.In(options)}),
        )

    async def async_step_profile(self, user_input: dict[str, Any] | None = None) -> FlowResult:
        assert self._agent
        errors: dict[str, str] = {}
        brand_id = self._data.get("_brand_id")
        if brand_id and not self._profiles:
            try:
                self._profiles = await self._agent.profiles_for_brand(brand_id)
            except AgentError as exc:
                _LOGGER.warning("profiles list failed (use known codeset id): %s", exc)

        if user_input is not None:
            raw = user_input.get("known_codeset_id") or user_input.get("profile_id")
            try:
                pid = int(str(raw).lstrip("#"))
            except (TypeError, ValueError):
                errors["base"] = "idc_failed"
                pid = -1
            if pid >= 0 and user_input.get("test_blast"):
                try:
                    await self._test_profile(pid)
                except Exception as exc:  # noqa: BLE001
                    errors["base"] = "test_failed"
                    _LOGGER.error("test blast: %s", exc)
            if pid >= 0 and user_input.get("confirm") and not errors:
                try:
                    await self._store_profile(pid)
                except AgentError as exc:
                    errors["base"] = "idc_failed"
                    _LOGGER.error("store profile: %s", exc)
                else:
                    return await self.async_step_power()

        brand = self._data.get(CONF_BRAND_NAME)
        options = {}
        for p in self._profiles:
            pid = p.get("profile_id") or p.get("codeset_id")
            if pid is None or int(pid) < 0:
                continue
            options[str(pid)] = profile_option_label(p, brand)

        fields: dict[Any, Any] = {}
        if options:
            fields[vol.Optional("profile_id")] = vol.In(options)
        fields[vol.Optional("known_codeset_id")] = str
        fields[vol.Optional("test_blast", default=False)] = bool
        fields[vol.Optional("confirm", default=False)] = bool
        return self.async_show_form(
            step_id="profile", data_schema=vol.Schema(fields), errors=errors
        )

    async def _test_profile(self, profile_id: int) -> None:
        assert self._agent
        # Resolve codeset id from summary list
        codeset_id = profile_id
        for p in self._profiles:
            if int(p.get("profile_id") or -1) == profile_id or int(p.get("codeset_id") or -1) == profile_id:
                codeset_id = int(p.get("codeset_id") or profile_id)
                break
        details = await self._agent.profile_details([codeset_id])
        if not details:
            raise AgentError("Empty profile details from IDC")
        codes = details[0].get("codes") or {}
        fn = "POWER_ON" if "POWER_ON" in codes else "POWER_TOGGLE"
        if fn not in codes:
            raise AgentError("Profile has no POWER_ON/POWER_TOGGLE")
        await self._agent.blast_function(
            self._data[CONF_REMOTE_MAC],
            codes,
            fn,
            duty_cycle=int(details[0].get("dutyCycle", 33)),
            blast_count=int(details[0].get("blastCount", 1)),
        )

    async def _store_profile(self, profile_id: int) -> None:
        assert self._agent
        codeset_id = profile_id
        name = f"#{profile_id}"
        for p in self._profiles:
            if int(p.get("profile_id") or -1) == profile_id or int(p.get("codeset_id") or -1) == profile_id:
                codeset_id = int(p.get("codeset_id") or profile_id)
                name = p.get("name") or name
                break
        details = await self._agent.profile_details([codeset_id])
        if not details:
            raise AgentError("Could not download profile codeset")
        d0 = details[0]
        self._data[CONF_PROFILE_ID] = int(d0.get("profile_id") or profile_id)
        self._data[CONF_CODESET_ID] = int(d0.get("codeset_id") or codeset_id)
        self._data[CONF_PROFILE_NAME] = d0.get("name") or name
        self._data[CONF_CODES] = d0.get("codes") or {}
        self._data[CONF_DUTY_CYCLE] = int(d0.get("dutyCycle", 33))
        self._data[CONF_BLAST_COUNT] = int(d0.get("blastCount", 1))
        if d0.get("brand"):
            self._data[CONF_BRAND_NAME] = d0["brand"]

    async def async_step_power(self, user_input: dict[str, Any] | None = None) -> FlowResult:
        if user_input is not None:
            self._data[CONF_POWER_MODE] = user_input[CONF_POWER_MODE]
            self._data[CONF_STATE_SOURCE] = user_input[CONF_STATE_SOURCE]
            if user_input.get(CONF_TV_IP):
                self._data[CONF_TV_IP] = user_input[CONF_TV_IP]
            if user_input.get(CONF_STATE_ENTITY):
                self._data[CONF_STATE_ENTITY] = user_input[CONF_STATE_ENTITY]
            custom = (user_input.get(CONF_DEVICE_NAME) or "").strip()
            if custom:
                self._data[CONF_DEVICE_NAME] = custom
            return await self._async_create()

        has_discrete = "POWER_ON" in (self._data.get(CONF_CODES) or {}) and "POWER_OFF" in (
            self._data.get(CONF_CODES) or {}
        )
        default_mode = POWER_MODE_DISCRETE if has_discrete else POWER_MODE_TOGGLE_STATE
        suggested = device_title(
            brand=self._data.get(CONF_BRAND_NAME),
            profile_name=self._data.get(CONF_PROFILE_NAME),
            codeset_id=self._data.get(CONF_CODESET_ID),
        )
        schema = vol.Schema(
            {
                vol.Optional(CONF_DEVICE_NAME, default=suggested): str,
                vol.Required(CONF_POWER_MODE, default=default_mode): vol.In(
                    {
                        POWER_MODE_DISCRETE: "Discrete POWER_ON/OFF (fallback toggle)",
                        POWER_MODE_TOGGLE_STATE: "POWER_TOGGLE gated by TV state",
                        POWER_MODE_TOGGLE_ONLY: "Always POWER_TOGGLE",
                    }
                ),
                vol.Required(CONF_STATE_SOURCE, default=STATE_DEVICECONTROL): vol.In(
                    {
                        STATE_DEVICECONTROL: "DeviceControl TV screen state (recommended)",
                        STATE_PING: "Ping TV IP",
                        STATE_HDMI: "HDMI HPD on Stick (often unavailable)",
                        STATE_ENTITY: "Another HA entity",
                        STATE_STICK_AWAKE: "Stick awake only (androidtv preview; NOT TV)",
                        STATE_ASSUMED: "Assumed state (last command — unreliable)",
                    }
                ),
                vol.Optional(CONF_TV_IP): str,
                vol.Optional(CONF_STATE_ENTITY): selector.EntitySelector(),
            }
        )
        return self.async_show_form(step_id="power", data_schema=schema)

    async def _async_create(self) -> FlowResult:
        # Drop ephemeral keys
        data = {k: v for k, v in self._data.items() if not k.startswith("_")}
        title = device_title(
            brand=data.get(CONF_BRAND_NAME),
            profile_name=data.get(CONF_PROFILE_NAME),
            codeset_id=data.get(CONF_CODESET_ID),
            friendly_name=data.get(CONF_DEVICE_NAME),
        )
        if self._adb:
            await self._adb.close()
        return self.async_create_entry(title=title or "Fire TV IR", data=data)

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: config_entries.ConfigEntry):
        return FireTvIrOptionsFlow(config_entry)


class FireTvIrOptionsFlow(config_entries.OptionsFlow):
    def __init__(self, entry: config_entries.ConfigEntry) -> None:
        self.entry = entry

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> FlowResult:
        if user_input is not None:
            return self.async_create_entry(title="", data=user_input)
        data = {**self.entry.data, **self.entry.options}
        suggested = data.get(CONF_DEVICE_NAME) or device_title(
            brand=data.get(CONF_BRAND_NAME),
            profile_name=data.get(CONF_PROFILE_NAME),
            codeset_id=data.get(CONF_CODESET_ID),
        )
        schema = vol.Schema(
            {
                vol.Optional(CONF_DEVICE_NAME, default=suggested): str,
                vol.Required(
                    CONF_POWER_MODE, default=data.get(CONF_POWER_MODE, POWER_MODE_TOGGLE_STATE)
                ): vol.In(
                    [POWER_MODE_DISCRETE, POWER_MODE_TOGGLE_STATE, POWER_MODE_TOGGLE_ONLY]
                ),
                vol.Required(
                    CONF_STATE_SOURCE,
                    default=data.get(CONF_STATE_SOURCE, STATE_DEVICECONTROL),
                ): vol.In(
                    [
                        STATE_DEVICECONTROL,
                        STATE_PING,
                        STATE_HDMI,
                        STATE_ENTITY,
                        STATE_STICK_AWAKE,
                        STATE_ASSUMED,
                    ]
                ),
                vol.Optional(CONF_TV_IP, default=data.get(CONF_TV_IP, "")): str,
                vol.Optional(CONF_STATE_ENTITY, default=data.get(CONF_STATE_ENTITY, "")): str,
            }
        )
        return self.async_show_form(step_id="init", data_schema=schema)
