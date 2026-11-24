---
id: 005
title: One Settings screen with collapsible sections replaces eight settings activities, and no setting contradicts another
kind: ux
size: L
judge: opus
status: done
lens: ux
proposed_by: coordinator on ricks-macbook, from the operator's request
proposed_on: 2026-11-23
scope_grew: [003, 004]
---

## Opportunity
About 45 settings are spread over 18 Activities (`docs/proposals/assets/ux-audit.md` §1, §4, §5):
- **Thin pages.** Four pages hold four controls or fewer: DarkTheme, Controller, Touchpad and QuickCycle. Behavior's six switches sit under a page titled "Controls & connection".
- **Unreachable cycle.** The input-mode cycle opens only on a long press of a toolbar pill. No path in Settings reaches it.
- **Split settings.** Sound on/off sits in Look & Feel, sound packs in their own screen, and mute on the keyboard toolbar. Scroll direction is in Settings, while scroll speed exists only on the toolbar.
- **Silent sound pack (C8).** Choosing a sound pack while muted leaves key sounds off (`HomeScreen.kt:976-984`, in `KeyboardSurface.kt` after the groundwork spec 001).
- **Caps-lock light (C3).** "Local" caps-lock mode lets the lock light disagree with the host. It was added for hosts that don't send lock-light reports (commit 11c13ec).
- **Dead Help path.** Help has a `first_run` variant that nothing opens (`HelpActivity.kt:36`).
- **Blind pickers.** Layouts have no preview, and sound packs have no play button.

## Proposal
In:

**One `SettingsActivity` with collapsible sections.** Expanded sections are remembered for the session (`rememberSaveable`):
- **Appearance:** whatever options the theme exposes, shown only when they apply. The surfaces spec owns the theme and the rule for when each option applies; until it lands, this section shows today's options unchanged.
- **Feedback:** haptics, written to `haptics_enabled`.
- **Keyboard:** geometry list with a preview; typing layout; modifier keys; Themes…; Sound (an Off row above the packs, each pack with play); Advanced (key touch area).
- **Touchpad:** pointer speed, scroll speed, invert, momentum, glide.
- **Controller:** profile, as a segmented control.
- **Multimedia:** volume buttons.
- **Input modes:** the old QuickCycle.
- **Connection:** reconnect on launch. Its Advanced subsection holds "Hide unnamed" and "Hide unlikely" (these filter Nearby only), MAC display and keep-audio.
- **Help & about:** Help…, About…, and Developer options… once unlocked.

Where the surfaces spec's `<Surface>TuneContent` composables exist on `main`, a section hosts them instead of building the same controls again. Preference keys and their value sets are the contract; a key is never renamed without a migration.

**Routing.** A `SettingsSection` enum and `settingsIntent(context, section)` open a section and scroll to it; an unknown section opens the top. This spec swaps the `Intent(...)` expressions that point at deleted Activities, and nothing else in the surface files:
- `KeyboardSurface.kt` (QuickCycle, KeyboardLayouts);
- `TouchpadView.kt` (QuickCycle);
- `GamepadView.kt` (QuickCycle, ControllerSettings);
- `MediaPresentationView.kt` (QuickCycle).

**Activities deleted,** with their manifest entries: LookAndFeel, DarkTheme, KeyboardSettings, KeyboardLayouts, TouchpadSettings, ControllerSettings, Behavior and QuickCycle. Kept as full screens: KeyboardThemes, SoundPacks, Help, About, Licenses, Developer options and Developer logs.

**Progressive disclosure.** A control shows only when it applies. The "include in toolbar cycle" checkboxes hide behind an "Edit cycle" toggle per list.

**Key sounds** live in Keyboard › Sound only. Choosing a pack, from anywhere, turns key sounds on through one tested function, `selectKeySound(...)` in `sound/SoundPreferences.kt`. The keyboard toolbar calls it.

**Lock lights.** The `lock_sync_mode` setting goes. A lock light follows the host once the host has sent a lock-light report on the current connection, and toggles locally until then. This is a per-connection flag beside `parseLedReport` (`BluetoothKeyboardManager.kt:1343-1355`), reset on connect. The rule is a pure function in `ui/LockLeds.kt` that the keyboard surface calls.

**Help.** The `first_run` path goes. Help copy and the README follow the new paths.

**`SettingsContent(state, callbacks)`** is stateless, so screenshot tests render every section.

