"""Minimal Ogg Opus muxer/parser (RFC 3533, RFC 7845) and a PCM16 WAV writer.

Pure Python so `sync.py` can turn device Opus packets into a playable .ogg
without ffmpeg or libopus. The parser exists so tests can check our own output.
"""

from __future__ import annotations

import struct
import wave
from dataclasses import dataclass, field

from .opus import DEFAULT_PRE_SKIP, packet_samples_48k

_OGG_HDR = struct.Struct("<4sBBqIIIB")  # capture, version, type, granule, serial, seq, crc, nsegs
FLAG_CONTINUED = 0x01
FLAG_BOS = 0x02
FLAG_EOS = 0x04

MAX_PACKETS_PER_PAGE = 50  # 1 s of 20 ms frames; keeps pages small and seekable


def _crc_table() -> list[int]:
    table = []
    for i in range(256):
        r = i << 24
        for _ in range(8):
            r = ((r << 1) ^ 0x04C11DB7) if r & 0x80000000 else (r << 1)
        table.append(r & 0xFFFFFFFF)
    return table


_CRC_TABLE = _crc_table()


def ogg_crc(data: bytes) -> int:
    """Ogg CRC32: poly 0x04C11DB7, init 0, not reflected, no final xor."""
    crc = 0
    for b in data:
        crc = ((crc << 8) & 0xFFFFFFFF) ^ _CRC_TABLE[((crc >> 24) ^ b) & 0xFF]
    return crc


def _lacing(packet: bytes) -> bytes:
    n = len(packet)
    return b"\xff" * (n // 255) + bytes([n % 255])


def build_page(packets: list[bytes], granule: int, serial: int, seq: int, flags: int = 0) -> bytes:
    lacing = b"".join(_lacing(p) for p in packets)
    if len(lacing) > 255:
        raise ValueError("too many lacing values for one page")
    hdr = _OGG_HDR.pack(b"OggS", 0, flags, granule, serial, seq, 0, len(lacing))
    page = bytearray(hdr + lacing + b"".join(packets))
    struct.pack_into("<I", page, 22, ogg_crc(page))
    return bytes(page)


def opus_head(channels: int, pre_skip: int, input_rate: int) -> bytes:
    return b"OpusHead" + struct.pack("<BBHIhB", 1, channels, pre_skip, input_rate, 0, 0)


def opus_tags(vendor: str, comments: dict[str, str] | None = None) -> bytes:
    v = vendor.encode()
    items = [f"{k}={val}".encode() for k, val in (comments or {}).items()]
    return (
        b"OpusTags" + struct.pack("<I", len(v)) + v + struct.pack("<I", len(items))
        + b"".join(struct.pack("<I", len(i)) + i for i in items)
    )


def mux_opus(
    packets: list[bytes],
    *,
    input_rate: int = 16000,
    channels: int = 1,
    pre_skip: int = DEFAULT_PRE_SKIP,
    serial: int = 0x4F574C53,
    comments: dict[str, str] | None = None,
) -> bytes:
    """Wraps raw Opus packets into an Ogg Opus stream.

    Granule positions count 48 kHz samples (RFC 7845 §4), taken from each packet's
    TOC so the stream is correct even if the frame length changes mid-segment.
    """
    out = [
        build_page([opus_head(channels, pre_skip, input_rate)], 0, serial, 0, FLAG_BOS),
        build_page([opus_tags("owl-tools", comments)], 0, serial, 1),
    ]
    seq, granule = 2, 0
    packets = [p for p in packets if p]  # a zero-length frame has no TOC; drop it
    if not packets:
        out.append(build_page([], 0, serial, seq, FLAG_EOS))
        return b"".join(out)
    i = 0
    while i < len(packets):
        page_pkts, lacing = [], 0
        while i < len(packets) and len(page_pkts) < MAX_PACKETS_PER_PAGE:
            n = len(packets[i]) // 255 + 1
            if lacing + n > 255:
                break
            page_pkts.append(packets[i])
            lacing += n
            i += 1
        granule += sum(packet_samples_48k(p) for p in page_pkts)
        out.append(build_page(page_pkts, granule, serial, seq, FLAG_EOS if i == len(packets) else 0))
        seq += 1
    return b"".join(out)


@dataclass
class OggPage:
    flags: int
    granule: int
    serial: int
    seq: int
    crc_ok: bool
    packets: list[bytes] = field(default_factory=list)
    ends_with_partial: bool = False


def parse_pages(data: bytes) -> list[OggPage]:
    """Parses an Ogg stream, verifying each page CRC. Packets spanning pages are joined."""
    pages: list[OggPage] = []
    pos, pending = 0, b""
    while pos < len(data):
        capture, ver, flags, granule, serial, seq, crc, nsegs = _OGG_HDR.unpack_from(data, pos)
        if capture != b"OggS" or ver != 0:
            raise ValueError(f"bad Ogg page at {pos}")
        lacing = data[pos + _OGG_HDR.size : pos + _OGG_HDR.size + nsegs]
        body_start = pos + _OGG_HDR.size + nsegs
        body_len = sum(lacing)
        raw = bytearray(data[pos : body_start + body_len])
        struct.pack_into("<I", raw, 22, 0)
        page = OggPage(flags, granule, serial, seq, ogg_crc(raw) == crc)
        off = body_start
        cur = pending if flags & FLAG_CONTINUED else b""
        for lv in lacing:
            cur += data[off : off + lv]
            off += lv
            if lv < 255:
                page.packets.append(cur)
                cur = b""
        page.ends_with_partial = bool(lacing) and lacing[-1] == 255
        pending = cur
        pages.append(page)
        pos = body_start + body_len
    return pages


def write_wav(path: str, pcm16: bytes, sample_rate: int, channels: int = 1) -> None:
    with wave.open(path, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm16)
