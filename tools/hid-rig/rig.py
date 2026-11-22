"""End-to-end HID check for Bluke.

Bumble plays the computer (a Bluetooth HID host) on the Android emulator's virtual
Bluetooth controller (netsim). Bluke runs in the emulator and is driven over adb
through the debug-only RigReceiver. Every check compares the exact bytes the host
receives with bytes written out by hand here, independent of the app's encoders.

Run through run.sh, which boots the emulator and installs the debug APK.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import re
import shlex
import subprocess
import sys

from bumble import hid
from bumble.core import PhysicalTransport
from bumble.device import Device, DeviceConfiguration
from bumble.hci import Address
from bumble.sdp import Client as SdpClient
from bumble.transport import open_transport

PACKAGE = "dev.arnv.bluke"
HID_SERVICE_UUID = 0x1124
SDP_HID_DESCRIPTOR_LIST = 0x0206
HOST_NAME = "BlukeRigHost"
TIMEOUT = 20.0

DATA_INPUT = 0xA1  # HIDP DATA | INPUT report header
DATA_OUTPUT = 0xA2  # HIDP DATA | OUTPUT report header


# ---------------------------------------------------------------------------- adb
class App:
    def __init__(self, adb: str, serial: str | None) -> None:
        self.base = [adb] + (["-s", serial] if serial else [])

    def _run(self, *args: str) -> str:
        out = subprocess.run(self.base + list(args), capture_output=True, text=True, timeout=60)
        if out.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {out.stderr.strip()}")
        return out.stdout

    def cmd(self, name: str, **extras: object) -> dict:
        args = ["shell", "am", "broadcast", "-f", "32", "-a", f"{PACKAGE}.debug.RIG",
                "-p", PACKAGE, "--es", "cmd", name]
        for key, value in extras.items():
            flag = {bool: "--ez", int: "--ei", float: "--ef"}.get(type(value), "--es")
            # adb shell runs this through the device's shell, so quote values like "*".
            args += [flag, key, str(value).lower() if isinstance(value, bool) else shlex.quote(str(value))]
        out = self._run(*args)
        match = re.search(r'data="(.*)"\s*$', out.strip(), re.S)
        if not match:
            raise RuntimeError(f"no result from rig command {name}: {out.strip()}")
        return json.loads(match.group(1))

    def tap_button(self, labels: tuple[str, ...]) -> bool:
        """Tap the first on-screen button whose text is one of labels (Android's pairing prompt)."""
        try:
            self._run("shell", "uiautomator", "dump", "/sdcard/rig-ui.xml")
        except RuntimeError:
            return False  # dump fails while windows animate; the caller retries
        xml = self._run("shell", "cat", "/sdcard/rig-ui.xml")
        for node in re.finditer(r"<node [^>]*>", xml):
            text = re.search(r'text="([^"]*)"', node.group(0))
            bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node.group(0))
            if text and bounds and text.group(1).strip().lower() in labels:
                x1, y1, x2, y2 = map(int, bounds.groups())
                self._run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                return True
        return False

    def restart(self) -> None:
        self._run("shell", "am", "force-stop", PACKAGE)
        self.cmd("skip-onboarding")
        self._run("shell", "am", "force-stop", PACKAGE)
        self._run("shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")


async def call(app: App, name: str, **extras: object) -> dict:
    result = await asyncio.to_thread(app.cmd, name, **extras)
    if not result.get("ok"):
        raise AssertionError(f"rig command {name} {extras} refused: {result}")
    return result


async def wait_state(app: App, predicate, what: str, timeout: float = TIMEOUT) -> dict:
    deadline = asyncio.get_running_loop().time() + timeout
    while True:
        state = await asyncio.to_thread(app.cmd, "state")
        if predicate(state):
            return state
        if asyncio.get_running_loop().time() > deadline:
            raise AssertionError(f"timed out waiting for {what}; app state {state}")
        await asyncio.sleep(0.5)


