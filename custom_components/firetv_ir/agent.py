"""Talk to Magisk FtvIr AgentService over ADB."""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import shlex
import time
from typing import Any

from .adb import FireStickAdb
from .const import AGENT_ACTION, AGENT_COMPONENT, RPC_OUT, RPC_STATUS
from .pronto import build_instant_fire

_LOGGER = logging.getLogger(__name__)


class AgentError(Exception):
    """Stick agent RPC failed."""


class StickAgent:
    """RPC client for com.mitch.ftvir AgentService."""

    def __init__(self, adb: FireStickAdb, *, region: str = "na") -> None:
        self.adb = adb
        self.region = region

    async def ping(self) -> dict[str, Any]:
        return await self._rpc("ping")

    async def list_remotes(self) -> list[dict[str, Any]]:
        data = await self._rpc("remotes")
        return list(data.get("remotes") or [])

    async def brands(self) -> list[dict[str, Any]]:
        data = await self._rpc("brands", region=self.region)
        return list(data.get("brands") or [])

    async def profiles_for_brand(self, brand_id: int | str) -> list[dict[str, Any]]:
        data = await self._rpc("profiles", region=self.region, brand_id=str(brand_id))
        return list(data.get("profiles") or [])

    async def profile_details(self, codeset_ids: list[int | str]) -> list[dict[str, Any]]:
        ids = ",".join(str(i) for i in codeset_ids)
        data = await self._rpc("profile", region=self.region, ids=ids)
        return list(data.get("profiles") or [])

    async def tv_state(self) -> dict[str, Any]:
        """DeviceControl fused screen/TV power (read-only; no IR)."""
        return await self._rpc("tv_state")

    async def blast_json(self, address: str, payload: dict[str, Any]) -> dict[str, Any]:
        # Inline JSON via Intent extra — avoids adb push / Stick base64 entirely.
        # (adb-shell push of an open file raises TypeError: BufferedReader.)
        raw = json.dumps(payload, separators=(",", ":"))
        if len(raw) < 700_000:
            return await self._rpc("blast", address=address, json=raw)
        remote = "/data/local/tmp/ftvir_blast.json"
        await self._write_remote_json(remote, payload)
        return await self._rpc("blast", address=address, json_file=remote)

    async def blast_pronto(
        self,
        address: str,
        pronto: str,
        *,
        duty_cycle: int = 33,
        blast_count: int = 1,
    ) -> dict[str, Any]:
        payload = build_instant_fire(
            pronto, duty_cycle=duty_cycle, blast_count=blast_count, reason="HA"
        )
        return await self.blast_json(address, payload)

    async def blast_function(
        self,
        address: str,
        codes: dict[str, Any],
        function: str,
        *,
        duty_cycle: int = 33,
        blast_count: int = 1,
    ) -> dict[str, Any]:
        entry = codes.get(function)
        if not entry:
            raise AgentError(f"Function not in profile: {function}")
        pronto = entry.get("code1") if isinstance(entry, dict) else entry
        if not pronto:
            raise AgentError(f"No code1 for {function}")
        return await self.blast_pronto(
            address, pronto, duty_cycle=duty_cycle, blast_count=blast_count
        )

    async def check_helper(self) -> tuple[bool, str]:
        """Return (ok, message) for Magisk helper presence."""
        path = await self.adb.shell("pm path com.mitch.ftvir")
        if "package:" not in path:
            return False, "com.mitch.ftvir not installed — run ./ftvir setup on the Stick"
        dump = await self.adb.shell("dumpsys package com.mitch.ftvir")
        if "AMAZON_REMOTE_DFU" not in dump:
            return False, "FtvIr missing AMAZON_REMOTE_DFU (install as Magisk priv-app)"
        try:
            await self.ping()
        except AgentError as exc:
            return False, f"Agent ping failed: {exc}"
        return True, "ok"

    async def _write_remote_json(self, remote_path: str, payload: dict[str, Any]) -> None:
        raw = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        b64 = base64.b64encode(raw).decode("ascii")
        remote = shlex.quote(remote_path)
        # Fire OS: try toybox/base64 decode; verify with a marker + size.
        script = (
            f"B64={shlex.quote(b64)}; DEST={remote}; "
            f"ok=0; "
            f"if echo \"$B64\" | toybox base64 -d > \"$DEST\" 2>/dev/null; then ok=1; "
            f"elif echo \"$B64\" | base64 -d > \"$DEST\" 2>/dev/null; then ok=1; "
            f"elif echo \"$B64\" | busybox base64 -d > \"$DEST\" 2>/dev/null; then ok=1; "
            f"fi; "
            f"if [ \"$ok\" = 1 ] && [ -s \"$DEST\" ]; then echo WRITE_OK; else echo FAIL_NO_BASE64; fi"
        )
        out = await self.adb.shell(script)
        if "WRITE_OK" in out:
            return
        # Last resort: in-memory push (never an open BufferedReader)
        await self.adb.push_bytes(raw, remote_path)

    async def _cat_rpc(self, path: str) -> str:
        """Read RPC file; Magisk su required (app_data_file SELinux)."""
        last = ""
        for cmd in (
            f"su -c 'cat {shlex.quote(path)}' 2>/dev/null",
            f"su -mm -c 'cat {shlex.quote(path)}' 2>/dev/null",
            f"cat {shlex.quote(path)} 2>/dev/null",
        ):
            last = await self.adb.shell(cmd)
            if last.strip().startswith("{"):
                return last
        return last

    async def _rpc(self, op: str, **extras: str) -> dict[str, Any]:
        # Clear previous status (needs su for app cache)
        await self.adb.shell(
            f"su -c 'rm -f {RPC_STATUS} {RPC_OUT}' 2>/dev/null || "
            f"su -mm -c 'rm -f {RPC_STATUS} {RPC_OUT}' 2>/dev/null || true"
        )
        args = [
            "am",
            "startservice",
            "-n",
            AGENT_COMPONENT,
            "-a",
            AGENT_ACTION,
            "--es",
            "op",
            op,
        ]
        for key, val in extras.items():
            if val is None:
                continue
            args.extend(["--es", key, str(val)])
        cmd = " ".join(shlex.quote(a) for a in args)
        start_out = await self.adb.shell(cmd)
        _LOGGER.debug("RPC start %s: %s", op, start_out.strip())

        deadline = time.monotonic() + 45.0
        while time.monotonic() < deadline:
            status_raw = (await self._cat_rpc(RPC_STATUS)).strip()
            if status_raw.startswith("{"):
                try:
                    status = json.loads(status_raw)
                except json.JSONDecodeError:
                    await asyncio.sleep(0.25)
                    continue
                out_raw = (await self._cat_rpc(RPC_OUT)).strip()
                try:
                    payload = json.loads(out_raw) if out_raw.startswith("{") else {}
                except json.JSONDecodeError:
                    payload = {}
                if not status.get("ok"):
                    raise AgentError(status.get("error") or payload.get("error") or "RPC failed")
                return payload
            await asyncio.sleep(0.25)
        raise AgentError(f"RPC timeout waiting for op={op}")
