---
id: 004
title: One connection state machine drives a home screen that always says what's happening and offers the one next step
kind: ux
size: L
judge: opus
status: proposed
lens: ux
proposed_by: coordinator on ricks-macbook, from the operator's request
proposed_on: 2026-11-23
---

## Opportunity
The UX audit (`docs/proposals/assets/ux-audit.md`) maps two explicit state machines, `BluetoothState` and `HidLifecycleState`, with implicit ones built on top of them:
- **Status by string match.** Connection status is read from English text. `DeviceRow.kt:157-169`, `HomeUiPolicy.kt:27-36`, `HomeScreen.kt:341-344` and `DeviceListSection.kt:50,68` look for words like "Connecting" and "rejected" in `statusMessage`.
- **Pairing state is text only.** "Host refused pairing", pairing and disconnecting exist only as that text (`BluetoothKeyboardManager.kt:252, 690, 695, 822`).
- **Wrong headline.** The status card always says "Ready to Scan" (`StatusHeaderCard.kt:90`), even when connected, connecting or denied.
- **Permission denied (C10).** Launch stays enabled and nothing grants the permission (`HomeUiPolicy.kt:11-16`). After a second denial, Android shows no prompt at all.
- **Surface outlives Bluetooth (C1).** A surface stays open after Bluetooth turns off (`HomeScreen.kt:596-603`).
- **Hidden paired host (C9).** The list filter and the row disagree on what "supported" means (`DeviceListSection.kt:93,139` vs `DeviceRow.kt:180`), and `hide_unsupported` defaults to true.
- **Scattered help triggers.** A 12 s stall effect, a 3-tap counter (`HomeScreen.kt:1230-1234`) and a churn counter (`:318-334`) each raise their own help card.
- **Slow mode switch.** Switching input mode takes 2-4 taps from home.
- **Onboarding friction.** A forced 5 s countdown (`OnboardingActivity.kt:150-159`), a generic "Updated to vX" dialog (`HomeScreen.kt:243-308`) and title-tap easter eggs that open YouTube (`:1136-1145`).
- **Unused help code.** `has_seen_help` is written but never read (`OnboardingActivity.kt:71`, `RigReceiver.kt:59`).

## Proposal
In:

**Typed state from the manager.** The manager's behaviour doesn't change, but it publishes as typed, read-only state what it today says only in `statusMessage`:
- a `lastConnectionFailure` flow (HostRefusedPairing, PairingRequestFailed, ConnectionRejected), set beside those messages and cleared at the next attempt;
- a disconnecting flag set at `:822`.

No transition changes.

**`connectionUi`.** One pure, total function in `ui/ConnectionUiPolicy.kt`:
- **Inputs:** the manager's flows, `attemptStartedAt` and `now`.
- **States:** Checking, Blocked (no adapter, no HID role, Bluetooth off, permission missing), Starting(attempt n), Ready, Pairing, Connecting, Connected(host), Disconnecting, and Failed (HostRefused, Stalled, ServiceFailed). Stalled means Connecting or Pairing for 12 s or more.
- **Output:** a headline, a status dot, at most one primary action, and details.
- **Replaces:** every `statusMessage` string match, `hidStatusLabel`, the `shouldOffer*` helpers, `blocksInputLaunch`, the stall effect, and the tap and churn triggers. The Failed state carries the help.
- **Surfaces:** it also maps to the input surfaces' `SurfaceHost` at the surface call site in `HomeScreen.kt`. It edits no surface file.

**Home.**
- A connection card with one primary action per state: Grant permission, Turn on Bluetooth, Retry, Reconnect <host>, Scan, Cancel, Disconnect, Restart HID or Help. A Details accordion holds the status line, attempt, MAC and Restart HID.
- The Paired list collapses automatically when connected. The Nearby list is open only while scanning or when it has results.
- With no paired hosts, the empty state shows the three connect steps.
- Mode tiles show every mode and launch it in one tap. A long press opens Settings at its input-modes section through `settingsIntent(section)` once that exists on `main`. The input-mode cycle governs only the surface's mode button.
- A stateless `HomeContent(HomeUiState, callbacks)` lets screenshot tests render every state without reflection.

