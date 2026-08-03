"""Remote entity — power, sources, and send_command with HA-standard actions."""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from homeassistant.components.remote import RemoteEntity, RemoteEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .buttons import all_functions, remote_activity_list, resolve_command
from .const import (
    CONF_BRAND_NAME,
    CONF_CODESET_ID,
    CONF_DEVICE_NAME,
    CONF_PROFILE_NAME,
    DOMAIN,
)
from .coordinator import FireTvIrCoordinator
from .naming import device_title


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

    def __init__(self, coordinator: FireTvIrCoordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._entry = entry
        self._attr_unique_id = f"{entry.entry_id}_remote"
        data = {**entry.data, **entry.options}
        brand = data.get(CONF_BRAND_NAME) or "TV"
        profile = data.get(CONF_PROFILE_NAME) or ""
        title = device_title(
            brand=brand,
            profile_name=profile,
            codeset_id=data.get(CONF_CODESET_ID),
            friendly_name=data.get(CONF_DEVICE_NAME),
        )
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            name=title,
            manufacturer=brand,
            model=profile or "IR TV",
            via_device=(DOMAIN, f"{entry.entry_id}_stick"),
        )
        activities = remote_activity_list(coordinator.codes)
        self._attr_supported_features = (
            RemoteEntityFeature.ACTIVITY if activities else RemoteEntityFeature(0)
        )
        self._attr_current_activity: str | None = None

    @property
    def is_on(self) -> bool | None:
        data = self.coordinator.data or {}
        on = data.get("is_on")
        if on is True:
            return True
        if on is False:
            return False
        return None

    @property
    def activity_list(self) -> list[str] | None:
        activities = remote_activity_list(self.coordinator.codes)
        return activities or None

    @property
    def current_activity(self) -> str | None:
        return self._attr_current_activity

    @property
    def extra_state_attributes(self) -> dict[str, list[str]]:
        codes = self.coordinator.codes
        return {
            "activity_list": remote_activity_list(codes),
            "functions": all_functions(codes),
        }

    async def async_turn_on(self, activity: str | None = None, **kwargs: Any) -> None:
        if activity:
            fn = resolve_command(activity, self.coordinator.codes)
            if not fn:
                raise ServiceValidationError(f"Unknown activity/command: {activity}")
            await self.coordinator.async_send_function(fn)
            self._attr_current_activity = fn
            self.async_write_ha_state()
            return
        await self.coordinator.async_turn_on()

    async def async_turn_off(self, activity: str | None = None, **kwargs: Any) -> None:
        await self.coordinator.async_turn_off()

    async def async_toggle(self, activity: str | None = None, **kwargs: Any) -> None:
        await self.coordinator.async_toggle()

    async def async_send_command(self, command: Iterable[str], **kwargs: Any) -> None:
        codes = self.coordinator.codes
        for cmd in command:
            fn = resolve_command(str(cmd), codes)
            if not fn:
                raise ServiceValidationError(
                    f"Unknown remote command: {cmd!r}. "
                    f"Use a codeset name or alias (e.g. volume_up, HDMI_4). "
                    f"See attributes.functions for the full list."
                )
            await self.coordinator.async_send_function(fn)
