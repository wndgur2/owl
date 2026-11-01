"""Bumble fake Owl (owlsync/ble_bumble.py): protocol §2.4 over real SMP/ATT.

The pure policy tests need nothing. The rest run two Bumble host stacks in this process,
joined by Bumble's virtual controllers (bumble.link.LocalLink): no radio, no emulator, but
real pairing, encryption, ATT errors and GATT notifications. The central below behaves like
Android where it matters (it pairs or encrypts after ATT 0x05/0x0F, encrypts when the
peripheral sends a Security Request and it has a key).
"""

import asyncio

import pytest

import owlproto as p
from owlsync.ble_bumble import (
    SMP_ERR_AUTH_REQUIREMENTS,
    SMP_ERR_UNSPECIFIED,
    BumblePeripheral,
    address_for_name,
    att_security_error,
    pairing_refusal,
)
from owlsync.client import OwlClient
from owlsync.device import OwlDevice
from owlsync.engine import SyncEngine, SyncFailed, SyncOptions
from owlsync.synth import populate
from owlsync.transport import NotPaired, Transport, TransportError

BONDING, SC = 0x01, 0x08
FAST = dict(idle_timeout=0.5, response_timeout=2.0, backoff_base=0.01, backoff_max=0.05)


# --- pure policy -----------------------------------------------------------------------------
def test_pairing_refusal_matches_zephyr_order_and_codes():
    assert pairing_refusal(BONDING | SC, pairing_open=True) is None
    assert pairing_refusal(BONDING | SC | 0x04, pairing_open=True) is None  # MITM bit asked: still Just Works
    assert pairing_refusal(BONDING | SC, pairing_open=False) == SMP_ERR_UNSPECIFIED  # pairing_accept()
    assert pairing_refusal(SC, pairing_open=True) == SMP_ERR_UNSPECIFIED  # BONDING_REQUIRED
    assert pairing_refusal(BONDING, pairing_open=True) == SMP_ERR_AUTH_REQUIREMENTS  # SC_PAIR_ONLY
    assert pairing_refusal(0, pairing_open=False) == SMP_ERR_UNSPECIFIED  # bonding is checked first


def test_att_error_tells_stranger_from_bonded_peer():
    assert att_security_error(encrypted=True, bonded=False) is None
    assert att_security_error(encrypted=False, bonded=False) == p.ATT_ERR_INSUFFICIENT_AUTHENTICATION
    assert att_security_error(encrypted=False, bonded=True) == p.ATT_ERR_INSUFFICIENT_ENCRYPTION


def test_address_follows_name_suffix():
    assert address_for_name("Owl-FA4E") == "C0:0F:F0:00:FA:4E"
    assert address_for_name("Owl-0001") == "C0:0F:F0:00:00:01"
    assert address_for_name("weird") == "C0:0F:F0:00:00:01"


def test_window_length_is_configurable():
    t = [0.0]
    dev = OwlDevice(clock=lambda: t[0], pairing_window_ms=10_000)
    dev.bonds = ["old"]
    assert dev.pairing_open()
    t[0] = 11
    assert not dev.pairing_open()
    dev.reboot()
    assert dev.pairing_open()


# --- in-process Bumble rig -------------------------------------------------------------------
bumble = pytest.importorskip("bumble")

from bumble import att  # noqa: E402
from bumble.controller import Controller  # noqa: E402
from bumble.core import UUID, ProtocolError  # noqa: E402
from bumble.device import Device, Peer  # noqa: E402
from bumble.hci import Address  # noqa: E402
from bumble.keys import MemoryKeyStore  # noqa: E402
from bumble.link import LocalLink  # noqa: E402
from bumble.pairing import PairingConfig, PairingDelegate  # noqa: E402


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


class Phone:
    """A Bumble central with a persistent identity and keystore (one 'phone')."""

    def __init__(self, link, address: str, sc: bool = True, bonding: bool = True):
        self.address = address
        ctrl = Controller(f"phone-{address}", link=link, public_address=address)
        self.dev = Device.with_hci(f"phone-{address}", Address(address), ctrl, ctrl)
        self.dev.keystore = MemoryKeyStore()
        self.dev.pairing_config_factory = lambda _c: PairingConfig(
            sc=sc, mitm=False, bonding=bonding, delegate=PairingDelegate(),
            identity_address_type=PairingConfig.AddressType.RANDOM)

    @property
    def peer_id(self) -> str:
        return str(Address(self.address))


