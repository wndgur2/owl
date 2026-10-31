"""Transport abstraction (phone/PC = GATT central side) and an in-process loopback.

A Transport moves raw characteristic values; it knows nothing about opcodes.
`BleakTransport` (ble.py) talks to real hardware; `LoopbackTransport` wires the
client straight to a `device.OwlDevice` in the same event loop, with fault
injection so tests can reproduce lost packets, disconnects, CRC errors and BUSY.
"""

from __future__ import annotations

import asyncio
import random
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Callable

import owlproto as p


class TransportError(ConnectionError):
    """Link-level failure: not connected, connection lost, write failed."""


Handler = Callable[[bytes], None]


class Transport(ABC):
    def __init__(self) -> None:
        self.on_control: Handler = lambda _b: None
        self.on_data: Handler = lambda _b: None
        self.on_status: Handler = lambda _b: None
        self.on_disconnect: Callable[[], None] = lambda: None

    name: str = "?"

    @property
    @abstractmethod
    def is_connected(self) -> bool: ...

    @property
    @abstractmethod
    def mtu(self) -> int: ...

    @abstractmethod
    async def connect(self) -> None:
        """Connect, negotiate MTU if the platform allows, subscribe STATUS/CONTROL/DATA."""

    @abstractmethod
    async def disconnect(self) -> None: ...

    @abstractmethod
    async def read_status(self) -> bytes: ...

    @abstractmethod
    async def write_control(self, data: bytes) -> None:
        """Write With Response to CONTROL."""


class PeripheralLink(ABC):
    """What the device model sees: a way to notify the connected central."""

    @property
    @abstractmethod
    def max_notify_len(self) -> int: ...

    @abstractmethod
    async def notify(self, char_uuid: str, value: bytes) -> None:
        """Raises TransportError if the central is gone."""


# --- loopback ----------------------------------------------------------------
@dataclass
class Faults:
    """One-shot faults for the loopback link. Offsets refer to DATA chunk offsets."""

    drop_data: set[tuple[int, int]] = field(default_factory=set)  # (seg_id, offset) lost once
    drop_rate: float = 0.0  # random loss of DATA notifications
    corrupt_data: set[tuple[int, int]] = field(default_factory=set)  # flip one bit once
    disconnect_after_data: int | None = None  # drop the link after N DATA notifications
    disconnect_after_ack: bool = False  # drop the link right after an ACK is processed (response lost)
    busy: dict[int, int] = field(default_factory=dict)  # op -> answer BUSY this many times
    lose_response: dict[int, int] = field(default_factory=dict)  # op -> swallow this many responses
    seed: int = 0
    log: list[str] = field(default_factory=list)

    def __post_init__(self) -> None:
        self._rng = random.Random(self.seed)


class LoopbackTransport(Transport, PeripheralLink):
    """Client and device in one process. Notifications are delivered via call_soon,
    so ordering matches a real BLE link (one connection, in-order delivery)."""

    def __init__(self, device, faults: Faults | None = None, mtu: int = p.ATT_MTU, name: str = "Owl-LOOP"):
        super().__init__()
        self.device = device
        self.faults = faults or Faults()
        self._mtu = mtu
        self._connected = False
        self._data_sent = 0
        self.name = name
        self.connect_count = 0

    # central side
    @property
    def is_connected(self) -> bool:
        return self._connected

    @property
    def mtu(self) -> int:
        return self._mtu

    async def connect(self) -> None:
        if self._connected:
            return
        self._connected = True
        self._data_sent = 0
        self.connect_count += 1
        self.device.on_connect(self)

    async def disconnect(self) -> None:
        self._drop(notify_central=False)

    def _drop(self, notify_central: bool = True) -> None:
        if not self._connected:
            return
        self._connected = False
        self.device.on_disconnect()
        if notify_central:
            asyncio.get_running_loop().call_soon(self.on_disconnect)

    async def read_status(self) -> bytes:
        self._check()
        return self.device.status().pack()

    async def write_control(self, data: bytes) -> None:
        self._check()
        op = data[0] if data else 0
        if self.faults.busy.get(op, 0) > 0 and len(data) >= 2:
            self.faults.busy[op] -= 1
            self.faults.log.append(f"busy op=0x{op:02x}")
            resp = p.Response(op, data[1], p.RESULT_BUSY, b"").pack()
            asyncio.get_running_loop().call_soon(self.on_control, resp)
            return
        # The ATT write completes before the device processes it, like real hardware.
        asyncio.get_running_loop().create_task(self.device.handle_control(data))

    def _check(self) -> None:
        if not self._connected:
            raise TransportError("not connected")

    # peripheral side
    @property
    def max_notify_len(self) -> int:
        return self._mtu - 3

    async def notify(self, char_uuid: str, value: bytes) -> None:
        if not self._connected:
            raise TransportError("central disconnected")
        if len(value) > self.max_notify_len:
            raise ValueError(f"notification of {len(value)} B exceeds MTU-3")
        loop = asyncio.get_running_loop()
        f = self.faults
        if char_uuid == p.DATA_UUID:
            key = p.DATA_HEADER.unpack_from(value)
            self._data_sent += 1
            if f.disconnect_after_data is not None and self._data_sent > f.disconnect_after_data:
                f.disconnect_after_data = None
                f.log.append(f"disconnect at data {key}")
                self._drop()
                raise TransportError("link lost (injected)")
            if key in f.drop_data or (f.drop_rate and f._rng.random() < f.drop_rate):
                f.drop_data.discard(key)
                f.log.append(f"drop {key}")
                await asyncio.sleep(0)
                return
            if key in f.corrupt_data:
                f.corrupt_data.discard(key)
                f.log.append(f"corrupt {key}")
                b = bytearray(value)
                b[-1] ^= 0x01
                value = bytes(b)
            loop.call_soon(self.on_data, value)
            await asyncio.sleep(0)  # let the central run, like a radio would
        elif char_uuid == p.CONTROL_UUID:
            op = value[0] & ~p.RESPONSE_BIT
            if f.lose_response.get(op, 0) > 0:
                f.lose_response[op] -= 1
                f.log.append(f"lose response op=0x{op:02x}")
                return
            if op == p.OP_ACK_SEGMENT and f.disconnect_after_ack and value[2] == p.RESULT_OK:
                # The device already deleted the segment; the phone never hears back.
                f.disconnect_after_ack = False
                f.log.append("disconnect after ack")
                self._drop()
                raise TransportError("link lost (injected)")
            loop.call_soon(self.on_control, value)
        elif char_uuid == p.STATUS_UUID:
            loop.call_soon(self.on_status, value)
