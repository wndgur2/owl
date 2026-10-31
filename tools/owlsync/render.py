"""Turns raw .owls segments into files people can use.

    out/raw/seg_000012.owls      exact bytes from the device (source of truth)
    out/audio/seg_000012.ogg     Opus audio (codec 1), playable anywhere
    out/audio/seg_000012.wav     PCM16 audio (codec 0)
    out/events/seg_000013.json   event segment, with names and restored times
    out/events/seg_000013.csv
    out/segments.csv             one row per segment
    out/events.csv               every event, sorted by device time
    out/timesync.json            TIME_SYNC records used for time restoration
"""

from __future__ import annotations

import csv
import glob
import json
import os

import owlproto as p

from . import ogg
from .opus import packet_samples_48k
from .timesync import TimeStore, iso

EVENT_NAMES = {
    p.EVT_BOOKMARK: "bookmark",
    p.EVT_RECORDING_OFF: "recording_off",
    p.EVT_RECORDING_ON: "recording_on",
    p.EVT_STORAGE_FULL: "storage_full",
    p.EVT_BATTERY_LOW: "battery_low",
    p.EVT_POWER_OFF: "power_off",
    p.EVT_POWER_ON: "power_on",
}
CAUSE_NAMES = {
    p.CAUSE_BUTTON: "button",
    p.CAUSE_APP: "app",
    p.CAUSE_BATTERY: "battery",
    p.CAUSE_CHARGED: "charged",
    p.CAUSE_OTHER: "other",
}
_EVENTS_WITH_CAUSE = {p.EVT_RECORDING_OFF, p.EVT_RECORDING_ON, p.EVT_POWER_OFF, p.EVT_POWER_ON}


def basename(seg_id: int) -> str:
    return f"seg_{seg_id:06d}"


def validate(blob: bytes, seg_id: int | None = None) -> list[str]:
    """Structural checks on a received segment. Empty list = looks fine."""
    problems = []
    if len(blob) < 32:
        return [f"only {len(blob)} bytes, shorter than the 32-byte header"]
    try:
        hdr = p.SegmentHeader.unpack(blob)
    except ValueError as e:
        return [str(e)]
    if hdr.version != p.SEGMENT_VERSION:
        problems.append(f"segment version {hdr.version}")
    if seg_id is not None and hdr.seg_id != seg_id:
        problems.append(f"header id {hdr.seg_id} != listed id {seg_id}")
    if 32 + hdr.payload_len != len(blob):
        problems.append(f"payload_len {hdr.payload_len} but {len(blob) - 32} bytes after header")
    if hdr.seg_type == p.SEG_TYPE_EVENTS and hdr.payload_len % 10:
        problems.append("event payload not a multiple of 10 bytes")
    if hdr.seg_type == p.SEG_TYPE_AUDIO:
        pos, end = 32, 32 + hdr.payload_len
        while pos < end:
            if pos + 2 > end:
                problems.append("truncated frame length")
                break
            pos += 2 + int.from_bytes(blob[pos : pos + 2], "little")
        if pos > end:
            problems.append("last frame runs past payload_len")
    return problems


def _audio_seconds(hdr: p.SegmentHeader, frames: list[bytes]) -> float:
    if hdr.codec == p.CODEC_OPUS:
        try:
            return sum(packet_samples_48k(f) for f in frames if f) / 48000
        except (ValueError, KeyError):
            return len(frames) * hdr.frame_ms / 1000
    if hdr.codec == p.CODEC_PCM16:
        return sum(len(f) for f in frames) / 2 / hdr.sample_rate / max(1, hdr.channels)
    return 0.0


