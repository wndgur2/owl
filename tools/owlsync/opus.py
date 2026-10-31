"""Optional libopus binding (ctypes) and Opus packet helpers.

The encoder is only used by the fake device to produce real Opus segments; the
decoder only by tests. Everything else (Ogg muxing, packet durations) is pure Python.
Install the system library to enable it: `brew install opus` / `apt install libopus0`.
"""

from __future__ import annotations

import ctypes
import ctypes.util
import os
import platform
import sys

OPUS_APPLICATION_VOIP = 2048
OPUS_SET_BITRATE_REQUEST = 4002
OPUS_GET_LOOKAHEAD_REQUEST = 4027

# Pre-skip for libopus at any input rate is 312 samples at 48 kHz (6.5 ms).
# Used for device-produced streams where we cannot ask the encoder.
DEFAULT_PRE_SKIP = 312

_FRAME_48K = {
    # TOC config -> samples per frame at 48 kHz (RFC 6716 §3.1)
    **{c: (480, 960, 1920, 2880)[c % 4] for c in range(0, 12)},  # SILK 10/20/40/60 ms
    **{c: (480, 960)[c % 2] for c in range(12, 16)},  # Hybrid 10/20 ms
    **{c: (120, 240, 480, 960)[c % 4] for c in range(16, 32)},  # CELT 2.5/5/10/20 ms
}


def packet_samples_48k(packet: bytes) -> int:
    """Duration of one Opus packet in 48 kHz samples, from its TOC byte."""
    if not packet:
        raise ValueError("empty Opus packet")
    toc = packet[0]
    per_frame = _FRAME_48K[toc >> 3]
    code = toc & 0x03
    if code == 0:
        frames = 1
    elif code in (1, 2):
        frames = 2
    else:
        if len(packet) < 2:
            raise ValueError("code-3 Opus packet without frame count byte")
        frames = packet[1] & 0x3F
    return per_frame * frames


def _candidates() -> list[str]:
    names = []
    found = ctypes.util.find_library("opus")
    if found:
        names.append(found)
    names += [
        "/opt/homebrew/lib/libopus.dylib",
        "/usr/local/lib/libopus.dylib",
        "libopus.so.0",
        "libopus.so",
        "opus.dll",
    ]
    env = os.environ.get("OWL_LIBOPUS")
    return ([env] if env else []) + names


_lib = None
_load_error: str | None = None


def load() -> ctypes.CDLL | None:
    """Returns the libopus handle, or None if it is not installed."""
    global _lib, _load_error
    if _lib is not None or _load_error is not None:
        return _lib
    if os.environ.get("OWL_LIBOPUS") == "none":  # pretend it is missing (tests the PCM16 fallback)
        _load_error = "libopus disabled by OWL_LIBOPUS=none"
        return None
    for name in _candidates():
        try:
            lib = ctypes.CDLL(name)
            break
        except OSError:
            continue
    else:
        _load_error = "libopus not found (brew install opus / apt install libopus0)"
        return None
    c = ctypes
    lib.opus_encoder_create.restype = c.c_void_p
    lib.opus_encoder_create.argtypes = [c.c_int32, c.c_int, c.c_int, c.POINTER(c.c_int)]
    lib.opus_encode.restype = c.c_int32
    lib.opus_encode.argtypes = [c.c_void_p, c.c_char_p, c.c_int, c.c_char_p, c.c_int32]
    lib.opus_encoder_destroy.argtypes = [c.c_void_p]
    lib.opus_decoder_create.restype = c.c_void_p
    lib.opus_decoder_create.argtypes = [c.c_int32, c.c_int, c.POINTER(c.c_int)]
    lib.opus_decode.restype = c.c_int
    lib.opus_decode.argtypes = [c.c_void_p, c.c_char_p, c.c_int32, c.c_void_p, c.c_int, c.c_int]
    lib.opus_decoder_destroy.argtypes = [c.c_void_p]
    _lib = lib
    return lib


def available() -> bool:
    return load() is not None


def _encoder_ctl(lib, enc, request: int, arg: int) -> int:
    fn = lib.opus_encoder_ctl
    # opus_encoder_ctl is variadic. On Apple arm64, variadic arguments go on the
    # stack while ctypes passes them in registers, so pad x2..x7 with dummies to
    # push the real argument onto the stack. Elsewhere the plain call works.
    if sys.platform == "darwin" and platform.machine() == "arm64":
        fn.argtypes = [ctypes.c_void_p, ctypes.c_int] + [ctypes.c_int64] * 7
        return fn(enc, request, 0, 0, 0, 0, 0, 0, arg)
    fn.argtypes = None
    return fn(ctypes.c_void_p(enc), ctypes.c_int(request), ctypes.c_void_p(arg) if request == OPUS_GET_LOOKAHEAD_REQUEST else ctypes.c_int(arg))


class Encoder:
    def __init__(self, sample_rate: int = 16000, channels: int = 1, bitrate: int = 16000):
        lib = load()
        if lib is None:
            raise RuntimeError(_load_error)
        self._lib = lib
        err = ctypes.c_int()
        self._enc = lib.opus_encoder_create(sample_rate, channels, OPUS_APPLICATION_VOIP, ctypes.byref(err))
        if err.value != 0 or not self._enc:
            raise RuntimeError(f"opus_encoder_create failed: {err.value}")
        self.sample_rate = sample_rate
        self.channels = channels
        _encoder_ctl(lib, self._enc, OPUS_SET_BITRATE_REQUEST, bitrate)
        la = ctypes.c_int32()
        if _encoder_ctl(lib, self._enc, OPUS_GET_LOOKAHEAD_REQUEST, ctypes.addressof(la)) == 0 and la.value > 0:
            self.pre_skip = la.value * 48000 // sample_rate
        else:
            self.pre_skip = DEFAULT_PRE_SKIP
        self._out = ctypes.create_string_buffer(1500)

    def encode(self, pcm16: bytes) -> bytes:
        frame_size = len(pcm16) // (2 * self.channels)
        n = self._lib.opus_encode(self._enc, pcm16, frame_size, self._out, len(self._out))
        if n < 0:
            raise RuntimeError(f"opus_encode failed: {n}")
        return self._out.raw[:n]

    def __del__(self):
        if getattr(self, "_enc", None):
            self._lib.opus_encoder_destroy(self._enc)
            self._enc = None


class Decoder:
    def __init__(self, sample_rate: int = 48000, channels: int = 1):
        lib = load()
        if lib is None:
            raise RuntimeError(_load_error)
        self._lib = lib
        err = ctypes.c_int()
        self._dec = lib.opus_decoder_create(sample_rate, channels, ctypes.byref(err))
        if err.value != 0 or not self._dec:
            raise RuntimeError(f"opus_decoder_create failed: {err.value}")
        self.channels = channels
        self._pcm = (ctypes.c_int16 * (5760 * channels))()

    def decode(self, packet: bytes) -> bytes:
        n = self._lib.opus_decode(self._dec, packet, len(packet), self._pcm, 5760, 0)
        if n < 0:
            raise RuntimeError(f"opus_decode failed: {n}")
        return bytes(self._pcm)[: n * 2 * self.channels]

    def __del__(self):
        if getattr(self, "_dec", None):
            self._lib.opus_decoder_destroy(self._dec)
            self._dec = None
