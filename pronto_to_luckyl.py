#!/usr/bin/env python3
"""Pronto hex → LuckyL InstantFire JSON for ftvir blast."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

FREQUENCY_COEFFICIENT = 0.241246
HUNDRED_THOUSAND = 100_000.0


def from_hex(s: str) -> int:
    return int(s, 16)


def calculate_frequency(pronto_freq_word: int) -> int:
    return int(1_000_000.0 / (pronto_freq_word * FREQUENCY_COEFFICIENT))


def get_raw_code(pronto_word: str, frequency_hz: int) -> int:
    micros = int(math.floor((from_hex(pronto_word) * HUNDRED_THOUSAND) / frequency_hz))
    return micros if micros != 0 else 1


def pronto_to_raw(pronto: str) -> tuple[int, list[int]]:
    parts = pronto.strip().split()
    if len(parts) < 5:
        raise ValueError(f"Pronto too short: {pronto!r}")
    freq = calculate_frequency(from_hex(parts[1]))
    return freq, [get_raw_code(p, freq) for p in parts[4:]]


def build_instant_fire(
    pronto: str,
    *,
    duty_cycle: int = 33,
    blast_count: int = 1,
    repeat_type: str = "BASIC",
    toggle_bit_mask: int = 0,
    post_delay_ms: int = 120,
    reason: str = "ADB",
) -> dict:
    freq, pulses = pronto_to_raw(pronto)
    return {
        "enableSDS": False,
        "InstantFire": [
            {
                "CommandType": "IR",
                "RepeatType": repeat_type,
                "IRCode": ["".join(f"{p}s" for p in pulses)],
                "Frequency": freq,
                "DutyCycle": duty_cycle,
                "Repeat": max(blast_count - 1, 0),
                "ToggleBitMask": toggle_bit_mask,
                "PostDelay": int(post_delay_ms),
            }
        ],
        "Context": {"Reason": reason},
    }


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--pronto", required=True, help="Pronto hex string")
    p.add_argument("--duty", type=int, default=33)
    p.add_argument("--blast", type=int, default=1)
    p.add_argument("--toggle", type=int, default=0)
    p.add_argument("--pretty", action="store_true")
    args = p.parse_args(argv)
    payload = build_instant_fire(
        args.pronto,
        duty_cycle=args.duty,
        blast_count=args.blast,
        repeat_type="TOGGLE" if args.toggle > 0 else "BASIC",
        toggle_bit_mask=args.toggle,
    )
    print(json.dumps(payload, indent=2 if args.pretty else None, separators=None if args.pretty else (",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
