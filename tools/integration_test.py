#!/usr/bin/env python3
"""End-to-end Python tests for firetv_ir (no Home Assistant runtime)."""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "custom_components"))

from firetv_ir.adb import FireStickAdb  # noqa: E402
from firetv_ir.agent import StickAgent  # noqa: E402
from firetv_ir.const import (  # noqa: E402
    POWER_MODE_DISCRETE,
    POWER_MODE_TOGGLE_ONLY,
    POWER_MODE_TOGGLE_STATE,
)
from firetv_ir.buttons import (  # noqa: E402
    remote_activity_list,
    resolve_command,
    select_option_list,
)
from firetv_ir.naming import device_title, is_generic_profile_name  # noqa: E402
from firetv_ir.power import resolve_turn_off, resolve_turn_on  # noqa: E402
from firetv_ir.pronto import build_instant_fire, pronto_to_raw  # noqa: E402
from firetv_ir.state import (  # noqa: E402
    AssumedStateDetector,
    HdmiLinkDetector,
    build_detector,
)

PASS = 0
FAIL = 0


def ok(name: str, detail: str = "") -> None:
    global PASS
    PASS += 1
    extra = f" — {detail}" if detail else ""
    print(f"  PASS  {name}{extra}")


def bad(name: str, detail: str) -> None:
    global FAIL
    FAIL += 1
    print(f"  FAIL  {name} — {detail}")


def test_pronto() -> None:
    print("\n== pronto ==")
    # Minimal valid Pronto (freq + one pulse pair)
    sample = "0000 006D 0000 0002 0015 0040 0015 06C3"
    try:
        freq, pulses = pronto_to_raw(sample)
        if freq <= 0 or not pulses:
            bad("pronto_to_raw", f"freq={freq} pulses={pulses}")
        else:
            ok("pronto_to_raw", f"freq={freq} n={len(pulses)}")
        payload = build_instant_fire(sample, duty_cycle=33, blast_count=1)
        ir = payload["InstantFire"][0]
        assert ir["CommandType"] == "IR"
        assert ir["Frequency"] == freq
        assert "s" in ir["IRCode"][0]
        ok("build_instant_fire", f"keys={list(payload)}")
    except Exception as exc:  # noqa: BLE001
        bad("pronto", str(exc))
        traceback.print_exc()


def test_power_semantics() -> None:
    print("\n== power mode helpers ==")
    codes_discrete = {"POWER_ON": {}, "POWER_OFF": {}, "POWER_TOGGLE": {}}
    codes_toggle = {"POWER_TOGGLE": {}}

    cases = [
        (POWER_MODE_DISCRETE, codes_discrete, False, "on", "POWER_ON"),
        (POWER_MODE_DISCRETE, codes_discrete, True, "on", None),
        (POWER_MODE_DISCRETE, codes_discrete, None, "on", "POWER_ON"),
        (POWER_MODE_DISCRETE, codes_discrete, True, "off", "POWER_OFF"),
        (POWER_MODE_DISCRETE, codes_discrete, False, "off", None),
        (POWER_MODE_TOGGLE_STATE, codes_toggle, False, "on", "POWER_TOGGLE"),
        (POWER_MODE_TOGGLE_STATE, codes_toggle, True, "on", None),
        (POWER_MODE_TOGGLE_STATE, codes_toggle, None, "on", None),
        (POWER_MODE_TOGGLE_STATE, codes_toggle, True, "off", "POWER_TOGGLE"),
        (POWER_MODE_TOGGLE_STATE, codes_toggle, False, "off", None),
        (POWER_MODE_TOGGLE_STATE, codes_toggle, None, "off", None),
        (POWER_MODE_TOGGLE_ONLY, codes_toggle, True, "on", "POWER_TOGGLE"),
        (POWER_MODE_TOGGLE_ONLY, codes_toggle, False, "off", "POWER_TOGGLE"),
    ]
    for mode, codes, is_on, direction, expect in cases:
        got = (
            resolve_turn_on(mode, codes, is_on)
            if direction == "on"
            else resolve_turn_off(mode, codes, is_on)
        )
        name = f"{mode}/{direction}/is_on={is_on}"
        if got == expect:
            ok(name, f"→ {got}")
        else:
            bad(name, f"expected {expect}, got {got}")