def render_segment(blob: bytes, out_dir: str, times: TimeStore, *, force: bool = False) -> dict:
    hdr = p.SegmentHeader.unpack(blob)
    name = basename(hdr.seg_id)
    start_ms = times.resolve(hdr.boot_count, hdr.start_uptime_ms)
    row = {
        "id": hdr.seg_id,
        "type": {p.SEG_TYPE_AUDIO: "audio", p.SEG_TYPE_EVENTS: "events"}.get(hdr.seg_type, str(hdr.seg_type)),
        "codec": {p.CODEC_OPUS: "opus", p.CODEC_PCM16: "pcm16"}.get(hdr.codec, str(hdr.codec))
        if hdr.seg_type == p.SEG_TYPE_AUDIO else "",
        "boot_count": hdr.boot_count,
        "start_uptime_ms": hdr.start_uptime_ms,
        "start_unix_ms": start_ms if start_ms is not None else "",
        "start_time": iso(start_ms),
        "duration_s": "",
        "bytes": len(blob),
        "file": "",
    }
    problems = validate(blob, hdr.seg_id)
    if problems:
        row["problems"] = "; ".join(problems)

    if hdr.seg_type == p.SEG_TYPE_AUDIO:
        frames = list(p.iter_audio_frames(blob))
        row["duration_s"] = round(_audio_seconds(hdr, frames), 3)
        os.makedirs(os.path.join(out_dir, "audio"), exist_ok=True)
        if hdr.codec == p.CODEC_OPUS:
            path = os.path.join(out_dir, "audio", name + ".ogg")
            if force or not os.path.exists(path):
                comments = {
                    "TITLE": f"Owl segment {hdr.seg_id}",
                    "OWL_SEGMENT_ID": str(hdr.seg_id),
                    "OWL_BOOT_COUNT": str(hdr.boot_count),
                    "OWL_START_UPTIME_MS": str(hdr.start_uptime_ms),
                }
                if start_ms is not None:
                    comments["DATE"] = iso(start_ms)
                data = ogg.mux_opus(frames, input_rate=hdr.sample_rate, channels=hdr.channels or 1, comments=comments)
                _write_atomic(path, data)
            row["file"] = os.path.relpath(path, out_dir)
        elif hdr.codec == p.CODEC_PCM16:
            path = os.path.join(out_dir, "audio", name + ".wav")
            if force or not os.path.exists(path):
                ogg.write_wav(path, b"".join(frames), hdr.sample_rate, hdr.channels or 1)
            row["file"] = os.path.relpath(path, out_dir)

    elif hdr.seg_type == p.SEG_TYPE_EVENTS:
        events = [event_row(hdr, e, times) for e in p.iter_events(blob)]
        os.makedirs(os.path.join(out_dir, "events"), exist_ok=True)
        jpath = os.path.join(out_dir, "events", name + ".json")
        with open(jpath, "w") as f:
            json.dump({"segment_id": hdr.seg_id, "boot_count": hdr.boot_count, "events": events}, f, indent=1)
        _write_csv(os.path.join(out_dir, "events", name + ".csv"), events)
        row["file"] = os.path.relpath(jpath, out_dir)
        row["events"] = events
    return row


def event_row(hdr: p.SegmentHeader, ev: tuple[int, int, int], times: TimeStore) -> dict:
    uptime, code, arg = ev
    unix_ms = times.resolve(hdr.boot_count, uptime)
    return {
        "segment_id": hdr.seg_id,
        "boot_count": hdr.boot_count,
        "uptime_ms": uptime,
        "event": EVENT_NAMES.get(code, f"event_{code}"),
        "event_code": code,
        "cause": CAUSE_NAMES.get(arg, str(arg)) if code in _EVENTS_WITH_CAUSE else "",
        "arg": arg,
        "unix_ms": unix_ms if unix_ms is not None else "",
        "time": iso(unix_ms),
    }


def render_all(out_dir: str, *, force: bool = False) -> list[dict]:
    """Re-renders every raw segment and rewrites the index files."""
    times = TimeStore(os.path.join(out_dir, "timesync.json"))
    rows, events = [], []
    for path in sorted(glob.glob(os.path.join(out_dir, "raw", "seg_*.owls"))):
        with open(path, "rb") as f:
            blob = f.read()
        try:
            row = render_segment(blob, out_dir, times, force=force)
        except Exception as e:  # noqa: BLE001 - one bad file must not hide the rest
            rows.append({"id": os.path.basename(path), "problems": f"render failed: {e}"})
            continue
        events += row.pop("events", [])
        rows.append(row)
    cols = ["id", "type", "codec", "boot_count", "start_uptime_ms", "start_unix_ms", "start_time",
            "duration_s", "bytes", "file", "problems"]
    _write_csv(os.path.join(out_dir, "segments.csv"), rows, cols)
    events.sort(key=lambda e: (e["boot_count"], e["uptime_ms"]))
    _write_csv(os.path.join(out_dir, "events.csv"), events)
    return rows


def _write_csv(path: str, rows: list[dict], cols: list[str] | None = None) -> None:
    cols = cols or ["segment_id", "boot_count", "uptime_ms", "event", "event_code", "cause", "arg", "unix_ms", "time"]
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


def _write_atomic(path: str, data: bytes) -> None:
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)