# --------------------------------------------------------------------------- host
class Host:
    def __init__(self, device: Device) -> None:
        self.device = device
        self.hid = hid.Host(device)
        self.reports: asyncio.Queue[bytes] = asyncio.Queue()
        self.last_keyboard: bytes | None = None
        self.hid.on(hid.HID.EVENT_INTERRUPT_DATA, self.on_report)

    def on_report(self, pdu: bytes) -> None:
        # Bluke re-sends the unchanged keyboard state to keep the link awake; a repeat changes nothing.
        if len(pdu) >= 2 and pdu[0] == DATA_INPUT and pdu[1] == 1:
            if pdu == self.last_keyboard:
                return
            self.last_keyboard = pdu
        self.reports.put_nowait(pdu)

    @property
    def interrupt_open(self) -> bool:
        return self.hid.l2cap_intr_channel is not None

    async def wait_channels(self, open_: bool, timeout: float = TIMEOUT) -> None:
        deadline = asyncio.get_running_loop().time() + timeout
        while self.interrupt_open != open_:
            if asyncio.get_running_loop().time() > deadline:
                raise AssertionError(f"HID interrupt channel never {'opened' if open_ else 'closed'}")
            await asyncio.sleep(0.2)

    def drain(self) -> None:
        while not self.reports.empty():
            self.reports.get_nowait()

    async def next_report(self, report_id: int) -> bytes:
        """Next input report with this ID, header and ID stripped; other IDs are skipped."""
        deadline = asyncio.get_running_loop().time() + TIMEOUT
        while True:
            remaining = deadline - asyncio.get_running_loop().time()
            if remaining <= 0:
                raise AssertionError(f"no report with ID {report_id}")
            pdu = await asyncio.wait_for(self.reports.get(), remaining)
            if len(pdu) >= 2 and pdu[0] == DATA_INPUT and pdu[1] == report_id:
                return pdu[2:]

    async def get_report(self, report_type: int, report_id: int) -> bytes | hid.Message.Handshake:
        """Ask for a report on the control channel, as macOS and Windows hosts do. Returns the
        DATA PDU (header, ID, report) or the handshake error the device answered with."""
        answer: asyncio.Future = asyncio.get_running_loop().create_future()

        def settle(value) -> None:
            if not answer.done():
                answer.set_result(value)

        self.hid.on(hid.HID.EVENT_CONTROL_DATA, settle)
        self.hid.on(hid.HID.EVENT_HANDSHAKE, settle)
        try:
            self.hid.get_report(report_type, report_id, 0)
            return await asyncio.wait_for(answer, 5.0)
        except asyncio.TimeoutError:
            raise AssertionError(f"no answer to GET_REPORT type {report_type} id {report_id}") from None
        finally:
            self.hid.remove_listener(hid.HID.EVENT_CONTROL_DATA, settle)
            self.hid.remove_listener(hid.HID.EVENT_HANDSHAKE, settle)

    async def descriptor(self) -> bytes:
        connection = self.hid.connection
        assert connection is not None, "no ACL connection for SDP"
        client = SdpClient(connection)
        await client.connect()
        try:
            handles = await client.search_services([_uuid16(HID_SERVICE_UUID)])
            assert handles, "the app advertises no HID service record"
            attributes = await client.get_attributes(handles[0], [SDP_HID_DESCRIPTOR_LIST])
        finally:
            await client.disconnect()
        for attribute in attributes:
            if attribute.id == SDP_HID_DESCRIPTOR_LIST:
                # Sequence of (type, descriptor bytes) pairs.
                return bytes(attribute.value.value[0].value[1].value)
        raise AssertionError("HID service record has no descriptor list")


def _uuid16(value: int):
    from bumble.core import UUID
    return UUID.from_16_bits(value)


# ------------------------------------------------------------------------- checks
def hexs(data: bytes) -> str:
    return data.hex(" ")


def expect(name: str, got: bytes, want: str) -> None:
    want_bytes = bytes.fromhex(want)
    if got != want_bytes:
        raise AssertionError(f"{name}: got [{hexs(got)}], want [{hexs(want_bytes)}]")


CENTER = "ff 7f"  # axis 0.0 -> 32767
FULL = "ff ff"    # axis 1.0 -> 65535

