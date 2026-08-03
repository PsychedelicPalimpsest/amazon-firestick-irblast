"""Shared power-mode → IR function resolution (HA coordinator + CLI)."""

from __future__ import annotations

from typing import Any

from .const import (
    POWER_MODE_DISCRETE,
    POWER_MODE_TOGGLE_ONLY,
)


def resolve_turn_on(
    mode: str, codes: dict[str, Any], is_on: bool | None
) -> str | None:
    """Return function to blast for turn_on, or None if already on / unknown / no-op.

    toggle_with_state: only blast when state is known *off*. Unknown (None) is a no-op
    so we never POWER_TOGGLE a TV that is already on.
    """
    if mode == POWER_MODE_TOGGLE_ONLY:
        return "POWER_TOGGLE" if "POWER_TOGGLE" in codes else None

    # Already on → never blast (including discrete POWER_ON)
    if is_on is True:
        return None

    if mode == POWER_MODE_DISCRETE and "POWER_ON" in codes:
        # Unknown state: still send discrete ON (safe / idempotent)
        return "POWER_ON"

    # toggle_with_state (or discrete without POWER_ON)
    if is_on is None:
        # Unknown — do not risk POWER_TOGGLE on an already-on TV
        return None
    # is_on is False
    if "POWER_TOGGLE" in codes:
        return "POWER_TOGGLE"
    if "POWER_ON" in codes:
        return "POWER_ON"
    return None


def resolve_turn_off(
    mode: str, codes: dict[str, Any], is_on: bool | None
) -> str | None:
    """Return function to blast for turn_off, or None if already off / unknown / no-op."""
    if mode == POWER_MODE_TOGGLE_ONLY:
        return "POWER_TOGGLE" if "POWER_TOGGLE" in codes else None

    if is_on is False:
        return None

    if mode == POWER_MODE_DISCRETE and "POWER_OFF" in codes:
        return "POWER_OFF"

    if is_on is None:
        return None
    # is_on is True
    if "POWER_TOGGLE" in codes:
        return "POWER_TOGGLE"
    if "POWER_OFF" in codes:
        return "POWER_OFF"
    return None


def resolve_toggle(mode: str, codes: dict[str, Any], is_on: bool | None) -> str | None:
    if mode == POWER_MODE_TOGGLE_ONLY:
        return "POWER_TOGGLE" if "POWER_TOGGLE" in codes else None
    if is_on is True:
        return resolve_turn_off(mode, codes, is_on)
    if is_on is False:
        return resolve_turn_on(mode, codes, is_on)
    # Unknown state: explicit toggle still fires POWER_TOGGLE
    return "POWER_TOGGLE" if "POWER_TOGGLE" in codes else None


def default_power_mode(codes: dict[str, Any]) -> str:
    """Prefer discrete when the codeset has ON/OFF."""
    if "POWER_ON" in codes and "POWER_OFF" in codes:
        return POWER_MODE_DISCRETE
    return "toggle_with_state"
