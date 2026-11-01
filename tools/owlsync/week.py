"""A week of pendant history for app QA (`fake_owl.py --week`, docs/qa/scenarios.md).

`plan_week()` turns a fixed day-by-day script into recordings, events, boots and phone GPS
fixes, ending at `now`. Random parts (how a conversation splits into segments, GPS jitter) come
from a seeded RNG, so the same (now's date, seed, first boot, first id) always gives the same
week. `WeekReplay` then feeds it to an OwlDevice one boot at a time.

Why one boot at a time: the phone restores real time per boot from a TIME_SYNC made *during*
that boot (protocol §4). In real life the phone synced every 15 minutes all week. Here the
phone only exists now, so the fake presents each boot in turn with the uptime that boot would
have now (uptime = now - boot start); the app's TIME_SYNC then anchors that boot's records at
the right wall-clock time, exactly as the week of syncs would have. Boots marked `synced=False`
are skipped (their records stay "time unknown", like a boot that ended before any sync), and
their segments are handed over with the next boot, which is when the real pendant would have
listed them.

Scaling (so BLE transfer in the emulator stays short): wall-clock times are realistic, audio is
not. A conversation is a run of 2-3 s segments 60-110 s apart (gaps under 2 minutes, so the app
merges them into one entry, decisions #30); a 30-minute talk is ~20 segments, ~1 minute of
audio. The whole week is ~530 segments, about 2 MB of Opus.

GPS: the app gives a recording the phone fix nearest its middle (within 15 min) and the pendant
has no GPS (decisions #4). Fixes for past days cannot come from the emulator (`geo fix` stamps
"now"), so `fixes` are written as CSV for the debug app hook (`cmd import-locations`,
android/README.md). Fixes every 5 minutes, like LocationRecorder.
"""

from __future__ import annotations

import csv
import datetime as dt
import random
from dataclasses import dataclass, field

import owlproto as p

from .device import OwlDevice, StoredSegment

# Places (made-up campus). Distances: hall-library ~200 m, store-home ~85 m (inside the 100 m
# radius on purpose, so naming 집 also labels the store), everything else > 300 m apart.
PLACES: dict[str, tuple[str, float, float]] = {
    "home": ("집", 37.58030, 127.04510),
    "store": ("편의점(집 근처)", 37.58106, 127.04520),
    "hall": ("공학관", 37.58860, 127.02630),
    "library": ("도서관", 37.58970, 127.02820),
    "union": ("학생회관", 37.58710, 127.02910),
    "lab": ("실험실", 37.59220, 127.02350),
    "cafe": ("카페", 37.58290, 127.03580),
}

DAY_MS = 86_400_000
FIX_EVERY_MS = 5 * 60_000

