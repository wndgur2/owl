"""TIME_SYNC records and wall-clock restoration (protocol §4).

Each sync pairs the device's (boot_count, uptime_ms) with the PC clock. A record
is only valid for its own boot: after a reboot uptime restarts at 0, so a boot
without any sync has unknown wall-clock time.
"""

from __future__ import annotations

import datetime as dt
import json
import os
from dataclasses import asdict, dataclass

import owlproto as p


@dataclass
class SyncRecord:
    boot_count: int
    uptime_ms: int
    unix_ms: int  # PC clock at the moment the device sampled uptime_ms (RTT midpoint)
    rtt_ms: int = 0
    tz_offset_min: int = 0


class TimeStore:
    def __init__(self, path: str):
        self.path = path
        self.records: list[SyncRecord] = []
        if os.path.exists(path):
            with open(path) as f:
                self.records = [SyncRecord(**r) for r in json.load(f).get("syncs", [])]

    def add(self, rec: SyncRecord) -> None:
        self.records.append(rec)
        tmp = self.path + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"syncs": [asdict(r) for r in self.records]}, f, indent=1)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.path)

    def resolve(self, boot_count: int, uptime_ms: int) -> int | None:
        """Unix ms for (boot_count, uptime_ms), or None if that boot was never synced.

        With several syncs in one boot, the one nearest in uptime wins, which
        limits the error from the device's crystal drift.
        """
        same = [r for r in self.records if r.boot_count == boot_count]
        if not same:
            return None
        best = min(same, key=lambda r: (abs(r.uptime_ms - uptime_ms), r.rtt_ms))
        return p.to_unix_ms(boot_count, uptime_ms, {boot_count: (best.uptime_ms, best.unix_ms)})


def iso(unix_ms: int | None) -> str:
    if unix_ms is None:
        return "unknown"
    return dt.datetime.fromtimestamp(unix_ms / 1000).astimezone().isoformat(timespec="milliseconds")


def local_tz_offset_min() -> int:
    off = dt.datetime.now().astimezone().utcoffset()
    return int(off.total_seconds() // 60) if off else 0