def test_buttons() -> None:
    print("\n== buttons ==")
    codes = {
        "HDMI_4": {},
        "HDMI_1": {},
        "VOLUME_UP": {},
        "MUTE": {},
        "0": {},
        "WEIRD_CODE": {},
    }
    acts = remote_activity_list(codes)
    if acts != ["HDMI_1", "HDMI_4"]:
        bad("remote_activity_list", str(acts))
    else:
        ok("remote_activity_list")
    sel = select_option_list(codes)
    if "VOLUME_UP" in sel or "0" in sel:
        bad("select_option_list excludes volume/digits", str(sel))
    else:
        ok("select_option_list reduced", f"{len(sel)} opts")
    if resolve_command("volume_up", codes) != "VOLUME_UP":
        bad("resolve_command alias", resolve_command("volume_up", codes))
    else:
        ok("resolve_command alias")


def test_naming() -> None:
    print("\n== naming ==")
    if not is_generic_profile_name("Model Group 2"):
        bad("generic Model Group 2", "expected True")
    else:
        ok("generic Model Group 2")
    got = device_title(brand="Vizio", profile_name="Model Group 2", codeset_id=4696)
    if got != "Vizio TV":
        bad("Vizio Model Group 2 → title", got)
    else:
        ok("Vizio Model Group 2 → Vizio TV")
    got = device_title(
        brand="Vizio",
        profile_name="Model Group 2",
        friendly_name="Living Room TV",
    )
    if got != "Living Room TV":
        bad("custom device name", got)
    else:
        ok("custom device name")


def test_assumed_state() -> None:
    print("\n== assumed state ==")
    d = AssumedStateDetector()

    async def run() -> None:
        if await d.async_is_on() is not None:
            bad("assumed initial", "expected None")
        else:
            ok("assumed initial", "None")
        d.note_on()
        if await d.async_is_on() is not True:
            bad("assumed note_on", str(await d.async_is_on()))
        else:
            ok("assumed note_on")
        d.note_toggle()
        if await d.async_is_on() is not False:
            bad("assumed toggle→off", str(await d.async_is_on()))
        else:
            ok("assumed toggle→off")

    asyncio.run(run())