# Day script, by day offset (0 = today). Items, in time order:
#   ("at", "HH:MM", place)             phone moves there (fixes follow it)
#   ("talk", "HH:MM", minutes)         a conversation: segments every 60-110 s for that long
#   ("seg", "HH:MM:SS", seconds)       one segment at an exact time (edge cases)
#   ("evt", "HH:MM:SS", event, arg)    a pendant event
#   ("boot", "HH:MM", cause, synced)   power-on: a new boot starts (power-on event logged in it)
#   ("noloc", "HH:MM", "HH:MM")        no GPS fixes in that window (location off)
# Comments say which scenario in docs/qa/scenarios.md each edge case is for.
SCRIPT: dict[int, list[tuple]] = {
    -7: [
        ("boot", "09:00", p.CAUSE_BUTTON, True),
        ("at", "09:00", "home"),
        ("talk", "09:30", 12),  # oldest audio: gone with the 7-day retention (A14)
        ("at", "13:00", "cafe"),
        ("talk", "13:20", 25),
        ("at", "18:00", "home"),
        ("talk", "20:10", 15),
    ],
    -6: [
        ("at", "08:40", "hall"),
        ("talk", "09:05", 40),
        # recording off by button between two segments 90 s apart: no merge (decisions #30)
        ("seg", "10:28:00", 3),
        ("evt", "10:28:30", p.EVT_RECORDING_OFF, p.CAUSE_BUTTON),
        ("evt", "10:29:00", p.EVT_RECORDING_ON, p.CAUSE_BUTTON),
        ("seg", "10:29:30", 3),
        ("evt", "10:31:00", p.EVT_RECORDING_OFF, p.CAUSE_BUTTON),
        ("evt", "11:10:00", p.EVT_BOOKMARK, 0),  # while recording off: standalone (#31)
        ("evt", "11:45:00", p.EVT_RECORDING_ON, p.CAUSE_BUTTON),
        ("at", "12:10", "union"),
        ("talk", "12:20", 30),
        ("evt", "12:31:00", p.EVT_BOOKMARK, 0),  # inside a conversation
        ("at", "14:00", "library"),
        ("talk", "15:00", 20),
        ("at", "19:00", "home"),
        ("talk", "21:00", 10),
    ],
    -5: [
        ("at", "09:30", "lab"),
        ("talk", "10:00", 45),
        ("at", "13:30", "hall"),
        # 2-minute boundary: seg2 starts exactly 120 s after seg1 ends (merges), seg3 121 s after
        # seg2 ends (does not). A bookmark in the merged gap belongs to the entry.
        ("seg", "14:00:00", 3),
        ("evt", "14:01:00", p.EVT_BOOKMARK, 0),
        ("seg", "14:02:03", 3),
        ("seg", "14:04:07", 3),
        ("talk", "15:00", 25),
        ("at", "18:30", "home"),
        ("talk", "20:00", 10),
        ("evt", "21:30:00", p.EVT_BATTERY_LOW, 0),
        ("evt", "22:40:00", p.EVT_POWER_OFF, p.CAUSE_BATTERY),  # battery died overnight
    ],
    -4: [
        ("boot", "07:50", p.CAUSE_CHARGED, True),  # auto power-on after charging (D10)
        ("at", "08:30", "hall"),
        ("talk", "09:00", 35),
        ("at", "12:00", "union"),
        ("talk", "12:15", 20),
        ("noloc", "13:00", "16:00"),  # phone location off: recording without a place
        ("at", "13:30", "library"),
        ("talk", "14:00", 15),
        ("evt", "14:05:00", p.EVT_BOOKMARK, 0),
        ("at", "17:30", "cafe"),
        ("talk", "17:40", 15),
        ("evt", "18:00:00", p.EVT_RECORDING_OFF, p.CAUSE_APP),  # turned off in the app
        ("evt", "18:40:00", p.EVT_BOOKMARK, 0),  # standalone, recording off
        ("evt", "19:30:00", p.EVT_RECORDING_ON, p.CAUSE_APP),
        ("at", "20:00", "home"),
        ("talk", "21:30", 10),
    ],
    -3: [
        ("at", "09:20", "lab"),
        ("talk", "09:40", 50),
        ("at", "13:00", "hall"),
        ("talk", "13:10", 30),
        ("at", "16:30", "home"),
        ("evt", "18:00:00", p.EVT_POWER_OFF, p.CAUSE_BUTTON),  # 5 s press: off
        # Back on, but the phone never syncs in this boot: its records have no real time (§4).
        ("boot", "20:00", p.CAUSE_BUTTON, False),
        ("talk", "20:10", 8),
        ("evt", "20:12:00", p.EVT_BOOKMARK, 0),
        ("talk", "20:40", 6),
        ("evt", "20:55:00", p.EVT_BOOKMARK, 0),  # unknown time, outside any recording
        # crash: no power-off event, next boot says "other restart"
        ("boot", "21:05", p.CAUSE_OTHER, True),
        ("talk", "22:00", 10),
    ],
    -2: [
        ("at", "08:50", "hall"),
        ("talk", "09:00", 45),
        ("at", "12:00", "union"),
        ("talk", "12:10", 25),
        ("at", "14:00", "library"),
        ("talk", "14:30", 30),
        ("evt", "14:50:00", p.EVT_BOOKMARK, 0),
        ("evt", "16:20:00", p.EVT_BOOKMARK, 0),  # standalone, between conversations
        ("at", "18:00", "cafe"),
        ("talk", "18:30", 40),
        ("at", "21:00", "home"),
    ],
    -1: [
        ("at", "10:00", "home"),
        ("at", "10:30", "store"),
        ("talk", "10:35", 5),  # 85 m from home: labelled 집 once home is named (100 m radius)
        ("at", "10:50", "home"),
        ("talk", "11:30", 20),
        # phone left at home, pendant fills up: storage-full event, nothing recorded until the
        # phone is back and has taken segments off (D4, decisions #17)
        ("at", "13:00", "cafe"),
        ("talk", "13:30", 60),
        ("evt", "16:40:00", p.EVT_STORAGE_FULL, 0),
        ("at", "18:30", "home"),
        ("talk", "19:00", 15),
        ("evt", "22:10:00", p.EVT_BATTERY_LOW, 0),  # then charged, no power-off
    ],
    0: [
        ("at", "09:00", "home"),
        ("talk", "10:00", 20),
        ("evt", "10:05:00", p.EVT_BOOKMARK, 0),
        ("at", "13:00", "cafe"),
        ("talk", "14:00", 30),
        ("at", "17:00", "home"),
        ("talk", "17:30", 10),
    ],
}


