"""Ogg Opus muxer, WAV output and the optional libopus / bless pieces.

libopus (`brew install opus` / `apt install libopus0`), ffprobe and bless are
optional: their tests skip when missing so CI only needs requirements.txt.
"""

import asyncio
import os
import shutil
import struct
import subprocess
import wave

import pytest

import owlproto as p
from owlsync import ogg, opus
from owlsync.device import OwlDevice
from owlsync.engine import SyncEngine, SyncOptions
from owlsync.synth import frames_from_pcm, populate, tone
from owlsync.transport import LoopbackTransport

needs_opus = pytest.mark.skipif(not opus.available(), reason="libopus not installed")


def fake_packet(toc: int, n: int) -> bytes:
    return bytes([toc]) + bytes((i * 7) & 0xFF for i in range(n - 1))


def test_packet_durations_from_toc():
    assert opus.packet_samples_48k(bytes([1 << 3])) == 960  # SILK 20 ms, 1 frame
    assert opus.packet_samples_48k(bytes([3 << 3])) == 2880  # SILK 60 ms
    assert opus.packet_samples_48k(bytes([(13 << 3) | 1])) == 1920  # Hybrid 20 ms x2
    assert opus.packet_samples_48k(bytes([(16 << 3) | 3, 4])) == 480  # CELT 2.5 ms x4
    assert opus.packet_samples_48k(bytes([(31 << 3) | 2, 0])) == 1920  # CELT 20 ms x2


def test_ogg_crc_known_vector():
    # Non-reflected CRC-32, poly 0x04C11DB7, init 0: one byte 0x01 yields the polynomial itself.
    assert ogg.ogg_crc(b"") == 0
    assert ogg.ogg_crc(b"\x01") == 0x04C11DB7


def check_stream(data: bytes, packets: list[bytes], input_rate=16000, pre_skip=opus.DEFAULT_PRE_SKIP):
    pages = ogg.parse_pages(data)
    assert all(pg.crc_ok for pg in pages)
    assert [pg.seq for pg in pages] == list(range(len(pages)))
    assert len({pg.serial for pg in pages}) == 1
    assert pages[0].flags & ogg.FLAG_BOS and not any(pg.flags & ogg.FLAG_BOS for pg in pages[1:])
    assert pages[-1].flags & ogg.FLAG_EOS and not any(pg.flags & ogg.FLAG_EOS for pg in pages[:-1])
    head = pages[0].packets[0]
    magic, ver, ch, skip, rate, gain, fam = struct.unpack("<8sBBHIhB", head)
    assert (magic, ver, ch, skip, rate, gain, fam) == (b"OpusHead", 1, 1, pre_skip, input_rate, 0, 0)
    assert pages[1].packets[0].startswith(b"OpusTags") and pages[1].granule == 0
    audio_pages = pages[2:]
    got = [pkt for pg in audio_pages for pkt in pg.packets]
    assert got == packets
    total = 0
    for pg in audio_pages:
        total += sum(opus.packet_samples_48k(x) for x in pg.packets)
        assert pg.granule == total and not pg.ends_with_partial
    return pages


def test_mux_granules_lacing_and_crc():
    pkts = [fake_packet(1 << 3, n) for n in (1, 40, 254, 255, 256, 510, 1000, 1275)] * 20
    pages = check_stream(ogg.mux_opus(pkts), pkts)
    assert len(pages) > 3  # big packets force several pages (255 lacing values max)


def test_mux_mixed_frame_sizes():
    pkts = [fake_packet(1 << 3, 30), fake_packet(3 << 3, 30), fake_packet((16 << 3) | 3, 30)[:1] + b"\x04" + b"x" * 10]
    check_stream(ogg.mux_opus(pkts * 30), pkts * 30)


def test_mux_empty_segment_is_still_valid():
    pages = ogg.parse_pages(ogg.mux_opus([]))
    assert len(pages) == 3 and pages[-1].flags & ogg.FLAG_EOS and all(pg.crc_ok for pg in pages)


