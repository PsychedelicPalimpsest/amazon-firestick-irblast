"""Home Assistant setup entrypoints (imported lazily from __init__)."""

from __future__ import annotations

import logging

import voluptuous as vol
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import Platform
from homeassistant.core import HomeAssistant, ServiceCall
from homeassistant.exceptions import ConfigEntryNotReady, HomeAssistantError
from homeassistant.helpers import config_validation as cv, device_registry as dr

from .adb import AdbError, FireStickAdb
from .agent import StickAgent
from .const import (
    CONF_ADB_SERVER_IP,
    CONF_ADB_SERVER_PORT,
    CONF_ADBKEY,
    CONF_HOST,
    CONF_PORT,
    CONF_REGION,
    DEFAULT_ADB_SERVER_PORT,
    DEFAULT_PORT,
    DEFAULT_REGION,
    DOMAIN,
)
from .coordinator import FireTvIrCoordinator

_LOGGER = logging.getLogger(__name__)

PLATFORMS = [Platform.MEDIA_PLAYER, Platform.REMOTE]


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up Fire TV IR from a config entry."""
    hass.data.setdefault(DOMAIN, {})
    data = entry.data

    adb = FireStickAdb(
        data[CONF_HOST],
        data.get(CONF_PORT, DEFAULT_PORT),
        adbkey=data.get(CONF_ADBKEY),
        adb_server_ip=data.get(CONF_ADB_SERVER_IP),
        adb_server_port=data.get(CONF_ADB_SERVER_PORT, DEFAULT_ADB_SERVER_PORT),
    )
    try:
        await adb.connect()
    except AdbError as exc:
        raise ConfigEntryNotReady(str(exc)) from exc

    agent = StickAgent(adb, region=data.get(CONF_REGION, DEFAULT_REGION))
    ok, msg = await agent.check_helper()
    if not ok:
        await adb.close()
        raise ConfigEntryNotReady(msg)

    coordinator = FireTvIrCoordinator(hass, entry, adb, agent)
    await coordinator.async_config_entry_first_refresh()

    hass.data[DOMAIN][entry.entry_id] = coordinator

    device_registry = dr.async_get(hass)
    device_registry.async_get_or_create(
        config_entry_id=entry.entry_id,
        identifiers={(DOMAIN, f"{entry.entry_id}_stick")},
        name=f"Fire Stick ({data[CONF_HOST]})",
        manufacturer="Amazon",
        model="Fire TV",
        configuration_url=f"http://{data[CONF_HOST]}",
    )

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_async_update_listener))

    _register_services(hass)
    return True


def _register_services(hass: HomeAssistant) -> None:
    if hass.services.has_service(DOMAIN, "send_function"):
        return

    async def handle_send_function(call: ServiceCall) -> None:
        function = call.data["function"]
        entry_id = call.data.get("entry_id")
        coords = (
            [hass.data[DOMAIN][entry_id]]
            if entry_id
            else list(hass.data[DOMAIN].values())
        )
        if not coords:
            raise HomeAssistantError("No Fire TV IR entries loaded")
        for coord in coords:
            await coord.async_send_function(function)

    async def handle_refresh_profile(call: ServiceCall) -> None:
        entry_id = call.data["entry_id"]
        coord = hass.data[DOMAIN].get(entry_id)
        if not coord:
            raise HomeAssistantError(f"Unknown entry_id: {entry_id}")
        await coord.async_refresh_profile()

    hass.services.async_register(
        DOMAIN,
        "send_function",
        handle_send_function,
        schema=vol.Schema(
            {
                vol.Required("function"): cv.string,
                vol.Optional("entity_id"): cv.entity_ids,
                vol.Optional("entry_id"): cv.string,
            }
        ),
    )
    hass.services.async_register(
        DOMAIN,
        "refresh_profile",
        handle_refresh_profile,
        schema=vol.Schema({vol.Required("entry_id"): cv.string}),
    )


async def _async_update_listener(hass: HomeAssistant, entry: ConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        coordinator: FireTvIrCoordinator = hass.data[DOMAIN].pop(entry.entry_id)
        await coordinator.adb.close()
        if not hass.data[DOMAIN]:
            hass.services.async_remove(DOMAIN, "send_function")
            hass.services.async_remove(DOMAIN, "refresh_profile")
    return unload_ok
