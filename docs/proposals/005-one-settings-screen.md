---
id: 005
title: One Settings screen with collapsible sections replaces eight settings activities, and no setting contradicts another
kind: ux
size: L
judge: opus
status: proposed
lens: ux
proposed_by: coordinator on ricks-macbook, from the operator's request
proposed_on: 2026-11-23
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

## Risks and the alternative considered
- **The lock-light rule** rests on a belief: hosts that report lock lights do so soon after connecting. The rig covers a host that follows the spec; real hosts are the operator's check.
- **Alternative: keep the Activities and restyle them.** It lost because the depth is the problem, as the audit's tap counts show.

## Needs from the operator
- [phone] With one Mac host and one Android host, toggle caps lock on the phone and on the host's own keyboard. Say whether the phone's light always matched.
