"""Reference implementation of the Owl BLE protocol v1.

protocol/ble-protocol.md is the spec; this module mirrors it byte for byte.
Firmware (C) and Android (Kotlin) code should match the layouts here.
Pure Python, no dependencies, so it can be imported by tests and tools alike.
"""

from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass
from typing import Iterator

PROTO_VER = 1
SEGMENT_VERSION = 1

_BASE = "a269{:04x}-8c8b-40f7-a91b-3ffa7ab560b9"
SERVICE_UUID = _BASE.format(0x0001)
STATUS_UUID = _BASE.format(0x0002)
CONTROL_UUID = _BASE.format(0x0003)
DATA_UUID = _BASE.format(0x0004)

ATT_MTU = 247
ATT_PAYLOAD = ATT_MTU - 3  # notify payload per packet
DATA_HEADER = struct.Struct("<II")  # segment_id, offset
DATA_CHUNK_MAX = ATT_PAYLOAD - DATA_HEADER.size  # 236

# --- opcodes / results -------------------------------------------------------
OP_TIME_SYNC = 0x01
OP_LIST_SEGMENTS = 0x02
OP_FETCH_SEGMENT = 0x03
OP_ACK_SEGMENT = 0x04
OP_ABORT = 0x05
OP_SET_RECORDING = 0x06
OP_UNPAIR = 0x07  # delete every bond on the device (§2.4)
OP_SET_CONFIG = 0x10
RESPONSE_BIT = 0x80

RESULT_OK = 0
RESULT_UNKNOWN_OP = 1
RESULT_BAD_LENGTH = 2
RESULT_NO_SEGMENT = 3
RESULT_CRC_MISMATCH = 4
RESULT_BUSY = 5

SEG_TYPE_AUDIO = 1
SEG_TYPE_EVENTS = 2
CODEC_PCM16 = 0
CODEC_OPUS = 1

EVT_BOOKMARK = 1
EVT_RECORDING_OFF = 2  # arg: CAUSE_BUTTON / CAUSE_APP
EVT_RECORDING_ON = 3  # arg: CAUSE_BUTTON / CAUSE_APP
EVT_STORAGE_FULL = 4
EVT_BATTERY_LOW = 5
EVT_POWER_OFF = 6  # arg: CAUSE_BUTTON / CAUSE_BATTERY
EVT_POWER_ON = 7  # arg: CAUSE_BUTTON / CAUSE_CHARGED / CAUSE_OTHER

CAUSE_BUTTON = 0
CAUSE_APP = 1
CAUSE_BATTERY = 2
CAUSE_CHARGED = 3
CAUSE_OTHER = 4

# --- STATUS ------------------------------------------------------------------
_STATUS = struct.Struct("<B3sHBBIIHHI")
assert _STATUS.size == 24

FLAG_CHARGING = 1 << 0  # on hold: the board has no charge-status pin (PRD 10 H)
FLAG_RECORDING = 1 << 1  # recording switched on by the user
FLAG_STORAGE_LOW = 1 << 2
FLAG_STORAGE_FULL = 1 << 3
FLAG_PAIRING_OPEN = 1 << 4  # a new pairing would be accepted right now (§2.4)

# --- security (§2.4) ---------------------------------------------------------
# Readable without encryption: STATUS value only. Everything else (STATUS/CONTROL/DATA
# CCCDs, CONTROL writes) needs an encrypted link from a bonded phone.
PAIRING_WINDOW_MS = 120_000  # after power-on; a bonded device accepts a new pairing only inside it
UNENCRYPTED_TIMEOUT_MS = 60_000  # device drops a link that is still unencrypted after this
ATT_ERR_INSUFFICIENT_AUTHENTICATION = 0x05  # no bond with this peer
ATT_ERR_INSUFFICIENT_ENCRYPTION = 0x0F  # bonded, but the link is not encrypted yet


