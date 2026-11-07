"""Live dashboard for the M0 bring-up: reads the board's serial log, shows each check.

    python3 tools/bringup_monitor.py        # then open http://localhost:8765

Needs a uart.conf build on the board (firmware/README.md) and nothing but the standard
library. The "measure" buttons reset the board over USB with OpenOCD: the bring-up app runs
its 1 s mic test at every boot, so a reset is a mic measurement. They need the OpenOCD config
that firmware/scripts/flash.sh caches in build/, so flash once first.
"""

import argparse
import glob
import json
import os
import queue
import re
import select
import subprocess
import termios
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

REPO = Path(__file__).resolve().parent.parent
OPENOCD_CFG = REPO / "build" / "xiao_nrf54l15-openocd.cfg"
PAGE = Path(__file__).with_name("bringup_monitor.html")

MIC_TIMEOUT_S = 8  # reset + boot + 1 s capture, with room to spare
MAX_LINES = 500
MIC_DEAD_RMS = 2  # firmware/README.md: R near 0 means the PDM channel is the wrong one
SPEECH_RATIO = 2.0  # how much louder speech must be to call the mic "reacting"

ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
LOG_LINE = re.compile(r"^\[(?P<ts>[\d:.,]+)\]\s+<(?P<level>\w+)>\s+(?P<module>\w+):\s*(?P<msg>.*)$")
BATTERY = re.compile(r"^battery: (-?\d+) mV")
JEDEC = re.compile(r"^flash: JEDEC (\w\w \w\w \w\w)")
MIC = re.compile(r"^mic: (\d+) samples, peak (\d+), rms (\d+)(?:, mean (-?\d+), ac rms (\d+))?")
GOOD_JEDEC = "ef 40 18"


def fresh_checks():
    return {
        "battery": {"status": "pending", "value": None, "note": "부팅하면 나와요"},
        "flash": {"status": "pending", "value": None, "note": "부팅하면 나와요"},
        "mic": {"status": "pending", "value": None, "note": "부팅하면 나와요"},
        "overall": {"status": "pending", "value": None, "note": "부팅하면 나와요"},
    }