# Buttons 0 and 2 (A, X), D-pad up, left stick full right. Android moves X to
# BTN_GAMEPAD offset 3; Web sends D-pad up as button 12 and leaves the hat neutral.
GAMEPAD_CASES = {
    "native_hat": f"05 00 00 00 {FULL} {CENTER} {CENTER} {CENTER} {CENTER}",
    "android": f"09 00 00 00 {FULL} {CENTER} {CENTER} {CENTER} {CENTER}",
    "web_buttons": f"05 10 00 0f {FULL} {CENTER} {CENTER} {CENTER} {CENTER}",
}
GAMEPAD_NEUTRAL = {
    "native_hat": f"00 00 00 0f {CENTER} {CENTER} {CENTER} {CENTER} {CENTER}",
    "android": f"00 00 00 0f {CENTER} {CENTER} {CENTER} {CENTER} {CENTER}",
    "web_buttons": f"00 00 00 0f {CENTER} {CENTER} {CENTER} {CENTER} {CENTER}",
}
# Right stick full right: Z on desktop; Android mirrors it to Rx as well.
RIGHT_STICK_CASES = {
    "native_hat": f"00 00 00 0f {CENTER} {CENTER} {FULL} {CENTER} {CENTER}",
    "android": f"00 00 00 0f {CENTER} {CENTER} {FULL} {FULL} {CENTER}",
    "web_buttons": f"00 00 00 0f {CENTER} {CENTER} {FULL} {CENTER} {CENTER}",
}


async def check_keyboard(app: App, host: Host) -> None:
    await call(app, "key", code=0x04, press=True)       # a
    expect("key a down", await host.next_report(1), "00 00 04 00 00 00 00 00")
    await call(app, "key", code=0xE1, press=True)       # left shift
    expect("shift+a", await host.next_report(1), "02 00 04 00 00 00 00 00")
    await call(app, "key", code=0x04, press=False)
    expect("a up", await host.next_report(1), "02 00 00 00 00 00 00 00")
    await call(app, "key", code=0xE1, press=False)
    expect("all up", await host.next_report(1), "00 00 00 00 00 00 00 00")


async def check_mouse(app: App, host: Host) -> None:
    await call(app, "mouse", buttons=1, x=5, y=-3, wheel=1)
    expect("mouse move+click", await host.next_report(2), "01 05 fd 01 00")
    await call(app, "mouse", pan=-2)
    expect("horizontal scroll", await host.next_report(2), "00 00 00 00 fe")
    await call(app, "mouse", buttons=0, x=0, y=0, wheel=0)
    expect("mouse release", await host.next_report(2), "00 00 00 00 00")


async def check_consumer(app: App, host: Host) -> None:
    await call(app, "consumer", control="VOLUME_UP")
    expect("volume up", await host.next_report(4), "e9 00")
    await call(app, "consumer", control="PLAY_PAUSE")
    expect("play/pause", await host.next_report(4), "cd 00")
    await call(app, "consumer", control="NONE")
    expect("consumer release", await host.next_report(4), "00 00")


async def check_gamepad(app: App, host: Host, mode: str) -> None:
    await call(app, "gamepad", buttons=0b101, dpad=0x01, lx=1.0)
    expect(f"gamepad {mode}", await host.next_report(3), GAMEPAD_CASES[mode])
    await call(app, "gamepad", rx=1.0)
    expect(f"gamepad {mode} right stick", await host.next_report(3), RIGHT_STICK_CASES[mode])
    await call(app, "gamepad")
    expect(f"gamepad {mode} neutral", await host.next_report(3), GAMEPAD_NEUTRAL[mode])


async def check_leds(app: App, host: Host) -> None:
    host.hid.send_data(bytes([0x01, 0x02]))  # report 1, Caps Lock on
    await wait_state(app, lambda s: s["capsLock"] and not s["numLock"], "Caps Lock from the host")
    host.hid.send_data(bytes([0x01, 0x01]))  # Num Lock only
    await wait_state(app, lambda s: s["numLock"] and not s["capsLock"], "Num Lock from the host")


