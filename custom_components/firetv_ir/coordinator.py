"""Data update coordinator for Fire TV IR."""

from __future__ import annotations

import asyncio
import logging
from datetime import timedelta
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .adb import AdbError, FireStickAdb
from .agent import AgentError, StickAgent
from .const import (
    CONF_BLAST_COUNT,
    CONF_CODES,
    CONF_DUTY_CYCLE,
    CONF_POWER_MODE,
    CONF_REMOTE_MAC,
    CONF_STATE_ENTITY,
    CONF_STATE_SOURCE,
    CONF_TV_IP,
    DOMAIN,
    POWER_MODE_TOGGLE_STATE,
    STATE_ASSUMED,
    STATE_DEVICECONTROL,
)
from .power import resolve_toggle, resolve_turn_off, resolve_turn_on
from .state import AssumedStateDetector, build_detector

_LOGGER = logging.getLogger(__name__)


class FireTvIrCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Hold ADB/agent, profile codes, and TV on/off state."""

    def __init__(
        self,
        hass: HomeAssistant,
        entry: ConfigEntry,
        adb: FireStickAdb,
        agent: StickAgent,
    ) -> None:
        super().__init__(
            hass,
            _LOGGER,
            name=DOMAIN,
            update_interval=timedelta(seconds=15),
        )
        self.entry = entry
        self.adb = adb
        self.agent = agent
        self._blast_lock = asyncio.Lock()
        self.assumed = AssumedStateDetector()
        self._rebuild_detector()

    @property
    def remote_mac(self) -> str:
        return self.entry.data[CONF_REMOTE_MAC]

    @property
    def codes(self) -> dict[str, Any]:
        return self.entry.data.get(CONF_CODES) or {}

    @property
    def power_mode(self) -> str:
        return self.entry.options.get(
            CONF_POWER_MODE, self.entry.data.get(CONF_POWER_MODE, POWER_MODE_TOGGLE_STATE)
        )

    @property
    def state_source(self) -> str:
        return self.entry.options.get(
            CONF_STATE_SOURCE,
            self.entry.data.get(CONF_STATE_SOURCE, STATE_DEVICECONTROL),
        )

    @property
    def duty_cycle(self) -> int:
        return int(self.entry.data.get(CONF_DUTY_CYCLE, 33))

    @property
    def blast_count(self) -> int:
        return int(self.entry.data.get(CONF_BLAST_COUNT, 1))

    def _rebuild_detector(self) -> None:
        self.detector = build_detector(
            self.state_source,
            adb=self.adb,
            hass=self.hass,
            agent=self.agent,
            tv_ip=self.entry.options.get(CONF_TV_IP, self.entry.data.get(CONF_TV_IP)) or None,
            entity_id=self.entry.options.get(
                CONF_STATE_ENTITY, self.entry.data.get(CONF_STATE_ENTITY)
            )
            or None,
            assumed=self.assumed,
        )

    def apply_options(self) -> None:
        """Rebuild detectors after options flow."""
        self._rebuild_detector()

    async def _async_update_data(self) -> dict[str, Any]:
        is_on: bool | None = None
        try:
            is_on = await self.detector.async_is_on()
        except Exception as exc:  # noqa: BLE001
            _LOGGER.warning("TV state read failed: %s", exc)
            if self.data:
                is_on = self.data.get("is_on")
        return {"is_on": is_on, "functions": sorted(self.codes.keys())}

    async def async_send_function(self, function: str) -> None:
        async with self._blast_lock:
            try:
                await self.agent.blast_function(
                    self.remote_mac,
                    self.codes,
                    function,
                    duty_cycle=self.duty_cycle,
                    blast_count=self.blast_count,
                )
            except (AgentError, AdbError) as exc:
                raise UpdateFailed(str(exc)) from exc

        # Only track last-command when the user explicitly chose "assumed"
        if self.state_source == STATE_ASSUMED:
            if function == "POWER_ON":
                self.assumed.note_on()
            elif function == "POWER_OFF":
                self.assumed.note_off()
            elif function == "POWER_TOGGLE":
                self.assumed.note_toggle()
        await self.async_request_refresh()

    def _is_on(self) -> bool | None:
        if self.data is None:
            return None
        return self.data.get("is_on")

    async def async_turn_on(self) -> None:
        fn = resolve_turn_on(self.power_mode, self.codes, self._is_on())
        if fn:
            await self.async_send_function(fn)

    async def async_turn_off(self) -> None:
        fn = resolve_turn_off(self.power_mode, self.codes, self._is_on())
        if fn:
            await self.async_send_function(fn)

    async def async_toggle(self) -> None:
        fn = resolve_toggle(self.power_mode, self.codes, self._is_on())
        if fn:
            await self.async_send_function(fn)

    async def async_volume_up(self) -> None:
        await self.async_send_function("VOLUME_UP")

    async def async_volume_down(self) -> None:
        await self.async_send_function("VOLUME_DOWN")

    async def async_mute(self) -> None:
        for fn in ("MUTE", "VOLUME_MUTE", "MUTE_TOGGLE"):
            if fn in self.codes:
                await self.async_send_function(fn)
                return
        raise UpdateFailed("No MUTE / VOLUME_MUTE / MUTE_TOGGLE in profile")

    async def async_select_source(self, source: str) -> None:
        await self.async_send_function(source)

    async def async_refresh_profile(self) -> None:
        """Re-fetch codeset from IDC via Stick and update entry data."""
        codeset_id = self.entry.data.get("codeset_id")
        if not codeset_id:
            raise UpdateFailed("No codeset_id stored")
        details = await self.agent.profile_details([codeset_id])
        if not details:
            raise UpdateFailed("IDC returned empty profile")
        d0 = details[0]
        new_data = {
            **self.entry.data,
            CONF_CODES: d0.get("codes") or {},
            CONF_DUTY_CYCLE: int(d0.get("dutyCycle", 33)),
            CONF_BLAST_COUNT: int(d0.get("blastCount", 1)),
        }
        self.hass.config_entries.async_update_entry(self.entry, data=new_data)
        await self.async_request_refresh()
