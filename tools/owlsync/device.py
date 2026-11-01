"""Device-side model of Owl BLE protocol v1 (the "fake Owl").

Transport-agnostic: a PeripheralLink (loopback or bless) calls on_connect /
handle_control / on_disconnect and reads status(); the device answers through
link.notify(). Behaviour follows protocol/ble-protocol.md; where the spec is
silent, the choice is marked "spec gap" below and listed in tools/README.md.
"""

from __future__ import annotations

import asyncio
import math
import struct
import time
import zlib
from dataclasses import dataclass, field
from typing import Callable

import owlproto as p

from .transport import PeripheralLink, TransportError

CONFIG_LIMITS = {  # key -> (default, min, max)
    1: (16000, 6000, 64000),  # Opus bitrate (bps)
    2: (2, 0, 3),  # VAD sensitivity
    3: (300, 1, 3600),  # max segment length (s)
}
CONFIG_NAMES = {"bitrate": 1, "vad": 2, "segment-len": 3}

_MIN_LEN = {  # request payload length per op (longer payloads are accepted, spec §7)
    p.OP_TIME_SYNC: 10,
    p.OP_LIST_SEGMENTS: 5,
    p.OP_FETCH_SEGMENT: 8,
    p.OP_ACK_SEGMENT: 8,
    p.OP_ABORT: 0,
    p.OP_SET_RECORDING: 1,
    p.OP_SET_CONFIG: 5,
    p.OP_UNPAIR: 0,
}


@dataclass
class StoredSegment:
    seg_id: int
    seg_type: int
    blob: bytes

    @property
    def crc32(self) -> int:
        return zlib.crc32(self.blob) & 0xFFFFFFFF


