"""BLE security model (protocol §2.4) over the loopback: bonding, the power-on
pairing window, bond replacement, UNPAIR, and what a refused central sees."""

import asyncio

import pytest

import owlproto as p
from owlsync.client import OwlClient
from owlsync.device import OwlDevice
from owlsync.engine import SyncEngine, SyncFailed, SyncOptions
from owlsync.synth import populate
from owlsync.transport import LoopbackTransport, NotPaired

FAST = dict(idle_timeout=0.05, response_timeout=0.5, backoff_base=0.001, backoff_max=0.01)
WINDOW_S = p.PAIRING_WINDOW_MS / 1000


class FakeClock:
    def __init__(self, t=1000.0):
        self.t = t

    def __call__(self):
        return self.t


def make_device(clock):
    dev = OwlDevice(boot_count=5, uptime_offset_ms=3_600_000, clock=clock)
    populate(dev, audio_segments=2, seconds=0.5, codec="pcm")
    return dev


def sync(dev, out, peer="pc", auto_pair=True):
    t = LoopbackTransport(dev, peer=peer, auto_pair=auto_pair)
    return asyncio.run(SyncEngine(t, SyncOptions(str(out), **FAST), log=lambda _m: None).run()), t


async def read_status_unpaired(dev, peer):
    t = LoopbackTransport(dev, peer=peer, auto_pair=False)
    await t.connect()
    try:
        return p.Status.unpack(await t.read_status()), t
    finally:
        await t.disconnect()


def test_unbonded_device_pairs_on_first_sync(tmp_path):
    clock = FakeClock()
    dev = make_device(clock)
    clock.t += 3600  # long after power-on: nothing bonded, so pairing is still open
    assert dev.status().flags & p.FLAG_PAIRING_OPEN
    n = len(dev.segments) + 1  # + the event log sealed on connect
    report, _t = sync(dev, tmp_path)
    assert dev.bonds == ["pc"] and len(report.acked) == n
    assert not dev.status().flags & p.FLAG_PAIRING_OPEN  # one new bond per window, and window over


def test_bonded_peer_reconnects_without_pairing(tmp_path):
    clock = FakeClock()
    dev = make_device(clock)
    sync(dev, tmp_path / "a")
    clock.t += 10 * 3600
    sid = dev.add_audio_segment([b"\x01\x02"], codec=p.CODEC_PCM16)
    report, _t = sync(dev, tmp_path / "b", auto_pair=False)  # would fail at CONTROL if not encrypted
    assert report.acked == [sid]


def test_stranger_is_refused_and_nothing_is_deleted(tmp_path):
    clock = FakeClock()
    dev = make_device(clock)
    sync(dev, tmp_path / "owner")
    dev.add_audio_segment([b"\x01\x02"], codec=p.CODEC_PCM16)
    before = dict(dev.segments)
    clock.t += WINDOW_S + 1

    t = LoopbackTransport(dev, peer="stranger")
    with pytest.raises(SyncFailed, match="Owl only talks to its bonded"):
        asyncio.run(SyncEngine(t, SyncOptions(str(tmp_path / "s"), **FAST), log=lambda _m: None).run())
    assert t.connect_count == 1, "NotPaired is final, no reconnect loop"
    assert dev.segments == before and dev.bonds == ["pc"]

    # STATUS is still readable on a plain link, CONTROL is not.
    st, _ = asyncio.run(read_status_unpaired(dev, "stranger"))
    assert st.pending_segments == len(before) and not st.flags & p.FLAG_PAIRING_OPEN

    async def control_unencrypted():
        t2 = LoopbackTransport(dev, peer="stranger", auto_pair=False)
        await t2.connect()
        with pytest.raises(NotPaired, match="0x05"):
            await t2.write_control(p.list_segments_request(1, 0))
    asyncio.run(control_unencrypted())


def test_window_after_power_on_replaces_old_bond(tmp_path):
    clock = FakeClock()
    dev = make_device(clock)
    sync(dev, tmp_path / "old", peer="old-phone")
    clock.t += 7200

    dev.reboot(p.CAUSE_BUTTON)  # owner lost the phone: turn the Owl off and on
    clock.t += WINDOW_S - 5
    assert dev.status().flags & p.FLAG_PAIRING_OPEN
    sync(dev, tmp_path / "new", peer="new-phone")
    assert dev.bonds == ["new-phone"], "the new bond replaces the old one"

    with pytest.raises(SyncFailed):
        sync(dev, tmp_path / "old2", peer="old-phone")  # still inside 120 s, but the window is used


def test_window_closes_after_120_s(tmp_path):
    clock = FakeClock()
    dev = make_device(clock)
    sync(dev, tmp_path / "a", peer="phone")
    dev.reboot()
    clock.t += WINDOW_S
    with pytest.raises(SyncFailed):
        sync(dev, tmp_path / "b", peer="other")
    assert dev.bonds == ["phone"]


def test_unpair_reopens_pairing(tmp_path):
    clock = FakeClock()
    dev = make_device(clock)
    sync(dev, tmp_path / "a", peer="phone")
    clock.t += 3600

    async def unpair():
        t = LoopbackTransport(dev, peer="phone")
        c = OwlClient(t, response_timeout=0.5, log=lambda _m: None)
        await c.connect()
        await c.unpair()
        await asyncio.sleep(0)
        await asyncio.sleep(0)
        return t
    t = asyncio.run(unpair())
    assert dev.bonds == [] and not t.is_connected, "device dropped the link after UNPAIR"
    assert dev.status().flags & p.FLAG_PAIRING_OPEN
    sync(dev, tmp_path / "b", peer="someone")  # anyone may register it now
    assert dev.bonds == ["someone"]


def test_cli_fake_status_pair_unpair(capsys):
    import sync as cli

    assert cli.main(["--fake", "--fake-segments", "1", "status"]) == 0
    assert "pairing-open" in capsys.readouterr().out
    assert cli.main(["--fake", "--fake-segments", "1", "pair"]) == 0
    assert "paired with Owl-LOOP" in capsys.readouterr().out
    assert cli.main(["--fake", "--fake-segments", "1", "unpair"]) == 0


def test_bleak_error_mapping():
    pytest.importorskip("bleak")
    from bleak.exc import BleakGATTProtocolError

    from owlsync.ble import is_auth_error

    assert is_auth_error(BleakGATTProtocolError(p.ATT_ERR_INSUFFICIENT_AUTHENTICATION))
    assert is_auth_error(BleakGATTProtocolError(p.ATT_ERR_INSUFFICIENT_ENCRYPTION))
    assert not is_auth_error(BleakGATTProtocolError(0x0E))  # Unlikely Error
    assert is_auth_error(Exception("[org.bluez.Error.NotPermitted] Not paired"))
    assert is_auth_error(Exception("[org.bluez.Error.AuthenticationFailed] Authentication Failed"))
    assert not is_auth_error(Exception("Device with address AA:BB was not found"))
