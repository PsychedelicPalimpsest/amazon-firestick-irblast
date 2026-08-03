"""Fire TV IR — HA entrypoints load lazily so ADB/agent helpers work without HA installed."""

from __future__ import annotations

from typing import Any

from .const import DOMAIN

__all__ = ["DOMAIN", "async_setup_entry", "async_unload_entry", "CONFIG_SCHEMA"]


def __getattr__(name: str) -> Any:
    if name in ("async_setup_entry", "async_unload_entry"):
        from . import hass_init

        return getattr(hass_init, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
