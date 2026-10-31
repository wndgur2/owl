"""Synthetic recordings for the fake device: speech-like tones or a WAV file,
encoded with libopus when available, otherwise stored as PCM16 (codec 0)."""

from __future__ import annotations

import math
import random
import struct
import wave

import owlproto as p

from . import opus
from .device import OwlDevice

RATE = 16000
FRAME_MS = 20
FRAME_SAMPLES = RATE * FRAME_MS // 1000  # 320


def tone(seconds: float, pitch_hz: float = 160.0, seed: int = 0) -> bytes:
    """A voice-ish test signal: harmonic stack with vibrato and ~4 Hz syllable envelope."""
    rnd = random.Random(seed)
    n = int(seconds * RATE)
    out = bytearray()
    phase = 0.0
    for i in range(n):
        t = i / RATE
        f0 = pitch_hz * (1 + 0.06 * math.sin(2 * math.pi * 5 * t) + 0.1 * math.sin(2 * math.pi * 0.7 * t))
        phase += 2 * math.pi * f0 / RATE
        env = max(0.0, math.sin(math.pi * 4 * t)) ** 0.6
        s = sum(math.sin(k * phase) / k for k in range(1, 6))
        v = 0.35 * env * s + 0.002 * (rnd.random() - 0.5)
        out += struct.pack("<h", max(-32767, min(32767, int(v * 32767 * 0.5))))
    return bytes(out)


def read_wav_16k_mono(path: str) -> bytes:
    """Loads a 16-bit PCM WAV, downmixes to mono and linearly resamples to 16 kHz."""
    with wave.open(path, "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError("only 16-bit PCM WAV is supported")
        ch, rate = w.getnchannels(), w.getframerate()
        raw = w.readframes(w.getnframes())
    samples = struct.unpack(f"<{len(raw) // 2}h", raw)
    mono = [sum(samples[i : i + ch]) / ch for i in range(0, len(samples), ch)]
    if rate != RATE:
        n_out = int(len(mono) * RATE / rate)
        res = []
        for j in range(n_out):
            x = j * rate / RATE
            i = int(x)
            a = mono[i]
            b = mono[i + 1] if i + 1 < len(mono) else a
            res.append(a + (b - a) * (x - i))
        mono = res
    return struct.pack(f"<{len(mono)}h", *(int(v) for v in mono))


def frames_from_pcm(pcm: bytes, use_opus: bool, bitrate: int = 16000) -> tuple[int, list[bytes]]:
    """Splits PCM16 into 20 ms frames. Returns (codec, frames)."""
    step = FRAME_SAMPLES * 2
    pcm = pcm + b"\x00" * (-len(pcm) % step)
    raw = [pcm[i : i + step] for i in range(0, len(pcm), step)]
    if use_opus:
        enc = opus.Encoder(RATE, 1, bitrate)
        return p.CODEC_OPUS, [enc.encode(f) for f in raw]
    return p.CODEC_PCM16, raw


def populate(
    dev: OwlDevice,
    *,
    audio_segments: int = 4,
    seconds: float = 3.0,
    prev_boot_segments: int = 1,
    wav_path: str | None = None,
    codec: str = "auto",
    bookmarks: bool = True,
    seed: int = 1,
) -> dict:
    """Fills `dev` with recordings across two boots.

    Segments from the previous boot have no TIME_SYNC, so their wall-clock time
    must come out as "unknown" on the phone/PC side (spec §4).
    Returns a summary dict (codec used, ids).
    """
    use_opus = {"auto": opus.available(), "opus": True, "pcm": False}[codec]
    if use_opus and not opus.available():
        raise RuntimeError("codec=opus requested but libopus is not installed")
    wav = read_wav_16k_mono(wav_path) if wav_path else None
    per_seg = int(seconds * RATE) * 2
    rnd = random.Random(seed)
    ids: list[int] = []

    def clip(k: int) -> bytes:
        if wav:
            start = (k * per_seg) % max(2, len(wav) - per_seg) if len(wav) > per_seg else 0
            start -= start % 2
            return wav[start : start + per_seg]
        return tone(seconds, pitch_hz=120 + 25 * k, seed=seed + k)

    # boot N-1: recorded, then power cycled before any sync
    prev_boot = dev.boot_count - 1 if prev_boot_segments and dev.boot_count > 1 else None
    up = 60_000
    k = 0
    if prev_boot is not None:
        events = [(500, p.EVT_POWER_ON, p.CAUSE_BUTTON)]
        for _ in range(prev_boot_segments):
            c, frames = frames_from_pcm(clip(k), use_opus)
            ids.append(dev.add_audio_segment(frames, codec=c, start_uptime_ms=up, boot_count=prev_boot))
            if bookmarks:
                events.append((up + 1000, p.EVT_BOOKMARK, 0))
            up += int(seconds * 1000) + rnd.randint(5_000, 60_000)
            k += 1
        events.append((up, p.EVT_POWER_OFF, p.CAUSE_BUTTON))
        dev.open_events = events
        ids.append(dev.seal_events(boot_count=prev_boot))

    # current boot: spread the recordings over the uptime that has already passed
    now = dev.uptime_ms()
    dev.add_event(p.EVT_POWER_ON, p.CAUSE_BUTTON, uptime_ms=min(300, now))
    span = max(1, now - 1_000 - int(seconds * 1000) * audio_segments)
    starts = sorted(rnd.randint(1_000, 1_000 + span) for _ in range(audio_segments))
    for j, st in enumerate(starts):
        c, frames = frames_from_pcm(clip(k), use_opus)
        ids.append(dev.add_audio_segment(frames, codec=c, start_uptime_ms=st))
        if bookmarks and j % 2 == 0:
            dev.add_event(p.EVT_BOOKMARK, 0, uptime_ms=st + int(seconds * 500))
        k += 1
    return {"codec": "opus" if use_opus else "pcm16", "ids": ids}
