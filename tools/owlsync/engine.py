"""Sync engine: the protocol §5 flow with the guarantees PRD N4 needs.

    connect -> TIME_SYNC -> LIST (paged) -> per segment:
        FETCH (resume from the first missing offset, also across reconnects)
        -> write raw file + fsync -> ACK -> render .ogg/.wav/.json

A segment is ACKed only after its bytes are durable on disk. Partial downloads
are kept as raw/seg_N.owls.part so the next connection continues where the
last one stopped. Link failures retry with exponential backoff.
"""

from __future__ import annotations

import asyncio
import json
import os
import random
import zlib
from struct import error as struct_error
from dataclasses import dataclass, field
from typing import Callable

import owlproto as p

from . import render
from .client import FetchStalled, OwlClient, ProtocolError
from .timesync import SyncRecord, TimeStore, local_tz_offset_min
from .transport import NotPaired, Transport, TransportError


class SegmentStore:
    """raw/ directory: complete segments, partial downloads, quarantined bad ones."""

    def __init__(self, out_dir: str):
        self.dir = os.path.join(out_dir, "raw")
        os.makedirs(self.dir, exist_ok=True)

    def final_path(self, seg_id: int) -> str:
        return os.path.join(self.dir, render.basename(seg_id) + ".owls")

    def part_path(self, seg_id: int) -> str:
        return self.final_path(seg_id) + ".part"

    def load_complete(self, seg_id: int, size: int) -> bytes | None:
        path = self.final_path(seg_id)
        if os.path.exists(path) and os.path.getsize(path) == size:
            with open(path, "rb") as f:
                return f.read()
        return None

    def load_partial(self, seg_id: int, size: int) -> bytes:
        """Contiguous prefix saved by an earlier, interrupted fetch (b"" if none/stale)."""
        meta = self.part_path(seg_id) + ".json"
        try:
            with open(meta) as f:
                if json.load(f).get("size") != size:
                    return b""
            with open(self.part_path(seg_id), "rb") as f:
                return f.read()[:size]
        except (OSError, ValueError):
            return b""

    def save_partial(self, asm: p.SegmentAssembler) -> int:
        gap = asm.first_gap()
        n = len(asm.buf) if gap is None else gap
        if n == 0:
            return 0
        _write_durable(self.part_path(asm.seg_id), bytes(asm.buf[:n]))
        _write_durable(self.part_path(asm.seg_id) + ".json", json.dumps({"size": len(asm.buf)}).encode())
        return n

    def write_final(self, seg_id: int, blob: bytes) -> str:
        path = self.final_path(seg_id)
        _write_durable(path, blob)
        for extra in (self.part_path(seg_id), self.part_path(seg_id) + ".json"):
            if os.path.exists(extra):
                os.remove(extra)
        return path

    def quarantine(self, seg_id: int) -> str:
        path = self.final_path(seg_id)
        bad = path + f".bad{random.randrange(1 << 16):04x}"
        os.replace(path, bad)
        return bad


