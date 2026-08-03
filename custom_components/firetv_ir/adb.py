"""ADB connection helpers (androidtv-style Python ADB + optional ADB server)."""

from __future__ import annotations

import asyncio
import logging
from io import BytesIO
from typing import Any

_LOGGER = logging.getLogger(__name__)


class AdbError(Exception):
    """ADB operation failed."""


class FireStickAdb:
    """Thin async wrapper around adb-shell or pure-python-adb server."""

    def __init__(
        self,
        host: str,
        port: int = 5555,
        *,
        adbkey: str | None = None,
        adb_server_ip: str | None = None,
        adb_server_port: int = 5037,
    ) -> None:
        self.host = host
        self.port = port
        self.adbkey = adbkey
        self.adb_server_ip = adb_server_ip
        self.adb_server_port = adb_server_port
        self._device: Any = None
        self._lock = asyncio.Lock()

    async def connect(self) -> None:
        async with self._lock:
            await asyncio.get_event_loop().run_in_executor(None, self._connect_sync)

    def _connect_sync(self) -> None:
        if self.adb_server_ip:
            from ppadb.client import Client as AdbClient

            client = AdbClient(host=self.adb_server_ip, port=self.adb_server_port)
            serial = f"{self.host}:{self.port}"
            devices = {d.serial: d for d in client.devices()}
            if serial not in devices:
                client.remote_connect(self.host, self.port)
                devices = {d.serial: d for d in client.devices()}
            if serial not in devices:
                raise AdbError(f"Device {serial} not connected to ADB server")
            self._device = ("server", devices[serial])
            return

        from adb_shell.adb_device import AdbDeviceTcp
        from adb_shell.auth.keygen import keygen
        from adb_shell.auth.sign_pythonrsa import PythonRSASigner
        import os

        key = self.adbkey
        if not key:
            key = os.path.expanduser("~/.config/firetv_ir/adbkey")
        os.makedirs(os.path.dirname(key), exist_ok=True)
        if not os.path.isfile(key):
            keygen(key)
        with open(key, encoding="utf-8") as f:
            priv = f.read()
        with open(key + ".pub", encoding="utf-8") as f:
            pub = f.read()
        signer = PythonRSASigner(pub, priv)
        device = AdbDeviceTcp(self.host, self.port, default_transport_timeout_s=10.0)
        device.connect(rsa_keys=[signer], auth_timeout_s=20.0)
        self._device = ("python", device)

    async def shell(self, command: str, timeout: float = 30.0) -> str:
        async with self._lock:
            return await asyncio.get_event_loop().run_in_executor(
                None, lambda: self._shell_sync(command, timeout)
            )

    def _shell_sync(self, command: str, timeout: float) -> str:
        if not self._device:
            raise AdbError("Not connected")
        kind, dev = self._device
        try:
            if kind == "server":
                out = dev.shell(command)
            else:
                out = dev.shell(command, timeout_s=timeout)
        except Exception as exc:  # noqa: BLE001
            raise AdbError(str(exc)) from exc
        if out is None:
            return ""
        if isinstance(out, bytes):
            return out.decode("utf-8", errors="replace")
        return str(out)

    async def push(self, local_path: str, remote_path: str) -> None:
        async with self._lock:
            await asyncio.get_event_loop().run_in_executor(
                None, lambda: self._push_sync(local_path, remote_path)
            )

    async def push_bytes(self, data: bytes, remote_path: str) -> None:
        """Push in-memory bytes (BytesIO) — never pass an open file handle to adb-shell."""
        async with self._lock:
            await asyncio.get_event_loop().run_in_executor(
                None, lambda: self._push_bytes_sync(data, remote_path)
            )

    def _push_sync(self, local_path: str, remote_path: str) -> None:
        if not self._device:
            raise AdbError("Not connected")
        _, dev = self._device
        try:
            # Must be a filesystem path str or BytesIO — not a BufferedReader.
            dev.push(local_path, remote_path)
        except Exception as exc:  # noqa: BLE001
            raise AdbError(str(exc)) from exc

    def _push_bytes_sync(self, data: bytes, remote_path: str) -> None:
        if not self._device:
            raise AdbError("Not connected")
        kind, dev = self._device
        try:
            if kind == "server":
                # ppadb expects a local path; stage a temp file
                import tempfile
                from pathlib import Path

                with tempfile.NamedTemporaryFile("wb", delete=False) as tmp:
                    tmp.write(data)
                    local = tmp.name
                try:
                    dev.push(local, remote_path)
                finally:
                    Path(local).unlink(missing_ok=True)
            else:
                # adb-shell accepts BytesIO without os.stat on a file handle
                dev.push(BytesIO(data), remote_path)
        except Exception as exc:  # noqa: BLE001
            raise AdbError(str(exc)) from exc

    async def close(self) -> None:
        async with self._lock:
            if not self._device:
                return
            kind, dev = self._device
            self._device = None
            if kind == "python":
                try:
                    dev.close()
                except Exception:  # noqa: BLE001
                    pass
