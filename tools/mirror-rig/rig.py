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
# drag; held to up: a drag to the point only if it moved, then the release (PROTOCOL.md,
# Pointer). The lift is at the last move's point, so the release injects no drag.
DRAG_EXPECTED = [
    ("move", AT_P0), ("down", AT_P0),
    ("drag", AT_P1),
    ("drag", AT_P2),
    ("up", AT_P2),
]
# DOWN P1, held still for HOLD_S while the synthetic screen changes every frame, UP P1. The
# companion releases a held button after 1 s with nothing from the phone's touch side; FrameAcks
# don't count, so only the phone's Heartbeat keeps it held: no up may come before the lift.
HOLD_S = 2.0
HOLD_EXPECTED = [
    ("move", AT_P1), ("down", AT_P1),
    ("up", AT_P1),
]
# DOWN P0, MOVE P3, then Home while held. Leaving the screen cancels the touch before the
# surface's ON_STOP, and the phone ends a cancelled touch by releasing at its last point (never a
# click): the up, with no drag since the point didn't move. The Release that ON_STOP sends next
# finds no button held, so it injects nothing. Either way no button stays down.
HOME_EXPECTED = [
    ("move", AT_P0), ("down", AT_P0),
    ("drag", AT_P3),
    ("up", AT_P3),
]

# Hover with the left-button key. The finger hovers at P1 (a hold past the tap timeout, so no
# click), then the key presses at the last point sent: a move then a press, both at P1. A finger
# DOWN at P2 and MOVE to P3 drag with the button held (held to held is a drag, PROTOCOL.md). Lifting
# the finger, and 2 s of nothing, send no release; the key's lift releases at the last point, P3,
# which a drag already reached, so it's only an up.
KEY_PRESS_EXPECTED = [("move", AT_P1), ("down", AT_P1)]
KEY_DRAG_EXPECTED = [("drag", AT_P2), ("drag", AT_P3)]
KEY_RELEASE_EXPECTED = [("up", AT_P3)]
KEY_HOLD_S = 2.0
TAP_TIMEOUT_S = 0.4


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

    def mirror(self, action: str = "state", **extras: str) -> dict:
        return self.cmd("mirror", action=action, **extras)

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


def host_uptime() -> str:
    return subprocess.run(["uptime"], capture_output=True, text=True).stdout.strip()