@dataclass
class Seg:
    seg_id: int
    start_ms: int  # wall clock
    seconds: int
    variant: int


@dataclass
class Boot:
    boot_count: int
    start_ms: int
    synced: bool
    cause: int
    segments: list[Seg] = field(default_factory=list)
    events: list[tuple[int, int, int]] = field(default_factory=list)  # (wall ms, event, arg)
    event_logs: list[tuple[int, list[tuple[int, int, int]]]] = field(default_factory=list)  # (id, events) sealed
    open_events: list[tuple[int, int, int]] = field(default_factory=list)  # current boot only

    def uptime(self, wall_ms: int) -> int:
        return wall_ms - self.start_ms


@dataclass
class Fix:
    time_ms: int
    lat: float
    lon: float
    accuracy_m: float


@dataclass
class Week:
    boots: list[Boot]
    fixes: list[Fix]
    next_id: int
    now_ms: int

    @property
    def segments(self) -> list[Seg]:
        return [s for b in self.boots for s in b.segments]

    def summary(self) -> str:
        lines = []
        for b in self.boots:
            t = dt.datetime.fromtimestamp(b.start_ms / 1000).strftime("%m-%d %H:%M")
            n_bm = sum(1 for e in b.events if e[1] == p.EVT_BOOKMARK)
            lines.append(f"boot {b.boot_count}: on {t} cause {b.cause}, {'synced' if b.synced else 'NEVER synced'}, "
                         f"{len(b.segments)} audio, {len(b.events)} events ({n_bm} bookmarks), "
                         f"{len(b.event_logs)} event logs sealed")
        lines.append(f"{len(self.segments)} audio segments, {sum(s.seconds for s in self.segments)} s of audio, "
                     f"{len(self.fixes)} GPS fixes")
        return "\n".join(lines)


def _at(day: dt.date, hhmm: str, tz) -> int:
    parts = [int(x) for x in hhmm.split(":")] + [0]
    t = dt.datetime.combine(day, dt.time(parts[0], parts[1], parts[2]), tz)
    return int(t.timestamp() * 1000)