async def check_numpad(app: App, host: Host) -> None:
    host.hid.send_data(bytes([0x01, 0x00]))  # host's Num Lock off
    await wait_state(app, lambda s: not s["numLock"], "Num Lock off from the host")
    await call(app, "numpad", legend="1", press=True)
    expect("num lock down", await host.next_report(1), "00 00 53 00 00 00 00 00")
    expect("num lock up", await host.next_report(1), "00 00 00 00 00 00 00 00")
    expect("keypad 1 down", await host.next_report(1), "00 00 59 00 00 00 00 00")
    await call(app, "numpad", legend="1", press=False)
    expect("keypad 1 up", await host.next_report(1), "00 00 00 00 00 00 00 00")
    # The host hasn't confirmed Num Lock yet: a second digit must not toggle it back off.
    await call(app, "numpad", legend="2", press=True)
    expect("keypad 2 down", await host.next_report(1), "00 00 5a 00 00 00 00 00")
    await call(app, "numpad", legend="2", press=False)
    expect("keypad 2 up", await host.next_report(1), "00 00 00 00 00 00 00 00")
    host.hid.send_data(bytes([0x01, 0x01]))  # host confirms Num Lock on
    await wait_state(app, lambda s: s["numLock"], "Num Lock on from the host")
    await call(app, "numpad", legend="*", press=True)
    expect("keypad * down", await host.next_report(1), "00 00 55 00 00 00 00 00")
    await call(app, "numpad", legend="*", press=False)
    expect("keypad * up", await host.next_report(1), "00 00 00 00 00 00 00 00")
    await call(app, "numpad", legend="%", press=True)
    expect("% shift down", await host.next_report(1), "02 00 00 00 00 00 00 00")
    expect("% down", await host.next_report(1), "02 00 22 00 00 00 00 00")
    await call(app, "numpad", legend="%", press=False)
    expect("% up", await host.next_report(1), "02 00 00 00 00 00 00 00")
    expect("% shift up", await host.next_report(1), "00 00 00 00 00 00 00 00")


async def check_get_report(app: App, host: Host) -> None:
    """Every GET_REPORT gets an answer: the current input, the LED byte, or an error."""
    input_, output, feature = (hid.Message.ReportType.INPUT_REPORT, hid.Message.ReportType.OUTPUT_REPORT,
                               hid.Message.ReportType.FEATURE_REPORT)
    await call(app, "key", code=0x04, press=True)       # a, held
    await host.next_report(1)
    expect("keyboard input", await host.get_report(input_, 1), "a1 01 00 00 04 00 00 00 00 00")
    await call(app, "key", code=0x04, press=False)
    await host.next_report(1)
    expect("keyboard input released", await host.get_report(input_, 1), "a1 01 00 00 00 00 00 00 00 00")
    expect("mouse input", await host.get_report(input_, 2), "a1 02 00 00 00 00 00")
    expect("consumer input", await host.get_report(input_, 4), "a1 04 00 00")
    host.hid.send_data(bytes([0x01, 0x03]))  # Num Lock and Caps Lock on
    await wait_state(app, lambda s: s["numLock"] and s["capsLock"], "Num and Caps Lock from the host")
    expect("LED output", await host.get_report(output, 1), "a2 01 03")
    host.hid.send_data(bytes([0x01, 0x01]))  # back to Num Lock only
    await wait_state(app, lambda s: s["numLock"] and not s["capsLock"], "Num Lock only from the host")
    for report_type, report_id in ((feature, 1), (input_, 9)):
        answer = await host.get_report(report_type, report_id)
        if answer != hid.Message.Handshake.ERR_INVALID_REPORT_ID:
            raise AssertionError(f"GET_REPORT type {report_type} id {report_id}: got {answer!r}, "
                                 "want ERR_INVALID_REPORT_ID")


async def switch_mode(app: App, host: Host, mode: str) -> None:
    await call(app, "mode", value=mode)
    await wait_state(app, lambda s: s["gamepadModeSwitch"] == "IDLE" and s["host"], f"mode {mode} to settle")
    await host.wait_channels(True)
    host.drain()


