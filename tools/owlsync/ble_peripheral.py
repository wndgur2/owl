"""Expose an OwlDevice as a real BLE peripheral with `bless` (best effort, macOS first).

Limits on macOS (CoreBluetooth peripheral role):
  * MTU, 2M PHY, DLE and connection interval are chosen by the central and the
    OS; the peripheral cannot request them. The fake reads the central's
    maximumUpdateValueLength and sizes DATA chunks to fit (236 B only if MTU >= 247).
  * Unverified (no Bluetooth permission while writing this): the GAP device name
    a connected central reads is probably the Mac's name, not the advertised
    local name; and a Mac is generally reported to be unable to connect to its own
    peripheral, so run `sync.py` on another machine or use the phone app.
  * There is no disconnect callback; "all characteristics unsubscribed" is
    treated as disconnect.

Security (protocol §2.4), what the fake can and cannot do:
  * Encryption requirement: yes, mostly. CONTROL is write-encryption-required and,
    on macOS, every notify characteristic gets CoreBluetooth's
    NotifyEncryptionRequired property, so subscribing needs an encrypted link and
    the central has to pair first (STATUS stays readable, as on the device).
    Unverified on hardware (no Bluetooth permission while writing this).
  * Pairing window / one-bond policy / refusing a pairing: no. CoreBluetooth
    accepts every pairing itself (Just Works) and gives the peripheral no hook to
    refuse one, so any phone can bond with the fake. STATUS bit4 (pairing open)
    comes from the device model, which never sees those pairings.
  * UNPAIR: the model answers OK and forgets its (empty) bond list; the Mac keeps
    its bond (remove it in System Settings > Bluetooth).
"""

from __future__ import annotations

import asyncio
import time

import owlproto as p

from .device import OwlDevice
from .transport import PeripheralLink, TransportError

# CBCharacteristicPropertyNotifyEncryptionRequired. bless has no name for it and
# passes property bits to CoreBluetooth unchanged on macOS (its 0x100 is
# "reliable_write", which is what the BlueZ backend would send instead).
CB_NOTIFY_ENCRYPTION_REQUIRED = 0x100
DEFAULT_NOTIFY_LEN = 182  # assumed fallback until the central's maximumUpdateValueLength is known
_hooks: dict = {}


def _install_delegate_hook() -> None:
    """Subclass bless's CoreBluetooth delegate to learn the central's MTU and
    subscription changes, and make bless use it."""
    import sys

    if sys.platform != "darwin" or _hooks.get("installed"):
        return
    import objc
    from bless.backends.corebluetooth import server as cb_server
    from bless.backends.corebluetooth.peripheral_manager_delegate import PeripheralManagerDelegate

    class OwlPeripheralManagerDelegate(PeripheralManagerDelegate):
        def peripheralManager_central_didSubscribeToCharacteristic_(self, pm, central, characteristic):  # noqa: N802
            objc.super(OwlPeripheralManagerDelegate, self).peripheralManager_central_didSubscribeToCharacteristic_(
                pm, central, characteristic)
            cb = _hooks.get("subscribe")
            if cb:
                cb(str(central.identifier().UUIDString()), str(characteristic.UUID().UUIDString()),
                   int(central.maximumUpdateValueLength()))

        def peripheralManager_central_didUnsubscribeFromCharacteristic_(self, pm, central, characteristic):  # noqa: N802
            objc.super(OwlPeripheralManagerDelegate, self).peripheralManager_central_didUnsubscribeFromCharacteristic_(
                pm, central, characteristic)
            cb = _hooks.get("unsubscribe")
            if cb:
                cb(str(central.identifier().UUIDString()), str(characteristic.UUID().UUIDString()))

    cb_server.PeripheralManagerDelegate = OwlPeripheralManagerDelegate
    _hooks["installed"] = True


BT_PERMISSION_HINT = (
    "Bluetooth is not available to this process. macOS grants Bluetooth per app: run from "
    "Terminal.app (or iTerm) and allow the prompt, or enable the app under System Settings > "
    "Privacy & Security > Bluetooth. Processes started by sandboxed tools may never get it."
)


def bluetooth_authorization() -> int | None:
    """macOS CBManager.authorization: 0 not determined, 1 restricted, 2 denied, 3 allowed."""
    import sys

    if sys.platform != "darwin":
        return None
    try:
        import CoreBluetooth

        return int(CoreBluetooth.CBManager.authorization())
    except Exception:  # noqa: BLE001
        return None