def plan_week(now_ms: int, *, seed: int = 7, first_boot: int = 1, first_id: int = 1, tz=None,
              until_ms: int | None = None) -> Week:
    """The script above, ending at now. Items later than `until_ms` (default now - 20 min) are left
    out, so "today" only has what already happened. Deterministic for (local date of now, seed,
    first_boot, first_id)."""
    tz = tz or dt.datetime.now().astimezone().tzinfo
    rnd = random.Random(seed)
    today = dt.datetime.fromtimestamp(now_ms / 1000, tz).date()
    until = now_ms - 20 * 60_000 if until_ms is None else until_ms

    boots: list[Boot] = []
    audio: list[tuple[int, Boot, int, int]] = []  # (wall, boot, seconds, variant)
    track: list[tuple[int, str]] = []  # (wall, place) moves
    noloc: list[tuple[int, int]] = []

    def boot() -> Boot:
        return boots[-1]

    for off in sorted(SCRIPT):
        day = today + dt.timedelta(days=off)
        for item in SCRIPT[off]:
            kind, when = item[0], _at(day, item[1], tz)
            if when > until:
                continue
            if kind == "boot":
                boots.append(Boot(first_boot + len(boots), when, item[3], item[2]))
                boot().events.append((when + 500, p.EVT_POWER_ON, item[2]))
            elif kind == "at":
                track.append((when, item[2]))
            elif kind == "noloc":
                noloc.append((when, _at(day, item[2], tz)))
            elif kind == "evt":
                boot().events.append((when, item[2], item[3]))
            elif kind == "seg":
                audio.append((when, boot(), item[2], rnd.randrange(6)))
            elif kind == "talk":
                end = when + item[2] * 60_000
                t = when + rnd.randint(0, 20) * 1000
                while t < end and t <= until:
                    secs = rnd.randint(2, 3)
                    audio.append((t, boot(), secs, rnd.randrange(6)))
                    t += rnd.randint(60, 110) * 1000  # gap = spacing - length < 2 min

    # Ids in creation order: an audio segment when it is recorded, an event log when the phone
    # connects (here: the end of each day, or the boot's end) and closes it (§5.1 rule 7).
    creations: list[tuple[int, int, object]] = [(w, 0, (b, s, v)) for w, b, s, v in audio]
    for i, b in enumerate(boots):
        end = boots[i + 1].start_ms if i + 1 < len(boots) else None
        by_day: dict[dt.date, list] = {}
        for e in sorted(b.events):
            by_day.setdefault(dt.datetime.fromtimestamp(e[0] / 1000, tz).date(), []).append(e)
        for d, evs in sorted(by_day.items()):
            seal = min(_at(d, "23:59", tz), end - 1) if end else _at(d, "23:59", tz)
            if end is None and d == today:
                b.open_events = evs  # still open: the device seals it when the phone connects
            else:
                creations.append((seal, 1, (b, evs)))
    sid = first_id
    for _w, kind, obj in sorted(creations, key=lambda c: (c[0], c[1])):
        if kind == 0:
            b, secs, var = obj
            b.segments.append(Seg(sid, _w, secs, var))
        else:
            b, evs = obj
            b.event_logs.append((sid, evs))
        sid += 1

    return Week(boots, _fixes(track, noloc, rnd, until), sid, now_ms)


def _fixes(track: list[tuple[int, str]], noloc: list[tuple[int, int]], rnd: random.Random, until: int) -> list[Fix]:
    out: list[Fix] = []
    for i, (t0, place) in enumerate(track):
        t1 = track[i + 1][0] if i + 1 < len(track) else until
        # stop at 23:00 when the next move is another day (phone on the nightstand: still fixes,
        # but the app only looks 15 min around a recording, so fewer rows is fine)
        t1 = min(t1, t0 + 14 * 3_600_000)
        _, lat, lon = PLACES[place]
        t = t0
        while t < t1 and t <= until:
            if not any(a <= t < b for a, b in noloc):
                # ~15 m of jitter, balanced-power accuracy 20-60 m
                out.append(Fix(t, round(lat + rnd.uniform(-1.3e-4, 1.3e-4), 6),
                               round(lon + rnd.uniform(-1.6e-4, 1.6e-4), 6), float(rnd.randint(20, 60))))
            t += FIX_EVERY_MS
    return out


def write_fixes_csv(fixes: list[Fix], path: str) -> None:
    """`time_ms,lat,lon,accuracy_m` per line, no header (read by the debug app hook)."""
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        for x in fixes:
            w.writerow([x.time_ms, x.lat, x.lon, x.accuracy_m])


