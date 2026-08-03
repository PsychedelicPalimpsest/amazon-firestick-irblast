"""TV power state detectors (non-IR). Never invent state from our own IR blasts."""

from __future__ import annotations

import logging
import re
from typing import Any, Protocol

from .adb import FireStickAdb
from .const import (
    STATE_ASSUMED,
    STATE_DEVICECONTROL,
    STATE_ENTITY,
    STATE_HDMI,
    STATE_PING,
    STATE_STICK_AWAKE,
)

_LOGGER = logging.getLogger(__name__)


class TvStateDetector(Protocol):
    async def async_is_on(self) -> bool | None:
        """Return True/False, or None if unknown."""


class AssumedStateDetector:
    """Explicit last-command tracker only — never used as a silent fallback."""

    def __init__(self) -> None:
        self._on: bool | None = None

    def note_on(self) -> None:
        self._on = True

    def note_off(self) -> None:
        self._on = False

    def note_toggle(self) -> None:
        if self._on is None:
            self._on = True
        else:
            self._on = not self._on

    async def async_is_on(self) -> bool | None:
        return self._on


class DeviceControlStateDetector:
    """TV screen/power via DeviceControl AIDL getScreenState (HPD + fused providers)."""

    def __init__(self, agent: Any) -> None:
        self.agent = agent

    async def async_is_on(self) -> bool | None:
        data = await self.agent.tv_state()
        val = data.get("is_on")
        if val is True:
            return True
        if val is False:
            return False
        return None


class StickAwakeDetector:
    """Fire Stick awake/screen_on — what the androidtv HA entity/preview tracks.

    This is NOT the external TV power. Exposed so it is not confused with TV state.
    """

    def __init__(self, adb: FireStickAdb) -> None:
        self.adb = adb

    async def async_is_on(self) -> bool | None:
        text = await self.adb.shell(
            "dumpsys power 2>/dev/null | grep -E 'mWakefulness=|Display Power: state='"
        )
        if re.search(r"mWakefulness=Asleep|Display Power: state=OFF", text):
            return False
        if re.search(r"mWakefulness=Awake|Display Power: state=ON", text):
            return True
        win = await self.adb.shell("dumpsys window 2>/dev/null | grep mScreenOnFully")
        if "mScreenOnFully=true" in win:
            return True
        if "mScreenOnFully=false" in win:
            return False
        return None


class HdmiLinkDetector:
    """Read-only HDMI HPD / sink props on the Stick (never sends CEC)."""

    def __init__(self, adb: FireStickAdb) -> None:
        self.adb = adb

    async def async_is_on(self) -> bool | None:
        for path in (
            "/sys/class/amhdmitx/amhdmitx0/hpd_state",
            "/sys/devices/virtual/amhdmitx/amhdmitx0/hpd_state",
            "/sys/class/drm/card0-HDMI-A-1/status",
        ):
            out = (await self.adb.shell(f"cat {path} 2>/dev/null")).strip().lower()
            if out in ("1", "connected"):
                return True
            if out in ("0", "disconnected"):
                return False

        blobs: list[str] = []
        for cmd in (
            "dumpsys hdmi_cec 2>/dev/null",
            "dumpsys display 2>/dev/null | grep -iE 'hpd|hdmi|hotplug|connect' | head -n 40",
            "getprop sys.hdmi_cec.sink_power",
            "getprop vendor.hdmi.hotplug",
        ):
            try:
                blobs.append(await self.adb.shell(cmd))
            except Exception:  # noqa: BLE001
                continue
        text = "\n".join(blobs)

        m = re.search(r"hpdStatus\s*[=:]\s*(\d+)", text, re.I)
        if m:
            return m.group(1) != "0"
        if re.search(r"hotplug\s*[:=]\s*(1|true|yes|connected)", text, re.I):
            return True
        if re.search(r"hotplug\s*[:=]\s*(0|false|no|disconnected)", text, re.I):
            return False
        if re.search(r"DEVICE_POWERSTATE_ON", text, re.I):
            return True
        if re.search(r"DEVICE_POWERSTATE_STANDBY|DEVICE_POWERSTATE_OFF", text, re.I):
            return False
        if re.search(r"\bhpd\s*[:=]\s*(true|1|yes)\b", text, re.I):
            return True
        if re.search(r"\bhpd\s*[:=]\s*(false|0|no)\b", text, re.I):
            return False

        prop = (await self.adb.shell("getprop sys.hdmi_cec.sink_power")).strip().lower()
        if prop in ("on", "1", "true"):
            return True
        if prop in ("off", "standby", "0", "false"):
            return False

        _LOGGER.debug("HDMI link state unknown")
        return None


class PingDetector:
    """ICMP reachability of the TV's LAN IP (from the Stick)."""

    def __init__(self, adb: FireStickAdb, tv_ip: str) -> None:
        self.adb = adb
        self.tv_ip = tv_ip

    async def async_is_on(self) -> bool | None:
        out = await self.adb.shell(
            f"ping -c 1 -W 1 {self.tv_ip} >/dev/null 2>&1; echo EXIT:$?"
        )
        if "EXIT:0" in out:
            return True
        if "EXIT:" in out:
            return False
        return None


class HaEntityDetector:
    """Follow another Home Assistant entity's on/off state."""

    def __init__(self, hass: Any, entity_id: str) -> None:
        self.hass = hass
        self.entity_id = entity_id

    async def async_is_on(self) -> bool | None:
        state = self.hass.states.get(self.entity_id)
        if state is None:
            return None
        val = state.state
        if val in ("on", "home", "open", "playing"):
            return True
        if val in ("off", "away", "closed", "idle", "standby", "unavailable", "unknown"):
            return False
        try:
            return float(val) > 5.0
        except (TypeError, ValueError):
            return None


def build_detector(
    kind: str,
    *,
    adb: FireStickAdb,
    hass: Any | None = None,
    agent: Any | None = None,
    tv_ip: str | None = None,
    entity_id: str | None = None,
    assumed: AssumedStateDetector | None = None,
) -> TvStateDetector:
    if kind == STATE_DEVICECONTROL:
        if agent is None:
            raise ValueError("agent required for devicecontrol state source")
        return DeviceControlStateDetector(agent)
    if kind == STATE_STICK_AWAKE:
        return StickAwakeDetector(adb)
    if kind == STATE_HDMI:
        return HdmiLinkDetector(adb)
    if kind == STATE_PING:
        if not tv_ip:
            raise ValueError("tv_ip required for ping state source")
        return PingDetector(adb, tv_ip)
    if kind == STATE_ENTITY:
        if not hass or not entity_id:
            raise ValueError("hass + state_entity required")
        return HaEntityDetector(hass, entity_id)
    if kind == STATE_ASSUMED:
        return assumed or AssumedStateDetector()
    # Default: DeviceControl if agent available, else HDMI read-only
    if agent is not None:
        return DeviceControlStateDetector(agent)
    return HdmiLinkDetector(adb)
