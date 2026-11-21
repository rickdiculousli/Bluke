# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Bluke is an Android app (Kotlin, Jetpack Compose, single `:app` module) that registers the phone as a Bluetooth HID device: keyboard, touchpad, gamepad and multimedia remote. It is a personal fork of `arnav-kr/Bluke`; nothing goes upstream. `README.md` holds requirements and the host behaviors that shape the code; read it before touching the Bluetooth layer.

## Commands

```bash
./gradlew assembleDebug                 # debug APK
./gradlew installDebug                  # install on a USB-debugging device
./gradlew fastTest                      # inner loop: debug unit tests minus Robolectric classes
./gradlew testDebugUnitTest             # full unit suite, Robolectric and screenshots included; run before pushing
./gradlew testDebugUnitTest --tests 'dev.arnv.bluke.bluetooth.HidLifecycleTest'   # one class (also works with fastTest)
./gradlew recordRoborazziDebug          # re-record screenshot baselines after an intended UI change
./gradlew verifyRoborazziDebug          # fail on screenshot drift
adb logcat -s BlukeHID BlukeLifecycle   # live HID and lifecycle logs from a device
```

- `fastTest` excludes any test class annotated `@RunWith(RobolectricTestRunner::class)`, found by scanning `app/src/test` sources. A new test that needs Android framework classes must carry that annotation, or `fastTest` runs it and fails.
- Screenshot baselines are committed under `app/src/test/screenshots/`; each test passes its own `filePath` to `captureRoboImage`.
- There is no lint or formatter task beyond the Android Gradle Plugin defaults. Code style is Kotlin `official`.
- Release builds sign from `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (or `local.properties`).

## End-to-end HID rig

`tools/hid-rig/run.sh` boots a headless Android emulator (wiped each boot), installs the debug APK, and runs `rig.py`: Google's Bumble stack plays the computer on the emulator's virtual Bluetooth controller (netsim), pairs with Bluke, and checks the exact bytes of keyboard, mouse, consumer, LED and every gamepad profile's reports, that all profiles advertise one descriptor, and disconnect/reconnect. Run it after touching the descriptor, report encoding or connection code. `KEEP_EMULATOR=1` leaves the emulator up; a run against an already-running emulator reuses it.

- The expected bytes in `rig.py` are written out by hand on purpose, independent of the Kotlin encoders. A deliberate encoding change updates both.
- The rig drives the app through `app/src/debug/.../debug/RigReceiver.kt`, a debug-only receiver guarded by the `DUMP` permission so only `adb` can reach it: `adb shell am broadcast -f 32 -a dev.arnv.bluke.debug.RIG -p dev.arnv.bluke --es cmd state` (commands: `state`, `skip-onboarding`, `connect`, `disconnect`, `key`, `mouse`, `gamepad`, `consumer`, `mode`, `release`). Add a command there when a new check needs one.
- First pairing shows Android's consent prompt; the rig taps Pair through `uiautomator`, as a person would.
- It proves Bluke against a spec-following host, not against Windows, Linux, Android or macOS hosts; their quirks still need a real host and phone.
- Needs emulator hardware acceleration: Hypervisor.framework on the Mac, read/write on `/dev/kvm` on Linux.

## Architecture

**One manager owns Bluetooth.** `BlukeApplication` lazily creates a single `BluetoothKeyboardManager` (`bluetooth/BluetoothKeyboardManager.kt`), shared by every activity. It binds the `BluetoothHidDevice` proxy, registers the HID app with SDP settings, tracks the connected host, scans and pairs, and sends reports. UI reads its `StateFlow`s (`serviceState`, `connectedDevice`, `lifecycleState`, lock-key LEDs, device lists) and calls `sendKey`, `sendMouseReport`, `sendGamepadReport`, `sendConsumerControl`. `HomeViewModel` folds those flows into `HomeUiState`.

**Threading.** Bluetooth callbacks and registration commands are serialized on the `bt-manager-scheduler` executor; reports go out on a separate `bt-report-sender` executor. A report is dropped if the connection epoch, HID proxy or host changed between submit and send. Connection selection is guarded by `connectionSelectionLock`.

**Policy is pulled out into small testable files.** The manager is too framework-bound to unit test, so decisions live in pure functions and coordinators beside it in `bluetooth/`, tested in `app/src/test/.../bluetooth/`:
- `HidRegistrationCoordinator` and `HidConnectionCoordinator` sit behind `BluetoothRegistrationFacade` and `BluetoothConnectionFacade`, so retries, timeouts and teardown are tested without Android Bluetooth.
- `HidLifecycle.kt` has the lifecycle states, failure kinds, `RetryPolicy`, and the rule for when a failure means the firmware lacks a usable HID Device role (the terminal `ProfileNotSupported` state).
- `HidSessionPolicy.kt`, `LatestRequestProcessor`, `GamepadModeSwitchState` cover incoming-host adoption, latest-wins request handling and mode-switch locking.
- UI-side policies follow the same pattern in `ui/*Policy.kt`.

When adding behavior to the manager, put the decision in one of these pure units and test it there.

**One shared HID descriptor.** `SHARED_HID_DESCRIPTOR` in `GamepadDescriptor.kt` declares every input kind under report IDs: 1 keyboard (with LED output), 2 mouse/touchpad, 3 gamepad, 4 consumer controls. All gamepad profiles (Native, Android, Web) advertise this same descriptor; switching profile only changes the bytes `buildGamepadReport` writes (`GamepadReport.kt`), never the descriptor, because hosts cache it per pairing. If you change the descriptor itself, bump `CURRENT_HID_DESCRIPTOR_REVISION` in `HidDescriptorRevision.kt`; that makes the home screen tell existing users to unpair and re-pair.

**Foreground service.** `BluetoothSessionService` is a connected-device foreground service that exists only to stop Android unregistering the HID app when the UI is backgrounded. It stops itself when `shouldKeepBluetoothSession` turns false (app hidden, no host, nothing pending). App visibility comes from activity counting in `BlukeApplication`, not from `MainActivity`.

**UI.** Each settings screen is its own `ComponentActivity` (all declared in `AndroidManifest.xml`); the main input surfaces (`KeyboardView`, `TouchpadView`, `GamepadView`, `MediaPresentationView`) are Compose views in `ui/`, chosen by `InputMode`. Settings persist in `SharedPreferences`/DataStore. Keyboard layouts send key positions, not characters (`KeyboardLayouts.kt`, `KeyboardCharacterLayouts.kt` for on-screen labels). Key-click sound packs are audio assets under `app/src/main/assets/audio/<pack>/{press,release}/` played by `sound/`.

**Diagnostics.** `DeveloperLogManager` keeps the in-app log shown in Developer options; it only records when enabled. Logs include Bluetooth MAC addresses and raw keycodes, so don't paste them into anything outward-facing.