class BringupLog:
    """What the bring-up app's log has shown so far. Knows nothing about ports or HTTP."""

    def __init__(self):
        self.boots = 0
        self.reset_cause = None
        self.checks = fresh_checks()
        # Once proven these stay proven: a later reset doesn't undo them.
        self.button = {"status": "pending", "value": None, "note": "D1 배선 후 짧게 눌러요"}
        self.wake = {"status": "pending", "value": None, "note": "D1 배선 후 5초 눌러요"}
        self.mics = []
        self.mic_count = 0
        self.pending_label = None

    def feed(self, raw):
        """Takes one log line, updates the state, returns it parsed for display."""
        text = ANSI.sub("", raw).strip()
        match = LOG_LINE.match(text)
        if not match:
            if text.startswith("*** Booting"):
                self._new_boot()
            return {"text": text, "level": "plain", "ts": None}
        if match["module"] == "owl":
            self._owl_message(match["msg"])
        return {"text": text, "level": match["level"], "ts": match["ts"]}

    def _new_boot(self):
        self.boots += 1
        self.reset_cause = None
        self.checks = fresh_checks()

    def _owl_message(self, msg):
        c = self.checks
        if msg.startswith("reset cause:"):
            self.reset_cause = msg.split(":", 1)[1].strip()
            if "wakeup from System OFF" in msg:
                self.wake = {"status": "pass", "value": "깨어남", "note": "버튼으로 다시 부팅됐어요"}
        elif m := BATTERY.match(msg):
            mv = int(m[1])
            c["battery"] = {
                "status": "fail" if mv < 0 else "info",
                "value": f"{mv} mV",
                "note": "USB만 꽂혀 있으면 의미 없는 값이에요. 배터리를 단 뒤에 봐요",
            }
        elif msg.startswith("flash: not ready"):
            c["flash"] = {
                "status": "fail",
                "value": "응답 없음",
                "note": "칩을 달기 전이면 정상이에요. 달았다면 배선(CS=D3)을 봐요",
            }
        elif m := JEDEC.match(msg):
            good = m[1] == GOOD_JEDEC
            c["flash"] = {
                "status": "wait" if good else "fail",
                "value": m[1],
                "note": "쓰기·읽기 시험 중이에요" if good else f"{GOOD_JEDEC}가 아니에요. README 체크리스트를 봐요",
            }
        elif msg.startswith("flash: erase/write/read OK"):
            c["flash"] = {**c["flash"], "status": "pass", "note": "지우기·쓰기·읽기 통과"}
        elif msg.startswith("flash: erase/write/read test FAILED"):
            c["flash"] = {**c["flash"], "status": "fail", "note": "지우기·쓰기·읽기 실패"}
        elif m := MIC.match(msg):
            # ac rms is what the main app's VAD sees; older bring-up builds only print rms.
            rms = int(m[5]) if m[5] is not None else int(m[3])
            mean = int(m[4]) if m[4] is not None else None
            self._mic_result(int(m[1]), int(m[2]), rms, int(m[3]), mean)
        elif msg.startswith("mic:"):
            c["mic"] = {"status": "fail", "value": "오류", "note": msg}
        elif msg.startswith("button: pressed"):
            self.button = {"status": "pass", "value": "눌림", "note": "D1 입력이 들어와요"}
        elif "System OFF, press again" in msg:
            self.wake = {"status": "wait", "value": "꺼짐", "note": "버튼을 다시 눌러 깨워요"}
        elif msg.startswith("bring-up "):
            passed = msg.endswith("PASSED")
            c["overall"] = {
                "status": "pass" if passed else "fail",
                "value": "통과" if passed else "실패",
                "note": "LED는 2초마다 짧게 깜빡여요" if passed else "LED는 빠르게 깜빡여요",
            }

    def _mic_result(self, samples, peak, rms, raw_rms, mean):
        self.mics.append(
            {
                "rms": rms,
                "raw_rms": raw_rms,
                "mean": mean,
                "peak": peak,
                "samples": samples,
                "label": self.pending_label,
            }
        )
        self.mics = self.mics[-40:]
        self.mic_count += 1
        self.pending_label = None
        self.checks["mic"] = self._mic_check(rms)

    def _mic_check(self, rms):
        if rms <= MIC_DEAD_RMS:
            return {
                "status": "fail",
                "value": f"rms {rms}",
                "note": "0에 가까워요. PDM 채널이 반대일 수 있어요",
            }
        quiet, speech = self.latest("quiet"), self.latest("speech")
        if quiet is None or speech is None:
            return {"status": "wait", "value": f"rms {rms}", "note": "소리는 들어와요. 조용할 때와 말할 때를 비교해요"}
        if speech["rms"] >= quiet["rms"] * SPEECH_RATIO:
            return {"status": "pass", "value": f"rms {rms}", "note": "말소리에 반응해요"}
        return {"status": "wait", "value": f"rms {rms}", "note": "차이가 작아요. 더 가까이서 말하며 다시 재봐요"}

    def latest(self, label):
        return next((m for m in reversed(self.mics) if m["label"] == label), None)

    def snapshot(self):
        return {
            "boots": self.boots,
            "reset_cause": self.reset_cause,
            "checks": {**self.checks, "button": self.button, "wake": self.wake},
            "mics": self.mics,
            "quiet": self.latest("quiet"),
            "speech": self.latest("speech"),
            "speech_ratio": SPEECH_RATIO,
        }


def open_serial(port):
    fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
    attrs = termios.tcgetattr(fd)
    attrs[0] = attrs[1] = attrs[3] = 0  # raw: no input, output or local processing
    attrs[2] = termios.CS8 | termios.CREAD | termios.CLOCAL
    attrs[4] = attrs[5] = termios.B115200
    termios.tcsetattr(fd, termios.TCSANOW, attrs)
    return fd


