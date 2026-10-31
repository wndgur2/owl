"""Checks that tools/owlproto.py matches the numbers in protocol/ble-protocol.md.

Run from the repo root:  python -m pytest tools/tests   (or)   python tools/tests/test_owlproto.py
"""

import os
import random
import sys
import zlib

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import owlproto as p  # noqa: E402


def test_sizes_match_spec():
    assert p.DATA_CHUNK_MAX == 236
    assert p.LIST_MAX_PER_RESPONSE == 26
    assert len(p.Status((0, 1, 0), 3900, 0, 0, 0, 0, 0).pack()) == 24
    hdr = p.SegmentHeader(1, 1, 20, 7, 3, 1234, 16000, 1, 0)
    assert len(hdr.pack()) == 32


def test_uuids():
    assert p.SERVICE_UUID == "a2690001-8c8b-40f7-a91b-3ffa7ab560b9"
    assert p.DATA_UUID == "a2690004-8c8b-40f7-a91b-3ffa7ab560b9"


def test_status_roundtrip():
    s = p.Status((0, 1, 2), 3850, p.FLAG_RECORDING | p.FLAG_CHARGING, 1024, 16384, 5, 42)
    assert p.Status.unpack(s.pack()) == s


def test_segment_roundtrip_and_header_fields():
    rnd = random.Random(1)
    frames = [bytes(rnd.getrandbits(8) for _ in range(rnd.randint(20, 80))) for _ in range(50)]
    hdr = p.SegmentHeader(p.SEG_TYPE_AUDIO, p.CODEC_OPUS, 20, 99, 3, 600_000, 16000, 1, 0)
    seg = p.build_audio_segment(hdr, frames)
    back = p.SegmentHeader.unpack(seg)
    assert back.sample_rate == 16000 and back.seg_id == 99 and back.payload_len == len(seg) - 32
    assert list(p.iter_audio_frames(seg)) == frames


def test_chunking_with_loss_and_resume():
    blob = bytes(range(256)) * 20  # 5120 bytes
    asm = p.SegmentAssembler(7, len(blob))
    chunks = list(p.chunk_segment(7, blob))
    assert all(len(c) <= p.ATT_PAYLOAD for c in chunks)
    for i, c in enumerate(chunks):
        if i != 5:  # simulate one lost notification
            asm.feed(c)
    gap = asm.first_gap()
    assert gap == 5 * p.DATA_CHUNK_MAX
    for c in p.chunk_segment(7, blob, start=gap):  # FETCH_SEGMENT(id, offset=gap)
        asm.feed(c)
    assert asm.complete() and asm.crc32() == zlib.crc32(blob)


def test_list_segments_roundtrip():
    entries = [(i, p.SEG_TYPE_AUDIO, 1000 + i) for i in range(26)]
    payload = p.build_list_segments(entries)
    assert 3 + len(payload) <= p.ATT_PAYLOAD
    assert p.parse_list_segments(payload) == entries


def test_control_response():
    r = p.Response(p.OP_TIME_SYNC, 9, p.RESULT_OK, (3).to_bytes(4, "little") + (5000).to_bytes(8, "little"))
    back = p.Response.unpack(r.pack())
    assert back.op == p.OP_TIME_SYNC and p.parse_time_sync_response(back.payload) == (3, 5000)


def test_event_segment_roundtrip():
    events = [
        (1_000, p.EVT_BOOKMARK, 0),
        (2_000, p.EVT_RECORDING_OFF, p.CAUSE_BUTTON),
        (3_000, p.EVT_RECORDING_ON, p.CAUSE_APP),
        (4_000, p.EVT_POWER_OFF, p.CAUSE_BATTERY),
    ]
    hdr = p.SegmentHeader(p.SEG_TYPE_EVENTS, p.CODEC_PCM16, 0, 12, 3, 0, 16000, 1, 0)
    seg = p.build_event_segment(hdr, events)
    assert seg[:4] == b"OWLS" and len(seg) == 32 + 10 * len(events)
    assert list(p.iter_events(seg)) == events


def test_set_recording_request():
    assert p.set_recording_request(4, False) == bytes([0x06, 4, 0])
    assert p.set_recording_request(5, True) == bytes([0x06, 5, 1])


def test_time_mapping():
    syncs = {3: (10_000, 1_700_000_000_000)}
    assert p.to_unix_ms(3, 70_000, syncs) == 1_700_000_060_000
    assert p.to_unix_ms(4, 1, syncs) is None


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok", name)
