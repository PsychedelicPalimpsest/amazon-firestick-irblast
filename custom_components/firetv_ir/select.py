"""Select entity — reduced dropdown of common IR buttons (not the full codeset)."""

from __future__ import annotations

from homeassistant.components.select import SelectEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .buttons import all_functions, select_option_list
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
    if not select_option_list(coordinator.codes):
        return
    async_add_entities([FireTvIrButtonSelect(coordinator, entry)])


class FireTvIrButtonSelect(CoordinatorEntity[FireTvIrCoordinator], SelectEntity):
    """Pick a common codeset function; selecting it blasts that IR button."""

    _attr_has_entity_name = True
    _attr_name = "IR button"
    _attr_icon = "mdi:remote"
    _attr_translation_key = "ir_button"

    def __init__(self, coordinator: FireTvIrCoordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._entry = entry
        self._attr_unique_id = f"{entry.entry_id}_ir_button"
        self._current: str | None = None
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

    @property
    def options(self) -> list[str]:
        return select_option_list(self.coordinator.codes)

    @property
    def extra_state_attributes(self) -> dict[str, list[str]]:
        return {"all_functions": all_functions(self.coordinator.codes)}

    @property
    def current_option(self) -> str | None:
        opts = self.options
        if not opts:
            return None
        if self._current and self._current in opts:
            return self._current
        return opts[0]

    async def async_select_option(self, option: str) -> None:
        if option not in self.coordinator.codes:
            raise ValueError(f"Unknown IR function: {option}")
        await self.coordinator.async_send_function(option)
        self._current = option
        self.async_write_ha_state()