**Files this spec owns:** `SettingsActivity`, the eight deleted Activities, `SettingsComponents.kt`, `KeyboardThemesActivity`, `SoundPacksActivity`, `HelpActivity`, `AboutActivity`, `LicensesActivity`, `AndroidManifest.xml`, `QuickCyclePreferences.kt`, `sound/SoundPreferences.kt`, `ui/LockLeds.kt`, and the README's Settings paths.

Out:
- the home screen and the input surfaces with their Tune sheets, which have their own specs;
- the keyboard theme editor's internals.

Judge: opus, because it's an information-architecture call with many dependents.

Scope grew: the surfaces spec (003) landed first, so Settings › Touchpad, Gamepad, Multimedia and Keyboard host its `*TuneContent`, Appearance renders its `appearanceOptions`, and its shared choice control (`TuneChoice`) and switch row took the fixes the hill-climb found on this screen. 003 also removed the keyboard toolbar's layout, theme and sound buttons, so those three toolbar cycles have no reader: their "Edit cycle" lists are cut, not hidden; only Input modes keeps its cycle. The home spec (004) left its mode tiles' long-press waiting for `settingsIntent`; it now opens Input modes.

## What you'll see
Settings is one calm screen. Every section is a tap away, and each section's header shows what's inside. Every control on screen does something in the current configuration. Keyboard geometry shows a preview and sound packs play a sample. The caps-lock light is right on hosts that report it and still works on hosts that don't.

## Verification
- **Tests that fail today** and pass after, under `./gradlew testDebugUnitTest`:
  - choosing a pack while muted turns key sounds on (today it leaves `key_sound_enabled` false);
  - a Settings path reaches the input-modes section (today none does);
  - the lock lights follow the host after its first lock-light report and toggle locally before it.
- **Other unit tests:**
  - routing: every old shortcut lands on its section, and an unknown section opens the top;
  - visibility: a control is hidden exactly when it can't apply;
  - migrations for every cut preference.
- **Screenshot tests** through `testing/ScreenshotMatrix.kt`: every section expanded, light and dark, font scale 1.0, 1.3 and 2.0, portrait.
- **The rig.** `tools/hid-rig/run.sh` stays green. The rig and `RigReceiver` set some preferences, so their keys stay.
- **Hill-climb** against `docs/proposals/assets/ui-rubric.md`:
  - Each round is a fresh Opus subagent. It gets the rubric, the `main` captures from the groundwork harness, the branch's captures and the diff, and none of the earlier rounds' text.
  - For Settings it scores C as "every control shows its current value without opening it, and every change confirms itself". It marks the connection-state and landscape clauses N/A.
  - Fix and re-score until the branch reads 9 or higher. Record each round in Shown.
  - If three rounds in a row bring no gain, or one finding survives two fixes, write down the belief both fixes assumed, test it, and report it with `bmesh ask`.