class CentralTransport(Transport):
    """owlsync Transport over a Bumble central, with Android's security behaviour."""

    def __init__(self, phone: Phone, owl_address: str, *, auto_pair: bool = True,
                 answer_security_request: bool = True):
        super().__init__()
        self.phone = phone
        self.owl = Address(owl_address)
        self.auto_pair = auto_pair
        self.answer_security_request = answer_security_request
        self.conn = None
        self.chars = {}
        self.pairings = 0
        self._sec_task = None

    name = "bumble-central"

    @property
    def is_connected(self) -> bool:
        return self.conn is not None

    @property
    def mtu(self) -> int:
        return self.conn.att_mtu if self.conn else 23

    async def connect(self) -> None:
        conn = await self.phone.dev.connect(self.owl)
        self.conn = conn
        self._sec_task = None
        conn.on("disconnection", lambda _r: self._lost(conn))
        conn.on("security_request", lambda _auth: self._on_security_request(conn))
        try:
            peer = Peer(conn)
            await peer.request_mtu(p.ATT_MTU)
            await peer.discover_services([UUID(p.SERVICE_UUID)])
            svc = peer.get_services_by_uuid(UUID(p.SERVICE_UUID))[0]
            await svc.discover_characteristics()
            for ch in svc.characteristics:
                await ch.discover_descriptors()
            self.chars = {str(ch.uuid).lower(): ch for ch in svc.characteristics}
            handlers = {p.STATUS_UUID: lambda v: self.on_status(bytes(v)),
                        p.CONTROL_UUID: lambda v: self.on_control(bytes(v)),
                        p.DATA_UUID: lambda v: self.on_data(bytes(v))}
            for uuid, fn in handlers.items():
                await self._secured(lambda ch=self.chars[uuid], fn=fn: ch.subscribe(fn))
        except NotPaired:
            await self.disconnect()
            raise
        except (ProtocolError, att.ATT_Error, asyncio.TimeoutError) as e:
            await self.disconnect()
            raise TransportError(f"connect failed: {e}") from e

    def _on_security_request(self, conn) -> None:
        if not self.answer_security_request:
            return

        async def encrypt():
            if await self.phone.dev.keystore.get(str(conn.peer_address)):
                await conn.encrypt()

        self._sec_task = asyncio.get_running_loop().create_task(encrypt())

    async def _secured(self, op):
        """Run a GATT op; on ATT 0x05/0x0F encrypt (have a key) or pair (none), retry once."""
        try:
            return await op()
        except att.ATT_Error as e:
            if e.error_code not in (p.ATT_ERR_INSUFFICIENT_AUTHENTICATION, p.ATT_ERR_INSUFFICIENT_ENCRYPTION):
                raise
            if not self.auto_pair:
                raise NotPaired(f"ATT error 0x{e.error_code:02x}") from e
            await self.secure()
            return await op()

    async def secure(self) -> None:
        conn = self.conn
        if self._sec_task is not None:
            await self._sec_task
        if conn.is_encrypted:
            return
        try:
            if await self.phone.dev.keystore.get(str(conn.peer_address)):
                await conn.encrypt()
            else:
                self.pairings += 1
                await conn.pair()
        except ProtocolError as e:
            raise NotPaired(f"pairing refused: {e}") from e

    def _lost(self, conn) -> None:
        if self.conn is conn:
            self.conn = None
            self.on_disconnect()

    async def disconnect(self) -> None:
        conn, self.conn = self.conn, None
        if conn is not None:
            try:
                await conn.disconnect()
            except Exception:  # noqa: BLE001 - already gone
                pass

    async def read_status(self) -> bytes:
        return bytes(await self.chars[p.STATUS_UUID].read_value())

    async def write_control(self, data: bytes) -> None:
        try:
            await self._secured(lambda: self.chars[p.CONTROL_UUID].write_value(data, with_response=True))
        except att.ATT_Error as e:
            raise TransportError(f"CONTROL write failed: {e}") from e


