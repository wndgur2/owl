"""bleak-based Transport for a real Owl (or a fake one advertised by fake_owl.py).

MTU and PHY: bleak cannot request them on macOS (CoreBluetooth negotiates on
its own) and only reads the MTU on Linux. The client works
with any MTU because DATA offsets, not chunk sizes, drive reassembly.

Pairing (protocol §2.4): Linux (BlueZ) and Windows pair through `BleakClient.pair()`.
macOS has no pairing API: CoreBluetooth pairs by itself (system dialog) the first
time an encrypted attribute is touched, i.e. the first CCCD write in connect().
Either way a refused pairing surfaces as an ATT error on that write, mapped to
NotPaired here.
"""

from __future__ import annotations

import asyncio

from bleak import BleakClient, BleakScanner

import owlproto as p

from .transport import NotPaired, Transport, TransportError

_AUTH_ERRORS = (p.ATT_ERR_INSUFFICIENT_AUTHENTICATION, p.ATT_ERR_INSUFFICIENT_ENCRYPTION)
# Words the backends use for the same failures (BlueZ D-Bus errors, CoreBluetooth messages).
_AUTH_WORDS = ("authentication", "encryption", "notpermitted", "not permitted", "notauthorized",
               "authenticationfailed", "authenticationrejected", "authenticationcanceled", "pairing")


def is_auth_error(e: BaseException) -> bool:
    """True if `e` means "the device wants an encrypted link from a bonded peer"."""
    code = getattr(e, "code", None)
    if isinstance(code, int) and code in _AUTH_ERRORS:
        return True
    text = str(e).lower().replace(".", " ")
    return any(w in text for w in _AUTH_WORDS)


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
    def __init__(self, device, name: str = "Owl", connect_timeout: float = 15.0, pair: bool = False):
        super().__init__()
        self.device = device
        self.name = name
        self.connect_timeout = connect_timeout
        self.pair_on_connect = pair
        self._client: BleakClient | None = None

    @property
    def is_connected(self) -> bool:
        return bool(self._client and self._client.is_connected)

    @property
    def mtu(self) -> int:
        return self._client.mtu_size if self._client else 23

    async def connect(self) -> None:
        await self._connect(subscribe=True)

    async def connect_plain(self) -> None:
        """Link only, no pairing and no subscriptions: enough to read STATUS (§2.4)."""
        await self._connect(subscribe=False)

    async def _connect(self, subscribe: bool) -> None:
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
            if not subscribe:
                return
            if self.pair_on_connect:
                await self.pair()
            await self._client.start_notify(p.STATUS_UUID, lambda _c, d: self.on_status(bytes(d)))
            await self._client.start_notify(p.CONTROL_UUID, lambda _c, d: self.on_control(bytes(d)))
            await self._client.start_notify(p.DATA_UUID, lambda _c, d: self.on_data(bytes(d)))
        except NotPaired:
            await self.disconnect()
            raise
        except (asyncio.TimeoutError, OSError, Exception) as e:  # noqa: BLE001 - bleak raises many types
            await self.disconnect()
            if is_auth_error(e):
                raise NotPaired(f"subscribe refused ({e})") from e
            raise TransportError(f"connect failed: {e}") from e

    async def pair(self) -> None:
        self._check()
        try:
            await self._client.pair()
        except NotImplementedError:
            pass  # macOS: CoreBluetooth pairs on the first encrypted access (start_notify)
        except Exception as e:  # noqa: BLE001
            raise NotPaired(f"pairing failed ({e})") from e

    async def unpair_local(self) -> None:
        if self._client is None:
            return
        try:
            await self._client.unpair()
        except NotImplementedError:
            print("macOS cannot remove a bond from code: System Settings > Bluetooth > (i) > Forget This Device")
        except Exception as e:  # noqa: BLE001
            print(f"local unpair failed: {e}")

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
            if is_auth_error(e):
                raise NotPaired(f"CONTROL write refused ({e})") from e
            raise TransportError(f"CONTROL write failed: {e}") from e

    def _check(self) -> None:
        if not self.is_connected:
            raise TransportError("not connected")
