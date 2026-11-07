"""Checks that tools/bringup_monitor.py reads the bring-up app's real log lines.

Run from the repo root:  python -m pytest tools/tests/test_bringup_monitor.py
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from bringup_monitor import BringupLog  # noqa: E402

# Captured from a real XIAO nRF54L15 Sense on USB, nothing soldered yet.
BARE_BOARD = [
    "[00:01:01.546,775] \x1b[1;31m<err> spi_nor: Device id 00 00 00 does not match config ef 40 18\x1b[0m",
    "*** Booting nRF Connect SDK v3.4.1-b20f8619ba9a ***",
    "*** Using Zephyr OS v4.4.2-33fa6a7aac6a ***",
    "[00:01:01.563,459] \x1b[0m<inf> owl: owl M0 bring-up\x1b[0m",
    "[00:01:01.568,208] \x1b[0m<inf> owl: reset cause: 0x00000002\x1b[0m",
    "[00:01:01.586,571] \x1b[0m<inf> owl: battery: 4054 mV\x1b[0m",
    "[00:01:01.591,338] \x1b[1;31m<err> owl: flash: not ready (wiring? CS on D3? jedec-id in overlay?)\x1b[0m",
    "[00:01:02.693,379] \x1b[0m<inf> owl: mic: 16000 samples, peak 671, rms 181 (of 32767)\x1b[0m",
    "[00:01:02.700,935] \x1b[0m<inf> owl: bring-up FAILED\x1b[0m",
]


def inf(msg):
    return f"[00:00:01.000,000] \x1b[0m<inf> owl: {msg}\x1b[0m"


def boot(log, *messages):
    log.feed("*** Booting nRF Connect SDK ***")
    for msg in messages:
        log.feed(inf(msg))


def mic_line(rms):
    return f"mic: 16000 samples, peak {rms * 3}, rms {rms} (of 32767)"


def test_bare_board_log():
    log = BringupLog()
    parsed = [log.feed(line) for line in BARE_BOARD]
    s = log.snapshot()
    assert s["boots"] == 1
    assert s["reset_cause"] == "0x00000002"
    assert s["checks"]["battery"]["value"] == "4054 mV"
    assert s["checks"]["battery"]["status"] == "info"
    assert s["checks"]["flash"]["status"] == "fail"
    assert s["checks"]["overall"]["status"] == "fail"
    assert s["mics"] == [
        {"rms": 181, "raw_rms": 181, "mean": None, "peak": 671, "samples": 16000, "label": None}
    ]
    assert parsed[0]["level"] == "err"
    assert parsed[1]["level"] == "plain"
    assert "\x1b" not in parsed[0]["text"]


def test_mic_uses_rms_without_dc_offset():
    log = BringupLog()
    boot(log, "mic: 16000 samples, peak 322, rms 251, mean -249, ac rms 30 (of 32767)")
    s = log.snapshot()
    assert s["mics"] == [
        {"rms": 30, "raw_rms": 251, "mean": -249, "peak": 322, "samples": 16000, "label": None}
    ]
    assert s["checks"]["mic"]["value"] == "rms 30"


def test_all_parts_pass():
    log = BringupLog()
    boot(
        log,
        "reset cause: 0x00000001",
        "battery: 3890 mV",
        "flash: JEDEC ef 40 18",
        "flash: erase/write/read OK",
        mic_line(100),
        "bring-up PASSED",
    )
    checks = log.snapshot()["checks"]
    assert checks["flash"]["status"] == "pass"
    assert checks["flash"]["value"] == "ef 40 18"
    assert checks["overall"]["status"] == "pass"


def test_wrong_flash_variant_fails():
    log = BringupLog()
    boot(log, "flash: JEDEC ef 70 18")
    assert log.snapshot()["checks"]["flash"]["status"] == "fail"


def test_new_boot_clears_boot_checks_but_keeps_mic_history():
    log = BringupLog()
    boot(log, "battery: 3890 mV", mic_line(100))
    boot(log)
    s = log.snapshot()
    assert s["boots"] == 2
    assert s["checks"]["battery"]["status"] == "pending"
    assert len(s["mics"]) == 1


def test_mic_near_zero_means_wrong_channel():
    log = BringupLog()
    boot(log, mic_line(1))
    assert log.snapshot()["checks"]["mic"]["status"] == "fail"


def test_mic_passes_only_when_speech_is_louder_than_quiet():
    log = BringupLog()
    log.pending_label = "quiet"
    boot(log, mic_line(100))
    assert log.snapshot()["checks"]["mic"]["status"] == "wait"

    log.pending_label = "speech"
    boot(log, mic_line(150))
    assert log.snapshot()["checks"]["mic"]["status"] == "wait"

    log.pending_label = "speech"
    boot(log, mic_line(900))
    s = log.snapshot()
    assert s["checks"]["mic"]["status"] == "pass"
    assert s["quiet"]["rms"] == 100
    assert s["speech"]["rms"] == 900


def test_label_applies_to_one_measurement_only():
    log = BringupLog()
    log.pending_label = "quiet"
    boot(log, mic_line(100), mic_line(200))
    assert [m["label"] for m in log.snapshot()["mics"]] == ["quiet", None]


def test_button_and_wake_survive_resets():
    log = BringupLog()
    boot(log, "button: pressed -> mic test, talk now")
    assert log.snapshot()["checks"]["button"]["status"] == "pass"

    log.feed(inf("button: held 5 s -> System OFF, press again to wake"))
    assert log.snapshot()["checks"]["wake"]["status"] == "wait"

    boot(log, "reset cause: 0x00010000 (wakeup from System OFF)")
    checks = log.snapshot()["checks"]
    assert checks["wake"]["status"] == "pass"
    assert checks["button"]["status"] == "pass"
