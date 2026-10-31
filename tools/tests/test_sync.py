"""End-to-end sync over the in-process loopback: sync engine <-> fake device.

No hardware, no Bluetooth, no optional dependencies. Fault cases come from
transport.Faults: lost DATA, disconnects, CRC mismatch, BUSY, lost responses.
"""

import asyncio
import csv
import json
import os
import struct
import zlib

import pytest

import owlproto as p
from owlsync.client import OwlClient
from owlsync.device import OwlDevice
from owlsync.engine import SyncEngine, SyncFailed, SyncOptions
from owlsync.synth import populate
from owlsync.transport import Faults, LoopbackTransport

FAST = dict(idle_timeout=0.05, response_timeout=0.5, backoff_base=0.001, backoff_max=0.01)


class FakeClock:
    def __init__(self, t=1000.0):
        self.t = t

    def __call__(self):
        return self.t


def make_device(n_audio=3, seconds=1.0, codec="pcm", **kw):
    dev = OwlDevice(boot_count=5, uptime_offset_ms=3_600_000, **kw)
    populate(dev, audio_segments=n_audio, seconds=seconds, codec=codec)
    return dev


def originals(dev):
    return {sid: s.blob for sid, s in dev.segments.items()}


def run_sync(dev, out, faults=None, mtu=p.ATT_MTU, **opts):
    t = LoopbackTransport(dev, faults, mtu=mtu)
    o = SyncOptions(str(out), **{**FAST, **opts})
    return asyncio.run(SyncEngine(t, o, log=lambda _m: None).run()), t


def raw(out, sid):
    with open(os.path.join(out, "raw", f"seg_{sid:06d}.owls"), "rb") as f:
        return f.read()


def assert_all_saved(out, blobs):
    for sid, blob in blobs.items():
        assert raw(out, sid) == blob, f"segment {sid} differs"


# --- happy path ----------------------------------------------------------------
def test_clean_sync_saves_acks_and_renders(tmp_path):
    dev = make_device()
    dev.seal_events()  # make the open event log part of the expected set
    blobs = originals(dev)
    r, _ = run_sync(dev, tmp_path)
    assert set(blobs) <= set(r.acked)  # plus the event log sealed on connect
    assert dev.segments == {}
    assert_all_saved(tmp_path, blobs)
    rows = list(csv.DictReader(open(tmp_path / "segments.csv")))
    assert len(rows) == len(blobs)
    for row in rows:
        assert row["file"] and os.path.exists(tmp_path / row["file"])
        assert not row["problems"]
    assert not list((tmp_path / "raw").glob("*.part"))


def test_ack_only_after_segment_is_durable_on_disk(tmp_path):
    checked = []

    def on_ack(sid):
        # The device processes ACK: the phone side file must already be complete.
        blob = dev.segments[sid].blob
        assert raw(tmp_path, sid) == blob
        assert not os.path.exists(tmp_path / "raw" / f"seg_{sid:06d}.owls.tmp")
        checked.append(sid)

    dev = make_device(on_ack=on_ack)
    r, _ = run_sync(dev, tmp_path)
    assert checked and sorted(checked) == sorted(r.acked)


def test_no_ack_keeps_everything_and_second_run_does_not_refetch(tmp_path):
    dev = make_device()
    r1, _ = run_sync(dev, tmp_path, ack=False)
    blobs = originals(dev)
    assert r1.acked == [] and len(dev.segments) == len(blobs)
    assert_all_saved(tmp_path, blobs)
    dev.fetch_requests.clear()
    r2, _ = run_sync(dev, tmp_path)
    assert dev.fetch_requests == []  # everything was already on disk
    assert sorted(r2.already_had) == sorted(blobs)
    assert dev.segments == {}


# --- faults --------------------------------------------------------------------
def test_dropped_chunks_are_refetched_from_first_gap(tmp_path):
    dev = make_device(n_audio=2, seconds=2.0)
    blobs = originals(dev)
    sid = min(s for s, b in blobs.items() if len(b) > 10 * p.DATA_CHUNK_MAX)
    size = len(blobs[sid])
    last = (size - 1) // p.DATA_CHUNK_MAX * p.DATA_CHUNK_MAX
    f = Faults(drop_data={(sid, 3 * p.DATA_CHUNK_MAX), (sid, 7 * p.DATA_CHUNK_MAX), (sid, last)})
    r, _ = run_sync(dev, tmp_path, f)
    assert not f.drop_data, "every planned drop happened"
    assert_all_saved(tmp_path, blobs)
    offsets = [o for s, o in dev.fetch_requests if s == sid]
    assert offsets[0] == 0 and 3 * p.DATA_CHUNK_MAX in offsets and last in offsets
    assert sid in r.acked