## Shown
- One Settings screen, collapsible sections, eight Activities gone: `OldSettingsShortcutsTest.noSourceNamesADeletedSettingsScreen` (sources and manifest name none of the eight); `settings-collapsed-*` captures show nine sections under three group labels, each header summarising its values.
- Every section a tap away and a Settings path to input modes: `SettingsRoutingTest.theInputModesPathShowsTheModeCheckboxes` (fails on today's code: no path existed) and `everySectionRoundTripsThroughItsIntent`, `anUnknownOrMissingSectionOpensTheTop`.
- Every old shortcut lands on its section: `InputModeShortcutsTest` long-presses the surface toolbar's mode button and a home mode tile and sees `SettingsActivity` started at Input modes (003 removed the layout and gamepad pills that had other shortcuts). Mirror's mode button is wired the same way but has no test.
- Choosing a pack while muted turns key sounds on, from anywhere: `SoundPreferencesTest.choosingAPackWhileMutedTurnsKeySoundsOn` (fails today); Settings, Sound packs and the keyboard Tune sheet all choose through `selectKeySound`.
- Lock lights follow the host after its first report and toggle locally before it: `LockLedsTest.afterTheHostsFirstReportTheLightsFollowTheHost`, `beforeTheHostsFirstReportThePhoneTogglesItsOwnLights` (fail today: no rule existed); the per-connection flag is `BluetoothKeyboardManager.hostReportsLockLights`, reset on a new link; `KeyboardSurfaceTest.connectedLockLightsAreThePhonesUntilTheHostReports` drives the real surface through both halves.
- Migrations for every cut preference: `LockLightMigrationTest.theOldLocalModeChoiceIsDropped` (`lock_sync_mode`); palette styles migrate in 003's `migratePaletteStyle`; the layout and theme cycle keys stay, read by their existing migrations; the sound cycle keys go: `SoundPreferencesTest.theOldSoundCycleKeysAreDroppedOnceAndTheChoiceStays`.
- A control shows only when it applies: `SettingsAppearanceTest.wallpaperColoursHideTheAccentAndPaletteStyle`, `aDarkStaticSchemeShowsAccentPaletteAndPureBlack` (through 003's `appearanceOptions`); `SettingsControlsTest.multimediaLeavesPointerSpeedToTouchpad`; key touch area folded under Advanced (`keyTouchAreaIsFoldedUnderAdvanced`). Glide showing only with momentum is 003's `TouchpadTuneContent`, seen in `settings-touchpad-*`, not tested here.
- Geometry shows a preview and sound packs play a sample: `settings-keyboard-*` and `soundpacks-*` captures (a live preview in the chosen theme; a "Play <name>" button on every sound row). Choosing a sound also plays it (`KeyboardSettingsController.selectSound`); no test hears it.
- Screenshots of every section, light and dark, fs 1.0, 1.3, 2.0, portrait: `ls app/src/test/screenshots | grep -cE "^(settings|soundpacks|keyboardthemes|help)-"` -> `102`.
- Verify: `./gradlew --no-daemon -q assembleDebug testDebugUnitTest` -> exit 0, `tests 564 failures 0 errors 0` (summed from the test-result XML).
- The rig mid-build, on the lock-light manager change: `tools/hid-rig/run.sh` -> `18/18 checks passed` (on ab1ed63; the first attempt failed to pair under load average 42 and passed unchanged on re-run).
- Hill-climb against `ui-rubric.md`, fresh Opus reviewers who never saw earlier rounds (rounds 1-3 one reviewer; from round 4, three, scored by median, as the operator asked):
  - Round 1: 4 (7 with 003's theme set aside). P1 segmented labels broke at fs2.0; fixed with a measured fallback.
  - Round 2: 4 (8). Sections grouped, value summaries, stepped labels, slider ticks.
  - Round 3: 4 (8). Keyboard themes library moved onto the kit.
  - Round 4: 4, 4, 4 (9, 9, 9 with theme set aside); every cap was 003's dark-primary P0, before 003 landed.
  - Round 5, after merging 003: 8, 8, 7, median 8. One slider, one choice fallback, haptics passed to hosted Tune content, US "color".
  - Round 6: 8, 8, 9, median 8. Multimedia stopped repeating Touchpad's sliders; swatch outlines; Help colours.
  - Round 7: 8, 8, 9, median 8. Help stopped painting advice as an error; header summaries name non-default values; one radio-row geometry.
  - Round 8: 9, 9, 9, median 9. Its P2s were then fixed: `TuneChoice` no longer changes layout with the selection (`SettingsControlsTest`), theme chips align.
  - One finding survived fixes: where key touch area sits (under Sound, then Layout, then Touch › Advanced). The belief both fixes assumed was that it needed a heading of its own; it doesn't: it is the spec's Advanced fold, last in Keyboard, set apart by a divider.
- Easy to miss: preference keys and value sets are the contract, never renamed: `hide_unsupported` keeps its key though it now reads "Hide unlikely devices", and `PaletteStyle.DEFAULT` keeps its stored value though it now reads "Colorful": `SettingsPreferencesTest.storedKeysAndValuesNeverChange` (every key and value as a literal).
- Not shown: the caps-lock light on real Mac and Windows hosts: needs a phone and hosts (the operator step below).
- Not shown: haptic strength, spring feel and sound-sample latency on a device: the emulator can't judge them; every reviewer listed them as not verified.

## Risks and the alternative considered
- **The lock-light rule** rests on a belief: hosts that report lock lights do so soon after connecting. The rig covers a host that follows the spec; real hosts are the operator's check.
- **Alternative: keep the Activities and restyle them.** It lost because the depth is the problem, as the audit's tap counts show.

## Needs from the operator
- [phone] With a Mac host (and a Windows host if one is at hand), connect, then toggle caps lock on the phone and on the host's own keyboard. Say whether the phone's light always matched.
