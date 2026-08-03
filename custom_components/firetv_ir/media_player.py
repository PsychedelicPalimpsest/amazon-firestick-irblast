"""TV media_player entity driven by IR + state detector."""

from __future__ import annotations

from homeassistant.components.media_player import (
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.update_coordinator import CoordinatorEntity

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
    async_add_entities([FireTvIrMediaPlayer(coordinator, entry)])


class FireTvIrMediaPlayer(CoordinatorEntity[FireTvIrCoordinator], MediaPlayerEntity):
    _attr_device_class = MediaPlayerDeviceClass.TV
    _attr_has_entity_name = True
    _attr_name = "TV"

    def __init__(self, coordinator: FireTvIrCoordinator, entry: ConfigEntry) -> None:
        super().__init__(coordinator)
        self._entry = entry
        self._attr_unique_id = f"{entry.entry_id}_tv"
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
        codes = coordinator.codes
        features = MediaPlayerEntityFeature.TURN_ON | MediaPlayerEntityFeature.TURN_OFF
        if "VOLUME_UP" in codes or "VOLUME_DOWN" in codes:
            features |= MediaPlayerEntityFeature.VOLUME_STEP
        if "MUTE" in codes or "VOLUME_MUTE" in codes:
            features |= MediaPlayerEntityFeature.VOLUME_MUTE
        sources = [k for k in codes if k.startswith("HDMI_") or k.startswith("INPUT_")]
        if sources:
            features |= MediaPlayerEntityFeature.SELECT_SOURCE
            self._attr_source_list = sorted(sources)
        self._attr_supported_features = features

    @property
    def state(self) -> MediaPlayerState | None:
        data = self.coordinator.data or {}
        on = data.get("is_on")
        if on is True:
            return MediaPlayerState.ON
        if on is False:
            return MediaPlayerState.OFF
        return None

    async def async_turn_on(self) -> None:
        await self.coordinator.async_turn_on()

    async def async_turn_off(self) -> None:
        await self.coordinator.async_turn_off()

    async def async_toggle(self) -> None:
        await self.coordinator.async_toggle()

    async def async_volume_up(self) -> None:
        await self.coordinator.async_volume_up()

    async def async_volume_down(self) -> None:
        await self.coordinator.async_volume_down()

    async def async_mute_volume(self, mute: bool) -> None:
        await self.coordinator.async_mute()

    async def async_select_source(self, source: str) -> None:
        await self.coordinator.async_select_source(source)
