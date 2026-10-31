"""Scan for Owl devices and, optionally, connect and read STATUS.

Usage:
    pip install -r tools/requirements.txt
    python tools/ble_scan.py              # list nearby Owl-* devices
    python tools/ble_scan.py --status     # connect to the strongest one and print STATUS

This is the PC-side test bench for milestone M3: it lets you check the firmware's
BLE side without the Android app in the loop.
"""

from __future__ import annotations

import argparse
import asyncio

from bleak import BleakClient

import owlproto as p
from owlsync.ble import scan  # shared with sync.py


async def read_status(dev) -> None:
    async with BleakClient(dev) as client:
        raw = await client.read_gatt_char(p.STATUS_UUID)
        st = p.Status.unpack(raw)
        print(f"proto v{st.proto_ver}  fw {'.'.join(map(str, st.fw_ver))}")
        print(f"battery   {st.battery_mv} mV")
        print(f"storage   {st.storage_used_kb}/{st.storage_total_kb} KB, pending {st.pending_segments}")
        print(f"boot      #{st.boot_count}  flags 0x{st.flags:02x}")


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--timeout", type=float, default=5.0)
    ap.add_argument("--status", action="store_true", help="connect and read STATUS")
    args = ap.parse_args()

    hits = await scan(args.timeout)
    if not hits:
        print("No Owl device found. Is the board advertising? (nRF Connect app on your phone is a good cross-check)")
        return
    for rssi, dev, name in hits:
        print(f"{rssi:>4} dBm  {dev.address}  {name}")
    if args.status:
        await read_status(hits[0][1])


if __name__ == "__main__":
    asyncio.run(main())
