---
id: 003
title: The input surfaces look and feel like a controller you hold, on a theme that works in dark mode
kind: ux
size: L
judge: opus
status: proposed
lens: ux
proposed_by: coordinator on ricks-macbook, from the operator's request
proposed_on: 2026-11-23
---

## Opportunity
A baseline of 122 screenshots (`docs/proposals/assets/003-visual-audit.md`) shows these problems:
- **Dark primary.** The static dark scheme reuses the light navy `#0A3D91` as its primary: 1.85:1 on the dark surface, so buttons, headers and icons vanish. The surfaces build their own private `darkColorScheme` with that same primary (`HomeScreen.kt:624-632` today, in `ui/InputSurface.kt` after the groundwork spec 001). The static primary is one of 20 user-picked accents (`ThemeConfig.kt:9-30`), and none of them has a designed dark tone.
- **Fake containers.** Containers are faked with alpha (`baseColor.copy(alpha = 0.3f)`, `Theme.kt:128,137`), which breaks Material's tonal pairs.
- **Toolbars.** Every input surface has a row of 9sp pills in 28dp chips that all look the same, so nothing reads as primary. Their chrome uses raw white and black with alpha instead of roles, plus an LED green, `#39FF14`, repeated 8 times.
- **Multimedia.** It's a grid of identical grey squares, and its upright labels truncate ("Bla…", "Ho…").
- **Gamepad.** It's top-heavy and low-contrast. Its Guide button, on Xbox and PlayStation alike, shows the Bluke app logo. Its edit handles are navy on near-black.
- **Haptics.** Three prefs (`haptics_enabled`, `touchpad_vibration_enabled`, `gamepad_vibration_enabled`), two mechanisms and four copies of the vibrate code. With haptics off, the touchpad, gamepad and multimedia still buzz.
- **Forgotten state.** The gamepad console, touchpad button mode and numpad reset every time the surface reopens (`TouchpadView.kt:82-83`, `GamepadView.kt:153`).
- **Press feedback.** Presses tween in over 60-80 ms instead of showing on touch-down (`RemoteControlButtons.kt:106-112`, `GamepadView.kt:1258-1265`).
- **Tune changes don't apply.** Surfaces read their settings once (`TouchpadView.kt:84-90`) or on resume only, so a setting changed inside the surface wouldn't apply until it reopens.

## Proposal
Use the installed `impeccable`, `material-3` and `apple-design` skills.

In:

**Theme** (`ui/theme/`):
- Every colour is a `colorScheme` role, with on-X paired on X.
- Every accent in `AccentColors` maps to a designed light and dark tonal set (primary, onPrimary, primaryContainer, onPrimaryContainer) that passes contrast. Hand-tune them or add a colour-utilities dependency; that's the lead's call. Cut an accent only if it can't pass, and list it in Shown.
- The surfaces use the theme's dark scheme, and the hand-built one in `InputSurface.kt` is deleted.
- Content colours stay in their existing tables (the keyboard `Colorways.PALETTES` and the console table). The toolbar chrome and LED colour become roles or named tokens.
- `palette_style` values that render identically are cut, and old values migrate to Default.
- The theme exposes which appearance options apply for a given dynamic and dark state. The settings spec renders that list and owns no appearance rule.

**Shared surface toolbar** (`ui/SurfaceToolbar.kt`) for the keyboard, touchpad, gamepad and multimedia:
- It holds Close, the mode button, a host chip, two or three high-frequency controls, and Tune.
- The host chip renders a `SurfaceHost(status: Connected | Connecting | Offline | Blocked, name: String?, onReconnect: (() -> Unit)?)`, which this spec defines. The call site fills it from `homeUiState.connectedDevice` plus the reconnect target. That removes the per-surface dot code and the second source of "connected". `ReconnectHostButton.kt` is deleted.
- The toolbar has no long-press into settings Activities. The mode button's long-press opens Settings at its input-modes section if a `settingsIntent(section)` helper exists on `main`; otherwise it does nothing.

**Tune sheet** (`ui/SurfaceTuneSheet.kt`) holds the surface's own settings. Each surface's tune controls are a stateless `<Surface>TuneContent(state, onChange)` that the settings screen can host too, so each control is built once:
- touchpad: pointer and scroll speed, invert, momentum, glide, button mode;
- keyboard: geometry, typing layout, modifier keys, theme, sound pack;
- gamepad: profile, console, edit and reset layout;
- multimedia: pointer and scroll speed, volume buttons.

Surfaces observe the preference keys they use, the way `Theme.kt:89-101` does, so a Tune change applies at once. Preference keys and their value sets are the contract with Settings; a key is never renamed without a migration.