@dataclass
class OwlDevice:
    boot_count: int = 1
    fw_ver: tuple[int, int, int] = (0, 1, 0)
    battery_mv: int = 3950
    storage_total_kb: int = 15 * 1024
    recording: bool = True
    uptime_offset_ms: int = 0  # pretend the device has been up this long at construction
    clock: Callable[[], float] = time.monotonic
    on_ack: Callable[[int], None] | None = None  # test hook, called before the segment is deleted
    log: Callable[[str], None] = lambda _m: None
    next_id: int = 1
    segments: dict[int, StoredSegment] = field(default_factory=dict)
    open_events: list[tuple[int, int, int]] = field(default_factory=list)
    config: dict[int, int] = field(default_factory=lambda: {k: v[0] for k, v in CONFIG_LIMITS.items()})
    time_syncs: list[tuple[int, int, int]] = field(default_factory=list)  # (boot, uptime, phone unix_ms)
    fetch_requests: list[tuple[int, int]] = field(default_factory=list)  # (seg_id, offset), for tests
    bytes_sent: int = 0  # DATA payload bytes handed to the link
    bonds: list[str] = field(default_factory=list)  # bonded central ids (§2.4: at most one stays)
    pairing_window_ms: int = p.PAIRING_WINDOW_MS  # shorter only in tests / fake_owl.py --pairing-window
    on_disconnected: Callable[[], None] | None = None  # fake_owl.py --week: next boot after a sync

    def __post_init__(self) -> None:
        self._t0 = self.clock()
        self._link: PeripheralLink | None = None
        self._fetch_task: asyncio.Task | None = None
        self._power_on_t = self._t0  # pairing window start (§2.4)
        self._window_used = False

    # --- clock / storage -----------------------------------------------------
    def uptime_ms(self) -> int:
        return self.uptime_offset_ms + int((self.clock() - self._t0) * 1000)

    def reboot(self, cause: int = p.CAUSE_OTHER) -> None:
        """Power cycle: boot_count+1, uptime restarts at 0, power-on event logged."""
        self.seal_events()
        self.boot_count += 1
        self.uptime_offset_ms = 0
        self._t0 = self.clock()
        self._power_on_t = self._t0
        self._window_used = False
        self.add_event(p.EVT_POWER_ON, cause)

    def jump_to_boot(self, boot_count: int, uptime_ms: int) -> None:
        """Week replay (owlsync/week.py): be in `boot_count`, up for `uptime_ms`. Unlike reboot(),
        logs nothing and leaves the pairing window alone; it is not a power cycle, only which
        past boot the phone sees next."""
        self.boot_count = boot_count
        self.uptime_offset_ms = uptime_ms
        self._t0 = self.clock()

    # --- security (§2.4) -----------------------------------------------------
    def pairing_open(self) -> bool:
        since = int((self.clock() - self._power_on_t) * 1000)
        return p.pairing_allowed(since, len(self.bonds), self._window_used, self.pairing_window_ms)

    def is_bonded(self, peer: str) -> bool:
        return peer in self.bonds

    def pair(self, peer: str) -> bool:
        """SMP pairing request from `peer`. On success the new bond replaces any other."""
        if not self.pairing_open():
            self.log(f"pairing refused for {peer}")
            return False
        self.on_bonded(peer)
        return True

    def on_bonded(self, peer: str) -> None:
        """A pairing with `peer` completed (a real link asks pairing_open() at the
        Pairing Request and reports here only after SMP succeeded, so a failed
        pairing leaves the old bond in place). The window closes, other bonds go."""
        self._window_used = True
        self.bonds = [peer]
        self.log(f"paired with {peer}")

    def storage_used_kb(self) -> int:
        return math.ceil(sum(len(s.blob) for s in self.segments.values()) / 1024)

    def status(self) -> p.Status:
        used = self.storage_used_kb()
        flags = p.FLAG_RECORDING if self.recording else 0
        if used >= self.storage_total_kb * 0.9:
            flags |= p.FLAG_STORAGE_LOW
        if used >= self.storage_total_kb:
            flags |= p.FLAG_STORAGE_FULL
        if self.pairing_open():
            flags |= p.FLAG_PAIRING_OPEN
        return p.Status(self.fw_ver, self.battery_mv, flags, used, self.storage_total_kb,
                        len(self.segments), self.boot_count)

    def _new_id(self) -> int:
        sid = self.next_id
        self.next_id += 1
        return sid

    def add_audio_segment(self, frames: list[bytes], *, codec: int = p.CODEC_OPUS, frame_ms: int = 20,
                          start_uptime_ms: int | None = None, boot_count: int | None = None,
                          sample_rate: int = 16000) -> int:
        sid = self._new_id()
        hdr = p.SegmentHeader(p.SEG_TYPE_AUDIO, codec, frame_ms, sid,
                              self.boot_count if boot_count is None else boot_count,
                              self.uptime_ms() if start_uptime_ms is None else start_uptime_ms,
                              sample_rate, 1, 0)
        self.segments[sid] = StoredSegment(sid, p.SEG_TYPE_AUDIO, p.build_audio_segment(hdr, frames))
        return sid

    def add_event(self, event: int, arg: int = 0, uptime_ms: int | None = None) -> None:
        self.open_events.append((self.uptime_ms() if uptime_ms is None else uptime_ms, event, arg))

    def seal_events(self, boot_count: int | None = None) -> int | None:
        """Turns the open event log into a listable segment.

        Spec gap: the spec doesn't say when the (growing) event log becomes a
        segment. The fake seals it when a central connects, so LIST never shows
        a segment whose size can still change.
        """
        if not self.open_events:
            return None
        sid = self._new_id()
        events = sorted(self.open_events)
        hdr = p.SegmentHeader(p.SEG_TYPE_EVENTS, p.CODEC_PCM16, 0, sid,
                              self.boot_count if boot_count is None else boot_count,
                              events[0][0], 16000, 1, 0)
        self.segments[sid] = StoredSegment(sid, p.SEG_TYPE_EVENTS, p.build_event_segment(hdr, events))
        self.open_events = []
        return sid

    # --- connection ----------------------------------------------------------
    @property
    def connected(self) -> bool:
        return self._link is not None

    def on_connect(self, link: PeripheralLink) -> None:
        self._cancel_fetch()
        self._link = link
        self.seal_events()
        self.log("central connected")

    def on_disconnect(self) -> None:
        self._cancel_fetch()
        self._link = None
        self.log("central disconnected")
        if self.on_disconnected:
            self.on_disconnected()

    def _cancel_fetch(self) -> None:
        if self._fetch_task and not self._fetch_task.done():
            self._fetch_task.cancel()
        self._fetch_task = None

    async def _notify(self, uuid: str, value: bytes) -> None:
        link = self._link
        if link is None:
            raise TransportError("no central")
        await link.notify(uuid, value)

    async def _notify_status(self) -> None:
        try:
            await self._notify(p.STATUS_UUID, self.status().pack())
        except TransportError:
            pass

    # --- CONTROL -------------------------------------------------------------
    async def handle_control(self, data: bytes) -> None:
        try:
            resp, after = self._dispatch(bytes(data))
            if resp is not None:
                await self._notify(p.CONTROL_UUID, resp.pack())
            if after is not None:
                await after()
        except TransportError:
            pass

    def _dispatch(self, data: bytes):
        if len(data) < 2:
            # Not even a seq to echo. Spec gap: answer with seq 0.
            return p.Response(data[0] if data else 0, 0, p.RESULT_BAD_LENGTH, b""), None
        op, seq, body = data[0], data[1], data[2:]

        def reply(result: int = p.RESULT_OK, payload: bytes = b"", after=None):
            return p.Response(op, seq, result, payload), after

        if op not in _MIN_LEN:
            return reply(p.RESULT_UNKNOWN_OP)
        if len(body) < _MIN_LEN[op]:
            return reply(p.RESULT_BAD_LENGTH)

        if op == p.OP_TIME_SYNC:
            unix_ms, _tz = struct.unpack_from("<Qh", body)
            up = self.uptime_ms()
            self.time_syncs.append((self.boot_count, up, unix_ms))
            self.log(f"TIME_SYNC boot={self.boot_count} uptime={up}")
            return reply(payload=struct.pack("<IQ", self.boot_count, up))

        if op == p.OP_LIST_SEGMENTS:
            after_id, max_count = struct.unpack_from("<IB", body)
            n = min(max_count, p.LIST_MAX_PER_RESPONSE)
            if self._link is not None:  # spec gap: 26 assumes MTU 247; shrink to fit smaller links
                n = min(n, (self._link.max_notify_len - 4) // 9)
            ids = sorted(i for i in self.segments if i > after_id)[:n]
            entries = [(i, self.segments[i].seg_type, len(self.segments[i].blob)) for i in ids]
            return reply(payload=p.build_list_segments(entries))

        if op == p.OP_FETCH_SEGMENT:
            sid, offset = struct.unpack_from("<II", body)
            self.fetch_requests.append((sid, offset))
            seg = self.segments.get(sid)
            if seg is None:
                return reply(p.RESULT_NO_SEGMENT)
            if offset > len(seg.blob):
                return reply(p.RESULT_BAD_LENGTH)  # spec gap: no "bad offset" code
            # Spec gap: FETCH while another FETCH streams. The fake replaces the
            # running stream (like an implicit ABORT); clients should ABORT first.
            self._cancel_fetch()

            async def start():
                self._fetch_task = asyncio.get_running_loop().create_task(self._stream(seg, offset))

            return reply(after=start)

        if op == p.OP_ACK_SEGMENT:
            sid, crc = struct.unpack_from("<II", body)
            seg = self.segments.get(sid)
            if seg is None:
                return reply(p.RESULT_NO_SEGMENT)
            if crc != seg.crc32:
                return reply(p.RESULT_CRC_MISMATCH)
            if self.on_ack:
                self.on_ack(sid)
            if self._fetch_task and getattr(self._fetch_task, "seg_id", None) == sid:
                self._cancel_fetch()
            del self.segments[sid]
            self.log(f"ACK {sid} -> deleted")
            return reply(after=self._notify_status)

        if op == p.OP_ABORT:
            self._cancel_fetch()
            return reply()

        if op == p.OP_SET_RECORDING:
            on = body[0] != 0
            if on != self.recording:
                self.recording = on
                self.add_event(p.EVT_RECORDING_ON if on else p.EVT_RECORDING_OFF, p.CAUSE_APP)
            return reply(after=self._notify_status)

        if op == p.OP_SET_CONFIG:
            key, value = struct.unpack_from("<BI", body)
            lim = CONFIG_LIMITS.get(key)
            if lim is None or not lim[1] <= value <= lim[2]:
                return reply(p.RESULT_BAD_LENGTH)  # spec gap: no "bad value" code
            self.config[key] = value
            return reply()

        if op == p.OP_UNPAIR:
            self._cancel_fetch()

            async def forget():
                self.bonds = []
                self.log("UNPAIR: all bonds deleted")
                if self._link is not None:
                    self._link.drop_central()

            return reply(after=forget)

        return reply(p.RESULT_UNKNOWN_OP)

    async def _stream(self, seg: StoredSegment, offset: int) -> None:
        asyncio.current_task().seg_id = seg.seg_id  # type: ignore[attr-defined]
        link = self._link
        if link is None:
            return
        chunk = min(p.DATA_CHUNK_MAX, link.max_notify_len - p.DATA_HEADER.size)
        try:
            for pkt in p.chunk_segment(seg.seg_id, seg.blob, offset, chunk):
                await self._notify(p.DATA_UUID, pkt)
                self.bytes_sent += len(pkt) - p.DATA_HEADER.size
        except (TransportError, asyncio.CancelledError):
            pass
