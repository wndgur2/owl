"""bleak-based Transport for a real Owl (or a fake one advertised by fake_owl.py).

MTU and PHY: bleak cannot request them on macOS (CoreBluetooth negotiates on
its own, usually MTU 185-527) and only reads the MTU on Linux. The client works
with any MTU because DATA offsets, not chunk sizes, drive reassembly.
"""

from __future__ import annotations

import asyncio

from bleak import BleakClient, BleakScanner

import owlproto as p

from .transport import Transport, TransportError


async def scan(timeout: float = 5.0) -> list[tuple[int, object, str]]:
    """Nearby Owl devices as (rssi, BLEDevice, name), strongest first."""
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    hits = []
    for dev, adv in found.values():
        name = adv.local_name or dev.name or ""
        if name.startswith("Owl") or p.SERVICE_UUID in [u.lower() for u in (adv.service_uuids or [])]:
            hits.append((adv.rssi, dev, name))
    hits.sort(key=lambda h: h[0], reverse=True)
    return hits


async def find(address: str | None = None, name: str | None = None, timeout: float = 8.0):
    """Returns (BLEDevice, name). Picks the strongest Owl-* unless address/name is given."""
    if address:
        dev = await BleakScanner.find_device_by_address(address, timeout=timeout)
        if dev is None:
            raise TransportError(f"device {address} not found")
        return dev, dev.name or address
    hits = await scan(timeout)
    if name:
        hits = [h for h in hits if h[2] == name]
    if not hits:
        raise TransportError("no Owl device found (is it advertising? nRF Connect on a phone is a good cross-check)")
    return hits[0][1], hits[0][2]


class BleakTransport(Transport):
    def __init__(self, device, name: str = "Owl", connect_timeout: float = 15.0):
        super().__init__()
        self.device = device
        self.name = name
        self.connect_timeout = connect_timeout
        self._client: BleakClient | None = None

    @property
    def is_connected(self) -> bool:
        return bool(self._client and self._client.is_connected)

    @property
    def mtu(self) -> int:
        return self._client.mtu_size if self._client else 23

    async def connect(self) -> None:
        if self.is_connected:
            return
        self._client = BleakClient(self.device, disconnected_callback=self._lost, timeout=self.connect_timeout)
        try:
            await self._client.connect()
            backend = getattr(self._client, "_backend", None)
            if hasattr(backend, "_acquire_mtu"):  # BlueZ: read the negotiated MTU
                try:
                    await backend._acquire_mtu()
                except Exception:  # noqa: BLE001 - best effort
                    pass
            await self._client.start_notify(p.STATUS_UUID, lambda _c, d: self.on_status(bytes(d)))
            await self._client.start_notify(p.CONTROL_UUID, lambda _c, d: self.on_control(bytes(d)))
            await self._client.start_notify(p.DATA_UUID, lambda _c, d: self.on_data(bytes(d)))
        except (asyncio.TimeoutError, OSError, Exception) as e:  # noqa: BLE001 - bleak raises many types
            await self.disconnect()
            raise TransportError(f"connect failed: {e}") from e

    def _lost(self, _client) -> None:
        self.on_disconnect()

    async def disconnect(self) -> None:
        if self._client:
            try:
                await self._client.disconnect()
            except Exception:  # noqa: BLE001
                pass

    async def read_status(self) -> bytes:
        self._check()
        try:
            return bytes(await self._client.read_gatt_char(p.STATUS_UUID))
        except Exception as e:  # noqa: BLE001
            raise TransportError(f"STATUS read failed: {e}") from e

    async def write_control(self, data: bytes) -> None:
        self._check()
        try:
            await self._client.write_gatt_char(p.CONTROL_UUID, data, response=True)
        except Exception as e:  # noqa: BLE001
            raise TransportError(f"CONTROL write failed: {e}") from e

    def _check(self) -> None:
        if not self.is_connected:
            raise TransportError("not connected")
