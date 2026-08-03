"""Curated IR function lists and remote command aliases."""

from __future__ import annotations

from typing import Any

# HA / voice-remote style aliases → codeset function names
COMMAND_ALIASES: dict[str, str] = {
    "power": "POWER_TOGGLE",
    "power_on": "POWER_ON",
    "power_off": "POWER_OFF",
    "power_toggle": "POWER_TOGGLE",
    "vol_up": "VOLUME_UP",
    "vol_down": "VOLUME_DOWN",
    "volume_up": "VOLUME_UP",
    "volume_down": "VOLUME_DOWN",
    "mute": "MUTE",
    "volume_mute": "VOLUME_MUTE",
    "channel_up": "CHANNEL_UP",
    "channel_down": "CHANNEL_DOWN",
    "ch_up": "CHANNEL_UP",
    "ch_down": "CHANNEL_DOWN",
    "up": "UP",
    "down": "DOWN",
    "left": "LEFT",
    "right": "RIGHT",
    "ok": "OK",
    "select": "OK",
    "enter": "OK",
    "back": "BACK",
    "exit": "EXIT",
    "home": "HOME",
    "menu": "MENU",
    "guide": "GUIDE",
    "info": "INFO",
}

# Extra buttons for the select dropdown (after HDMI/INPUT sources)
_SELECT_EXTRA_PRIORITY: tuple[str, ...] = (
    "MUTE",
    "VOLUME_MUTE",
    "MENU",
    "GUIDE",
    "INFO",
    "EXIT",
    "BACK",
    "OK",
    "UP",
    "DOWN",
    "LEFT",
    "RIGHT",
    "CC",
    "CLOSED_CAPTION",
    "CAPTION",
    "ASPECT",
    "PICTURE_MODE",
    "SLEEP",
    "INPUT",
)


def _source_keys(codes: dict[str, Any]) -> list[str]:
    return sorted(
        k for k in codes if k.startswith("HDMI_") or k.startswith("INPUT_")
    )


def remote_activity_list(codes: dict[str, Any]) -> list[str]:
    """HDMI / INPUT sources for remote.turn_on(activity=…)."""
    return _source_keys(codes)


def select_option_list(codes: dict[str, Any]) -> list[str]:
    """Reduced dropdown: sources + a few common extras (not the full codeset)."""
    opts = _source_keys(codes)
    for fn in _SELECT_EXTRA_PRIORITY:
        if fn in codes and fn not in opts:
            opts.append(fn)
    return opts


def resolve_command(command: str, codes: dict[str, Any]) -> str | None:
    """Map a remote.send_command string to a codeset function, or None if unknown."""
    raw = (command or "").strip()
    if not raw:
        return None
    if raw in codes:
        return raw
    upper = raw.upper()
    if upper in codes:
        return upper
    alias = COMMAND_ALIASES.get(raw.lower())
    if alias and alias in codes:
        return alias
    for key in codes:
        if key.lower() == raw.lower():
            return key
    return None


def all_functions(codes: dict[str, Any]) -> list[str]:
    return sorted(codes.keys())