**Feel:**
- One haptics helper replaces the four vibrate copies. `haptics_enabled` becomes the only haptics key, migrated as old `haptics_enabled` AND touchpad AND gamepad (an explicit off wins). The surface vibration toggles and the two old keys go.
- Feedback appears on touch-down, in the same frame. Haptics fire on the causal event only.
- No decorative animation on repeated input. Motion respects the system animator scale.
- Touch targets are at least 48dp.

**Surfaces:**
- Multimedia gets a hierarchy: transport first, volume second, the rest grouped. Labels are never truncated, and "Black" is explained.
- Gamepad gets a balanced layout, a Guide glyph that means Guide, and edit handles you can see.
- Console, button mode and numpad persist as surface state, in keys `gamepad_console`, `touchpad_button_mode` and `touchpad_numpad`. They aren't settings, and Settings doesn't show them.

**Screenshot tests** through `testing/ScreenshotMatrix.kt` (from the groundwork spec 001):
- every surface in light and dark, at font scale 1.0 and 1.3, in landscape and in the upright multimedia view;
- every gamepad profile, caps-lock lit, and each `SurfaceHost` status;
- they replace `GreetingScreenshotTest` and the three surface baselines this spec owns.

**Files this spec owns:**
- `ui/theme/**`, `InputSurface.kt`, `KeyboardSurface.kt`, `SurfaceToolbar.kt`, `SurfaceTuneSheet.kt`;
- `TouchpadView.kt`, `GamepadView.kt`, `MediaPresentationView.kt`, `RemoteControlButtons.kt`, `RemoteControlPreferences.kt`, `KeyCap.kt`, `KeyboardView.kt`;
- the haptics helper and its migration.

Out:
- the home screen and the settings screens, which have their own specs;
- key encoding and the HID descriptor;
- new input modes.

Judge: opus, because it's taste-heavy, and the rubric settles the calls.

## What you'll see
Dark mode reads cleanly everywhere. Each surface has one obvious primary area and a quiet toolbar with a Tune button, and touchpad speed can be changed from the touchpad itself. Keys and buttons respond the instant a finger lands, with the same haptics on every surface. Gamepad and multimedia look designed, not gridded. Reopening a surface brings back how you left it.

## Verification
- **Tests that fail today** and pass after, under `./gradlew testDebugUnitTest`:
  - Contrast, over every static scheme × accent × light/dark: on-X on X at least 4.5:1, primary and outline on surface at least 3:1. It fails today at 1.85:1.
  - With `haptics_enabled` false, no surface vibrates. It fails today on the touchpad.
  - The haptics migration.
  - Leaving and re-entering a surface keeps console, button mode and numpad.
  - A frame-1 capture with `mainClock.autoAdvance = false` shows the pressed state on touch-down.
  - A Tune change applies without reopening the surface.
- **`verifyRoborazziDebug`** passes on the new baselines.
- **The rig.** `tools/hid-rig/run.sh` stays green: report bytes don't change.
- **Hill-climb** against `docs/proposals/assets/ui-rubric.md`:
  - Each round is a fresh Opus subagent. It gets the rubric, the `main` captures from the groundwork harness, the branch's captures and the diff, and none of the earlier rounds' text.
  - It runs material-3's audit and Impeccable's critique as raw inputs, then scores 1-10 through the rubric's caps.
  - Dimension C on the surfaces is judged by how the toolbar renders the `SurfaceHost` it's given.
  - Fix, re-capture and re-score until the branch reads 9 or higher. Record each round's score and top findings in Shown, and list gesture feel and haptic strength as not verified.
  - If three rounds in a row bring no gain, or one finding survives two fixes, write down the belief both fixes assumed, test it, and report it with `bmesh ask`.

## Shown

## Risks and the alternative considered
- **The theme change moves every screenshot.** The home and settings specs build at the same time. Whichever lands later re-records the baselines the earlier one moved, and explains each change in its commit.
- **The home spec replaces the `SurfaceHost` adapter's body.** If it lands first, this spec wires its chip from that spec's `ConnectionUi`.
- **Alternative: polish each surface without a shared toolbar or Tune sheet.** It lost because surface settings would stay two screens away. The UX audit names that distance as the cause.

## Needs from the operator
- Add `verifyRoborazziDebug` to the repo's verify, so committed baselines can't drift silently. Publish the new verify line with `bmesh repos propose` from the admin box. This one step covers all three UI specs.
- [phone] After this lands, use each surface for a minute and say whether the haptics and press feedback feel right. The emulator can't judge that.