# --------------------------------------------------------------------------- main
async def run(args: argparse.Namespace) -> int:
    app = App(args.adb, args.serial)
    results: list[tuple[str, str]] = []

    async def step(name: str, coro) -> None:
        try:
            await coro
            results.append((name, "ok"))
            print(f"PASS {name}", flush=True)
        except Exception as error:  # noqa: BLE001 - every failure is reported and the run continues
            results.append((name, str(error)))
            print(f"FAIL {name}: {error}", flush=True)

    async with await open_transport(args.transport) as transport:
        config = DeviceConfiguration(
            name=HOST_NAME,
            address=Address("F0:F1:F2:F3:F4:F5"),
            class_of_device=0x00010C,  # computer, laptop
            classic_enabled=True,
        )
        device = Device.from_config_with_hci(config, transport.source, transport.sink)
        host = Host(device)
        await device.power_on()
        await device.set_discoverable(True)
        await device.set_connectable(True)
        address = str(device.public_address).replace("/P", "")
        print(f"host {HOST_NAME} at {address}", flush=True)

        app.restart()
        await wait_state(app, lambda s: s["serviceState"] in ("PairingMode", "ReadyDisconnected"),
                         "the app to register as a HID device", timeout=60)

        async def accept_pairing_prompt() -> None:
            # A first pairing shows Android's consent prompt, as on a real phone.
            while not host.interrupt_open:
                if await asyncio.to_thread(app.tap_button, ("pair",)):
                    print("accepted Android's pairing prompt", flush=True)
                    return
                await asyncio.sleep(1)

        async def connect() -> None:
            await call(app, "connect", address=address)
            prompt = asyncio.create_task(accept_pairing_prompt())
            try:
                await host.wait_channels(True, timeout=60)
            finally:
                prompt.cancel()
            await wait_state(app, lambda s: (s["host"] or "").upper() == address.upper(), "the app to see the host")
            host.drain()

        await step("pair and connect", connect())
        if not host.interrupt_open:
            print("cannot continue without a connection", flush=True)
            return 1

        descriptors: dict[str, bytes] = {}

        async def read_descriptor(mode: str) -> None:
            descriptors[mode] = await host.descriptor()
            for report_id in (1, 2, 3, 4):
                assert bytes([0x85, report_id]) in descriptors[mode], f"descriptor lacks report ID {report_id}"

        await step("keyboard reports", check_keyboard(app, host))
        await step("mouse reports", check_mouse(app, host))
        await step("consumer reports", check_consumer(app, host))
        await step("LED output reports", check_leds(app, host))
        await step("numpad keys", check_numpad(app, host))
        await step("GET_REPORT answers", check_get_report(app, host))
        for mode in ("native_hat", "android", "web_buttons"):
            await step(f"switch to {mode}", switch_mode(app, host, mode))
            await step(f"descriptor in {mode}", read_descriptor(mode))
            await step(f"gamepad reports in {mode}", check_gamepad(app, host, mode))

        async def same_descriptor() -> None:
            distinct = {d for d in descriptors.values()}
            assert len(descriptors) == 3 and len(distinct) == 1, \
                f"descriptor differs across profiles: {[(m, len(d)) for m, d in descriptors.items()]}"
        await step("one descriptor across profiles", same_descriptor())

        async def reconnect() -> None:
            await call(app, "disconnect")
            await host.wait_channels(False)
            await wait_state(app, lambda s: not s["host"], "the app to drop the host")
            await connect()
            await check_keyboard(app, host)
        await step("disconnect and reconnect", reconnect())

        await call(app, "mode", value="native_hat")

    failed = [name for name, outcome in results if outcome != "ok"]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed", flush=True)
    return 1 if failed else 0


def netsim_transport() -> str:
    """netsimd writes netsim.ini to $TMPDIR, but Bumble on Linux looks in $XDG_RUNTIME_DIR
    first and never falls back, so find the file and pass its gRPC port explicitly."""
    candidates = [os.environ.get("TMPDIR"), os.environ.get("XDG_RUNTIME_DIR"), "/tmp",
                  os.path.expanduser("~/Library/Caches/TemporaryItems")]
    for directory in filter(None, candidates):
        ini = os.path.join(directory, "netsim.ini")
        if os.path.exists(ini):
            with open(ini) as f:
                port = re.search(r"^grpc\.port=(\d+)", f.read(), re.M)
            if port:
                return f"android-netsim:localhost:{port.group(1)}"
    return "android-netsim"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--transport", default=None, help="Bumble transport (default: the running netsim)")
    parser.add_argument("--adb", default=os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb"))
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    args = parser.parse_args()
    args.transport = args.transport or netsim_transport()
    sys.exit(asyncio.run(run(args)))


if __name__ == "__main__":
    main()