class Rig:
    def __init__(self, segments: int = 2, **periph_kw):
        self.clock = Clock()
        self.link = LocalLink()
        self.dev = OwlDevice(boot_count=3, uptime_offset_ms=3_600_000, clock=self.clock)
        populate(self.dev, audio_segments=segments, seconds=0.5, codec="pcm")
        self.logs: list[str] = []
        self.owl = BumblePeripheral(self.dev, transport="", name="Owl-7E57", log=self.logs.append, **periph_kw)
        self._ctrl = Controller("owl", link=self.link)
        self.phones: dict[str, Phone] = {}

    async def start(self):
        await self.owl.start(self._ctrl, self._ctrl)
        return self

    async def phone(self, address: str, **kw) -> Phone:
        ph = Phone(self.link, address, **kw)
        await ph.dev.power_on()
        self.phones[address] = ph
        return ph

    def transport(self, phone: Phone, **kw) -> CentralTransport:
        return CentralTransport(phone, self.owl.address, **kw)

    async def sync(self, phone: Phone, **kw):
        report = await SyncEngine(self.transport(phone, **kw), SyncOptions(self.out, render=False, **FAST),
                                  log=lambda _m: None).run()
        await asyncio.sleep(0.05)  # let the peripheral see the disconnection
        return report

    async def keys(self) -> list[str]:
        return [name for name, _k in await self.owl.bumble.keystore.get_all()]


def run(coro):
    return asyncio.run(asyncio.wait_for(coro, 30))


A, B = "F0:00:00:00:00:0A", "F0:00:00:00:00:0B"


def test_first_sync_pairs_bonds_and_downloads_everything(tmp_path):
    async def main():
        rig = await Rig().start()
        rig.out = str(tmp_path)
        a = await rig.phone(A)
        n = len(rig.dev.segments) + 1  # + the event log sealed on connect
        report = await rig.sync(a)
        assert len(report.acked) == n and not rig.dev.segments
        assert rig.dev.bonds == [a.peer_id] and await rig.keys() == [a.peer_id]
        assert not rig.dev.status().flags & p.FLAG_PAIRING_OPEN  # the window gave its one bond
        assert f"MTU {p.ATT_MTU}" in rig.logs  # 236-byte DATA chunks (§2.3)

    run(main())


def test_stranger_reads_status_but_nothing_else(tmp_path):
    async def main():
        rig = await Rig().start()
        t = rig.transport(await rig.phone(A), auto_pair=False)
        with pytest.raises(NotPaired, match="0x05"):
            await t.connect()  # the first CCCD write is refused
        await asyncio.sleep(0.05)
        conn = await rig.phones[A].dev.connect(Address(rig.owl.address))
        peer = Peer(conn)
        await peer.discover_services([UUID(p.SERVICE_UUID)])
        svc = peer.get_services_by_uuid(UUID(p.SERVICE_UUID))[0]
        await svc.discover_characteristics()
        chars = {str(c.uuid).lower(): c for c in svc.characteristics}
        st = p.Status.unpack(bytes(await chars[p.STATUS_UUID].read_value()))
        assert st.flags & p.FLAG_PAIRING_OPEN and st.pending_segments == len(rig.dev.segments)
        with pytest.raises(att.ATT_Error) as e:
            await chars[p.CONTROL_UUID].write_value(bytes([p.OP_ACK_SEGMENT, 1]) + bytes(8), with_response=True)
        assert e.value.error_code == p.ATT_ERR_INSUFFICIENT_AUTHENTICATION
        assert rig.dev.bonds == []

    run(main())


