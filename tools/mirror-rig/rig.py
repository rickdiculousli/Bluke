"""End-to-end Mirror check for Bluke.

The companion's rig example (synthetic 1920x1080 display at (-1920, 0), recording injector)
connects to the debug app on the emulator through `adb forward`, pairs with the code the phone
shows, streams frames, and records every event it would inject. Touches go in through
`adb shell input motionevent`, as a finger would, and the recorded events are checked against
display coordinates written out by hand below, independent of the app's and companion's mapping
code.

Run through run.sh, which builds the companion example, boots the emulator and installs the
debug APK. Stdlib only.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path

PACKAGE = "dev.arnv.bluke"
REPO = Path(__file__).resolve().parents[2]
TIMEOUT = 30.0
RUNS = int(os.environ.get("RUNS", "100"))

# ---------------------------------------------------------------------------- expectations
# The synthetic display: 1920x1080 at origin (-1920, 0). Its frames are downscaled to fit the
# phone's 480x480 cap, so 480x270. Every input surface runs in landscape, so on the emulator
# (2400x1080 turned) the image sits upright, letterboxed to a 1920x1080 box: no rotation.
#
# A touch at fractions (u, v) of the box is sent as n = round(f × 65535) per axis, which the
# companion maps to origin + n/65535 × (size − 1) (PROTOCOL.md, Pointer). Worked out by hand:
#
#   box (0.25, 0.25) -> n (16384, 16384)
#       x = -1920 + 16384/65535 × 1919 = -1440.24, y = 16384/65535 × 1079 = 269.75
#   box (0.50, 0.50) -> n (32768, 32768)
#       x = -1920 + 32768/65535 × 1919 =  -960.49, y = 32768/65535 × 1079 = 539.51
#   box (0.75, 0.60) -> n (49151, 39321)
#       x = -1920 + 49151/65535 × 1919 =  -480.76, y = 39321/65535 × 1079 = 647.40
#   box (0.40, 0.30) -> n (26214, 19660)
#       x = -1920 + 26214/65535 × 1919 = -1152.40, y = 19660/65535 × 1079 = 323.69
#
# Touches land on whole screen pixels, and the box spans one screen pixel per display pixel, so
# a point can be off by half a display pixel per axis; the tolerance allows that with room for
# float rounding.
TOLERANCE = 1.5

P0, P1, P2, P3 = (0.25, 0.25), (0.50, 0.50), (0.75, 0.60), (0.40, 0.30)
AT_P0 = (-1440.24, 269.75)
AT_P1 = (-960.49, 539.51)
AT_P2 = (-480.76, 647.40)
AT_P3 = (-1152.40, 323.69)

# DOWN P0, MOVE P1, MOVE P2, UP P2. Up from a held button: a move then press; held to held: a
# drag; held to up: a drag to the point, then the release (PROTOCOL.md, Pointer).
DRAG_EXPECTED = [
    ("move", AT_P0), ("down", AT_P0),
    ("drag", AT_P1),
    ("drag", AT_P2),
    ("drag", AT_P2), ("up", AT_P2),
]
# DOWN P0, MOVE P3, then Home while held. Leaving the screen cancels the touch before the
# surface's ON_STOP, and the phone ends a cancelled touch as a lift at its last point: a drag
# there, then the up. The Release that ON_STOP sends next finds no button held, so it injects
# nothing. Either way no button stays down.
HOME_EXPECTED = [
    ("move", AT_P0), ("down", AT_P0),
    ("drag", AT_P3),
    ("drag", AT_P3), ("up", AT_P3),
]


class RigFailure(Exception):
    pass


# ---------------------------------------------------------------------------- adb
class Phone:
    def __init__(self, adb: str) -> None:
        self.adb = [adb]

    def run(self, *args: str, timeout: float = 60) -> str:
        out = subprocess.run(self.adb + list(args), capture_output=True, text=True, timeout=timeout)
        if out.returncode != 0:
            raise RigFailure(f"adb {' '.join(args)} failed: {out.stderr.strip() or out.stdout.strip()}")
        return out.stdout

    def shell(self, script: str) -> str:
        return self.run("shell", script)

    def cmd(self, name: str, **extras: str) -> dict:
        args = ["shell", "am", "broadcast", "-f", "32", "-a", f"{PACKAGE}.debug.RIG",
                "-p", PACKAGE, "--es", "cmd", name]
        for key, value in extras.items():
            args += ["--es", key, shlex.quote(value)]
        out = self.run(*args)
        match = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
        if not match:
            raise RigFailure(f"no result from rig command {name}: {out.strip()}")
        result = json.loads(match.group(1))
        if not result.get("ok"):
            raise RigFailure(f"rig command {name} {extras} refused: {result}")
        return result

    def mirror(self, action: str = "state") -> dict:
        return self.cmd("mirror", action=action)

    def wait_mirror(self, predicate, what: str, timeout: float = TIMEOUT) -> dict:
        deadline = time.monotonic() + timeout
        while True:
            state = self.mirror()
            if predicate(state):
                return state
            if time.monotonic() > deadline:
                raise RigFailure(f"timed out waiting for {what}; Mirror state {state}")
            time.sleep(0.3)

    def tap_text(self, label: str, timeout: float = TIMEOUT) -> None:
        """Taps the on-screen element whose text is label, found through uiautomator."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                self.run("shell", "uiautomator", "dump", "/sdcard/rig-ui.xml")
                xml = self.run("shell", "cat", "/sdcard/rig-ui.xml")
            except RigFailure:
                time.sleep(0.5)  # dump fails while windows animate
                continue
            for node in re.finditer(r"<node [^>]*>", xml):
                text = re.search(r'text="([^"]*)"', node.group(0))
                bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node.group(0))
                if text and bounds and text.group(1).strip() == label:
                    x1, y1, x2, y2 = map(int, bounds.groups())
                    self.run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    return
            time.sleep(0.5)
        raise RigFailure(f"no on-screen element reads {label!r}")