def test_random_loss(tmp_path):
    dev = make_device(n_audio=3, seconds=2.0)
    blobs = originals(dev)
    r, _ = run_sync(dev, tmp_path, Faults(drop_rate=0.15, seed=7))
    assert_all_saved(tmp_path, blobs)
    assert not set(blobs) & set(dev.segments)
    assert r.reconnects == 0


def test_disconnect_mid_fetch_resumes_at_first_missing_offset(tmp_path):
    dev = make_device(n_audio=1, seconds=3.0)
    blobs = originals(dev)
    sid = max(blobs, key=lambda s: len(blobs[s]))
    first = min(blobs)
    # disconnect after 10 chunks of the big segment (count the chunks of earlier ones too)
    before = sum(-(-len(b) // p.DATA_CHUNK_MAX) for s, b in blobs.items() if s < sid)
    f = Faults(disconnect_after_data=before + 10)
    r, t = run_sync(dev, tmp_path, f)
    assert t.connect_count == 2 and r.reconnects == 1
    assert r.resumed_from == {sid: 10 * p.DATA_CHUNK_MAX}
    assert (sid, 10 * p.DATA_CHUNK_MAX) in dev.fetch_requests
    assert_all_saved(tmp_path, blobs)
    assert first in r.acked


def test_resume_survives_a_new_process(tmp_path):
    """Interrupted run gives up; a fresh engine (new process) continues from the .part file."""
    dev = make_device(n_audio=1, seconds=3.0, codec="pcm")
    blobs = originals(dev)
    sid = max(blobs, key=lambda s: len(blobs[s]))
    before = sum(-(-len(b) // p.DATA_CHUNK_MAX) for s, b in blobs.items() if s < sid)
    f = Faults(disconnect_after_data=before + 25)
    t = LoopbackTransport(dev, f)

    # Make every reconnect fail by dropping the link immediately after connecting.
    orig_connect = t.connect

    async def connect_then_fail():
        if t.connect_count >= 1:
            raise ConnectionError("radio off")
        await orig_connect()

    t.connect = connect_then_fail
    with pytest.raises(SyncFailed):
        asyncio.run(SyncEngine(t, SyncOptions(str(tmp_path), **{**FAST, "max_attempts": 2}),
                               log=lambda _m: None).run())
    part = tmp_path / "raw" / f"seg_{sid:06d}.owls.part"
    assert part.read_bytes() == blobs[sid][: 25 * p.DATA_CHUNK_MAX]

    dev.fetch_requests.clear()
    sent_before = dev.bytes_sent
    r, _ = run_sync(dev, tmp_path)
    assert (sid, 25 * p.DATA_CHUNK_MAX) in dev.fetch_requests
    assert dev.bytes_sent - sent_before < sum(len(b) for b in blobs.values())  # nothing resent twice
    assert_all_saved(tmp_path, blobs)
    assert not part.exists()


def test_crc_mismatch_quarantines_and_refetches(tmp_path):
    dev = make_device(n_audio=1)
    blobs = originals(dev)
    sid = max(blobs, key=lambda s: len(blobs[s]))
    f = Faults(corrupt_data={(sid, p.DATA_CHUNK_MAX)})
    r, _ = run_sync(dev, tmp_path, f)
    assert r.crc_retries == 1 and sid in r.acked
    bad = list((tmp_path / "raw").glob(f"seg_{sid:06d}.owls.bad*"))
    assert len(bad) == 1 and bad[0].read_bytes() != blobs[sid]
    assert_all_saved(tmp_path, blobs)


def test_busy_is_retried_with_backoff(tmp_path):
    dev = make_device(n_audio=1)
    blobs = originals(dev)
    f = Faults(busy={p.OP_TIME_SYNC: 1, p.OP_LIST_SEGMENTS: 2, p.OP_FETCH_SEGMENT: 3, p.OP_ACK_SEGMENT: 2})
    r, _ = run_sync(dev, tmp_path, f)
    assert all(v == 0 for v in f.busy.values())
    assert set(blobs) <= set(r.acked)  # plus the event log sealed on connect


def test_lost_ack_response_is_harmless(tmp_path):
    dev = make_device(n_audio=2)
    blobs = originals(dev)
    r, t = run_sync(dev, tmp_path, Faults(disconnect_after_ack=True))
    assert t.connect_count == 2
    assert_all_saved(tmp_path, blobs)
    assert not set(blobs) & set(dev.segments)


def test_lost_control_response_times_out_and_reconnects(tmp_path):
    dev = make_device(n_audio=1)
    blobs = originals(dev)
    r, t = run_sync(dev, tmp_path, Faults(lose_response={p.OP_LIST_SEGMENTS: 1}), response_timeout=0.1)
    assert t.connect_count == 2
    assert_all_saved(tmp_path, blobs)


def test_gives_up_after_attempts_without_progress(tmp_path):
    dev = make_device(n_audio=1)
    with pytest.raises(SyncFailed):
        run_sync(dev, tmp_path, Faults(lose_response={p.OP_TIME_SYNC: 99}), response_timeout=0.05, max_attempts=3)


# --- LIST paging, MTU ----------------------------------------------------------
def _many_segments(n):
    dev = OwlDevice(boot_count=1, uptime_offset_ms=10_000_000)
    for i in range(n):
        dev.add_audio_segment([bytes([i % 256]) * 40] * 3, codec=p.CODEC_PCM16, start_uptime_ms=1000 + i)
    return dev


def test_list_paging_over_26_segments(tmp_path):
    dev = _many_segments(60)
    blobs = originals(dev)

    async def go():
        t = LoopbackTransport(dev)
        c = OwlClient(t, response_timeout=0.5)
        await c.connect()
        entries = await c.list_all()
        await c.close()
        return entries

    entries = asyncio.run(go())
    assert [e[0] for e in entries] == sorted(blobs)
    r, _ = run_sync(dev, tmp_path)
    assert len(r.acked) == 60
    assert_all_saved(tmp_path, blobs)


def test_small_mtu_shrinks_chunks_and_list_pages(tmp_path):
    dev = _many_segments(30)
    big = dev.add_audio_segment([b"\x01\x02" * 300] * 5, codec=p.CODEC_PCM16)
    blobs = originals(dev)
    r, _ = run_sync(dev, tmp_path, mtu=100)  # notify payload 97 -> DATA chunk 89, LIST page 10
    assert len(r.acked) == 31 and big in r.acked
    assert_all_saved(tmp_path, blobs)


# --- time restoration ----------------------------------------------------------
def test_wall_clock_restored_per_boot_and_unknown_without_sync(tmp_path):
    clock = FakeClock()
    dev = OwlDevice(boot_count=4, uptime_offset_ms=7_200_000, clock=clock)
    populate(dev, audio_segments=2, seconds=0.5, prev_boot_segments=1, codec="pcm")
    hdrs = {sid: p.SegmentHeader.unpack(s.blob) for sid, s in dev.segments.items()}
    r, _ = run_sync(dev, tmp_path)
    ts = r.time_sync
    assert ts.boot_count == 4 and ts.uptime_ms == 7_200_000
    rows = {int(row["id"]): row for row in csv.DictReader(open(tmp_path / "segments.csv"))}
    for sid, h in hdrs.items():
        row = rows[sid]
        if h.boot_count == 4:
            assert int(row["start_unix_ms"]) == ts.unix_ms + (h.start_uptime_ms - ts.uptime_ms)
        else:
            assert h.boot_count == 3 and row["start_time"] == "unknown" and row["start_unix_ms"] == ""
    events = list(csv.DictReader(open(tmp_path / "events.csv")))
    assert {e["event"] for e in events} >= {"power_on", "bookmark", "power_off"}
    for e in events:
        assert (e["time"] == "unknown") == (e["boot_count"] == "3")
    stored = json.load(open(tmp_path / "timesync.json"))["syncs"]
    assert stored[0]["boot_count"] == 4


def test_timestore_picks_nearest_sync_in_same_boot(tmp_path):
    from owlsync.timesync import SyncRecord, TimeStore

    s = TimeStore(str(tmp_path / "t.json"))
    s.add(SyncRecord(2, 1_000, 1_700_000_000_000))
    s.add(SyncRecord(2, 1_000_000, 1_700_000_999_500))  # device clock 0.5 s slow over ~1000 s
    s.add(SyncRecord(3, 5_000, 1_800_000_000_000))
    assert s.resolve(2, 2_000) == 1_700_000_001_000
    assert s.resolve(2, 999_000) == 1_700_000_998_500
    assert s.resolve(3, 6_000) == 1_800_000_001_000
    assert s.resolve(1, 6_000) is None
    assert TimeStore(str(tmp_path / "t.json")).resolve(3, 6_000) == 1_800_000_001_000  # persisted


# --- device side ---------------------------------------------------------------
def _exchange(dev, requests):
    async def go():
        got = []
        t = LoopbackTransport(dev)
        t.on_control = got.append
        await t.connect()
        for r in requests:
            await t.write_control(r)
            await asyncio.sleep(0.01)
        await t.disconnect()
        return [p.Response.unpack(g) for g in got]

    return asyncio.run(go())


def test_device_result_codes():
    dev = make_device(n_audio=1)
    sid = min(dev.segments)
    good_crc = zlib.crc32(dev.segments[sid].blob)
    resps = _exchange(dev, [
        bytes([0x7E, 1]),  # unknown op
        p.request(p.OP_FETCH_SEGMENT, 2, b"\x01"),  # short payload
        p.fetch_segment_request(3, 9999, 0),  # no such segment
        p.ack_segment_request(4, sid, good_crc ^ 1),  # wrong CRC
        p.request(p.OP_SET_CONFIG, 5, struct.pack("<BI", 2, 9)),  # VAD out of range
        p.request(p.OP_SET_CONFIG, 6, struct.pack("<BI", 1, 12000)),
        p.request(p.OP_ABORT, 7),
        p.ack_segment_request(8, sid, good_crc),
        p.ack_segment_request(9, sid, good_crc),  # already gone
    ])
    assert [(r.seq, r.result) for r in resps] == [
        (1, p.RESULT_UNKNOWN_OP), (2, p.RESULT_BAD_LENGTH), (3, p.RESULT_NO_SEGMENT),
        (4, p.RESULT_CRC_MISMATCH), (5, p.RESULT_BAD_LENGTH), (6, p.RESULT_OK), (7, p.RESULT_OK),
        (8, p.RESULT_OK), (9, p.RESULT_NO_SEGMENT),
    ]
    assert dev.config[1] == 12000 and sid not in dev.segments


def test_abort_stops_the_stream():
    dev = OwlDevice()
    sid = dev.add_audio_segment([b"\x00" * 200] * 100, codec=p.CODEC_PCM16)

    async def go():
        data = []
        t = LoopbackTransport(dev)
        t.on_data = data.append
        t.on_control = lambda _b: None
        await t.connect()
        await t.write_control(p.fetch_segment_request(1, sid, 0))
        for _ in range(5):
            await asyncio.sleep(0)
        await t.write_control(p.request(p.OP_ABORT, 2))
        await asyncio.sleep(0.05)
        return data

    data = asyncio.run(go())
    total = -(-len(dev.segments[sid].blob) // p.DATA_CHUNK_MAX)
    assert 0 < len(data) < total


def test_set_recording_updates_status_and_logs_app_event(tmp_path):
    dev = make_device(n_audio=1)

    async def toggle():
        t = LoopbackTransport(dev)
        c = OwlClient(t, response_timeout=0.5)
        await c.connect()
        await c.set_recording(False)
        st = await c.read_status()
        await asyncio.sleep(0.01)
        await c.close()
        return st, c.last_status

    st, notified = asyncio.run(toggle())
    assert not st.flags & p.FLAG_RECORDING and not notified.flags & p.FLAG_RECORDING
    run_sync(dev, tmp_path)  # new connection seals the event log
    events = list(csv.DictReader(open(tmp_path / "events.csv")))
    assert any(e["event"] == "recording_off" and e["cause"] == "app" for e in events)


def test_status_counts_pending_and_storage():
    dev = make_device(n_audio=2)
    st = dev.status()
    assert st.pending_segments == len(dev.segments) and st.boot_count == 5
    assert st.storage_used_kb == -(-sum(len(s.blob) for s in dev.segments.values()) // 1024)
    dev.storage_total_kb = st.storage_used_kb
    assert dev.status().flags & p.FLAG_STORAGE_FULL and dev.status().flags & p.FLAG_STORAGE_LOW


# --- CLI -----------------------------------------------------------------------
def test_sync_cli_with_fake_device(tmp_path, capsys):
    import sync

    assert sync.main(["--fake", "--fake-segments", "2", "sync", "--out", str(tmp_path)]) == 0
    assert "ACKed" in capsys.readouterr().out
    assert (tmp_path / "segments.csv").exists()
    assert sync.main(["render", "--out", str(tmp_path)]) == 0
    assert sync.main(["--fake", "set-config", "vad", "9"]) == 1  # out of range -> device says bad length
