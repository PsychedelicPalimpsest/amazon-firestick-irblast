"""Pronto hex → LuckyL InstantFire JSON."""

from __future__ import annotations

import math
from typing import Any

FREQUENCY_COEFFICIENT = 0.241246
HUNDRED_THOUSAND = 100_000.0


def _from_hex(s: str) -> int:
    return int(s, 16)


def pronto_to_raw(pronto: str) -> tuple[int, list[int]]:
    parts = pronto.strip().split()
    if len(parts) < 5:
        raise ValueError(f"Pronto too short: {pronto!r}")
    freq = int(1_000_000.0 / (_from_hex(parts[1]) * FREQUENCY_COEFFICIENT))
    pulses: list[int] = []
    for p in parts[4:]:
        micros = int(math.floor((_from_hex(p) * HUNDRED_THOUSAND) / freq))
        pulses.append(micros if micros != 0 else 1)
    return freq, pulses


def build_instant_fire(
    pronto: str,
    *,
    duty_cycle: int = 33,
    blast_count: int = 1,
    post_delay_ms: int = 120,
    reason: str = "HA",
) -> dict[str, Any]:
    freq, pulses = pronto_to_raw(pronto)
    packed = "".join(f"{p}s" for p in pulses)
    return {
        "enableSDS": False,
        "InstantFire": [
            {
                "CommandType": "IR",
                "RepeatType": "BASIC",
                "IRCode": [packed],
                "Frequency": freq,
                "DutyCycle": duty_cycle,
                "Repeat": max(blast_count - 1, 0),
                "ToggleBitMask": 0,
                "PostDelay": int(post_delay_ms),
            }
        ],
        "Context": {"Reason": reason},
    }
