# Bluke

Android app that registers the phone as a Bluetooth HID device (keyboard, touchpad, gamepad, multimedia remote) so a host uses it with no companion software. Personal fork of `arnav-kr/Bluke`; nothing goes upstream.

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