def pairing_allowed(since_power_on_ms: int, bond_count: int, window_used: bool,
                    window_ms: int = PAIRING_WINDOW_MS) -> bool:
    """§2.4: no bond -> always; bonded -> only in the window and only once per window.
    `window_ms` is only shortened by test fakes (fake_owl.py --pairing-window)."""
    if bond_count <= 0:
        return True
    return not window_used and since_power_on_ms < window_ms


@dataclass
class Status:
    fw_ver: tuple[int, int, int]
    battery_mv: int
    flags: int
    storage_used_kb: int
    storage_total_kb: int
    pending_segments: int
    boot_count: int
    proto_ver: int = PROTO_VER

    def pack(self) -> bytes:
        return _STATUS.pack(
            self.proto_ver, bytes(self.fw_ver), self.battery_mv, self.flags, 0,
            self.storage_used_kb, self.storage_total_kb,
            self.pending_segments, 0, self.boot_count,
        )

    @classmethod
    def unpack(cls, data: bytes) -> "Status":
        (ver, fw, mv, flags, _r0, used, total, pending, _r1, boot) = _STATUS.unpack(data[: _STATUS.size])
        return cls(tuple(fw), mv, flags, used, total, pending, boot, ver)


# --- CONTROL -----------------------------------------------------------------
def request(op: int, seq: int, payload: bytes = b"") -> bytes:
    return bytes([op, seq & 0xFF]) + payload


def time_sync_request(seq: int, unix_ms: int, tz_offset_min: int) -> bytes:
    return request(OP_TIME_SYNC, seq, struct.pack("<Qh", unix_ms, tz_offset_min))


def list_segments_request(seq: int, after_id: int, max_count: int = 26) -> bytes:
    return request(OP_LIST_SEGMENTS, seq, struct.pack("<IB", after_id, max_count))


def fetch_segment_request(seq: int, seg_id: int, offset: int = 0) -> bytes:
    return request(OP_FETCH_SEGMENT, seq, struct.pack("<II", seg_id, offset))


def ack_segment_request(seq: int, seg_id: int, crc32: int) -> bytes:
    return request(OP_ACK_SEGMENT, seq, struct.pack("<II", seg_id, crc32))


def set_recording_request(seq: int, on: bool) -> bytes:
    return request(OP_SET_RECORDING, seq, bytes([1 if on else 0]))


def unpair_request(seq: int) -> bytes:
    return request(OP_UNPAIR, seq)


@dataclass
class Response:
    op: int
    seq: int
    result: int
    payload: bytes

    @classmethod
    def unpack(cls, data: bytes) -> "Response":
        if len(data) < 3 or not data[0] & RESPONSE_BIT:
            raise ValueError("not a CONTROL response")
        return cls(data[0] & ~RESPONSE_BIT, data[1], data[2], bytes(data[3:]))

    def pack(self) -> bytes:
        return bytes([self.op | RESPONSE_BIT, self.seq, self.result]) + self.payload


_LIST_ENTRY = struct.Struct("<IBI")  # id, type, size
assert _LIST_ENTRY.size == 9
LIST_MAX_PER_RESPONSE = (ATT_PAYLOAD - 3 - 1) // _LIST_ENTRY.size  # op,seq,result,count


def parse_list_segments(payload: bytes) -> list[tuple[int, int, int]]:
    count = payload[0]
    return [_LIST_ENTRY.unpack_from(payload, 1 + i * _LIST_ENTRY.size) for i in range(count)]


def build_list_segments(entries: list[tuple[int, int, int]]) -> bytes:
    if len(entries) > LIST_MAX_PER_RESPONSE:
        raise ValueError("too many entries for one response")
    return bytes([len(entries)]) + b"".join(_LIST_ENTRY.pack(*e) for e in entries)


def parse_time_sync_response(payload: bytes) -> tuple[int, int]:
    """Returns (boot_count, uptime_ms)."""
    return struct.unpack("<IQ", payload[:12])


# --- DATA --------------------------------------------------------------------
def chunk_segment(seg_id: int, blob: bytes, start: int = 0, chunk: int = DATA_CHUNK_MAX) -> Iterator[bytes]:
    for off in range(start, len(blob), chunk):
        yield DATA_HEADER.pack(seg_id, off) + blob[off : off + chunk]


