"""Expose an OwlDevice as a BLE peripheral with Google Bumble (a Bluetooth host stack in Python).

Main use: the Android emulator. Its Bluetooth controller is virtual (netsim), and Bumble can
attach a second virtual controller to the same simulated radio (`android-netsim:` transport),
so the app's real BLE path (CDM scan, connectGatt, MTU, createBond, encryption) talks to this
fake over "air" that only exists inside netsim. Any other Bumble transport works too (a USB
dongle `usb:0`, or the in-process link the tests use).

Unlike bless/CoreBluetooth (ble_peripheral.py), Bumble lets the peripheral own SMP, the part of
BLE that does pairing. So the fake applies protocol §2.4 like the firmware (firmware/src/ble.c):

  * LE Secure Connections only, Just Works (NoInputNoOutput), bonding required.
  * A Pairing Request is refused while the model says pairing is closed: bonded and outside the
    120 s power-on window, or the window already produced a bond. Refusal codes match Zephyr's
    (`pairing_refusal` below).
  * The new bond replaces the old one only after pairing succeeded (OwlDevice.on_bonded).
  * CCCD writes and CONTROL writes need an encrypted link: ATT 0x05 for a peer without a bond,
    0x0F for a bonded peer that has not encrypted yet. STATUS reads are open.
  * A bonded peer gets an SMP Security Request right after connecting; a stranger does not.
  * A link still unencrypted after 60 s is dropped with HCI 0x05.
  * UNPAIR deletes every key and drops the link after the response went out.
  * Keys survive restarts when a key file is given (Bumble JsonKeyStore), like the firmware's
    settings partition; a phone that still has a bond the fake forgot gets "key missing".
"""

from __future__ import annotations

import asyncio
from typing import Callable

import owlproto as p

from .device import OwlDevice
from .transport import PeripheralLink, TransportError

# --- pure policy (no Bumble import, unit-tested in CI) -------------------------------------
SMP_AUTHREQ_BONDING = 0x01
SMP_AUTHREQ_SC = 0x08
SMP_ERR_AUTH_REQUIREMENTS = 0x03
SMP_ERR_UNSPECIFIED = 0x08
HCI_AUTHENTICATION_FAILURE = 0x05
HCI_REMOTE_USER_TERMINATED = 0x13
NOTIFY_WINDOW = 8  # ACL packets in flight before notify() waits (keeps ABORT/ACK responses snappy)


def pairing_refusal(auth_req: int, pairing_open: bool) -> int | None:
    """SMP Pairing Failed reason for a Pairing Request, or None to go ahead.

    Same order and codes as Zephyr's smp_pairing_req() with the firmware's Kconfig
    (CONFIG_BT_BONDING_REQUIRED, CONFIG_BT_SMP_SC_PAIR_ONLY, pairing_accept() returning
    BT_SECURITY_ERR_PAIR_NOT_ALLOWED, which smp_err_get() maps to 0x08)."""
    if not auth_req & SMP_AUTHREQ_BONDING:
        return SMP_ERR_UNSPECIFIED  # would not bond (§2.4)
    if not auth_req & SMP_AUTHREQ_SC:
        return SMP_ERR_AUTH_REQUIREMENTS  # LE legacy pairing
    if not pairing_open:
        return SMP_ERR_UNSPECIFIED  # §2.4 step 3
    return None


def att_security_error(encrypted: bool, bonded: bool) -> int | None:
    """ATT error for a protected attribute (CCCDs, CONTROL write), or None if allowed."""
    if encrypted:
        return None
    return p.ATT_ERR_INSUFFICIENT_ENCRYPTION if bonded else p.ATT_ERR_INSUFFICIENT_AUTHENTICATION


def address_for_name(name: str) -> str:
    """Static random address whose low 2 bytes are the name's suffix (§2: Owl-XXXX = low 2 bytes
    in hex), so the same --name always gives the same address and the phone's bond stays valid
    across fake restarts. Top two bits 11 = static random address."""
    suffix = name.rsplit("-", 1)[-1]
    try:
        low = int(suffix, 16) & 0xFFFF
    except ValueError:
        low = 0x0001
    return f"C0:0F:F0:00:{low >> 8:02X}:{low & 0xFF:02X}"