async def test_live(host: str, port: int, adb_server: str | None, blast: bool) -> None:
    print("\n== live Stick ==")
    adb = FireStickAdb(host, port, adb_server_ip=adb_server)
    try:
        await adb.connect()
        ok("adb.connect", f"{host}:{port} via {'server' if adb_server else 'python'}")
    except Exception as exc:  # noqa: BLE001
        bad("adb.connect", str(exc))
        return

    agent = StickAgent(adb, region="na")

    try:
        pong = await agent.ping()
        if pong.get("pong") is True:
            ok("agent.ping", json.dumps(pong))
        else:
            bad("agent.ping", json.dumps(pong))
    except Exception as exc:  # noqa: BLE001
        bad("agent.ping", str(exc))

    try:
        good, msg = await agent.check_helper()
        if good:
            ok("agent.check_helper", msg)
        else:
            bad("agent.check_helper", msg)
    except Exception as exc:  # noqa: BLE001
        bad("agent.check_helper", str(exc))

    remote_mac = None
    try:
        remotes = await agent.list_remotes()
        fire = [r for r in remotes if r.get("likely_fire_remote")]
        if fire:
            remote_mac = fire[0]["address"]
            ok("agent.remotes", f"{len(remotes)} bonded, fire={remote_mac}")
        elif remotes:
            remote_mac = remotes[0]["address"]
            ok("agent.remotes", f"{len(remotes)} bonded, first={remote_mac}")
        else:
            bad("agent.remotes", "empty list")
    except Exception as exc:  # noqa: BLE001
        bad("agent.remotes", str(exc))

    vizio_id = None
    try:
        brands = await agent.brands()
        matches = [b for b in brands if "vizio" in str(b.get("name", "")).lower()]
        if matches:
            vizio_id = int(matches[0]["id"])
            ok("agent.brands", f"{len(brands)} total, Vizio id={vizio_id}")
        elif brands:
            ok("agent.brands", f"{len(brands)} total (no Vizio)")
        else:
            bad("agent.brands", "empty")
    except Exception as exc:  # noqa: BLE001
        bad("agent.brands", str(exc))

    codeset_id = None
    codes: dict = {}
    if vizio_id is not None:
        try:
            profiles = await agent.profiles_for_brand(vizio_id)
            if profiles:
                codeset_id = int(profiles[0].get("codeset_id") or profiles[0].get("profile_id"))
                ok("agent.profiles", f"{len(profiles)} for Vizio, first={codeset_id}")
            else:
                bad("agent.profiles", "empty for Vizio")
        except Exception as exc:  # noqa: BLE001
            bad("agent.profiles", str(exc))

    # Prefer a codeset known to have IR (Equipment Control # may differ by type).
    # Try listed codeset, then fall back to fixture InstantFire blast only.
    for try_id in ([codeset_id] if codeset_id else []) + [979]:
        if try_id is None:
            continue
        try:
            details = await agent.profile_details([try_id])
            if not details:
                bad(f"agent.profile({try_id})", "empty details")
                continue
            d0 = details[0]
            codes = d0.get("codes") or {}
            n = len(codes)
            if n > 0:
                ok(
                    f"agent.profile({try_id})",
                    f"name={d0.get('name')!r} codes={n} duty={d0.get('dutyCycle')}",
                )
                codeset_id = try_id
                break
            bad(f"agent.profile({try_id})", "0 codes in payload")
        except Exception as exc:  # noqa: BLE001
            bad(f"agent.profile({try_id})", str(exc))

    # HDMI state (read-only)
    try:
        hdmi = HdmiLinkDetector(adb)
        state = await hdmi.async_is_on()
        ok("state.hdmi_link", f"is_on={state}")
    except Exception as exc:  # noqa: BLE001
        bad("state.hdmi_link", str(exc))

    try:
        det = build_detector("assumed", adb=adb, assumed=AssumedStateDetector())
        s = await det.async_is_on()
        ok("state.build_detector(assumed)", f"is_on={s}")
    except Exception as exc:  # noqa: BLE001
        bad("state.build_detector", str(exc))

    if not blast:
        print("\n  (skip blast — pass --blast to send IR)")
        await adb.close()
        return

    if not remote_mac:
        bad("blast", "no remote MAC")
        await adb.close()
        return

    # Prefer VOLUME_MUTE / VOLUME_UP from fetched codes; else fixture InstantFire.
    try:
        if "VOLUME_MUTE" in codes or "MUTE" in codes:
            fn = "VOLUME_MUTE" if "VOLUME_MUTE" in codes else "MUTE"
            await agent.blast_function(remote_mac, codes, fn)
            ok("agent.blast_function", fn)
        elif "VOLUME_UP" in codes:
            await agent.blast_function(remote_mac, codes, "VOLUME_UP")
            ok("agent.blast_function", "VOLUME_UP")
        elif "POWER_TOGGLE" in codes:
            await agent.blast_function(remote_mac, codes, "POWER_TOGGLE")
            ok("agent.blast_function", "POWER_TOGGLE (TV may toggle)")
        else:
            fixture = ROOT / "codes" / "power.json"
            payload = json.loads(fixture.read_text())
            await agent.blast_json(remote_mac, payload)
            ok("agent.blast_json", f"fixture {fixture.name}")
    except Exception as exc:  # noqa: BLE001
        bad("blast", str(exc))
        traceback.print_exc()

    await adb.close()
    ok("adb.close")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="192.168.1.112")
    ap.add_argument("--port", type=int, default=5555)
    ap.add_argument("--adb-server", default="127.0.0.1")
    ap.add_argument("--no-adb-server", action="store_true")
    ap.add_argument(
        "--blast",
        action="store_true",
        help="Send a real IR blast (mute/vol/power)",
    )
    ap.add_argument("--skip-live", action="store_true")
    args = ap.parse_args()

    print("firetv_ir integration test")
    test_pronto()
    test_power_semantics()
    test_buttons()
    test_naming()
    test_assumed_state()

    if not args.skip_live:
        server = None if args.no_adb_server else args.adb_server
        asyncio.run(test_live(args.host, args.port, server, args.blast))

    print(f"\n== summary: {PASS} passed, {FAIL} failed ==")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
