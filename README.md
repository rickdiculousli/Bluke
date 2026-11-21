# Bluke

Android app that registers the phone as a Bluetooth HID device (keyboard, touchpad, gamepad, multimedia remote) so a host uses it with no companion software. Personal fork of `arnav-kr/Bluke`; nothing goes upstream.

## Requirements

- Android 9 (API 28)+ with firmware that exposes the Bluetooth HID Device profile; some chipsets and OEM ROMs don't.
- Hosts: Windows, Linux, Android, ChromeOS. Apple hosts are unsupported.
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

## License and credits

AGPL-3.0, see [LICENSE](LICENSE). Part of the switch audio comes from [kbsim](https://github.com/tplai/kbsim) ([kbs.im](https://kbs.im)).