# --- Bumble peripheral ---------------------------------------------------------------------
class BumblePeripheral(PeripheralLink):
    def __init__(self, device: OwlDevice, transport: str = "android-netsim:", name: str = "Owl-FA4E",
                 address: str | None = None, keys_path: str | None = None, log: Callable[[str], None] = print,
                 unencrypted_timeout_s: float = p.UNENCRYPTED_TIMEOUT_MS / 1000):
        self.device = device
        self.transport_spec = transport
        self.name = name
        self.address = address or address_for_name(name)
        self.keys_path = keys_path
        self.log = log
        self.unencrypted_timeout_s = unencrypted_timeout_s
        self.bumble = None  # bumble.device.Device once started
        self._transport = None
        self._conn = None
        self._chars: dict[str, object] = {}
        self._timeout_task: asyncio.Task | None = None
        self.drop_after_data: int | None = None  # test hook: drop the link after N more DATA chunks

    # --- PeripheralLink ------------------------------------------------------------------
    @property
    def max_notify_len(self) -> int:
        conn = self._conn
        return (conn.att_mtu if conn is not None else 23) - 3

    @property
    def connected(self) -> bool:
        return self._conn is not None

    async def notify(self, char_uuid: str, value: bytes) -> None:
        conn = self._conn
        if conn is None:
            raise TransportError("central disconnected")
        # Bumble queues notifications without limit; wait while the controller has a window's
        # worth in flight so a FETCH stream doesn't pile up seconds of data ahead of ABORT.
        queue = self.bumble.host.le_acl_packet_queue or self.bumble.host.acl_packet_queue
        while queue is not None and queue.pending > NOTIFY_WINDOW:
            await asyncio.sleep(0.002)
            if self._conn is not conn:
                raise TransportError("central disconnected")
        if char_uuid == p.DATA_UUID and self.drop_after_data is not None:
            if self.drop_after_data <= 0:
                self.drop_after_data = None
                self.log("dropping the link mid-FETCH (test hook)")
                await self._disconnect(conn, HCI_REMOTE_USER_TERMINATED)
                raise TransportError("link dropped (test hook)")
            self.drop_after_data -= 1
        await self.bumble.notify_subscriber(conn, self._chars[char_uuid], value)

    def drop_central(self) -> None:
        """UNPAIR: the model already emptied its bond list; delete the keys to match, let the
        queued response reach the phone, then drop the link (§2.2 op 7)."""
        conn = self._conn
        asyncio.get_running_loop().create_task(self._unpair(conn))

    async def _unpair(self, conn) -> None:
        await self._sync_keystore()
        if conn is not None:
            try:
                await asyncio.wait_for(conn.drain(), 2.0)
            except (asyncio.TimeoutError, ValueError):
                pass
            await self._disconnect(conn, HCI_REMOTE_USER_TERMINATED)
        await self.device._notify_status()

    # --- security --------------------------------------------------------------------------
    def peer_id(self, conn) -> str:
        # After pairing (or with a stored IRK) Bumble resolves the phone's private address to
        # its identity address, which is also the key under which the keystore keeps the bond.
        return str(conn.peer_address)

    def _security_error(self, conn) -> int | None:
        return att_security_error(bool(conn.is_encrypted), self.device.is_bonded(self.peer_id(conn)))

    def _check_security(self, bearer) -> None:
        from bumble import att

        conn = getattr(bearer, "connection", bearer)  # EATT bearer or plain Connection
        err = self._security_error(conn)
        if err is not None:
            self.log(f"ATT error 0x{err:02x} for {self.peer_id(conn)} (not encrypted)")
            raise att.ATT_Error(err)

    async def _sync_keystore(self) -> None:
        """Delete keys the model no longer counts as bonds (bond replaced, UNPAIR)."""
        ks = self.bumble.keystore
        for name, _keys in await ks.get_all():
            if name not in self.device.bonds:
                await ks.delete(name)
                self.log(f"bond with {name} deleted")
        await self.bumble.refresh_resolving_list()

    def _session_class(self):
        from bumble import smp

        periph = self

        class OwlSmpSession(smp.Session):
            async def on_smp_pairing_request_command_async(self, command) -> None:
                peer = periph.peer_id(self.connection)
                reason = pairing_refusal(command.auth_req, periph.device.pairing_open())
                if reason is not None:
                    periph.log(f"pairing refused for {peer} (auth_req 0x{command.auth_req:02x}, "
                               f"SMP reason 0x{reason:02x})")
                    self.send_pairing_failed(smp.ErrorCode(reason))
                    return
                periph.log(f"pairing request from {peer} accepted")
                await super().on_smp_pairing_request_command_async(command)

        return OwlSmpSession

    # --- connection events -----------------------------------------------------------------
    def _on_connection(self, conn) -> None:
        if self._conn is not None:  # one central at a time, like CONFIG_BT_MAX_CONN=1
            asyncio.get_running_loop().create_task(self._disconnect(conn, HCI_REMOTE_USER_TERMINATED))
            return
        self._conn = conn
        peer = self.peer_id(conn)
        bonded = self.device.is_bonded(peer)
        self.log(f"connected: {peer}{' (bonded)' if bonded else ''}")
        conn.on("disconnection", lambda reason: self._on_disconnection(conn, reason))
        conn.on("pairing", lambda keys: self._on_pairing(conn))
        conn.on("pairing_failure", lambda reason: self.log(f"pairing failed: SMP reason {int(reason):#04x}"))
        conn.on("connection_encryption_change", lambda: self.log(f"encryption {'on' if conn.is_encrypted else 'off'}"))
        conn.on("connection_encryption_failure",
                lambda error: self.log(f"encryption failed: HCI 0x{int(error):02x} (phone has a key we don't)"))
        conn.on("connection_att_mtu_update", lambda: self.log(f"MTU {conn.att_mtu}"))
        self.device.on_connect(self)
        if bonded:
            # §2.4 step 1: Android 12-15 only encrypts when asked, so ask a bonded phone at once.
            self.bumble.request_pairing(conn)
        self._timeout_task = asyncio.get_running_loop().create_task(self._unencrypted_timeout(conn))

    def _on_disconnection(self, conn, reason: int) -> None:
        if self._conn is not conn:
            return
        self._conn = None
        if self._timeout_task:
            self._timeout_task.cancel()
            self._timeout_task = None
        self.log(f"disconnected (HCI 0x{int(reason):02x})")
        self.device.on_disconnect()

    def _on_pairing(self, conn) -> None:
        self.device.on_bonded(self.peer_id(conn))
        loop = asyncio.get_running_loop()

        async def after():
            await self._sync_keystore()  # the previous phone's bond goes now, not before
            await self.device._notify_status()  # bit4 closed

        loop.create_task(after())

    async def _unencrypted_timeout(self, conn) -> None:
        await asyncio.sleep(self.unencrypted_timeout_s)
        if self._conn is conn and not conn.is_encrypted:
            self.log(f"not encrypted after {self.unencrypted_timeout_s:.0f} s, disconnecting")
            await self._disconnect(conn, HCI_AUTHENTICATION_FAILURE)

    async def _disconnect(self, conn, reason: int) -> None:
        async def run():
            try:
                await conn.disconnect(reason)
            except Exception as e:  # noqa: BLE001 - already gone
                self.log(f"disconnect: {e}")

        # Shielded: the caller is often a task the disconnection itself cancels (the FETCH
        # stream, the unencrypted timer). Cancelling Bumble's disconnect() midway leaves it a
        # cancelled future that its disconnection event then tries to resolve (InvalidStateError).
        await asyncio.shield(asyncio.get_running_loop().create_task(run()))

    async def reboot(self) -> None:
        """Power cycle: the link drops, boot_count+1, the pairing window opens again."""
        if self._conn is not None:
            await self._disconnect(self._conn, HCI_REMOTE_USER_TERMINATED)
        self.device.reboot(p.CAUSE_BUTTON)

    # --- GATT ------------------------------------------------------------------------------
    def _build_service(self):
        from bumble import att, gatt

        Props = gatt.Characteristic.Properties
        server = self.bumble.gatt_server

        def guarded_cccd(ch):
            def write(bearer, value):
                self._check_security(bearer)
                server.write_cccd(bearer, ch, value)

            # Reading a CCCD is harmless (firmware: BT_GATT_PERM_READ | BT_GATT_PERM_WRITE_ENCRYPT).
            return gatt.Descriptor(gatt.GATT_CLIENT_CHARACTERISTIC_CONFIGURATION_DESCRIPTOR,
                                   att.Attribute.READABLE | att.Attribute.WRITEABLE,
                                   att.AttributeValueV2(lambda bearer: server.read_cccd(bearer, ch), write=write))

        def control_write(bearer, value):
            self._check_security(bearer)
            asyncio.get_running_loop().create_task(self.device.handle_control(bytes(value)))

        status = gatt.Characteristic(p.STATUS_UUID, Props.READ | Props.NOTIFY, att.Attribute.READABLE,
                                     att.AttributeValueV2(lambda _bearer: self.device.status().pack()))
        control = gatt.Characteristic(p.CONTROL_UUID, Props.WRITE | Props.NOTIFY, att.Attribute.WRITEABLE,
                                      att.AttributeValueV2(write=control_write))
        data = gatt.Characteristic(p.DATA_UUID, Props.NOTIFY, att.Attribute.Permissions(0), b"")
        for ch in (status, control, data):
            ch.descriptors = [guarded_cccd(ch)]
        self._chars = {p.STATUS_UUID: status, p.CONTROL_UUID: control, p.DATA_UUID: data}
        return gatt.Service(p.SERVICE_UUID, [status, control, data])

    # --- lifecycle -------------------------------------------------------------------------
    async def start(self, hci_source=None, hci_sink=None) -> None:
        """Open the transport (or use the given HCI pipes, for tests) and start advertising."""
        from bumble import hci
        from bumble.core import UUID, AdvertisingData
        from bumble.device import Device, DeviceConfiguration
        from bumble.keys import JsonKeyStore, MemoryKeyStore
        from bumble.pairing import PairingConfig, PairingDelegate

        if hci_source is None:
            from bumble.transport import open_transport

            self._transport = await open_transport(self.transport_spec)
            hci_source, hci_sink = self._transport.source, self._transport.sink

        cfg = DeviceConfiguration(name=self.name, address=hci.Address(self.address))
        dev = Device.from_config_with_hci(cfg, hci_source, hci_sink)
        self.bumble = dev
        dev.keystore = JsonKeyStore(self.address, self.keys_path) if self.keys_path else MemoryKeyStore()
        dev.pairing_config_factory = lambda _conn: PairingConfig(
            sc=True, mitm=False, bonding=True,
            delegate=PairingDelegate(PairingDelegate.NO_OUTPUT_NO_INPUT),
            identity_address_type=PairingConfig.AddressType.RANDOM)
        dev.smp_session_proxy = self._session_class()
        dev.add_service(self._build_service())
        dev.gatt_server.max_mtu = p.ATT_MTU  # firmware: 247 (§2.3)
        dev.on("connection", self._on_connection)
        await dev.power_on()

        self.device.bonds = [name for name, _k in await dev.keystore.get_all()]
        if self.device.bonds:
            self.log(f"bonded with {', '.join(self.device.bonds)} (from {self.keys_path})")
        # 3 (flags) + 10 (name) + 18 (128-bit UUID) = 31 bytes: fits a legacy advertisement.
        dev.advertising_data = bytes(AdvertisingData([
            (AdvertisingData.FLAGS, bytes([0x06])),  # LE General Discoverable, no BR/EDR
            (AdvertisingData.COMPLETE_LOCAL_NAME, self.name.encode()),
            (AdvertisingData.COMPLETE_LIST_OF_128_BIT_SERVICE_CLASS_UUIDS, UUID(p.SERVICE_UUID).to_bytes()),
        ]))
        await dev.start_advertising(auto_restart=True, own_address_type=hci.OwnAddressType.RANDOM)
        self.log(f"advertising as {self.name} ({self.address}) on {self.transport_spec or 'custom HCI'}")

    async def stop(self) -> None:
        if self.bumble is not None:
            try:
                if self._conn is not None:
                    await self._disconnect(self._conn, HCI_REMOTE_USER_TERMINATED)
                await self.bumble.power_off()
            except Exception:  # noqa: BLE001 - shutting down anyway
                pass
        if self._transport is not None:
            await self._transport.close()