**C10.** Grant permission runs MainActivity's request. When the system won't show the prompt, it opens Bluke's app-settings page, and the resume re-check restores the state. Permission missing blocks Launch.

**C1.** A surface returns home when `connectionUi` is Blocked. Checking and Starting leave it open and show in the host chip.

**C9.** One predicate, `isLikelyHost(device)`, means bonded, HID-capable, or a supported class. It lives in a policy file and is used by both the list and the row. The Paired list is never filtered; `hide_unknown` and `hide_unsupported` apply to Nearby only.

**Onboarding.** `OnboardingActivity` is deleted. The card's Blocked(permission missing) state and the no-paired-hosts empty state replace it. `RigReceiver`'s `skip-onboarding` keeps working. The "Updated to vX" dialog, the easter eggs, the `has_seen_help` writes, and the developer-options home mocks go; the screenshots of every state replace those mocks.

**Files this spec owns:**
- `HomeScreen.kt`, except the surfaces that the groundwork spec 001 moved out of it;
- `HomeViewModel.kt`, `HomeUiPolicy.kt`, `ConnectionHelpPolicy.kt`, `StatusHeaderCard.kt`, `DeviceListSection.kt`, `DeviceRow.kt`, `ProfileNotSupportedScreen.kt`;
- `InputMode.kt`, `MainActivity.kt`, `OnboardingActivity.kt`, `DeveloperOptionsActivity.kt`;
- the typed failure flow in `bluetooth/`, `RigReceiver.kt` and `rig.py`.

Out:
- the input surfaces' files, which belong to the surfaces spec;
- Settings and Help, which belong to the settings spec;
- any change to how the manager connects.

Judge: opus, because it sits on the connection path real hosts depend on.

## What you'll see
Open Bluke and the card says exactly what's happening ("Connected to Rick's MacBook", "Bluetooth is off", "Host refused pairing") with one button for the next step. Device lists stay out of the way until needed. Every mode is one tap from home. A first launch goes straight to home, which asks for the permission inline.

## Verification
- **Tests that fail today** and pass after, under `./gradlew testDebugUnitTest`:
  - C1: a surface plus Bluetooth off goes home, while a surface plus Checking stays open;
  - C9: a bonded host of an unsupported class, with `hide_unsupported`, is listed;
  - C10: permission missing blocks Launch and offers Grant;
  - Connected shows a "Connected to <host>" headline, not "Ready to Scan".
- **A generated table test** of `connectionUi` over every `BluetoothState` × every `HidLifecycleState` shape × {no target, unbonded target, bonded target, connected} × scanning. Each case yields a headline and at most one primary action.
- **A guard test** that fails if any file under `ui/` calls `statusMessage.contains`.
- **Screenshot tests** of home in every state through `testing/ScreenshotMatrix.kt`.
- **The rig.** `tools/hid-rig/run.sh` stays green, disconnect and reconnect included. Add a rig command if a new state needs one.
- **Hill-climb** against `docs/proposals/assets/ui-rubric.md`:
  - Each round is a fresh Opus subagent. It gets the rubric, the `main` captures from the groundwork harness, the branch's captures and the diff, and none of the earlier rounds' text.
  - Fix and re-score until the branch reads 9 or higher under the rubric's caps. Record each round in Shown.
  - If three rounds in a row bring no gain, or one finding survives two fixes, write down the belief both fixes assumed, test it, and report it with `bmesh ask`.

## Shown

## Risks and the alternative considered
- **Real hosts differ from the rig.** Windows, macOS and Linux behave in ways the rig doesn't. `connectionUi` is total over its inputs, so the card can't hide a state the manager reaches. The table test proves that.
- **Asking for the permission inline** replaces onboarding's explanation page. The card's Blocked copy carries that explanation instead.
- **Alternative: fix only the "Ready to Scan" label.** It lost because the string matching is the cause, and four other places would still read English.

## Needs from the operator
- [phone] Pair, disconnect and reconnect one real host. Say whether the card ever showed the wrong state.