async def _create_server(cls, name: str, loop, timeout: float = 20.0):
    """bless blocks forever in its constructor until the radio reports powered-on
    (never happens without permission), so build it on a daemon thread with a timeout."""
    import threading

    auth = bluetooth_authorization()
    if auth in (1, 2):
        raise RuntimeError(f"Bluetooth permission denied. {BT_PERMISSION_HINT}")
    fut = loop.create_future()

    def build():
        try:
            srv = cls(name=name, loop=loop)
            loop.call_soon_threadsafe(lambda: fut.done() or fut.set_result(srv))
        except Exception as e:  # noqa: BLE001
            loop.call_soon_threadsafe(lambda err=e: fut.done() or fut.set_exception(err))

    threading.Thread(target=build, daemon=True).start()
    try:
        return await asyncio.wait_for(fut, timeout)
    except asyncio.TimeoutError:
        raise RuntimeError(f"Bluetooth did not power on within {timeout:.0f}s "
                           f"(authorization={auth}). {BT_PERMISSION_HINT}") from None


class BlessPeripheral(PeripheralLink):
    def __init__(self, device: OwlDevice, name: str = "Owl-FA4E", log=print):
        self.device = device
        self.name = name
        self.log = log
        self._server = None
        self._subs: dict[str, set[str]] = {}
        self._max_len = DEFAULT_NOTIFY_LEN
        self._loop: asyncio.AbstractEventLoop | None = None
        self.notify_retries = 0

    # --- PeripheralLink -------------------------------------------------------
    @property
    def max_notify_len(self) -> int:
        return self._max_len

    @property
    def connected(self) -> bool:
        return bool(self._subs)

    async def notify(self, char_uuid: str, value: bytes) -> None:
        if not self.connected:
            raise TransportError("central disconnected")
        if char_uuid not in {c for s in self._subs.values() for c in s}:
            return  # the central did not subscribe to this characteristic
        char = self._server.get_characteristic(char_uuid)
        char.value = bytearray(value)
        deadline = time.monotonic() + 5.0
        # updateValue returns NO when CoreBluetooth's transmit queue is full
        # (Apple docs). bless only passes that bool back, so retry until it's sent.
        while not self._server.update_value(p.SERVICE_UUID, char_uuid):
            self.notify_retries += 1
            if not self.connected:
                raise TransportError("central disconnected")
            if time.monotonic() > deadline:
                raise TransportError("notify queue stuck for 5 s")
            await asyncio.sleep(0.004)

    # --- callbacks (CoreBluetooth queue thread -> asyncio loop) ---------------
    def _on_subscribe(self, central: str, char: str, max_len: int) -> None:
        self._loop.call_soon_threadsafe(self._subscribed, central, char.lower(), max_len)

    def _on_unsubscribe(self, central: str, char: str) -> None:
        self._loop.call_soon_threadsafe(self._unsubscribed, central, char.lower())

    def _subscribed(self, central: str, char: str, max_len: int) -> None:
        first = not self._subs
        self._subs.setdefault(central, set()).add(char)
        if max_len > 0:
            self._max_len = max_len
        if first:
            self.log(f"central {central[:8]} connected, max notify {max_len} B "
                     f"-> DATA chunk {min(p.DATA_CHUNK_MAX, max_len - p.DATA_HEADER.size)} B")
            self.device.on_connect(self)

    def _unsubscribed(self, central: str, char: str) -> None:
        subs = self._subs.get(central)
        if subs is None:
            return
        subs.discard(char)
        if not subs:
            del self._subs[central]
        if not self._subs:
            self.log("central disconnected")
            self.device.on_disconnect()

    def _write(self, characteristic, value) -> None:
        uuid = str(characteristic.uuid).lower()
        if uuid == p.CONTROL_UUID:
            data = bytes(value)
            self._loop.call_soon_threadsafe(lambda: self._loop.create_task(self.device.handle_control(data)))

    def _read(self, characteristic, **_kw) -> bytearray:
        if str(characteristic.uuid).lower() == p.STATUS_UUID:
            return bytearray(self.device.status().pack())
        return bytearray()

    # --- lifecycle ------------------------------------------------------------
    async def start(self) -> None:
        _install_delegate_hook()
        from bless import BlessServer, GATTAttributePermissions as Perm, GATTCharacteristicProperties as Prop

        self._loop = asyncio.get_running_loop()
        _hooks["subscribe"] = self._on_subscribe
        _hooks["unsubscribe"] = self._on_unsubscribe
        server = await _create_server(BlessServer, self.name, self._loop)
        server.read_request_func = self._read
        server.write_request_func = self._write
        import sys

        notify = Prop.notify
        if sys.platform == "darwin":
            notify = Prop(Prop.notify.value | CB_NOTIFY_ENCRYPTION_REQUIRED)
        await server.add_new_service(p.SERVICE_UUID)
        await server.add_new_characteristic(p.SERVICE_UUID, p.STATUS_UUID, Prop.read | notify, None, Perm.readable)
        await server.add_new_characteristic(p.SERVICE_UUID, p.CONTROL_UUID, Prop.write | notify, None,
                                            Perm.write_encryption_required)
        await server.add_new_characteristic(p.SERVICE_UUID, p.DATA_UUID, notify, None, Perm.readable)
        await server.start()
        self._server = server
        self.log(f"advertising as {self.name} (service {p.SERVICE_UUID})")

    async def stop(self) -> None:
        if self._server:
            await self._server.stop()
