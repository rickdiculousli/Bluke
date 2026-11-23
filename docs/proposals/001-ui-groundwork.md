---
id: 001
title: Clear the ground for three parallel UI builds: cut the dead gamepad switch machine, move the input surface out of HomeScreen, add one screenshot helper
kind: maintenance
size: M
judge: opus
status: done
lens: eng
proposed_by: coordinator on ricks-macbook, from the operator's request
proposed_on: 2026-11-23
---

## Opportunity
Three UI specs follow this one: the input surfaces, the home screen, and the settings screen. Built in parallel today, they would collide in three places:
- **`ui/HomeScreen.kt`.** It holds the home screen (lines ~80-595 and 1126-1382), the input-surface host (`:596-675`, including a hand-built `darkColorScheme` at `:624-632`), and the whole keyboard surface with its toolbar (`:676-1121`).
- **The dead descriptor-switch machine,** which the home and surfaces specs would both touch. `requiresGamepadDescriptorRestart` always returns false (`bluetooth/GamepadDescriptor.kt:141-145`), so `switchGamepadDescriptor`, `GamepadModeSwitchState`'s REGISTERING and RECONNECTING states, and the busy and error dialogs (`ui/GamepadView.kt:424-464`) never run. They still sit in the files both specs edit.
- **The screenshot captures.** All three specs need light/dark/font-scale captures, and each would write its own helper.

Separately, a hill-climb compares the branch against captures of `main`, and `main` has no harness for most screens. The visual audit's harness (`docs/proposals/assets/001-AllScreensScreenshotTest.kt`) captures 122 screens, but against the code as it is today.

## Proposal
In:
- **Cut the dead machine.** Delete `switchGamepadDescriptor`, `registerLatestGamepadDescriptor`, `requiresGamepadDescriptorRestart`, `GamepadModeSwitchState` and its tests, the `gamepadModeSwitchError`/`Host` flows, `retryGamepadModeSwitch`, the busy and error dialogs, and the descriptor-migration copy (`HomeScreen.kt:366`, `ControllerSettingsActivity.kt:84`, `HelpActivity.kt:141`). Two guards are live behaviour and stay, rewritten as a plain "registered" flag:
  - `sendGamepadReport` drops reports while unregistered (`BluetoothKeyboardManager.kt:1750-1752`);
  - `ensureRegistered` returns early when already registered (`:1370-1372`).

  `changeGamepadMode` returns false only when the app is closed. In the same commit:
  - remove `gamepadModeSwitch` from `RigReceiver`'s `state`, and the wait on it in `tools/hid-rig/rig.py:326`;
  - keep the rig's `mode` command working.
- **Pure move, no behaviour change.** Move the input-surface host into `ui/InputSurface.kt` and the keyboard surface with its toolbar into `ui/KeyboardSurface.kt`. `HomeScreen.kt` keeps one call: `InputSurface(...)`. The hand-built dark scheme moves along unchanged; the surfaces spec deletes it.
- **One screenshot helper** at `app/src/test/java/dev/arnv/bluke/testing/ScreenshotMatrix.kt`, annotated for Robolectric:
  - It takes a name, landscape or not, font scales (default 1.0 and 1.3) and content. It sets light or dark through the `theme_mode` preference and night qualifiers, sets `dynamic_color=false`, and applies the font scale through `LocalDensity`.
  - It writes `src/test/screenshots/<name>-<light|dark>-fs<scale>[-land].png`.
  - Its own test captures one existing surface through it.
- **Main baseline harness.** Update `docs/proposals/assets/001-AllScreensScreenshotTest.kt` so it runs against this spec's landed tree, and put its run instructions in its header comment. Each later UI spec's reviewer copies it into a scratch checkout of `main` and runs it to get the "from `main`" captures. It is not part of the test suite.

Out: any visible change, theme changes, and the home, settings and surfaces redesigns.

Judge: opus, because the cut runs through the real Bluetooth registration path.

## What you'll see
Nothing changes in the app. `HomeScreen.kt` loses about 600 lines to two new files. The gamepad mode-switch code is gone. The rig still passes.

## Verification
- `./gradlew testDebugUnitTest`: `GamepadDescriptorTest` and `GamepadModeSwitchStateTest` are deleted or rewritten against what's left, and a new test shows gamepad reports are dropped while unregistered.
- `tools/hid-rig/run.sh` green, every profile and reconnect included.
- The existing screenshot baselines (`multimedia-landscape.png`, `multimedia-upright.png`, `touchpad-modifier-rails.png`) don't change under `verifyRoborazziDebug`. The move is proven pure.
- The harness asset runs and produces its captures. Paste the count into Shown.

## Shown
- Nothing changes in the app: the all-screens harness run with `recordRoborazziDebug` on `main` and on this branch, then a sha256 compare of the 122 PNGs -> `identical 119 differ 3`. The three are `home-dialog-gamepad-guide-{light,dark}` and `help-full-light`, where the spec deletes the re-pair copy. Every keyboard, touchpad, gamepad and media capture is identical.
- `HomeScreen.kt` loses its surfaces to two new files: `wc -l` -> `649 HomeScreen.kt` (from 1382), `108 InputSurface.kt`, `774 KeyboardSurface.kt`. HomeScreen calls `rememberKeyboardSurfaceState(...)` and `InputSurface(...)`.
- The mode-switch code is gone: `grep -rnE "GamepadModeSwitch|switchGamepadDescriptor|registerLatestGamepadDescriptor|requiresGamepadDescriptorRestart|retryGamepadModeSwitch|gamepadModeSwitch" app tools README.md AGENTS.md | wc -l` -> `0`.
- Mode-switch tests deleted or rewritten: `GamepadModeSwitchStateTest` deleted; `GamepadDescriptorTest` keeps only its descriptor and report tests.
- Gamepad reports dropped while unregistered: `BluetoothManagerSessionTest.gamepadReportsAreDroppedWhileUnregistered`, which fails (`expected:<0> but was:<1>`) with the guard removed.
- Existing baselines unchanged: `./gradlew verifyRoborazziDebug --tests 'dev.arnv.bluke.ui.MediaPresentationScreenshotTest'` -> `BUILD SUCCESSFUL in 23s`, and `git diff --stat main` on the three PNGs is empty.
- One screenshot helper: `captureMatrix` in `app/src/test/java/dev/arnv/bluke/testing/ScreenshotMatrix.kt`. `ScreenshotMatrixTest` checks its pixels in memory through `onCaptured`, so a plain test run catches a broken helper: forcing light theme in the helper -> `failures="1"`, dropping its font scale -> `failures="2"`, as written -> `failures="0"`.
- The harness asset runs and produces its captures: `ls screens-branch | wc -l` -> `122`, with no `SCREEN FAILURES`. Its header says how to run it (`recordRoborazziDebug`, since a plain test run writes no images).
- Easy to miss: the move keeps the keyboard's state as long-lived as before. `KeyboardSurfaceState` is remembered by HomeScreen rather than inside the surface, so lock LEDs, held keys and a pending character layout survive leaving the surface: `rememberKeyboardSurfaceState` in `KeyboardSurface.kt`, called from `HomeScreen.kt`.

## Risks and the alternative considered
- **The cut is the one part of this on the real-host path.** The rig covers it against a spec-following host. A real host keeps working because only unreachable branches go.
- **Alternative: let each UI lead resolve the collisions on rebase.** It lost because three leads rewriting the same 1,400-line file in parallel spend their effort on merges, not design.

## Needs from the operator