def test_bonded_phone_gets_security_request_and_0x0f_until_encrypted(tmp_path):
    async def main():
        rig = await Rig().start()
        rig.out = str(tmp_path)
        a = await rig.phone(A)
        await rig.sync(a)
        rig.dev.add_event(p.EVT_BOOKMARK)

        # A phone that ignores the Security Request gets 0x0F (bonded, not encrypted yet) ...
        t = rig.transport(a, auto_pair=False, answer_security_request=False)
        with pytest.raises(NotPaired, match="0x0f"):
            await t.connect()
        await asyncio.sleep(0.05)
        # ... one that answers it encrypts with the stored key, without pairing again.
        t = rig.transport(a)
        await t.connect()
        assert t.conn.is_encrypted and t.pairings == 0
        assert any("connected: " + a.peer_id + " (bonded)" in m for m in rig.logs)
        await t.disconnect()

    run(main())


def test_new_phone_refused_outside_window_and_old_bond_kept(tmp_path):
    async def main():
        rig = await Rig().start()
        rig.out = str(tmp_path)
        a, b = await rig.phone(A), await rig.phone(B)
        await rig.sync(a)
        rig.clock.t += p.PAIRING_WINDOW_MS / 1000 + 1

        with pytest.raises(SyncFailed, match="pairing refused"):
            await rig.sync(b)
        assert rig.dev.bonds == [a.peer_id] and await rig.keys() == [a.peer_id]
        assert any("SMP reason 0x08" in m for m in rig.logs)

        rig.dev.reboot()  # power cycle: window open again, and the new bond replaces the old one
        assert rig.dev.status().flags & p.FLAG_PAIRING_OPEN
        await rig.sync(b)
        assert rig.dev.bonds == [b.peer_id] and await rig.keys() == [b.peer_id]
        # The window gave its bond: phone A cannot take it back in the same boot.
        with pytest.raises(SyncFailed, match="pairing refused"):
            await rig.sync(a)

    run(main())


@pytest.mark.parametrize("kw, reason", [(dict(sc=False), "AUTHENTICATION_REQUIREMENTS"),
                                        (dict(bonding=False), "UNSPECIFIED_REASON")])
def test_legacy_or_non_bonding_pairing_refused(tmp_path, kw, reason):
    async def main():
        rig = await Rig().start()
        rig.out = str(tmp_path)
        with pytest.raises(SyncFailed, match=reason):
            await rig.sync(await rig.phone(A, **kw))
        assert rig.dev.bonds == [] and await rig.keys() == []

    run(main())


def test_unpair_answers_then_forgets_and_drops(tmp_path):
    async def main():
        rig = await Rig().start()
        rig.out = str(tmp_path)
        a = await rig.phone(A)
        await rig.sync(a)
        t = rig.transport(a)
        client = OwlClient(t, **{k: v for k, v in FAST.items() if k.endswith("timeout")})
        await client.connect()
        lost = asyncio.get_running_loop().create_future()
        t.on_disconnect = lambda: lost.done() or lost.set_result(True)
        await client.unpair()  # raises if the response did not arrive
        await asyncio.wait_for(lost, 3)
        assert rig.dev.bonds == [] and await rig.keys() == []
        assert rig.dev.status().flags & p.FLAG_PAIRING_OPEN  # no bond -> anyone may pair

    run(main())


def test_unencrypted_link_is_dropped_with_auth_failure():
    async def main():
        rig = await Rig(unencrypted_timeout_s=0.3).start()
        a = await rig.phone(A)
        conn = await a.dev.connect(Address(rig.owl.address))
        reason = asyncio.get_running_loop().create_future()
        conn.on("disconnection", lambda r: reason.done() or reason.set_result(r))
        assert await asyncio.wait_for(reason, 3) == 0x05
        assert any("not encrypted after" in m for m in rig.logs)

    run(main())


def test_fetch_resumes_after_link_drop(tmp_path):
    async def main():
        rig = await Rig(segments=1).start()
        rig.out = str(tmp_path)
        rig.dev.add_audio_segment([bytes(200)] * 60, codec=p.CODEC_PCM16)  # ~12 KB, ~50 chunks
        rig.owl.drop_after_data = 20
        report = await rig.sync(await rig.phone(A))
        assert report.reconnects >= 1 and not rig.dev.segments
        assert any(off > 0 for _sid, off in rig.dev.fetch_requests)

    run(main())
