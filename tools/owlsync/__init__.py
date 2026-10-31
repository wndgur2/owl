"""PC-side tooling for Owl BLE protocol v1, built on top of owlproto.py.

Modules:
    transport   Transport interface (central side) + in-process loopback with fault injection
    client      Protocol client: request/response matching, LIST paging, FETCH with gap recovery
    sync        Sync engine: resume across reconnects, write+fsync before ACK, retries with backoff
    device      Device-side model of protocol v1 (the fake Owl) + synthetic segment content
    ogg         Pure-Python Ogg Opus muxer/parser and WAV writer
    opus        Optional libopus binding via ctypes (encoder for the fake, decoder for checks)
    timesync    TIME_SYNC store and (boot_count, uptime_ms) -> wall-clock restoration
    render      Raw .owls -> .ogg/.wav/.json/.csv
    ble         bleak transport (real hardware)
    ble_peripheral  bless peripheral that exposes the fake device over real BLE (macOS)
"""

import os
import sys

# owlproto.py lives next to this package (tools/). Make it importable no matter
# where the tools are launched from (repo root, tools/, pytest).
_TOOLS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _TOOLS_DIR not in sys.path:
    sys.path.insert(0, _TOOLS_DIR)
