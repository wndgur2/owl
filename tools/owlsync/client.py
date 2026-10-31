"""Protocol client over any Transport: CONTROL request/response by seq, STATUS,
LIST paging and FETCH with in-stream gap detection."""

from __future__ import annotations

import asyncio
import random
import struct
import time
from dataclasses import dataclass

import owlproto as p

from .transport import Transport, TransportError

RESULT_NAMES = {
    p.RESULT_OK: "OK",
    p.RESULT_UNKNOWN_OP: "unknown op",
    p.RESULT_BAD_LENGTH: "bad length",
    p.RESULT_NO_SEGMENT: "no segment",
    p.RESULT_CRC_MISMATCH: "CRC mismatch",
    p.RESULT_BUSY: "busy",
}


class ProtocolError(Exception):
    def __init__(self, op: int, result: int):
        super().__init__(f"op 0x{op:02x} failed: {RESULT_NAMES.get(result, result)} ({result})")
        self.op = op
        self.result = result


class FetchStalled(TransportError):
    """FETCH made no progress after several re-requests."""


@dataclass
class TimeSyncResult:
    boot_count: int
    uptime_ms: int
    unix_ms: int  # PC clock at the RTT midpoint
    rtt_ms: int


class OwlClient:
    def __init__(
        self,
        transport: Transport,
        *,
        response_timeout: float = 5.0,
        idle_timeout: float = 2.0,
        busy_retries: int = 6,
        busy_backoff: float = 0.1,
        log=lambda _m: None,
    ):
        self.t = transport
        self.response_timeout = response_timeout
        self.idle_timeout = idle_timeout
        self.busy_retries = busy_retries
        self.busy_backoff = busy_backoff
        self.log = log
        self._seq = random.randrange(256)
        self._pending: dict[int, asyncio.Future] = {}
        self._fetch: _FetchState | None = None
        self._lost = asyncio.Event()
        self.last_status: p.Status | None = None
        self.stats = {"data_packets": 0, "refetches": 0, "busy": 0}

    # --- connection ----------------------------------------------------------
    async def connect(self) -> None:
        self._lost = asyncio.Event()
        self.t.on_control = self._on_control
        self.t.on_data = self._on_data
        self.t.on_status = self._on_status
        self.t.on_disconnect = self._on_disconnect
        await self.t.connect()

    async def close(self) -> None:
        try:
            await self.t.disconnect()
        finally:
            self._fail_pending(TransportError("closed"))

    def _on_disconnect(self) -> None:
        self._lost.set()
        self._fail_pending(TransportError("connection lost"))

    def _fail_pending(self, exc: Exception) -> None:
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(exc)
        self._pending.clear()
        if self._fetch:
            self._fetch.wake.set()

    def _on_control(self, data: bytes) -> None:
        try:
            resp = p.Response.unpack(data)
        except ValueError:
            self.log(f"ignoring malformed CONTROL notify {data.hex()}")
            return
        fut = self._pending.pop(resp.seq, None)
        if fut and not fut.done():
            fut.set_result(resp)

    def _on_status(self, data: bytes) -> None:
        try:
            self.last_status = p.Status.unpack(data)
        except Exception:  # noqa: BLE001 - a bad STATUS must not kill the session
            self.log(f"ignoring malformed STATUS {data.hex()}")

    def _on_data(self, data: bytes) -> None:
        self.stats["data_packets"] += 1
        if self._fetch and len(data) >= p.DATA_HEADER.size:
            self._fetch.feed(data)

    # --- CONTROL -------------------------------------------------------------
    async def request(self, op: int, payload: bytes = b"", *, check: bool = True) -> p.Response:
        """Sends one CONTROL request, retrying BUSY with exponential backoff."""
        for attempt in range(self.busy_retries + 1):
            self._seq = (self._seq + 1) & 0xFF
            seq = self._seq
            fut = asyncio.get_running_loop().create_future()
            self._pending[seq] = fut
            try:
                await self.t.write_control(p.request(op, seq, payload))
                resp = await asyncio.wait_for(fut, self.response_timeout)
            except asyncio.TimeoutError:
                raise TransportError(f"no response to op 0x{op:02x} within {self.response_timeout}s") from None
            finally:
                self._pending.pop(seq, None)
            if resp.op != op:
                raise TransportError(f"response op 0x{resp.op:02x} for request 0x{op:02x}")
            if resp.result == p.RESULT_BUSY and attempt < self.busy_retries:
                self.stats["busy"] += 1
                await asyncio.sleep(self.busy_backoff * 2**attempt)
                continue
            if check and resp.result != p.RESULT_OK:
                raise ProtocolError(op, resp.result)
            return resp
        raise AssertionError("unreachable")

    async def read_status(self) -> p.Status:
        self.last_status = p.Status.unpack(await self.t.read_status())
        return self.last_status

    async def time_sync(self, tz_offset_min: int = 0) -> TimeSyncResult:
        t0 = time.time()
        resp = await self.request(p.OP_TIME_SYNC, struct.pack("<Qh", int(t0 * 1000), tz_offset_min))
        t1 = time.time()
        boot, uptime = p.parse_time_sync_response(resp.payload)
        return TimeSyncResult(boot, uptime, int((t0 + t1) * 500), int((t1 - t0) * 1000))

    async def list_segments(self, after_id: int = 0, max_count: int = p.LIST_MAX_PER_RESPONSE):
        resp = await self.request(p.OP_LIST_SEGMENTS, struct.pack("<IB", after_id, max_count))
        return p.parse_list_segments(resp.payload)

    async def list_all(self) -> list[tuple[int, int, int]]:
        """Pages through LIST_SEGMENTS (up to 26 per response), continuing from the last id."""
        out: list[tuple[int, int, int]] = []
        after = 0
        while True:
            page = await self.list_segments(after)
            if not page:
                return out
            if page[0][0] <= after:
                raise TransportError(f"LIST_SEGMENTS went backwards after id {after}")
            out += page
            after = page[-1][0]
            # No "more" flag in v1 and a device on a small MTU may return fewer
            # than 26, so a short page doesn't prove the end: page until empty.

    async def ack(self, seg_id: int, crc32: int) -> None:
        await self.request(p.OP_ACK_SEGMENT, struct.pack("<II", seg_id, crc32))

    async def abort(self) -> None:
        await self.request(p.OP_ABORT)

    async def set_recording(self, on: bool) -> None:
        await self.request(p.OP_SET_RECORDING, bytes([1 if on else 0]))

    async def unpair(self) -> None:
        """UNPAIR: the device deletes every bond and drops the link (§2.4)."""
        await self.request(p.OP_UNPAIR)

    async def set_config(self, key: int, value: int) -> None:
        await self.request(p.OP_SET_CONFIG, struct.pack("<BI", key, value))

    # --- FETCH ---------------------------------------------------------------
    async def fetch(self, asm: p.SegmentAssembler, *, max_stalls: int = 5, on_progress=None) -> None:
        """Fills `asm` (possibly pre-filled for resume) until complete.

        Each round asks for the first missing offset. A round ends when the last
        byte arrives, when an out-of-order chunk reveals a hole, or after
        idle_timeout without data. Before asking again the client sends ABORT, so
        the old stream is over: notifications sent before the ABORT response
        arrive before it on the same link.
        Raises TransportError on disconnect, FetchStalled if rounds stop helping.
        """
        stalls = 0
        rounds = 0
        while (gap := asm.first_gap()) is not None:
            if self._lost.is_set() or not self.t.is_connected:
                raise TransportError("connection lost during fetch")
            if rounds:
                self.stats["refetches"] += 1
                self.log(f"seg {asm.seg_id}: re-requesting from offset {gap}")
            rounds += 1
            st = _FetchState(asm, gap, on_progress)
            self._fetch = st
            try:
                await self.request(p.OP_FETCH_SEGMENT, struct.pack("<II", asm.seg_id, gap))
                while not st.done():
                    st.wake.clear()
                    try:
                        await asyncio.wait_for(st.wake.wait(), self.idle_timeout)
                    except asyncio.TimeoutError:
                        st.idle = True
                    if self._lost.is_set():
                        raise TransportError("connection lost during fetch")
                if not asm.complete():
                    await self.abort()  # keeps feeding asm until the ABORT response
            finally:
                self._fetch = None
            new_gap = asm.first_gap()
            if new_gap is not None and new_gap <= gap:
                stalls += 1
                if stalls > max_stalls:
                    raise FetchStalled(f"seg {asm.seg_id}: no progress at offset {gap}")
            else:
                stalls = 0


class _FetchState:
    def __init__(self, asm: p.SegmentAssembler, start: int, on_progress):
        self.asm = asm
        self.size = len(asm.buf)
        self.expected = start  # next contiguous offset of this round's stream
        self.hole_seen = False
        self.reached_end = False
        self.idle = False
        self.wake = asyncio.Event()
        self.on_progress = on_progress

    def feed(self, pkt: bytes) -> None:
        seg_id, off = p.DATA_HEADER.unpack_from(pkt)
        if seg_id != self.asm.seg_id:
            return
        n = len(pkt) - p.DATA_HEADER.size
        self.asm.feed(pkt)
        if off > self.expected:
            self.hole_seen = True  # a notification was lost in between
        elif off + n > self.expected:
            self.expected = off + n
        if off + n >= self.size:
            self.reached_end = True
        if self.on_progress:
            self.on_progress(self.asm)
        self.wake.set()

    def done(self) -> bool:
        return self.idle or self.hole_seen or self.reached_end or self.asm.complete()