class ClipPool:
    """Six speech-like clips, encoded once; a segment of S seconds is the first S s of one of
    them (Opus packets decode on their own, so cutting is fine)."""

    def __init__(self, use_opus: bool, seconds: int = 6):
        from .synth import frames_from_pcm, tone

        self.codec = p.CODEC_OPUS if use_opus else p.CODEC_PCM16
        self.clips = [frames_from_pcm(tone(seconds, 110 + 22 * k, seed=100 + k), use_opus)[1] for k in range(6)]

    def frames(self, variant: int, seconds: int) -> list[bytes]:
        return self.clips[variant % len(self.clips)][: seconds * 50]


def load_boot(dev: OwlDevice, boot: Boot, clips: ClipPool) -> None:
    """Puts one boot's recordings and closed event logs on the device's "flash"."""
    for s in boot.segments:
        hdr = p.SegmentHeader(p.SEG_TYPE_AUDIO, clips.codec, 20, s.seg_id, boot.boot_count,
                              boot.uptime(s.start_ms), 16000, 1, 0)
        dev.segments[s.seg_id] = StoredSegment(s.seg_id, p.SEG_TYPE_AUDIO,
                                               p.build_audio_segment(hdr, clips.frames(s.variant, s.seconds)))
    for sid, evs in boot.event_logs:
        up = [(boot.uptime(w), e, a) for w, e, a in evs]
        hdr = p.SegmentHeader(p.SEG_TYPE_EVENTS, p.CODEC_PCM16, 0, sid, boot.boot_count, up[0][0], 16000, 1, 0)
        dev.segments[sid] = StoredSegment(sid, p.SEG_TYPE_EVENTS, p.build_event_segment(hdr, up))


class WeekReplay:
    """Presents the week's boots to the phone one at a time (see the module docstring).

    `start()` jumps to the first synced boot. After each disconnect that followed a TIME_SYNC in
    the boot on show, `on_disconnect()` moves to the next synced boot, first loading any
    never-synced boots in between. The last boot is the current one: from then on the device
    behaves normally (live `record`, `reboot`, ...)."""

    def __init__(self, dev: OwlDevice, week: Week, clips: ClipPool, wall_ms, log=print):
        self.dev, self.week, self.clips, self.wall_ms, self.log = dev, week, clips, wall_ms, log
        self.pos = -1  # index of the boot on show
        self.loaded = 0  # boots[:loaded] are on the device

    @property
    def done(self) -> bool:
        return self.pos == len(self.week.boots) - 1

    def start(self) -> None:
        self.dev.next_id = self.week.next_id
        self._advance()

    def on_disconnect(self) -> None:
        if self.done:
            return
        b = self.week.boots[self.pos]
        if any(s[0] == b.boot_count for s in self.dev.time_syncs):
            self._advance()

    def _advance(self) -> None:
        boots = self.week.boots
        nxt = next((i for i in range(self.pos + 1, len(boots)) if boots[i].synced or i == len(boots) - 1), None)
        if nxt is None:
            return
        while self.loaded <= nxt:
            load_boot(self.dev, boots[self.loaded], self.clips)
            self.loaded += 1
        b = boots[nxt]
        self.pos = nxt
        self.dev.jump_to_boot(b.boot_count, self.wall_ms() - b.start_ms)
        if self.done:
            self.dev.open_events = [(b.uptime(w), e, a) for w, e, a in b.open_events]
        left = sum(1 for x in boots[nxt + 1:] if x.synced)
        self.log(f"week: showing boot {b.boot_count} (on since "
                 f"{dt.datetime.fromtimestamp(b.start_ms / 1000):%m-%d %H:%M}), {len(self.dev.segments)} segments "
                 f"on flash; {left} more boot(s) after the next sync" if left else
                 f"week: showing boot {b.boot_count}, the current one; {len(self.dev.segments)} segments on flash")