class SegmentAssembler:
    """Collects DATA notifications for one segment; tolerates duplicates and reports gaps."""

    def __init__(self, seg_id: int, size: int):
        self.seg_id = seg_id
        self.buf = bytearray(size)
        self.have = bytearray(size)  # 1 byte per byte; fine for a PC tool

    def feed(self, packet: bytes) -> None:
        seg_id, off = DATA_HEADER.unpack_from(packet)
        if seg_id != self.seg_id:
            return
        data = packet[DATA_HEADER.size :]
        end = min(off + len(data), len(self.buf))
        self.buf[off:end] = data[: end - off]
        self.have[off:end] = b"\x01" * (end - off)

    def first_gap(self) -> int | None:
        idx = self.have.find(0)
        return None if idx < 0 else idx

    def complete(self) -> bool:
        return self.first_gap() is None

    def crc32(self) -> int:
        return zlib.crc32(self.buf) & 0xFFFFFFFF


# --- Segment -----------------------------------------------------------------
_SEG_HDR = struct.Struct("<4sBBBBIIQHBBI")
assert _SEG_HDR.size == 32
SEG_MAGIC = b"OWLS"


@dataclass
class SegmentHeader:
    seg_type: int
    codec: int
    frame_ms: int
    seg_id: int
    boot_count: int
    start_uptime_ms: int
    sample_rate: int
    channels: int
    payload_len: int
    version: int = SEGMENT_VERSION

    def pack(self) -> bytes:
        return _SEG_HDR.pack(
            SEG_MAGIC, self.version, self.seg_type, self.codec, self.frame_ms,
            self.seg_id, self.boot_count, self.start_uptime_ms,
            self.sample_rate // 100, self.channels, 0, self.payload_len,
        )

    @classmethod
    def unpack(cls, data: bytes) -> "SegmentHeader":
        (magic, ver, typ, codec, frame_ms, sid, boot, start, sr100, ch, _r, plen) = _SEG_HDR.unpack_from(data)
        if magic != SEG_MAGIC:
            raise ValueError(f"bad magic {magic!r}")
        return cls(typ, codec, frame_ms, sid, boot, start, sr100 * 100, ch, plen, ver)


def build_audio_segment(hdr: SegmentHeader, frames: list[bytes]) -> bytes:
    payload = b"".join(struct.pack("<H", len(f)) + f for f in frames)
    hdr.payload_len = len(payload)
    return hdr.pack() + payload


def iter_audio_frames(segment: bytes) -> Iterator[bytes]:
    hdr = SegmentHeader.unpack(segment)
    pos, end = _SEG_HDR.size, _SEG_HDR.size + hdr.payload_len
    while pos < end:
        (n,) = struct.unpack_from("<H", segment, pos)
        pos += 2
        yield segment[pos : pos + n]
        pos += n


_EVENT = struct.Struct("<QBB")


def build_event_segment(hdr: SegmentHeader, events: list[tuple[int, int, int]]) -> bytes:
    payload = b"".join(_EVENT.pack(*e) for e in events)
    hdr.payload_len = len(payload)
    return hdr.pack() + payload


def iter_events(segment: bytes) -> Iterator[tuple[int, int, int]]:
    hdr = SegmentHeader.unpack(segment)
    for off in range(_SEG_HDR.size, _SEG_HDR.size + hdr.payload_len, _EVENT.size):
        yield _EVENT.unpack_from(segment, off)


# --- time --------------------------------------------------------------------
def to_unix_ms(boot_count: int, uptime_ms: int, syncs: dict[int, tuple[int, int]]) -> int | None:
    """syncs: boot_count -> (device_uptime_ms_at_sync, phone_unix_ms_at_sync)."""
    if boot_count not in syncs:
        return None
    dev_ms, phone_ms = syncs[boot_count]
    return phone_ms + (uptime_ms - dev_ms)