def _write_durable(path: str, data: bytes) -> None:
    """Write to tmp, fsync, rename over, fsync the directory: survives a crash at any point."""
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
    if hasattr(os, "O_DIRECTORY"):
        fd = os.open(os.path.dirname(path) or ".", os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


@dataclass
class SyncOptions:
    out_dir: str
    ack: bool = True
    render: bool = True
    max_attempts: int = 5  # connection attempts in a row without progress
    backoff_base: float = 1.0
    backoff_max: float = 30.0
    response_timeout: float = 5.0
    idle_timeout: float = 2.0
    max_crc_retries: int = 2


@dataclass
class SyncReport:
    saved: list[int] = field(default_factory=list)
    acked: list[int] = field(default_factory=list)
    already_had: list[int] = field(default_factory=list)
    crc_retries: int = 0
    reconnects: int = 0
    resumed_from: dict[int, int] = field(default_factory=dict)  # seg_id -> offset
    bytes: int = 0
    warnings: list[str] = field(default_factory=list)
    time_sync: SyncRecord | None = None
    status: p.Status | None = None


class SyncFailed(Exception):
    pass


class SyncEngine:
    def __init__(self, transport: Transport, opts: SyncOptions, log: Callable[[str], None] = print):
        self.t = transport
        self.o = opts
        self.log = log
        self.store = SegmentStore(opts.out_dir)
        self.times = TimeStore(os.path.join(opts.out_dir, "timesync.json"))
        self.report = SyncReport()
        self._handled: set[int] = set()  # done in this run (needed for --no-ack, where LIST keeps them)
        self._crc_failures: dict[int, int] = {}
        self._progress = False

    async def run(self) -> SyncReport:
        failures = 0
        while True:
            self._progress = False
            try:
                await self._session()
                return self.report
            except (TransportError, ConnectionError, asyncio.TimeoutError, OSError) as e:
                if isinstance(e, (FileNotFoundError, PermissionError, IsADirectoryError)):
                    raise
                if isinstance(e, NotPaired):
                    raise SyncFailed(str(e)) from e  # retrying cannot help (§2.4)
                failures = 0 if self._progress else failures + 1
                if failures >= self.o.max_attempts:
                    raise SyncFailed(f"giving up after {failures} failed attempts: {e}") from e
                delay = min(self.o.backoff_max, self.o.backoff_base * 2 ** max(0, failures - 1))
                delay *= 0.75 + random.random() / 2
                self.report.reconnects += 1
                self.log(f"link problem: {e} -> reconnecting in {delay:.1f}s")
                await asyncio.sleep(delay)

    async def _session(self) -> None:
        client = OwlClient(self.t, response_timeout=self.o.response_timeout,
                           idle_timeout=self.o.idle_timeout, log=self.log)
        await client.connect()
        try:
            try:
                self.report.status = await client.read_status()
            except (TransportError, ValueError, struct_error) as e:
                self.report.warnings.append(f"STATUS read failed: {e}")
            ts = await client.time_sync(local_tz_offset_min())
            rec = SyncRecord(ts.boot_count, ts.uptime_ms, ts.unix_ms, ts.rtt_ms, local_tz_offset_min())
            self.times.add(rec)
            self.report.time_sync = rec
            self.log(f"TIME_SYNC boot #{ts.boot_count}, uptime {ts.uptime_ms} ms (rtt {ts.rtt_ms} ms)")
            while True:
                entries = [e for e in await client.list_all() if e[0] not in self._handled]
                if not entries:
                    break
                self.log(f"{len(entries)} segment(s) to fetch")
                for seg_id, seg_type, size in entries:
                    await self._one(client, seg_id, seg_type, size)
                    self._handled.add(seg_id)
        finally:
            try:
                await client.close()
            except Exception:  # noqa: BLE001
                pass
        if self.o.render:
            render.render_all(self.o.out_dir)

    async def _one(self, client: OwlClient, seg_id: int, seg_type: int, size: int) -> None:
        blob = self.store.load_complete(seg_id, size)
        if blob is not None:
            self.report.already_had.append(seg_id)
            self.log(f"seg {seg_id}: already on disk")
        else:
            try:
                blob = await self._fetch(client, seg_id, size)
            except ProtocolError as e:
                if e.result != p.RESULT_NO_SEGMENT:
                    raise
                self.report.warnings.append(f"seg {seg_id}: listed but gone when fetched")
                return
            problems = render.validate(blob, seg_id)
            if problems:
                self.report.warnings.append(f"seg {seg_id}: {'; '.join(problems)}")
            self.store.write_final(seg_id, blob)  # durable before ACK (spec §5)
            self.report.saved.append(seg_id)
            self.report.bytes += len(blob)
            self._progress = True
        if not self.o.ack:
            return
        crc = zlib.crc32(blob) & 0xFFFFFFFF
        try:
            await client.ack(seg_id, crc)
        except ProtocolError as e:
            if e.result == p.RESULT_NO_SEGMENT:
                return  # already deleted: an earlier ACK landed but its response was lost
            if e.result != p.RESULT_CRC_MISMATCH:
                raise
            n = self._crc_failures[seg_id] = self._crc_failures.get(seg_id, 0) + 1
            bad = self.store.quarantine(seg_id)
            self.report.crc_retries += 1
            self.log(f"seg {seg_id}: device says CRC mismatch, kept as {os.path.basename(bad)}, fetching again")
            if n > self.o.max_crc_retries:
                self.report.warnings.append(f"seg {seg_id}: CRC mismatch {n} times, left on device")
                return
            await self._one(client, seg_id, seg_type, size)
            return
        self.report.acked.append(seg_id)
        self._progress = True
        self.log(f"seg {seg_id}: ACK ok, device may delete it")

    async def _fetch(self, client: OwlClient, seg_id: int, size: int) -> bytes:
        asm = p.SegmentAssembler(seg_id, size)
        prefix = self.store.load_partial(seg_id, size)
        if prefix:
            asm.buf[: len(prefix)] = prefix
            asm.have[: len(prefix)] = b"\x01" * len(prefix)
            self.report.resumed_from[seg_id] = len(prefix)
            self.log(f"seg {seg_id}: resuming at {len(prefix)}/{size}")
        start = len(prefix)
        try:
            await client.fetch(asm)
        except (TransportError, asyncio.TimeoutError, FetchStalled):
            if self.store.save_partial(asm) > start:
                self._progress = True
            raise
        return bytes(asm.buf)

