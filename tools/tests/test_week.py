"""The week seed for app QA (owlsync/week.py, docs/qa/scenarios.md).

Checks the plan's edge cases are really in the data, that it is deterministic, and that the
boot-by-boot replay gives the right wall-clock times through a real sync (PC engine, loopback).
"""

import asyncio
import csv
import datetime as dt
import time

import owlproto as p
from owlsync import week as w
from owlsync.device import OwlDevice
from owlsync.engine import SyncEngine, SyncOptions
from owlsync.transport import LoopbackTransport

TZ = dt.timezone(dt.timedelta(hours=9))
NOW = int(dt.datetime(2026, 11, 1, 18, 30, tzinfo=TZ).timestamp() * 1000)


def at(day_off, hhmmss):
    h, m, s = (int(x) for x in hhmmss.split(":"))
    d = dt.date(2026, 11, 1) + dt.timedelta(days=day_off)
    return int(dt.datetime.combine(d, dt.time(h, m, s), TZ).timestamp() * 1000)


def plan(**kw):
    return w.plan_week(NOW, tz=TZ, **kw)


def seg_at(week, wall):
    return next(s for s in week.segments if s.start_ms == wall)


def test_deterministic_and_seeded():
    a, b = plan(), plan()
    assert [(s.seg_id, s.start_ms, s.seconds, s.variant) for s in a.segments] == \
           [(s.seg_id, s.start_ms, s.seconds, s.variant) for s in b.segments]
    assert [(f.time_ms, f.lat, f.lon) for f in a.fixes] == [(f.time_ms, f.lat, f.lon) for f in b.fixes]
    assert [s.start_ms for s in plan(seed=8).segments] != [s.start_ms for s in a.segments]


def test_shape_boots_and_ids():
    week = plan()
    assert [(b.boot_count, b.cause, b.synced) for b in week.boots] == [
        (1, p.CAUSE_BUTTON, True), (2, p.CAUSE_CHARGED, True), (3, p.CAUSE_BUTTON, False), (4, p.CAUSE_OTHER, True)]
    # ids: unique, in creation (time) order, and the device continues after them
    audio = sorted((s.start_ms, s.seg_id) for s in week.segments)
    assert [i for _t, i in audio] == sorted(i for _t, i in audio)
    logs = [(max(e[0] for e in evs), sid) for b in week.boots for sid, evs in b.event_logs]
    for last_event, sid in logs:  # a log is closed after its last event, so it outranks earlier audio
        assert all(i < sid for t, i in audio if t <= last_event)
    ids = [i for _t, i in audio] + [i for _t, i in logs]
    assert len(set(ids)) == len(ids) == week.next_id - 1
    # every record lies inside its boot (uptime >= 0, before the next power-on)
    for i, b in enumerate(week.boots):
        end = week.boots[i + 1].start_ms if i + 1 < len(week.boots) else NOW
        walls = [s.start_ms for s in b.segments] + [e[0] for _id, evs in b.event_logs for e in evs]
        assert all(b.start_ms <= t < end for t in walls)
    assert all(s.start_ms <= NOW - 20 * 60_000 for s in week.segments)  # nothing in the future
    # practical for BLE in the emulator (docs/qa/scenarios.md "규모")
    assert 300 < len(week.segments) < 800 and sum(s.seconds for s in week.segments) < 2000


def test_continues_from_state():
    week = plan(first_boot=6, first_id=900)
    assert week.boots[0].boot_count == 6 and min(s.seg_id for s in week.segments) == 900