class Monitor:
    """Serial reader thread, the shared state, and the browsers listening to it."""

    def __init__(self, port_override=None):
        self.port_override = port_override
        self.log = BringupLog()
        self.lock = threading.Lock()
        self.lines = []
        self.clients = set()
        self.port = None
        self.measuring = None
        self.error = None
        self.mic_seen = threading.Event()

    def find_port(self):
        if self.port_override:
            return self.port_override if os.path.exists(self.port_override) else None
        ports = sorted(glob.glob("/dev/cu.usbmodem*"))
        return ports[0] if ports else None

    def state(self):
        return {
            **self.log.snapshot(),
            "port": self.port,
            "measuring": self.measuring,
            "error": self.error,
        }

    def publish(self, line=None):
        message = {"state": self.state()}
        if line:
            message["line"] = line
        for client in list(self.clients):
            client.put(message)

    def set_port(self, port):
        with self.lock:
            if port != self.port:
                self.port = port
                self.publish()

    def on_line(self, raw):
        with self.lock:
            mics_before = self.log.mic_count
            line = self.log.feed(raw)
            if not line["text"]:
                return
            self.lines = (self.lines + [line])[-MAX_LINES:]
            if self.log.mic_count != mics_before:
                self.mic_seen.set()
            self.publish(line)

    def read_serial_forever(self):
        while True:
            port = self.find_port()
            if not port:
                self.set_port(None)
                time.sleep(1)
                continue
            try:
                self.read_port(port)
            except OSError:
                pass  # unplugged; look for it again
            self.set_port(None)
            time.sleep(1)

    def read_port(self, port):
        fd = open_serial(port)
        self.set_port(port)
        pending = b""
        try:
            while True:
                ready, _, _ = select.select([fd], [], [], 0.5)
                if not ready:
                    if not os.path.exists(port):
                        return
                    continue
                try:
                    data = os.read(fd, 4096)
                except BlockingIOError:
                    continue
                if not data:
                    return
                *complete, pending = (pending + data).split(b"\n")
                for raw in complete:
                    self.on_line(raw.decode("utf-8", "replace"))
        finally:
            os.close(fd)

    def measure(self, label):
        """Resets the board so it reruns its mic test. False if one is already running."""
        with self.lock:
            if self.measuring:
                return False
            self.measuring = label or "reset"
            self.error = None
            self.log.pending_label = label
            self.mic_seen.clear()
            self.publish()
        threading.Thread(target=self._measure_thread, daemon=True).start()
        return True

    def _measure_thread(self):
        error = reset_board()
        if not error and not self.mic_seen.wait(MIC_TIMEOUT_S):
            error = "리셋했지만 마이크 결과가 안 왔어요. bring-up 펌웨어의 uart.conf 빌드인지 확인해요"
        with self.lock:
            self.measuring = None
            self.error = error
            self.log.pending_label = None
            self.publish()


def reset_board():
    """Returns an error message, or None. The USB debugger and the serial bridge coexist."""
    if not OPENOCD_CFG.exists():
        return "OpenOCD 설정이 없어요. firmware/scripts/flash.sh로 한 번 구워요"
    try:
        done = subprocess.run(
            ["openocd", "-f", str(OPENOCD_CFG), "-c", "init", "-c", "reset run", "-c", "shutdown"],
            capture_output=True,
            text=True,
            timeout=20,
        )
    except FileNotFoundError:
        return "openocd가 없어요. brew install openocd"
    except subprocess.TimeoutExpired:
        return "OpenOCD가 응답하지 않아요. USB를 다시 꽂아봐요"
    if done.returncode != 0:
        errors = [l for l in done.stderr.splitlines() if l.startswith("Error")]
        return "리셋 실패: " + (errors[0] if errors else f"openocd 종료 코드 {done.returncode}")
    return None


class Handler(BaseHTTPRequestHandler):
    monitor = None

    def log_message(self, *args):
        pass

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/":
            body = PAGE.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)
        elif path == "/events":
            self.stream_events()
        else:
            self.send_error(404)

    def stream_events(self):
        client = queue.Queue()
        with self.monitor.lock:
            self.monitor.clients.add(client)
            first = {"state": self.monitor.state(), "lines": self.monitor.lines}
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.send_event(first)
            while True:
                try:
                    self.send_event(client.get(timeout=15))
                except queue.Empty:
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
        except OSError:
            pass  # the page went away
        finally:
            with self.monitor.lock:
                self.monitor.clients.discard(client)

    def send_event(self, message):
        self.wfile.write(f"data: {json.dumps(message, ensure_ascii=False)}\n\n".encode())
        self.wfile.flush()

    def do_POST(self):
        # Only this page may reset the board, not any website open in the same browser.
        origin = self.headers.get("Origin")
        if origin and urlsplit(origin).netloc != self.headers.get("Host"):
            self.send_error(403)
            return
        if urlsplit(self.path).path != "/measure":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length") or 0)
        label = json.loads(self.rfile.read(length) or b"{}").get("label")
        if label not in ("quiet", "speech", None):
            self.send_error(400)
            return
        started = self.monitor.measure(label)
        self.send_response(202 if started else 409)
        self.send_header("Content-Length", "0")
        self.end_headers()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--port", help="serial port (default: the first /dev/cu.usbmodem*)")
    parser.add_argument("--http-port", type=int, default=8765)
    args = parser.parse_args()

    Handler.monitor = Monitor(args.port)
    threading.Thread(target=Handler.monitor.read_serial_forever, daemon=True).start()
    server = ThreadingHTTPServer(("127.0.0.1", args.http_port), Handler)
    print(f"http://localhost:{args.http_port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