def test_pcm16_segment_renders_to_wav(tmp_path):
    dev = OwlDevice(boot_count=2, uptime_offset_ms=600_000)
    populate(dev, audio_segments=1, seconds=0.5, prev_boot_segments=0, codec="pcm")
    sid = next(s for s, seg in dev.segments.items() if seg.seg_type == p.SEG_TYPE_AUDIO)
    pcm = b"".join(p.iter_audio_frames(dev.segments[sid].blob))
    asyncio.run(SyncEngine(LoopbackTransport(dev), SyncOptions(str(tmp_path), idle_timeout=0.05),
                           log=lambda _m: None).run())
    with wave.open(str(tmp_path / "audio" / f"seg_{sid:06d}.wav")) as w:
        assert (w.getframerate(), w.getnchannels(), w.getsampwidth()) == (16000, 1, 2)
        assert w.readframes(w.getnframes()) == pcm and w.getnframes() == 8000


@needs_opus
def test_libopus_roundtrip_through_ogg():
    pcm = tone(1.0, 150)
    codec, frames = frames_from_pcm(pcm, use_opus=True)
    assert codec == p.CODEC_OPUS and len(frames) == 50
    assert all(opus.packet_samples_48k(f) == 960 for f in frames)
    data = ogg.mux_opus(frames)
    pages = check_stream(data, frames)
    dec = opus.Decoder(48000, 1)
    out = b"".join(dec.decode(pk) for pg in pages[2:] for pk in pg.packets)
    assert len(out) == 50 * 960 * 2
    samples = struct.unpack(f"<{len(out) // 2}h", out)
    assert max(abs(s) for s in samples[4800:]) > 1000  # not silence


@needs_opus
def test_bitrate_setting_reaches_the_encoder():
    pcm = tone(1.0, 150)
    low = opus.Encoder(16000, 1, 8000)
    high = opus.Encoder(16000, 1, 32000)
    n_low = sum(len(low.encode(pcm[i : i + 640])) for i in range(0, len(pcm), 640))
    n_high = sum(len(high.encode(pcm[i : i + 640])) for i in range(0, len(pcm), 640))
    assert n_high > 2 * n_low
    assert low.pre_skip == 312


@needs_opus
@pytest.mark.skipif(not shutil.which("ffprobe"), reason="ffprobe not installed (local-only check)")
def test_ffprobe_accepts_synced_ogg(tmp_path):
    dev = OwlDevice(boot_count=2, uptime_offset_ms=600_000)
    populate(dev, audio_segments=1, seconds=2.0, prev_boot_segments=0, codec="opus")
    asyncio.run(SyncEngine(LoopbackTransport(dev), SyncOptions(str(tmp_path), idle_timeout=0.05),
                           log=lambda _m: None).run())
    path = next((tmp_path / "audio").glob("*.ogg"))
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=codec_name,sample_rate,channels:format=duration",
         "-of", "default=nw=1", str(path)], capture_output=True, text=True, check=True).stdout
    assert "codec_name=opus" in out and "channels=1" in out
    dur = float(next(line for line in out.splitlines() if line.startswith("duration=")).split("=")[1])
    assert abs(dur - 2.0) < 0.05
    if shutil.which("ffmpeg"):
        subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-i", str(path), "-f", "null", "-"], check=True)


def test_bless_peripheral_constructs_when_installed():
    pytest.importorskip("bless")
    from owlsync.ble_peripheral import BlessPeripheral

    link = BlessPeripheral(OwlDevice(), log=lambda _m: None)
    assert link.max_notify_len >= 20 and not link.connected


def test_bleak_transport_imports_when_installed():
    pytest.importorskip("bleak")
    from owlsync.ble import BleakTransport

    assert not BleakTransport(device=None).is_connected
    assert os.path.basename(BleakTransport.__module__) == "owlsync.ble"