def test_edge_cases_are_in_the_data():
    week = plan()
    # 2-minute boundary (decisions #30): 120 s gap merges, 121 s does not
    a, b, c = (seg_at(week, at(-5, t)) for t in ("14:00:00", "14:02:03", "14:04:07"))
    assert (a.seconds, b.seconds) == (3, 3)
    assert b.start_ms - (a.start_ms + a.seconds * 1000) == 120_000
    assert c.start_ms - (b.start_ms + b.seconds * 1000) == 121_000
    # recording off/on between two segments 87 s apart
    b1 = week.boots[0]
    evs = [e for _id, log in b1.event_logs for e in log]
    x, y = seg_at(week, at(-6, "10:28:00")), seg_at(week, at(-6, "10:29:30"))
    assert y.start_ms - (x.start_ms + 3000) == 87_000
    assert (at(-6, "10:28:30"), p.EVT_RECORDING_OFF, p.CAUSE_BUTTON) in evs
    # never-synced boot with a bookmark outside any recording, app-cause recording off, storage full
    assert sum(1 for _i, log in week.boots[2].event_logs for e in log if e[1] == p.EVT_BOOKMARK) == 2
    b2 = [e for _i, log in week.boots[1].event_logs for e in log]
    assert (at(-4, "18:00:00"), p.EVT_RECORDING_OFF, p.CAUSE_APP) in b2
    b4 = [e for _i, log in week.boots[3].event_logs for e in log]
    assert (at(-1, "16:40:00"), p.EVT_STORAGE_FULL, 0) in b4
    # today's events stay open (sealed when the phone connects, §5.1 rule 7)
    assert week.boots[3].open_events and all(e[0] >= at(0, "00:00:00") for e in week.boots[3].open_events)
    # GPS: none during the "location off" window, every 5 min otherwise
    assert not [f for f in week.fixes if at(-4, "13:00:00") <= f.time_ms < at(-4, "16:00:00")]
    near = [f for f in week.fixes if at(-1, "10:30:00") <= f.time_ms < at(-1, "10:50:00")]
    assert near and all(w.PLACES["store"][1] - 2e-4 < f.lat < w.PLACES["store"][1] + 2e-4 for f in near)


def test_store_is_inside_home_radius_and_others_are_apart():
    def dist(a, b):
        import math
        (_, la1, lo1), (_, la2, lo2) = w.PLACES[a], w.PLACES[b]
        x = math.radians(lo2 - lo1) * math.cos(math.radians((la1 + la2) / 2))
        return math.hypot(x, math.radians(la2 - la1)) * 6_371_000
    assert dist("home", "store") < 100
    keys = [k for k in w.PLACES if k != "store"]
    assert all(dist(a, b) > 150 for i, a in enumerate(keys) for b in keys[i + 1:])


def test_replay_restores_wall_clock_per_boot(tmp_path):
    """Three syncs (boot 1, 2, 4) through the real PC engine: every record of a synced boot
    comes out at its planned time; boot 3's stay unknown."""
    now = int(time.time() * 1000)
    week = w.plan_week(now, tz=TZ)
    dev = OwlDevice(boot_count=1)
    replay = w.WeekReplay(dev, week, w.ClipPool(use_opus=False, seconds=3), lambda: int(time.time() * 1000),
                          log=lambda _m: None)
    dev.on_disconnected = replay.on_disconnect
    replay.start()
    shown = []
    opts = SyncOptions(str(tmp_path), idle_timeout=0.05, response_timeout=0.5, backoff_base=0.001, backoff_max=0.01)
    for _ in range(3):
        shown.append(dev.boot_count)
        asyncio.run(SyncEngine(LoopbackTransport(dev, None), opts, log=lambda _m: None).run())
    assert shown == [1, 2, 4] and replay.done and dev.segments == {}

    planned = {s.seg_id: (b, s.start_ms) for b in week.boots for s in b.segments}
    rows = [r for r in csv.DictReader(open(tmp_path / "segments.csv")) if r["type"] == "audio"]
    assert len(rows) == len(planned)
    for r in rows:
        boot, start = planned[int(r["id"])]
        if boot.synced:
            assert abs(int(r["start_unix_ms"]) - start) < 1000, r
        else:
            assert r["start_unix_ms"] == ""
