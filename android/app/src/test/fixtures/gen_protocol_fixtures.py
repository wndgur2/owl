"""Prints byte vectors from tools/owlproto.py for android ProtocolFixtures.kt.

Run from the repo root:
    python3 android/app/src/test/fixtures/gen_protocol_fixtures.py > /tmp/fx.txt
and paste the output into ProtocolFixtures.kt when the protocol changes.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "../../../../../tools"))
import owlproto as p  # noqa: E402


def out(name, data):
    print(f'    const val {name} = "{data.hex()}"')


out("STATUS", p.Status((0, 1, 2), 3987, p.FLAG_RECORDING | p.FLAG_STORAGE_LOW, 2150, 16384, 4, 0x01020304).pack())
out("TIME_SYNC_REQ", p.time_sync_request(7, 1793440000123, 540))
out("TIME_SYNC_RESP", p.Response(p.OP_TIME_SYNC, 7, 0, (12).to_bytes(4, "little") + (3600123).to_bytes(8, "little")).pack())
out("LIST_REQ", p.list_segments_request(8, 41, 26))
entries = [(42, p.SEG_TYPE_AUDIO, 98765), (43, p.SEG_TYPE_EVENTS, 52), (0xFFFFFFF0, p.SEG_TYPE_AUDIO, 0x80000001)]
out("LIST_RESP", p.Response(p.OP_LIST_SEGMENTS, 8, 0, p.build_list_segments(entries)).pack())
out("FETCH_REQ", p.fetch_segment_request(9, 42, 236 * 3))
out("ACK_REQ", p.ack_segment_request(10, 42, 0xDEADBEEF))
out("SET_REC_OFF_REQ", p.set_recording_request(11, False))
out("SET_REC_ON_REQ", p.set_recording_request(12, True))
out("ERR_RESP", p.Response(p.OP_FETCH_SEGMENT, 9, p.RESULT_NO_SEGMENT, b"").pack())

hdr = p.SegmentHeader(p.SEG_TYPE_AUDIO, p.CODEC_OPUS, 20, 42, 12, 3_000_000, 16000, 1, 0)
frames = [bytes([i] * (40 + i)) for i in range(5)]
seg = p.build_audio_segment(hdr, frames)
out("AUDIO_SEGMENT", seg)
print(f"    const val AUDIO_SEGMENT_CRC = 0x{p.zlib.crc32(seg) & 0xFFFFFFFF:08X}L")
chunks = list(p.chunk_segment(42, seg))
out("AUDIO_CHUNK0", chunks[0])
out("AUDIO_CHUNK_LAST", chunks[-1])
print(f"    const val AUDIO_CHUNK_COUNT = {len(chunks)}")

ehdr = p.SegmentHeader(p.SEG_TYPE_EVENTS, p.CODEC_PCM16, 0, 43, 12, 0, 16000, 1, 0)
events = [(3_000_500, p.EVT_BOOKMARK, 0), (3_100_000, p.EVT_RECORDING_OFF, p.CAUSE_APP),
          (3_200_000, p.EVT_RECORDING_ON, p.CAUSE_BUTTON), (3_300_000, p.EVT_POWER_OFF, p.CAUSE_BATTERY)]
eseg = p.build_event_segment(ehdr, events)
out("EVENT_SEGMENT", eseg)
print(f"    const val EVENT_SEGMENT_CRC = 0x{p.zlib.crc32(eseg) & 0xFFFFFFFF:08X}L")
