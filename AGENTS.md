# Working in Bluke

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

<!-- bmesh:rules core design tests ddf83108 -->
## Engineering rules
From bmesh's rule sets `core`, `design`, `tests`. Where the rest of this file says otherwise, the rest wins. Edit them freely: `bmesh scaffold` keeps an edited block and marks it customized.

### core
- Make the smallest change that solves the problem. Delete what's dead before adding.
- Read the code before you change it, and say what you learned before deciding.
- When the `pick-principles` skill is installed, use it before designing, building, debugging, migrating or deleting, or briefing a builder.
- Fix the first place it went wrong, not where it showed. A guard is a fix only when the state it handles is legitimate.
- Prove it works on the real thing: run it the way a user would. Passing tests prove only what they reach.
- When two fixes have failed on the same assumption, stop and test the assumption.
- A rule you've had to state twice becomes a check or a script, not a third sentence.
- Do reversible work, then report it. Ask only what no experiment or reading can settle.
- Before trusting a number (a timing, a speedup, a count), find what limits it and rule out that it measured something else.

### design
- Name the data shape before writing logic.
- Keep the domain in one structure (a type, a table, a state machine), not in conditionals spread across files.
- Check input where it enters the system. Inside, trust your own types.
- One writer per file, branch, key or record. Remove the sharing before reaching for a lock.
- Make steps safe to run twice, so a retry after a crash ends in the same state.
- When a new API replaces an old one, move every caller and delete the old one in the same change.

### tests
- Test what callers can observe, not how the code does it. A test that breaks on a rename is testing internals.
- Test through the real entry point (the command, the package's public functions) where you can.
- A piece isn't done while no test exercises its code.
- A flaky test is a bug. Find the cause rather than retrying it away.
<!-- /bmesh:rules -->

<!-- bmesh:done -->
## Definition of done
A change is done when all of these hold: the repo's verify commands pass (`export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} JAVA_HOME=${JAVA_HOME:-$(mise where java@temurin-21)}; ./gradlew --no-daemon -q assembleDebug testDebugUnitTest`, `tools/hid-rig/run.sh`); a change sized M or larger (its spec's `size` as built: a change whose scope grew is sized by what it grew to, and work with no spec is sized as a spec would be (XS one file, S one module, M crosses modules, L changes an interface others depend on); at medium effort for M, high for L or larger) was reviewed once against its spec by Opus in a session that didn't write it and the findings were fixed, while an XS or S change is done on verify alone; the branch is rebased onto `main` with verify re-run; it is merged fast-forward to `main` and pushed to `rickdiculousli/Bluke`. If the rebase conflicts, resolve it: read your spec and the specs that landed since you branched, make every intent hold, widen your spec if a landed one changes what yours must look like, re-validate, then merge. Stop only when the widened scope can't be met from the specs alone; then report the block with `bmesh blocked` and the reason. End with one line: what's done (the commit on `main`), its push state from a fresh fetch (pushed, with the commit `origin/main` is at and that it contains yours, or not pushed and why), what wasn't checked, and what, if anything, waits on the operator. Say what each thing is: the first time a message names a spec, issue, PR, commit, job or numbered item, it says in plain words what that is, with the number after it in brackets if at all, so the operator never has to look anything up. The last line is final: it reads on its own, names nothing not explained above it, and says in plain words what waits on the operator and what they'd do about it.
<!-- /bmesh:done -->

<!-- bmesh:proposals -->
## Proposals
Half-page specs live in `docs/proposals/NNN-slug.md` (the template is `bmesh spec template`; the lifecycle is in `docs/proposals/README.md`). The build thread owns its spec's status: it sets `done` on its own branch, so the spec lands with its code; a build in flight, or blocked, is its lead's node on the mesh, never a status in git. Rejected specs stay so they aren't re-proposed. A spec the operator picks is built by a buildermesh lead (see Buildermesh below).
<!-- /bmesh:proposals -->

<!-- bmesh:mesh -->
## Buildermesh
Builds here run as nodes on a buildermesh mesh through the `bmesh` CLI: a lead owns one spec in its own worktree, executors build pieces on branches under the lead's and hand them back through the mesh, and routines run this repo's checks. The repo's routine kinds live in `.bmesh/routines/<kind>.toml` (`verify` runs `export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} JAVA_HOME=${JAVA_HOME:-$(mise where java@temurin-21)}; ./gradlew --no-daemon -q assembleDebug testDebugUnitTest`, `tools/hid-rig/run.sh`; `review` asks Opus, in a session that never saw the change, for findings; add a kind rather than a script when a check should be recorded). Commit before queueing a job: a job names a commit, and every result is recorded under that commit's tree hash, so a deterministic kind answers a repeat run from its record. A spec lands with its code, reading `done` in the tree that lands, and `bmesh land` rewords the landed commit to carry its proof as two trailers, `Spec: NNN` and `Verified: <entries>` (`verify ok t:<tree8>; review ok t:<tree8> m:<model>`), composed from the results recorded for the landed tree; `bmesh done` is the last word: it refuses until that commit is on `origin/main` with a result for every entry. Every read of the admin or approve key first shows a `bmesh:` dialog naming what it signs; a Keychain prompt about bmesh with no such dialog before it is not bmesh's, and the operator denies it.
<!-- /bmesh:mesh -->
