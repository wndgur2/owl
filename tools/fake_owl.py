"""Fake Owl pendant: protocol v1 device side, advertised over BLE.

Lets the Android app (or sync.py on another machine) be tested before the board exists.
Two radio backends:

  --transport bless (default)   the Mac's own Bluetooth via CoreBluetooth. Cannot refuse
                                pairings (owlsync/ble_peripheral.py).
  --transport bumble            Google Bumble on the Android emulator's virtual radio (netsim),
                                or any Bumble transport via --hci. Full §2.4 security
                                (owlsync/ble_bumble.py).

    pip install -r requirements.txt -r requirements-peripheral.txt   # bless
    pip install -r requirements.txt -r requirements-bumble.txt       # bumble
    python fake_owl.py                          # 4 tone segments (Opus if libopus is installed)
    python fake_owl.py --wav speech.wav --segments 6 --seconds 5
    python fake_owl.py --record-every 60        # add a new segment every minute while running
    python fake_owl.py --state fake_state.json  # boot_count/next id (and bumble bonds) survive restarts
    python fake_owl.py --transport bumble --state /tmp/owl.json   # Android emulator must be running
    python fake_owl.py --transport bumble --state /tmp/qa.json --week --locations-out /tmp/qa_fixes.csv
                                                # a week of history, one boot per sync (owlsync/week.py)

Commands on stdin while running (Enter after each): `reboot` (power cycle: link drops,
pairing window opens again), `record` (add a segment), `bookmark`, `rec` (2 s press: recording
off/on), `battery MV`, `storage KB` (flash size, to fill it up), `status`, `next` (--week: show
the next boot now), and with bumble `drop N` (the next FETCH loses the link after N DATA chunks,
to test resume) and `lose N` (the Nth next DATA chunk never arrives). Ctrl-C to stop.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import owlproto as p  # noqa: E402
from owlsync.device import OwlDevice  # noqa: E402
from owlsync.synth import frames_from_pcm, populate, tone  # noqa: E402
from owlsync import opus  # noqa: E402
from owlsync import week as weekmod  # noqa: E402


def load_state(path: str | None) -> dict:
    if path and os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return {}


def save_state(path: str | None, dev: OwlDevice) -> None:
    if path:
        with open(path, "w") as f:
            json.dump({"boot_count": dev.boot_count, "next_id": dev.next_id}, f)


async def run(args) -> None:
    state = load_state(args.state)
    boot = state.get("boot_count", args.boot_count - 1) + 1  # every start is a new boot
    dev = OwlDevice(boot_count=boot, uptime_offset_ms=int(args.uptime_h * 3600_000),
                    battery_mv=args.battery_mv, storage_total_kb=args.storage_kb,
                    log=lambda m: print(f"[owl] {m}", flush=True), next_id=state.get("next_id", 1))
    replay = None
    if args.week:
        def phone_now_ms() -> int:  # the week is laid out on the phone's clock (emulators drift seconds)
            return int((time.time() + args.phone_clock_offset) * 1000)

        now_ms = phone_now_ms()
        week = weekmod.plan_week(now_ms, seed=args.seed, first_boot=state.get("boot_count", 0) + 1,
                                 first_id=state.get("next_id", 1))
        print(week.summary())
        if args.locations_out:
            weekmod.write_fixes_csv(week.fixes, args.locations_out)
            print(f"GPS fixes -> {args.locations_out} (load them with the app's debug import-locations)")
        clips = weekmod.ClipPool(args.codec != "pcm" and opus.available())
        replay = weekmod.WeekReplay(dev, week, clips, phone_now_ms,
                                    log=lambda m: print(f"[owl] {m}", flush=True))
        replay.start()

        def after_disconnect() -> None:
            replay.on_disconnect()
            save_state(args.state, dev)

        dev.on_disconnected = after_disconnect
    else:
        info = populate(dev, audio_segments=args.segments, seconds=args.seconds,
                        prev_boot_segments=0 if args.no_prev_boot else 1, wav_path=args.wav, codec=args.codec)
        print(f"{len(dev.segments)} segments ({info['codec']}); open events: {len(dev.open_events)}")
    save_state(args.state, dev)
    print(f"boot #{dev.boot_count}, {len(dev.segments)} segments, {dev.storage_used_kb()} KB")

    if args.pairing_window is not None:
        dev.pairing_window_ms = int(args.pairing_window * 1000)
    ble_log = lambda m: print(f"[ble] {m}", flush=True)  # noqa: E731
    if args.transport == "bumble":
        from owlsync.ble_bumble import BumblePeripheral

        keys = args.keys or (os.path.splitext(args.state)[0] + ".keys.json" if args.state else None)
        periph = BumblePeripheral(dev, transport=args.hci, name=args.name, keys_path=keys, log=ble_log,
                                  unencrypted_timeout_s=args.unencrypted_timeout)
    else:
        from owlsync.ble_peripheral import BlessPeripheral

        periph = BlessPeripheral(dev, name=args.name, log=ble_log)
    await periph.start()

    k = 100

    def record() -> None:
        nonlocal k
        if not dev.recording or dev.storage_used_kb() >= dev.storage_total_kb:
            print("[owl] not recording (recording off or flash full)", flush=True)
            return
        codec, frames = frames_from_pcm(tone(args.seconds, 110 + (k % 7) * 20, seed=k),
                                        args.codec != "pcm" and opus.available())
        sid = dev.add_audio_segment(frames, codec=codec)
        if k % 2 == 0:
            dev.add_event(p.EVT_BOOKMARK)
        save_state(args.state, dev)
        print(f"[owl] recorded segment {sid}", flush=True)
        k += 1

    async def recorder():
        while True:
            await asyncio.sleep(args.record_every)
            record()

    async def commands():
        loop = asyncio.get_running_loop()
        while True:
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                return  # stdin closed (e.g. run in the background)
            cmd = line.strip()
            if cmd == "reboot":
                if hasattr(periph, "reboot"):
                    await periph.reboot()
                else:
                    dev.reboot(p.CAUSE_BUTTON)
                save_state(args.state, dev)
                print(f"[owl] rebooted: boot #{dev.boot_count}, pairing window open "
                      f"{dev.pairing_window_ms // 1000} s", flush=True)
            elif cmd.startswith("drop") and hasattr(periph, "drop_after_data"):
                n = int(cmd.split()[1]) if len(cmd.split()) > 1 else 10
                periph.drop_after_data = n
                print(f"[owl] next FETCH: link drops after {n} DATA chunks", flush=True)
            elif cmd.startswith("lose") and hasattr(periph, "lose_data_at"):
                n = int(cmd.split()[1]) if len(cmd.split()) > 1 else 10
                periph.lose_data_at = n
                print(f"[owl] next FETCH: DATA chunk #{n} is lost", flush=True)
            elif cmd == "record":
                record()
            elif cmd == "bookmark":
                dev.add_event(p.EVT_BOOKMARK)
                print("[owl] bookmark", flush=True)
            elif cmd == "rec":
                dev.recording = not dev.recording
                dev.add_event(p.EVT_RECORDING_ON if dev.recording else p.EVT_RECORDING_OFF, p.CAUSE_BUTTON)
                await dev._notify_status()
                print(f"[owl] recording {'on' if dev.recording else 'off'} (button)", flush=True)
            elif cmd.startswith("battery ") and cmd.split()[1].isdigit():
                was_low = dev.battery_mv < 3600
                dev.battery_mv = int(cmd.split()[1])
                if dev.battery_mv < 3600 and not was_low:
                    dev.add_event(p.EVT_BATTERY_LOW)
                await dev._notify_status()
                print(f"[owl] battery {dev.battery_mv} mV", flush=True)
            elif cmd.startswith("storage ") and cmd.split()[1].isdigit():
                dev.storage_total_kb = int(cmd.split()[1])
                if dev.storage_used_kb() >= dev.storage_total_kb:
                    dev.add_event(p.EVT_STORAGE_FULL)
                await dev._notify_status()
                print(f"[owl] flash {dev.storage_used_kb()}/{dev.storage_total_kb} KB", flush=True)
            elif cmd == "next" and replay is not None:
                replay._advance()
                save_state(args.state, dev)
            elif cmd == "status":
                st = dev.status()
                print(f"[owl] {st}; bonds={dev.bonds}; recording={dev.recording}", flush=True)
            elif cmd:
                print("[owl] commands: reboot, record, bookmark, rec, battery MV, storage KB, status, next (--week), "
                      "drop [N], lose [N] (bumble)", flush=True)

    tasks = [asyncio.create_task(commands())]
    if args.record_every:
        tasks.append(asyncio.create_task(recorder()))
    try:
        await asyncio.Event().wait()
    finally:
        for t in tasks:
            t.cancel()
        await periph.stop()
        save_state(args.state, dev)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--name", default="Owl-FA4E", help="advertised name (keep it short: <=10 chars keeps the UUID in the ad)")
    ap.add_argument("--segments", type=int, default=4, help="audio segments in the current boot")
    ap.add_argument("--seconds", type=float, default=3.0, help="length of each audio segment")
    ap.add_argument("--wav", help="16-bit WAV to cut segments from (default: synthetic voice-like tone)")
    ap.add_argument("--codec", choices=["auto", "opus", "pcm"], default="auto")
    ap.add_argument("--no-prev-boot", action="store_true", help="skip the unsynced previous-boot segment")
    ap.add_argument("--boot-count", type=int, default=2)
    ap.add_argument("--uptime-h", type=float, default=2.0, help="pretend uptime at start (hours)")
    ap.add_argument("--battery-mv", type=int, default=3950)
    ap.add_argument("--storage-kb", type=int, default=15 * 1024)
    ap.add_argument("--record-every", type=float, default=0, help="seconds between new segments (0 = off)")
    ap.add_argument("--state", help="JSON file that keeps boot_count / next id across runs")
    ap.add_argument("--week", action="store_true",
                    help="a week of history instead of --segments (owlsync/week.py, docs/qa/scenarios.md)")
    ap.add_argument("--seed", type=int, default=7, help="--week: RNG seed (same seed + date = same week)")
    ap.add_argument("--locations-out", metavar="CSV", help="--week: write the phone GPS fixes for the app here")
    ap.add_argument("--phone-clock-offset", type=float, default=0.0, metavar="S",
                    help="--week: phone clock minus this computer's, in seconds (an emulator is often a few "
                         "seconds off; see docs/qa/scenarios.md), so recordings land on exact times")
    ap.add_argument("--transport", choices=["bless", "bumble"], default="bless", help="radio backend (see above)")
    ap.add_argument("--hci", default="android-netsim:",
                    help="bumble: Bumble transport spec (default: the running Android emulator's netsim)")
    ap.add_argument("--keys", help="bumble: bond key file (default: <state>.keys.json with --state, else memory)")
    ap.add_argument("--pairing-window", type=float, metavar="S",
                    help="pairing window after power-on in seconds (default 120, §2.4); bumble enforces it")
    ap.add_argument("--unencrypted-timeout", type=float, default=p.UNENCRYPTED_TIMEOUT_MS / 1000, metavar="S",
                    help="bumble: drop a link still unencrypted after S seconds (default 60, §2.4)")
    args = ap.parse_args()
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass
    except RuntimeError as e:
        print(f"error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
