"""Remote entity exposing all cached IR functions."""

from __future__ import annotations

from collections.abc import Iterable

from homeassistant.components.remote import RemoteEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .const import CONF_BRAND_NAME, CONF_PROFILE_NAME, DOMAIN
from .coordinator import FireTvIrCoordinator


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    coordinator: FireTvIrCoordinator = hass.data[DOMAIN][entry.entry_id]
    async_add_entities([FireTvIrRemote(coordinator, entry)])


class FireTvIrRemote(CoordinatorEntity[FireTvIrCoordinator], RemoteEntity):
    _attr_has_entity_name = True
    _attr_name = "TV Remote"
    _attr_is_on = True

    def __init__(self, coordinator: FireTvIrCoordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._entry = entry
        self._attr_unique_id = f"{entry.entry_id}_remote"
        brand = entry.data.get(CONF_BRAND_NAME, "TV")
        profile = entry.data.get(CONF_PROFILE_NAME, "")
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            name=f"{brand} {profile}".strip(),
            manufacturer=brand,
            model=profile or "IR TV",
            via_device=(DOMAIN, f"{entry.entry_id}_stick"),
        )
        self._attr_activity_list = sorted(coordinator.codes.keys())

    @property
    def current_activity(self) -> str | None:
        return None

    async def async_send_command(self, command: Iterable[str], **kwargs) -> None:
        for cmd in command:
            await self.coordinator.async_send_function(cmd)
