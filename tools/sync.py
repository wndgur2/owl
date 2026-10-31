"""PC sync client for Owl BLE protocol v1 (PRD T2).

Usage (from tools/ or the repo root):
    python sync.py sync --out owl-data            # TIME_SYNC, LIST, FETCH, save, ACK, render
    python sync.py sync --out owl-data --no-ack   # same, but leave everything on the device
    python sync.py status | list
    python sync.py set-recording off
    python sync.py set-config bitrate 12000       # dev only (decisions.md #12)
    python sync.py render --out owl-data          # rebuild .ogg/.wav/.csv from raw/ offline
    python sync.py --fake sync --out /tmp/x       # no hardware: in-process fake device

Device selection: strongest Owl-* nearby, or --address / --name.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import owlproto as p  # noqa: E402
from owlsync import render  # noqa: E402
from owlsync.client import OwlClient, ProtocolError  # noqa: E402
from owlsync.device import CONFIG_NAMES  # noqa: E402
from owlsync.engine import SyncEngine, SyncFailed, SyncOptions  # noqa: E402
from owlsync.transport import TransportError  # noqa: E402


def make_fake_transport(args):
    from owlsync.device import OwlDevice
    from owlsync.synth import populate
    from owlsync.transport import LoopbackTransport

    dev = OwlDevice(boot_count=2, uptime_offset_ms=2 * 3600_000, log=lambda m: print(f"  [fake] {m}"))
    info = populate(dev, audio_segments=args.fake_segments, seconds=3.0)
    print(f"fake device: {len(dev.segments)} segments + open event log, codec {info['codec']}")
    return LoopbackTransport(dev, name="Owl-LOOP")


async def make_transport(args):
    if args.fake:
        return make_fake_transport(args)
    from owlsync.ble import BleakTransport, find

    dev, name = await find(args.address, args.name, args.scan_timeout)
    print(f"using {name} ({dev.address})")
    return BleakTransport(dev, name)


def print_status(st: p.Status) -> None:
    flags = [n for bit, n in [(p.FLAG_CHARGING, "charging"), (p.FLAG_RECORDING, "recording"),
                              (p.FLAG_STORAGE_LOW, "storage-low"), (p.FLAG_STORAGE_FULL, "storage-full")]
             if st.flags & bit]
    print(f"proto v{st.proto_ver}  fw {'.'.join(map(str, st.fw_ver))}")
    print(f"battery   {st.battery_mv} mV")
    print(f"storage   {st.storage_used_kb}/{st.storage_total_kb} KB, pending {st.pending_segments}")
    print(f"boot      #{st.boot_count}  flags 0x{st.flags:02x} {' '.join(flags) or '-'}")


async def with_client(args, fn):
    t = await make_transport(args)
    client = OwlClient(t, response_timeout=args.response_timeout, log=print)
    await client.connect()
    try:
        print(f"connected, MTU {t.mtu}")
        return await fn(client)
    finally:
        await client.close()


async def cmd_sync(args) -> int:
    t = await make_transport(args)
    opts = SyncOptions(args.out, ack=not args.no_ack, max_attempts=args.attempts,
                       idle_timeout=args.idle_timeout, response_timeout=args.response_timeout)
    try:
        r = await SyncEngine(t, opts, log=print).run()
    except SyncFailed as e:
        print(f"sync failed: {e}  (partial downloads kept in {args.out}/raw, rerun to resume)")
        return 1
    if r.status:
        print_status(r.status)
    print(f"saved {len(r.saved)} segment(s), {r.bytes} B; ACKed {len(r.acked)}; "
          f"already on disk {len(r.already_had)}; reconnects {r.reconnects}; CRC retries {r.crc_retries}")
    if not opts.ack:
        print("--no-ack: nothing was deleted on the device")
    for w in r.warnings:
        print(f"warning: {w}")
    print(f"output: {os.path.abspath(args.out)} (segments.csv, events.csv, audio/, events/, raw/)")
    return 0


async def cmd_status(args) -> int:
    async def run(c: OwlClient):
        print_status(await c.read_status())
    await with_client(args, run)
    return 0


async def cmd_list(args) -> int:
    async def run(c: OwlClient):
        entries = await c.list_all()
        types = {p.SEG_TYPE_AUDIO: "audio", p.SEG_TYPE_EVENTS: "events"}
        for sid, typ, size in entries:
            print(f"{sid:>8}  {types.get(typ, typ):<6}  {size:>8} B")
        print(f"{len(entries)} segment(s), {sum(e[2] for e in entries)} B")
    await with_client(args, run)
    return 0


async def cmd_set_recording(args) -> int:
    async def run(c: OwlClient):
        await c.set_recording(args.state == "on")
        print_status(await c.read_status())
    await with_client(args, run)
    return 0


async def cmd_set_config(args) -> int:
    key = CONFIG_NAMES.get(args.key) or int(args.key)

    async def run(c: OwlClient):
        await c.set_config(key, args.value)
        print(f"config {key} = {args.value}: OK")
    await with_client(args, run)
    return 0


async def cmd_render(args) -> int:
    rows = render.render_all(args.out, force=True)
    for r in rows:
        print(f"{r.get('id')!s:>8}  {r.get('type', '?'):<6}  {r.get('start_time', ''):<30}  {r.get('file', '')}"
              f"  {r.get('problems', '')}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--address", help="BLE address (macOS: CoreBluetooth UUID)")
    ap.add_argument("--name", help="exact advertised name, e.g. Owl-1A2B")
    ap.add_argument("--scan-timeout", type=float, default=8.0)
    ap.add_argument("--response-timeout", type=float, default=5.0)
    ap.add_argument("--fake", action="store_true", help="use an in-process fake device instead of BLE")
    ap.add_argument("--fake-segments", type=int, default=4)
    ap.add_argument("-v", "--verbose", action="store_true")
    sub = ap.add_subparsers(dest="cmd")

    s = sub.add_parser("sync", help="fetch, save and ACK every segment")
    s.add_argument("--out", default="owl-data")
    s.add_argument("--no-ack", action="store_true", help="do not ACK (device keeps everything)")
    s.add_argument("--attempts", type=int, default=5, help="reconnect attempts without progress")
    s.add_argument("--idle-timeout", type=float, default=2.0, help="s without DATA before re-requesting")
    sub.add_parser("status", help="read STATUS")
    sub.add_parser("list", help="LIST_SEGMENTS (all pages)")
    r = sub.add_parser("set-recording", help="SET_RECORDING on|off")
    r.add_argument("state", choices=["on", "off"])
    c = sub.add_parser("set-config", help="SET_CONFIG key value (dev only, decisions.md #12)")
    c.add_argument("key", help="bitrate | vad | segment-len | numeric key")
    c.add_argument("value", type=int)
    rd = sub.add_parser("render", help="rebuild audio/events/index files from raw/")
    rd.add_argument("--out", default="owl-data")

    args = ap.parse_args(argv)
    if args.cmd is None:
        args = ap.parse_args([*(argv if argv is not None else sys.argv[1:]), "sync"])
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.WARNING)
    cmds = {"sync": cmd_sync, "status": cmd_status, "list": cmd_list, "set-recording": cmd_set_recording,
            "set-config": cmd_set_config, "render": cmd_render}
    try:
        return asyncio.run(cmds[args.cmd](args))
    except (TransportError, ProtocolError) as e:
        print(f"error: {e}")
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
