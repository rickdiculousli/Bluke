# Bluke

Android app that registers the phone as a Bluetooth HID device (keyboard, touchpad, gamepad, multimedia remote) so a host uses it with no companion software. The one exception is Mirror (below), which needs a small companion on the computer. Personal fork of `arnav-kr/Bluke`; nothing goes upstream.

## Requirements

- Android 9 (API 28)+ with firmware that exposes the Bluetooth HID Device profile; some chipsets and OEM ROMs don't.
- Hosts: Windows, Linux, Android, ChromeOS, macOS. iPhone and iPad are unsupported.
- JDK compatible with the project's Android Gradle Plugin, Android SDK as set in `app/build.gradle.kts`, and the bundled Gradle wrapper.

## Build and test

```bash
./gradlew assembleDebug    # APK at app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug     # install on a USB-debugging device
./gradlew fastTest         # debug unit tests minus Robolectric classes
./gradlew testDebugUnitTest  # all unit tests, Robolectric and Roborazzi screenshots included
```

Release builds sign from the `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` environment variables.

## Debugging on a device

- Developer options: tap the logo in Settings > About five times. Developer Logs there shows the in-app log, with Clear and Export.
- Live logs over ADB: `adb logcat -s BlukeHID BlukeLifecycle`.
- Logs carry the Bluetooth MAC address and raw keycodes.

## Mirror

Mirror shows a low-resolution live copy of the computer's screen on the phone. A finger on the image presses, drags and releases at that spot on the computer, which is how cards are dragged in a game like Slay the Spire. Direct mode presses on touch; Hover mode moves the pointer without pressing, so tooltips show, and a tap clicks.

- It needs `bluke-mirror`, the companion in `companion/` (Rust). This version runs on macOS. Build it with `cargo build --release -p bluke-mirror` in `companion/` and run `bluke-mirror pair` once, then `bluke-mirror`. It needs Screen Recording and Accessibility (System Settings > Privacy & Security), granted to the terminal it runs in. A rebuilt unsigned binary can lose them, and it says which pane to open.
- Frames and touches go over Wi-Fi, not Bluetooth. This is Bluke's only network access (the `INTERNET`, `ACCESS_NETWORK_STATE` and `WAKE_LOCK` permissions). The phone listens on its Wi-Fi address, on port 47800, only while Mirror is on screen, and holds a low-latency Wi-Fi lock for that time. The companion finds it over mDNS (`_bluke-mirror._tcp`) or `--phone <address>`, which Mirror shows.
- Both devices must be on the same network. Guest Wi-Fi and access-point isolation block the link.
- The link is TLS 1.3 between keys each side pinned at pairing. Pairing is a PAKE (SPAKE2) on a six-digit code the phone shows and you type into the companion. A new code is drawn per attempt, and three wrong codes close pairing until Mirror is reopened. Nothing but pairing messages is exchanged until each side has checked the other's pinned key.
- The companion injects only pointer moves, the left button and the wheel, inside the mirrored display, never keys. It releases the button when the link drops, goes quiet for a second, or Mirror leaves the screen.
- To forget a paired phone, delete `paired-phone` in the companion's config directory (`~/Library/Application Support/bluke-mirror/` on macOS, `~/.config/bluke-mirror/` elsewhere). To forget the computer on the phone, use Forget in Mirror's menu.
- The protocol is `companion/PROTOCOL.md`. `cargo test -p bluke-mirror-core` in `companion/` tests the companion's core, and `tools/mirror-rig/run.sh` runs the companion's synthetic source against the app on the emulator.

## Host behavior that shapes the code

- Hosts cache the HID descriptor per pairing. After any descriptor change, unpair on both phone and host and pair again from the app's Scan, or input behaves erratically.
- "Paired" in system settings doesn't mean an input session; the app must open the connection itself.
- Many Linux desktops show no pairing prompt when the phone initiates. Scan once from the app, then pair from the Linux side.
- Gamepad profiles switch live without re-pairing: Native (Windows/Linux), Android (remaps the right stick), Web (D-pad sent as buttons, since browsers ignore hat switches). Windows sees a generic controller, so most PC games need Steam Input.
- Keyboard layouts send key positions, not characters; the host's input language decides what gets typed.
- Some hosts route audio to the phone as a Bluetooth speaker; the "Keep audio on this phone" setting counters it.
- Battery savers can kill the background session when the screen locks.
- After ~100 ms with nothing to send, the Bluetooth link drops into sniff subrating and the next report lands 100-450 ms late. While Bluke is in use (a minute after the last input or touch) the manager re-sends the unchanged keyboard state whenever nothing went out for 40 ms, which holds the delay near 30 ms; hosts ignore a repeated report.
- Hosts may ask for a report's current state (GET_REPORT); macOS does. Bluke answers from the last report it sent, mouse movement zeroed.
- macOS sees one device whose main role is keyboard, so an app reads the gamepad only with Input Monitoring permission (System Settings > Privacy & Security). On first pairing it runs the Keyboard Setup Assistant. "Modifier keys: Mac" in keyboard settings puts Cmd and Opt beside the space bar.
- macOS treats the touchpad as a mouse: it accelerates wheel steps and adds no momentum. Two-finger scrolling therefore sends wheel steps by physical finger distance, follows the faster finger, and plays its own short glide after a flick, timed after a Mac trackpad's.
- macOS scrolls a wheel report by how soon it follows the last one, not by its size: a lone report moves a page 1 px whatever it carries, a fast run up to ~90 px each, and a reversal starts over. Short back-and-forth two-finger scrolls therefore move little on a Mac. Turning off macOS wheel acceleration (`defaults write -g com.apple.scrollwheel.scaling -1`) would fix it; Bluke leaves that to the user.

## License and credits

AGPL-3.0, see [LICENSE](LICENSE). Part of the switch audio comes from [kbsim](https://github.com/tplai/kbsim) ([kbs.im](https://kbs.im)).
