#!/usr/bin/env python3
"""Debug CLI for Stick AgentService (shared Python stack with the HA integration)."""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "custom_components"))

from firetv_ir.adb import FireStickAdb  # noqa: E402
from firetv_ir.agent import StickAgent  # noqa: E402


async def main() -> int:
    p = argparse.ArgumentParser(description="Fire TV IR agent RPC debug")
    p.add_argument("--host", required=True)
    p.add_argument("--port", type=int, default=5555)
    p.add_argument("--adbkey")
    p.add_argument("--adb-server")
    p.add_argument("--region", default="na")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("ping")
    sub.add_parser("remotes")
    b = sub.add_parser("brands")
    b.add_argument("--filter")
    pr = sub.add_parser("profiles")
    pr.add_argument("brand_id")
    pf = sub.add_parser("profile")
    pf.add_argument("codeset_id")
    args = p.parse_args()

    adb = FireStickAdb(
        args.host,
        args.port,
        adbkey=args.adbkey,
        adb_server_ip=args.adb_server,
    )
    await adb.connect()
    agent = StickAgent(adb, region=args.region)
    try:
        if args.cmd == "ping":
            print(json.dumps(await agent.ping(), indent=2))
        elif args.cmd == "remotes":
            print(json.dumps(await agent.list_remotes(), indent=2))
        elif args.cmd == "brands":
            brands = await agent.brands()
            if args.filter:
                q = args.filter.lower()
                brands = [b for b in brands if q in b.get("name", "").lower()]
            print(json.dumps(brands[:50], indent=2))
            print(f"# total matched: {len(brands)}", file=sys.stderr)
        elif args.cmd == "profiles":
            print(json.dumps(await agent.profiles_for_brand(args.brand_id), indent=2))
        elif args.cmd == "profile":
            print(json.dumps(await agent.profile_details([args.codeset_id]), indent=2))
    finally:
        await adb.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