def row(label: str, q: dict) -> str:
    return f"{label:<40} {q['count']:>5} {q['p50']:8.3f} {q['p95']:8.3f} {q['max']:8.3f}"


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
            phone.tap_text("Mirror")  # the Mirror tile in home's mode dock
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

        # 3b. A press held still while frames flow stays held until the finger lifts.
        rec.mark()
        decoded_before = phone.mirror()["framesDecoded"]
        hold = at(bounds, P1)
        phone.shell(f"input motionevent DOWN {hold[0]} {hold[1]}")
        time.sleep(HOLD_S)
        while_held = [e["kind"] for e in rec.since_mark()]
        decoded_during = phone.mirror()["framesDecoded"] - decoded_before
        phone.shell(f"input motionevent UP {hold[0]} {hold[1]}")
        if while_held != ["move", "down"]:
            raise RigFailure(f"press held still {HOLD_S} s: recorded {while_held} before the lift, expected only move, down")
        if decoded_during < 10:
            raise RigFailure(f"press held still {HOLD_S} s: only {decoded_during} frames decoded meanwhile; the screen should change every frame")
        check_events(f"press held still {HOLD_S} s ({decoded_during} frames meanwhile)",
                     rec.wait(len(HOLD_EXPECTED), "held press"), HOLD_EXPECTED)

        # 5 (before 4, which closes Mirror). Timing: RUNS more drags, then read the phone's numbers.
        # Load on the box and the emulator before and after, so the numbers carry what they ran under.
        print(f"timing: {RUNS} drags")
        uptimes = [("before", host_uptime(), phone.shell("uptime").strip())]
        batch = 10
        for start in range(0, RUNS, batch):
            rec.mark()
            phone.shell(motions(*drag * min(batch, RUNS - start)))
            rec.wait(len(DRAG_EXPECTED) * min(batch, RUNS - start), "timing drags")
        # One Ping a second: wait for RUNS round trips so the link's share has as many samples.
        state = phone.wait_mirror(lambda s: len(s["pingRoundTripUs"]) >= RUNS, f"{RUNS} ping round trips",
                                  timeout=RUNS * 1.5 + 30)
        event_to_touch = [us / 1000 for us in state["eventToTouchUs"]]
        touch_to_flush = [us / 1000 for us in state["touchToFlushUs"]]
        event_to_flush = [us / 1000 for us in state["eventToFlushUs"]]
        round_trips = [us / 1000 for us in state["pingRoundTripUs"]]
        decoded_to_drawn = [us / 1000 for us in state["decodedToDrawnUs"]]
        uptimes.append(("after", host_uptime(), phone.shell("uptime").strip()))

        # 5b. Hover and the left-button key: the key holds the button while a finger drags.
        phone.mirror("touch-mode", touch="HOVER")
        phone.wait_mirror(lambda s: s["touchMode"] == "HOVER", "Hover mode")
        hover, p2, p3 = at(bounds, P1), at(bounds, P2), at(bounds, P3)
        phone.shell(f"input motionevent DOWN {hover[0]} {hover[1]}; sleep {TAP_TIMEOUT_S + 0.3}; "
                    f"input motionevent UP {hover[0]} {hover[1]}")
        time.sleep(0.5)
        rec.mark()
        if phone.mirror()["keyHeld"]:
            raise RigFailure("the key reads held before it was pressed")
        if not phone.mirror("key-down")["keyHeld"]:
            raise RigFailure("the key doesn't read held after key-down")
        check_events("key down after hovering", rec.wait(len(KEY_PRESS_EXPECTED), "key press"), KEY_PRESS_EXPECTED)
        rec.mark()
        phone.shell(motions(("DOWN", p2), ("MOVE", p3)))
        check_events("finger drags with the key held", rec.wait(len(KEY_DRAG_EXPECTED), "held drag"), KEY_DRAG_EXPECTED)
        rec.mark()
        time.sleep(KEY_HOLD_S)
        quiet = [e["kind"] for e in rec.since_mark()]
        if any(k in ("up", "release") for k in quiet):
            raise RigFailure(f"key held {KEY_HOLD_S} s with a finger down: recorded {quiet}, expected no release")
        phone.shell(f"input motionevent UP {p3[0]} {p3[1]}")
        time.sleep(0.5)
        after_lift = [e["kind"] for e in rec.since_mark()]
        if any(k in ("up", "release", "down") for k in after_lift):
            raise RigFailure(f"finger lifted with the key held: recorded {after_lift}, expected no release or click")
        if not phone.mirror()["keyHeld"]:
            raise RigFailure("the key reads released after the finger lifted")
        rec.mark()
        if phone.mirror("key-up")["keyHeld"]:
            raise RigFailure("the key still reads held after key-up")
        check_events("key up", rec.wait(len(KEY_RELEASE_EXPECTED), "key release"), KEY_RELEASE_EXPECTED)
        phone.mirror("touch-mode", touch="DIRECT")  # the Home check below is a Direct drag
        phone.wait_mirror(lambda s: s["touchMode"] == "DIRECT", "Direct mode")

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

    # Composition. The phone's share is measured per Pointer (MotionEvent time to TLS flush); the
    # link and the companion aren't, so touch -> inject adds three rows' own quantiles: its p95 and
    # max are upper bounds on the pipeline's.
    dispatch = quantiles(event_to_touch)
    flush = quantiles(touch_to_flush)
    phone = quantiles(event_to_flush)
    one_way = quantiles([rt / 2 for rt in round_trips])
    inject = summary["inject_ms"]
    ack = summary["frame_ack_ms"]
    parts = (phone, one_way, inject)
    touch_total = {k: sum(p[k] for p in parts) for k in ("p50", "p95", "max")}
    touch_total["count"] = min(p["count"] for p in parts)
    # Quantiles don't subtract: the ack's way back is taken off as the link's median one way.
    frame_total = {k: max(ack[k] - one_way["p50"], 0.0) for k in ("p50", "p95", "max")}
    frame_total["count"] = ack["count"]
    if touch_total["count"] < RUNS or frame_total["count"] < RUNS:
        raise RigFailure(f"fewer than {RUNS} samples: touch {touch_total['count']}, frame {frame_total['count']}")

    rows = [
        ("phone event -> onTouch", dispatch),
        ("phone onTouch -> Pointer flushed", flush),
        ("phone event -> Pointer flushed [a]", phone),
        ("link one way, ping RTT / 2 [b]", one_way),
        ("companion read -> recorded inject [c]", inject),
        ("touch -> recorded inject, a+b+c", touch_total),
    ]
    capture = summary.get("capture_to_sent_ms")
    if capture:
        rows.append(("companion capture -> frame sent", capture))
    rows += [
        ("companion frame sent -> FrameAck", ack),
        ("frame sent -> decoded on phone", frame_total),
        ("phone frame decoded -> drawn", quantiles(decoded_to_drawn)),
    ]
    print()
    print("```text")
    print(f"Mirror timing: emulator, loopback through adb forward (not Wi-Fi), {RUNS} drags; ms")
    for when, host, emulator in uptimes:
        print(f"uptime {when}, host: {host}")
        print(f"uptime {when}, emulator: {emulator}")
    print(f"{'row':<40} {'count':>5} {'p50':>8} {'p95':>8} {'max':>8}")
    for label, q in rows:
        print(row(label, q))
    print(f"frames sent {summary['frames_sent']}, acked {summary['frames_acked']}, sessions {summary['sessions']}")
    print("a is measured per Pointer; a+b+c adds quantiles, so its p95 and max are upper bounds.")
    print("frame sent -> decoded is send -> ack minus the median one way.")
    print("decoded -> drawn is the decode finishing to the image's draw pass, per frame (newest 512).")
    print("```")
    print("mirror rig: ok")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RigFailure as e:
        print(f"mirror rig: FAILED: {e}", file=sys.stderr)
        sys.exit(1)
