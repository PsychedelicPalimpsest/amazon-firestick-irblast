#!/usr/bin/env python3
"""
ftvir control CLI — same Python stack as the Home Assistant firetv_ir integration.

  ./ftvir on|off|toggle
  ./ftvir send VOLUME_UP
  ./ftvir buttons
  ./ftvir use-profile 4696
  ./ftvir brands --filter Vizio
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "custom_components"))

from firetv_ir.adb import FireStickAdb  # noqa: E402
from firetv_ir.agent import AgentError, StickAgent  # noqa: E402
from firetv_ir.const import (  # noqa: E402
    POWER_MODE_DISCRETE,
    POWER_MODE_TOGGLE_ONLY,
    POWER_MODE_TOGGLE_STATE,
    STATE_ASSUMED,
    STATE_DEVICECONTROL,
    STATE_HDMI,
    STATE_PING,
    STATE_STICK_AWAKE,
)
from firetv_ir.power import (  # noqa: E402
    default_power_mode,
    resolve_toggle,
    resolve_turn_off,
    resolve_turn_on,
)
from firetv_ir.state import AssumedStateDetector, build_detector  # noqa: E402

DEFAULT_CACHE = ROOT / "codes" / "profile_cache.json"
ASSUMED_PATH = ROOT / "codes" / "assumed_state.json"


def _env(name: str, default: str | None = None) -> str | None:
    v = os.environ.get(name)
    if v is None or v == "":
        return default
    return v


def _parse_host_port(serial: str | None, host: str | None, port: int) -> tuple[str, int]:
    if host:
        return host, port
    serial = serial or _env("ANDROID_SERIAL") or ""
    if ":" in serial:
        h, p = serial.rsplit(":", 1)
        try:
            return h, int(p)
        except ValueError:
            return serial, port
    if serial:
        return serial, port
    raise SystemExit("Set ANDROID_SERIAL or --host (e.g. 192.168.1.112:5555)")


class Session:
    def __init__(self, args: argparse.Namespace) -> None:
        host, port = _parse_host_port(args.serial, args.host, args.port)
        self.host = host
        self.port = port
        self.mac = args.mac or _env("FTVIR_MAC") or "14:91:38:B5:D6:69"
        self.region = args.region or _env("FTVIR_REGION") or "na"
        self._power_mode_explicit = bool(args.power_mode or _env("FTVIR_POWER_MODE"))
        self.power_mode = (
            args.power_mode
            or _env("FTVIR_POWER_MODE")
            or POWER_MODE_TOGGLE_STATE
        )
        self.state_source = (
            args.state_source or _env("FTVIR_STATE_SOURCE") or STATE_DEVICECONTROL
        )
        self.tv_ip = args.tv_ip or _env("FTVIR_TV_IP")
        self.cache_path = Path(
            args.cache or _env("FTVIR_PROFILE_CACHE") or DEFAULT_CACHE
        )
        self.assumed_path = Path(_env("FTVIR_ASSUMED_STATE") or ASSUMED_PATH)
        adb_server = args.adb_server
        if adb_server is None and not args.no_adb_server:
            adb_server = _env("FTVIR_ADB_SERVER") or "127.0.0.1"
        if args.no_adb_server:
            adb_server = None
        self.adb = FireStickAdb(
            host,
            port,
            adbkey=args.adbkey or _env("FTVIR_ADBKEY"),
            adb_server_ip=adb_server,
            adb_server_port=int(_env("FTVIR_ADB_SERVER_PORT") or "5037"),
        )
        self.agent = StickAgent(self.adb, region=self.region)
        self.assumed = AssumedStateDetector()
        if self.state_source == STATE_ASSUMED:
            self._load_assumed()
        self._profile: dict[str, Any] = {}

    @property
    def codes(self) -> dict[str, Any]:
        return self._profile.get("codes") or {}

    @property
    def duty_cycle(self) -> int:
        return int(self._profile.get("duty_cycle", self._profile.get("dutyCycle", 33)))

    @property
    def blast_count(self) -> int:
        return int(self._profile.get("blast_count", self._profile.get("blastCount", 1)))

    async def connect(self) -> None:
        await self.adb.connect()

    async def close(self) -> None:
        await self.adb.close()

    def _load_assumed(self) -> None:
        if not self.assumed_path.is_file():
            return
        try:
            data = json.loads(self.assumed_path.read_text(encoding="utf-8"))
            val = data.get("is_on")
            if val is True:
                self.assumed.note_on()
            elif val is False:
                self.assumed.note_off()
        except (OSError, json.JSONDecodeError, TypeError):
            pass

    def _save_assumed(self) -> None:
        try:
            is_on = self.assumed._on  # noqa: SLF001 — CLI persistence
            self.assumed_path.parent.mkdir(parents=True, exist_ok=True)
            self.assumed_path.write_text(
                json.dumps({"is_on": is_on}) + "\n", encoding="utf-8"
            )
        except OSError:
            pass

    def _apply_power_mode_default(self) -> None:
        if not self._power_mode_explicit and self.codes:
            self.power_mode = default_power_mode(self.codes)

    def load_cache(self) -> bool:
        if not self.cache_path.is_file():
            return False
        self._profile = json.loads(self.cache_path.read_text(encoding="utf-8"))
        self._apply_power_mode_default()
        return bool(self.codes)

    def save_cache(self) -> None:
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.cache_path.write_text(
            json.dumps(self._profile, indent=2) + "\n", encoding="utf-8"
        )

    async def require_codes(self) -> None:
        if self.codes:
            self._apply_power_mode_default()
            return
        if self.load_cache():
            return
        raise SystemExit(
            f"No profile codes loaded. Run: ./ftvir use-profile <codeset_id>\n"
            f"(expected cache: {self.cache_path})"
        )

    async def fetch_profile(self, codeset_id: int | str) -> dict[str, Any]:
        details = await self.agent.profile_details([codeset_id])
        if not details:
            raise AgentError(f"No profile returned for codeset {codeset_id}")
        d0 = details[0]
        self._profile = {
            "codeset_id": int(d0.get("codeset_id") or codeset_id),
            "profile_id": int(d0.get("profile_id") or codeset_id),
            "name": d0.get("name"),
            "brand": d0.get("brand"),
            "codes": d0.get("codes") or {},
            "duty_cycle": int(d0.get("dutyCycle", 33)),
            "blast_count": int(d0.get("blastCount", 1)),
        }
        self._apply_power_mode_default()
        self.save_cache()
        return self._profile

    async def is_on(self) -> bool | None:
        """Read TV power from the configured source only — never invent from IR."""
        det = build_detector(
            self.state_source,
            adb=self.adb,
            agent=self.agent,
            tv_ip=self.tv_ip,
            assumed=self.assumed,
        )
        return await det.async_is_on()

    async def send(self, function: str) -> None:
        await self.require_codes()
        if function not in self.codes:
            avail = ", ".join(sorted(self.codes)[:20])
            raise SystemExit(f"Unknown function {function!r}. Try: ./ftvir buttons\n({avail}…)")
        await self.agent.blast_function(
            self.mac,
            self.codes,
            function,
            duty_cycle=self.duty_cycle,
            blast_count=self.blast_count,
        )
        # Only persist assumed when that source is explicit — blasting ≠ TV on
        if self.state_source == STATE_ASSUMED:
            if function == "POWER_ON":
                self.assumed.note_on()
            elif function == "POWER_OFF":
                self.assumed.note_off()
            elif function == "POWER_TOGGLE":
                self.assumed.note_toggle()
            self._save_assumed()

    async def power(self, action: str) -> tuple[str | None, bool | None]:
        """Return (function_blasted_or_None, is_on_reading)."""
        await self.require_codes()
        is_on = await self.is_on()
        if action == "on":
            fn = resolve_turn_on(self.power_mode, self.codes, is_on)
        elif action == "off":
            fn = resolve_turn_off(self.power_mode, self.codes, is_on)
        else:
            fn = resolve_toggle(self.power_mode, self.codes, is_on)
        if not fn:
            return None, is_on
        await self.send(fn)
        return fn, is_on


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="ftvir",
        description="Fire Stick IR control (shared with Home Assistant firetv_ir)",
    )
    p.add_argument("--host")
    p.add_argument("--port", type=int, default=5555)
    p.add_argument("--serial", help="ANDROID_SERIAL host:port")
    p.add_argument("--mac")
    p.add_argument("--adbkey")
    p.add_argument("--adb-server", default=None)
    p.add_argument("--no-adb-server", action="store_true")
    p.add_argument("--region", default=None)
    p.add_argument("--cache", help="Profile codeset cache JSON path")
    p.add_argument(
        "--power-mode",
        choices=[POWER_MODE_DISCRETE, POWER_MODE_TOGGLE_STATE, POWER_MODE_TOGGLE_ONLY],
    )
    p.add_argument(
        "--state-source",
        choices=[
            STATE_DEVICECONTROL,
            STATE_PING,
            STATE_HDMI,
            STATE_STICK_AWAKE,
            STATE_ASSUMED,
        ],
    )
    p.add_argument("--tv-ip")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("ping", help="Agent RPC ping")
    sub.add_parser("status", help="ADB + helper + profile summary")
    sub.add_parser("remotes", help="List bonded remotes")

    b = sub.add_parser("brands", help="List TV brands from Stick catalog")
    b.add_argument("--filter", default="")

    pr = sub.add_parser("profiles", help="List profiles for a brand id")
    pr.add_argument("brand_id")

    up = sub.add_parser("use-profile", help="Download codeset and cache for send/on/off")
    up.add_argument("codeset_id")

    sub.add_parser("buttons", help="List cached IR functions")
    sub.add_parser("state", help="Read TV on/off from configured state source")

    s = sub.add_parser("send", help="Blast a named IR function")
    s.add_argument("function", help="e.g. VOLUME_UP, HDMI_4, POWER_TOGGLE")

    sub.add_parser("on", help="Turn TV on (respects power mode + state)")
    sub.add_parser("off", help="Turn TV off")
    sub.add_parser("toggle", help="Toggle power")

    bj = sub.add_parser("blast-json", help="Blast raw InstantFire JSON file")
    bj.add_argument("file")

    return p


async def run(args: argparse.Namespace) -> int:
    sess = Session(args)
    await sess.connect()
    try:
        if args.cmd == "ping":
            print(json.dumps(await sess.agent.ping(), indent=2))
            return 0

        if args.cmd == "status":
            ok, msg = await sess.agent.check_helper()
            remotes = await sess.agent.list_remotes()
            cached = sess.load_cache()
            print(f"adb={sess.host}:{sess.port}")
            print(f"helper={'ok' if ok else 'FAIL'}: {msg}")
            print(f"mac={sess.mac}")
            print(f"remotes={len(remotes)}")
            if cached:
                print(
                    f"profile={sess._profile.get('name')!r} "
                    f"codeset={sess._profile.get('codeset_id')} "
                    f"functions={len(sess.codes)} "
                    f"power_mode={sess.power_mode} "
                    f"state={sess.state_source}"
                )
            else:
                print(f"profile=none (run ./ftvir use-profile <id>) cache={sess.cache_path}")
            if not ok:
                return 1
            print("ok")
            return 0

        if args.cmd == "remotes":
            print(json.dumps(await sess.agent.list_remotes(), indent=2))
            return 0

        if args.cmd == "brands":
            brands = await sess.agent.brands()
            q = (args.filter or "").lower()
            if q:
                brands = [b for b in brands if q in str(b.get("name", "")).lower()]
            print(json.dumps(brands, indent=2))
            print(f"# {len(brands)} brands", file=sys.stderr)
            return 0

        if args.cmd == "profiles":
            print(
                json.dumps(
                    await sess.agent.profiles_for_brand(args.brand_id), indent=2
                )
            )
            return 0

        if args.cmd == "use-profile":
            prof = await sess.fetch_profile(args.codeset_id)
            print(
                f"ok cached codeset={prof.get('codeset_id')} "
                f"name={prof.get('name')!r} functions={len(prof.get('codes') or {})} "
                f"→ {sess.cache_path}"
            )
            return 0

        if args.cmd == "buttons":
            await sess.require_codes()
            sess.load_cache()
            for name in sorted(sess.codes):
                print(name)
            return 0

        if args.cmd == "state":
            sess.load_cache()
            is_on = await sess.is_on()
            print(f"is_on={is_on} source={sess.state_source}")
            if sess.state_source == STATE_DEVICECONTROL:
                try:
                    print(json.dumps(await sess.agent.tv_state(), indent=2))
                except AgentError as exc:
                    print(f"devicecontrol error: {exc}", file=sys.stderr)
            return 0

        if args.cmd == "send":
            sess.load_cache()
            await sess.send(args.function.upper())
            print(f"ok {args.function.upper()}")
            return 0

        if args.cmd in ("on", "off", "toggle"):
            sess.load_cache()
            fn, is_on = await sess.power(args.cmd)
            if fn is None:
                if args.cmd == "toggle":
                    reason = "no POWER_TOGGLE in profile"
                elif is_on is True and args.cmd == "on":
                    reason = "already on"
                elif is_on is False and args.cmd == "off":
                    reason = "already off"
                elif is_on is None and sess.power_mode != POWER_MODE_TOGGLE_ONLY:
                    reason = (
                        f"state unknown (source={sess.state_source}); "
                        f"refusing POWER_TOGGLE — fix state source "
                        f"(devicecontrol/ping) or use --power-mode discrete / toggle_only"
                    )
                else:
                    reason = "state already matches"
                print(f"ok noop ({args.cmd}: {reason})")
            else:
                print(f"ok {args.cmd} → {fn} (mode={sess.power_mode}, is_on={is_on})")
            return 0

        if args.cmd == "blast-json":
            path = Path(args.file)
            if not path.is_file():
                path = ROOT / args.file
            payload = json.loads(path.read_text(encoding="utf-8"))
            await sess.agent.blast_json(sess.mac, payload)
            print("ok")
            return 0

        raise SystemExit(f"unknown cmd {args.cmd}")
    finally:
        await sess.close()


def main(argv: list[str] | None = None) -> int:
    # Allow env file: parent ftvir bash usually sources config.env
    args = build_parser().parse_args(argv)
    return asyncio.run(run(args))


if __name__ == "__main__":
    raise SystemExit(main())
