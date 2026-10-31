"""Fake Owl pendant: protocol v1 device side, advertised over real BLE (macOS, bless).

Lets the Android app (or sync.py on another machine) be tested before the board exists.

    pip install -r requirements.txt -r requirements-peripheral.txt
    python fake_owl.py                          # 4 tone segments (Opus if libopus is installed)
    python fake_owl.py --wav speech.wav --segments 6 --seconds 5
    python fake_owl.py --record-every 60        # add a new segment every minute while running
    python fake_owl.py --state fake_state.json  # boot_count/next id survive restarts (reboot each run)

Keys while running: none; Ctrl-C to stop. See owlsync/ble_peripheral.py for what
CoreBluetooth cannot emulate (MTU/PHY control, connecting to itself).
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import owlproto as p  # noqa: E402
from owlsync.device import OwlDevice  # noqa: E402
from owlsync.synth import frames_from_pcm, populate, tone  # noqa: E402
from owlsync import opus  # noqa: E402


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
                    log=lambda m: print(f"[owl] {m}"), next_id=state.get("next_id", 1))
    info = populate(dev, audio_segments=args.segments, seconds=args.seconds,
                    prev_boot_segments=0 if args.no_prev_boot else 1, wav_path=args.wav, codec=args.codec)
    save_state(args.state, dev)
    print(f"boot #{dev.boot_count}, {len(dev.segments)} segments ({info['codec']}), "
          f"{dev.storage_used_kb()} KB; open events: {len(dev.open_events)}")

    from owlsync.ble_peripheral import BlessPeripheral

    periph = BlessPeripheral(dev, name=args.name, log=lambda m: print(f"[ble] {m}"))
    await periph.start()

    async def recorder():
        k = 100
        while True:
            await asyncio.sleep(args.record_every)
            if not dev.recording:
                continue
            codec, frames = frames_from_pcm(tone(args.seconds, 110 + (k % 7) * 20, seed=k),
                                            args.codec != "pcm" and opus.available())
            sid = dev.add_audio_segment(frames, codec=codec)
            if k % 2 == 0:
                dev.add_event(p.EVT_BOOKMARK)
            save_state(args.state, dev)
            print(f"[owl] recorded segment {sid}")
            k += 1

    tasks = [asyncio.create_task(recorder())] if args.record_every else []
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
