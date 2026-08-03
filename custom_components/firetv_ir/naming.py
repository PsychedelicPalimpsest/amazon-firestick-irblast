"""Friendly device titles — Amazon codesets are often named 'Model Group N'."""

from __future__ import annotations

import re
from typing import Any

# IDC often ships opaque labels like "Model Group 2" instead of a real model.
_GENERIC_PROFILE = re.compile(
    r"(?i)^(?:model\s*group|code\s*set|codeset|profile)\s*#?\s*\d+$"
)


def is_generic_profile_name(name: str | None) -> bool:
    return bool(name and _GENERIC_PROFILE.match(str(name).strip()))


def device_title(
    *,
    brand: str | None = None,
    profile_name: str | None = None,
    codeset_id: int | str | None = None,
    friendly_name: str | None = None,
) -> str:
    """Title for the HA config entry / device registry."""
    if friendly_name and str(friendly_name).strip():
        return str(friendly_name).strip()
    brand_s = (brand or "").strip()
    profile_s = (profile_name or "").strip()
    if is_generic_profile_name(profile_s):
        if brand_s:
            return f"{brand_s} TV"
        if codeset_id not in (None, ""):
            return f"TV #{codeset_id}"
        return "TV"
    if brand_s and profile_s:
        if profile_s.lower().startswith(brand_s.lower()):
            return profile_s
        return f"{brand_s} {profile_s}".strip()
    return brand_s or profile_s or (f"TV #{codeset_id}" if codeset_id not in (None, "") else "TV")


def profile_option_label(profile: dict[str, Any], brand: str | None = None) -> str:
    """Label in the config-flow profile picker."""
    pid = profile.get("codeset_id") or profile.get("profile_id")
    name = profile.get("name") or ""
    badge = "discrete" if profile.get("has_discrete_power") else "toggle"
    nfn = len(profile.get("functions") or [])
    prefix = f"{brand} · " if brand else ""
    if is_generic_profile_name(name):
        return f"{prefix}#{pid} ({nfn} fn, {badge})"
    return f"{prefix}#{pid} {name} ({nfn} fn, {badge})"