def at(bounds: list[float], frac: tuple[float, float]) -> tuple[int, int]:
    """The whole screen pixel at fractions (u, v) of the image box."""
    left, top, right, bottom = bounds
    return round(left + frac[0] * (right - left)), round(top + frac[1] * (bottom - top))


def motions(*steps: tuple[str, tuple[int, int]]) -> str:
    """One shell line injecting these touch events, 50 ms apart. Android resamples moves that
    arrive under 20 ms apart, extrapolating a point along the finger's path; spacing them keeps
    each point where it was put."""
    return "; sleep 0.05; ".join(f"input motionevent {a} {p[0]} {p[1]}" for a, p in steps)


# ---------------------------------------------------------------------------- companion
class Companion:
    """The rig example, paired with --pair and stopped by closing its stdin."""

    def __init__(self, port: int, record: Path, config_dir: Path) -> None:
        exe = os.environ.get("MIRROR_RIG_COMPANION") or str(REPO / "companion/target/release/examples/rig")
        self.proc = subprocess.Popen(
            [exe, "--phone", f"127.0.0.1:{port}", "--record", str(record), "--config-dir", str(config_dir),
             "--pair", "--stop-on-eof", "--seconds", "3600"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
        )
        self.log: list[str] = []
        self.paired = threading.Event()
        threading.Thread(target=self._read_stderr, daemon=True).start()

    def _read_stderr(self) -> None:
        for line in self.proc.stderr:
            line = line.rstrip()
            self.log.append(line)
            if line == "rig: paired":
                self.paired.set()

    def send_code(self, code: str) -> None:
        self.proc.stdin.write(code + "\n")
        self.proc.stdin.flush()

    def finish(self) -> dict:
        self.proc.stdin.close()
        try:
            out, _ = self.proc.communicate(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            raise RigFailure("the companion didn't stop after its stdin closed")
        if self.proc.returncode != 0:
            raise RigFailure(f"the companion exited {self.proc.returncode}: {self.tail()}")
        lines = [l for l in out.splitlines() if l.startswith("{")]
        if not lines:
            raise RigFailure(f"the companion printed no summary: {out!r} {self.tail()}")
        return json.loads(lines[-1])

    def kill(self) -> None:
        if self.proc.poll() is None:
            self.proc.kill()

    def tail(self) -> str:
        return " | ".join(self.log[-8:])


class Recording:
    """Reads the injected events the companion appends, one JSON line each."""

    def __init__(self, path: Path) -> None:
        self.path = path
        self.offset = 0

    def all(self) -> list[dict]:
        if not self.path.exists():
            return []
        return [json.loads(l) for l in self.path.read_text().splitlines() if l.strip()]

    def mark(self) -> None:
        self.offset = len(self.all())

    def since_mark(self) -> list[dict]:
        return self.all()[self.offset:]

    def wait(self, count: int, what: str, timeout: float = 10.0) -> list[dict]:
        deadline = time.monotonic() + timeout
        while True:
            events = self.since_mark()
            if len(events) >= count:
                time.sleep(0.3)  # nothing more should follow
                return self.since_mark()
            if time.monotonic() > deadline:
                raise RigFailure(f"{what}: expected {count} events, recorded {events}")
            time.sleep(0.1)


def check_events(what: str, events: list[dict], expected: list[tuple[str, tuple[float, float]]]) -> None:
    got = [(e["kind"], (e["x"], e["y"])) for e in events]
    problems = []
    if [k for k, _ in got] != [k for k, _ in expected]:
        problems.append(f"kinds {[k for k, _ in got]} != {[k for k, _ in expected]}")
    else:
        for i, ((kind, (x, y)), (_, (ex, ey))) in enumerate(zip(got, expected)):
            if abs(x - ex) > TOLERANCE or abs(y - ey) > TOLERANCE:
                problems.append(f"event {i} {kind} at ({x}, {y}), expected ({ex}, {ey}) ± {TOLERANCE}")
    if problems:
        raise RigFailure(f"{what}: " + "; ".join(problems) + f"; recorded {got}")
    print(f"{what}: ok ({', '.join(f'{k} ({x:.2f}, {y:.2f})' for k, (x, y) in got)})")


# ---------------------------------------------------------------------------- timing
def quantiles(samples_ms: list[float]) -> dict:
    s = sorted(samples_ms)
    pick = lambda q: s[round((len(s) - 1) * q)] if s else 0.0
    return {"count": len(s), "p50": pick(0.5), "p95": pick(0.95), "max": s[-1] if s else 0.0}


def fmt(q: dict) -> str:
    return f"count {q['count']:>5}  p50 {q['p50']:7.3f}  p95 {q['p95']:7.3f}  max {q['max']:7.3f} ms"


# ---------------------------------------------------------------------------- the run
def main() -> int:
    phone = Phone(os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb"))
    cache = REPO / "cache" / "mirror-rig"
    cache.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    config_dir = cache / f"companion-{stamp}"
    record = cache / f"recording-{stamp}.jsonl"
    forwarded = None
    companion = None
    try:
        # 1. Open Mirror the way a person would, then let adb forward reach it.
        phone.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; svc power stayon true")
        # Android's one-time "Viewing full screen" prompt would cover the surface and take the
        # touches; a person dismisses it once, so mark it seen.
        phone.shell("settings put secure immersive_mode_confirmations confirmed")
        phone.run("shell", "am", "force-stop", PACKAGE)
        phone.cmd("skip-onboarding")
        phone.mirror("setup")
        phone.run("shell", "am", "force-stop", PACKAGE)
        phone.run("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        # A tap that lands while the home screen is still settling is lost; tap again, as a person would.
        for attempt in range(3):
            phone.tap_text("Launch Mirror")
            try:
                phone.wait_mirror(lambda s: s["phase"] in ("Waiting", "NoWifi"), "Mirror to start listening", timeout=8)
                break
            except RigFailure:
                if attempt == 2:
                    raise
        state = phone.mirror("loopback")
        port = state.get("loopbackPort") or phone.wait_mirror(lambda s: s["loopbackPort"], "a loopback port")["loopbackPort"]
        forwarded = phone.run("forward", "tcp:0", f"tcp:{port}").strip()
        print(f"Mirror listening on the phone's loopback port {port}, forwarded from 127.0.0.1:{forwarded}")

        # 2. Pair with the code the phone shows, then wait for a drawn frame.
        companion = Companion(int(forwarded), record, config_dir)
        state = phone.wait_mirror(lambda s: s["code"], "the pairing code")
        companion.send_code(state["code"])
        if not companion.paired.wait(TIMEOUT):
            raise RigFailure(f"the companion never paired: {companion.tail()}")
        print("paired")
        state = phone.wait_mirror(
            lambda s: s["framesDrawn"] > 0 and s["imageBounds"], "a drawn frame")
        print(f"frame drawn: framesDrawn {state['framesDrawn']}, lastDrawnSeq {state['lastDrawnSeq']}")
        bounds = state["imageBounds"]
        left, top, right, bottom = bounds
        if abs((right - left) / (bottom - top) - 16 / 9) > 0.01:
            raise RigFailure(f"expected the 16:9 image upright on the landscape screen; box {bounds}")
        rec = Recording(record)

        # 3. A drag, checked against the hand-written coordinates.
        rec.mark()
        drag = [("DOWN", at(bounds, P0)), ("MOVE", at(bounds, P1)), ("MOVE", at(bounds, P2)), ("UP", at(bounds, P2))]
        phone.shell(motions(*drag))
        check_events("drag", rec.wait(len(DRAG_EXPECTED), "drag"), DRAG_EXPECTED)

        # 5 (before 4, which closes Mirror). Timing: RUNS more drags, then read the phone's numbers.
        print(f"timing: {RUNS} drags")
        batch = 10
        for start in range(0, RUNS, batch):
            rec.mark()
            phone.shell(motions(*drag * min(batch, RUNS - start)))
            rec.wait(len(DRAG_EXPECTED) * min(batch, RUNS - start), "timing drags")
        # One Ping a second: wait for RUNS round trips so the link's share has as many samples.
        state = phone.wait_mirror(lambda s: len(s["pingRoundTripUs"]) >= RUNS, f"{RUNS} ping round trips",
                                  timeout=RUNS * 1.5 + 30)
        touch_to_write = [us / 1000 for us in state["touchToWriteUs"]]
        round_trips = [us / 1000 for us in state["pingRoundTripUs"]]

        # 4. A held drag is released when Mirror leaves the screen.
        rec.mark()
        phone.shell(motions(("DOWN", at(bounds, P0)), ("MOVE", at(bounds, P3))))
        rec.wait(3, "drag before Home")
        phone.shell("input keyevent KEYCODE_HOME")
        check_events("held drag, then Home", rec.wait(len(HOME_EXPECTED), "release on Home"), HOME_EXPECTED)
        phone.wait_mirror(lambda s: s["phase"] == "Off", "Mirror to stop after Home")

        summary = companion.finish()
        companion = None
    finally:
        if companion:
            companion.kill()
        if forwarded:
            subprocess.run(phone.adb + ["forward", "--remove", f"tcp:{forwarded}"], capture_output=True)

    # Composition. Each sum adds the components' own quantiles, so the p95 and max are upper
    # bounds on the true pipeline quantiles (the components aren't sampled per touch).
    write = quantiles(touch_to_write)
    one_way = quantiles([rt / 2 for rt in round_trips])
    inject = summary["inject_ms"]
    ack = summary["frame_ack_ms"]
    touch_total = {k: write[k] + one_way[k] + inject[k] for k in ("p50", "p95", "max")}
    touch_total["count"] = min(write["count"], one_way["count"], inject["count"])
    frame_total = {k: max(ack[k] - one_way[k], 0.0) for k in ("p50", "p95", "max")}
    frame_total["count"] = min(ack["count"], one_way["count"])
    if touch_total["count"] < RUNS or frame_total["count"] < RUNS:
        raise RigFailure(f"fewer than {RUNS} samples: touch {touch_total['count']}, frame {frame_total['count']}")

    print()
    print("Mirror pipeline timing on the emulator (loopback through adb forward, not Wi-Fi)")
    print(f"  phone touch -> Pointer written      {fmt(write)}   (MotionEvent time to TLS write, 1 ms clock)")
    print(f"  link one way (ping RTT / 2)         {fmt(one_way)}")
    print(f"  companion read -> recorded inject   {fmt(inject)}")
    print(f"  touch -> recorded inject            {fmt(touch_total)}   (sum of the three rows above)")
    print(f"  companion frame send -> FrameAck    {fmt(ack)}")
    print(f"  frame sent -> decoded on the phone  {fmt(frame_total)}   (send -> ack minus one way)")
    print(f"  frames sent {summary['frames_sent']}, acked {summary['frames_acked']}, sessions {summary['sessions']}")
    print("mirror rig: ok")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RigFailure as e:
        print(f"mirror rig: FAILED: {e}", file=sys.stderr)
        sys.exit(1)
