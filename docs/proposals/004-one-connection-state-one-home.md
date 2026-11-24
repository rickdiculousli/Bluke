---
id: 004
title: One connection state machine drives a home screen that always says what's happening and offers the one next step
kind: ux
size: L
judge: opus
status: done
scope_grew: [002]
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

**Scope grew (002, the host-screen mirror, landed first):** its end-to-end rig opened Mirror by tapping home's old "Launch Mirror" button, and its README line described that button. Both now use the Mirror tile, and the rig receiver's `setup` no longer writes `launch_mode`.

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
- Card says what's happening, with one next step: `connectionUi` and `ConnectionUi.card()` in `app/src/main/java/dev/arnv/bluke/ui/ConnectionUiPolicy.kt`, rendered by `ConnectionCard.kt`; every state is a baseline, e.g. `app/src/test/screenshots/home-connected-light-fs1.0.png` ("Connected to Rick's MacBook Pro"), `home-blocked-bluetooth-off-*`, `home-failed-host-refused-*` ("Host refused pairing").
- Connected reads "Connected to <host>", not "Ready to Scan": `ConnectionUiPolicyTest.connectedSaysConnectedToTheHostNotReadyToScan`; on main the headline was fixed text: `git grep -n '"Ready to Scan"' main -- app/src/main` -> `main:app/src/main/java/dev/arnv/bluke/ui/StatusHeaderCard.kt:90: text = if (isScanning) "Scanning..." else "Ready`.
- C10, permission missing blocks Launch and offers Grant: `ConnectionUiPolicyTest.permissionMissingBlocksLaunchAndOffersGrant`; the prompt-or-app-settings rule is `HomeUiPolicyTest.grantAsksAndroidUntilItStopsShowingThePrompt`, wired in `MainActivity.requestBluetoothPermissions`.
- C1, a surface goes home when Bluetooth is blocked and stays open while checking: `ConnectionUiPolicyTest.aSurfaceGoesHomeWhenBluetoothTurnsOffButStaysOpenWhileChecking`, wired as the `closesSurface` effect in `HomeScreen.kt`.
- C9, a paired host of an unlikely class is listed with hide-unlikely on: `HostListPolicyTest.aPairedHostOfAnUnlikelyClassIsListedWithHideUnlikelyOn`; one predicate, `isLikelyHost`, in `HostListPolicy.kt`, read by the Nearby filter and the row.
- Those policy tests pass: `./gradlew testDebugUnitTest --tests ...ConnectionUiPolicyTest --tests ...HostListPolicyTest --tests ...StatusMessageGuardTest --tests ...HomeUiPolicyTest` -> `ConnectionUiPolicyTest tests="15" skipped="0" failures="0"`, `HostListPolicyTest tests="4" ... failures="0"`, `StatusMessageGuardTest tests="1" ... failures="0"`, `HomeUiPolicyTest tests="5" ... failures="0"`.
- Generated table over every BluetoothState × lifecycle shape × {no target, unbonded, bonded, connected} × scanning (also × disconnecting × each failure × stalled): `ConnectionUiPolicyTest.everyInputYieldsOneHeadlineAndAtMostOnePrimaryAction`, 12,672 cases, each one headline and at most one primary.
- Guard against status-text matching: `StatusMessageGuardTest`; on main it would fail: `git grep -nE 'statusMessage\s*\.\s*contains' main -- app/src/main/java/dev/arnv/bluke/ui` -> `main:app/src/main/java/dev/arnv/bluke/ui/DeviceListSection.kt:50: ...statusMessage.contains(...`.
- Typed state from the manager: `lastConnectionFailure` and `isDisconnecting` in `bluetooth/BluetoothKeyboardManager.kt`, set beside the existing messages; no transition changed. `RigReceiver`'s `state` reports both, and `rig.py` checks the failure is clear after connecting and waits for the disconnect to finish.
- Lists stay out of the way: Paired collapses while connected or scanning (`home-connected-*`, `home-scanning-*`, filmstrip `home-motion-connect-*ms.png`), Nearby opens only while scanning or with results, and no paired hosts shows the three steps (`home-ready-no-hosts-*`).
- Every mode one tap from home: the mode dock in `HomeContent.kt` shows `InputMode.entries` and launches on tap; its pressed tone shows on touch-down (`home-motion-tile-press-0ms.png`, `-16ms`). Long press opens the input-mode cycle screen (`QuickCycleActivity`) until `settingsIntent(section)` exists on `main`.
- First launch goes straight to home, which asks inline: `OnboardingActivity` deleted; `git grep -nE "OnboardingActivity|has_seen_help|mock_update_popup|dQw4w9WgXcQ" HEAD -- app tools | wc -l` -> `0` (main: `15`). `RigReceiver`'s `skip-onboarding` still marks an up-to-date install.
- Stateless `HomeContent(HomeUiState, callbacks)`: screenshot tests render it with fixture states and no reflection: `./gradlew verifyRoborazziDebug --tests 'dev.arnv.bluke.ui.Home*'` -> exit 0 over 93 committed baselines (72 portrait states: 18 states × light/dark × font scale 1.0/1.3; 8 short-window landscape; 13 filmstrip frames).
- All-screens harness runs on this tree: `docs/proposals/assets/001-AllScreensScreenshotTest.kt` updated for the mode tiles and no onboarding; its run wrote 102 PNGs with no `SCREEN FAILURES`.
- Hill-climb against `ui-rubric.md`, a fresh Opus reviewer each round with no earlier text:
  - Round 1: 7/10 (A3 B3 C3 D3 E3 F3 G3). Top findings, all P2: scan results below the fold; refusal copy repeated the host name; outlined-button border under 3:1; first launch opened on a red card with "Restart HID" jargon. All fixed, plus the modes moved into a dock outside the scroll so a press shows on touch-down, springs throughout, and haptics gated.
  - Round 2: 9/10 (A4 B3 C4 D4 E3 F4 G3), conditional on re-committing the home baselines a re-record had dropped. Its P2s were a duplicated scan control and Details misaligned 12dp. Both were fixed and the baselines re-recorded.
  - Round 3: 8/10 (A4 B3 C4 D3 E4 F4 G4). Five P2s: the light pressed tone was invisible, the haptic fired on release, Nearby's header sat empty, the badge floated beside long copy, and "Connect" didn't look tappable. All fixed: the haptic now fires on press, Connect is a tonal pill.
  - Round 4: 8/10 (A3 B3 C4 D4 E4 F3 G3). Five new P2s. One finding came back after a fix, the outlined border. The belief both fixes assumed was "the `outline` role holds 3:1 on every card container". Measured, that's false on the tinted dark cards: 2.93:1 on secondaryContainer and 2.88:1 on errorContainer. Borders now use each card's on-colour (at least 7.2:1). Also fixed: tiles press to `secondary`, the Details clip, the row that repeated the card's Reconnect, and a one-row dock in short windows, with landscape captures added.
  - Round 5: 8/10 (A4 B3 C3 D4 E4 F4 G3). Three P2s: a disconnecting row read "Connecting…", rows offered Connect while a tap couldn't show anything, and there were four different left edges. An executor fixed all three (`HostRowState.DISCONNECTING` and `INERT`, tested in `ConnectionUiPolicyTest`, and one 32dp content edge).
  - Three rounds without a gain tripped the spec's rule. The belief tested was that a fresh reviewer re-finds the defects still there. The findings show it isn't so: rounds 3–5's P2 sets barely overlap, and no round found a P0 or P1 in this branch's scope. Reported to the operator with `bmesh ask`.
  - Round 6: **9/10** (A4 B3 C4 D4 E4 F4 G3, 26/28), on the tree with round 5's fixes. Its one P2: in Connected, the full-width Disconnect outweighs the mode tiles. Left for the operator's taste call: Disconnect is the state's one primary action by the spec's own list. The 9 is conditional on the theme spec (003) fixing the static dark primary. With dynamic colour off, the dark Nearby "Scan" text is 1.85:1, and home uses the right role there.
  - From here the operator changed the protocol: each round has three independent fresh Opus reviewers, the round's score is their median, and any P2 raised by two or more reviewers gets fixed.
  - Round 7 (three reviewers, on 7322643): **9, 9 and 8, median 9**. No P0 or P1 from any of them. All three raised one P2: Disconnect outweighed the mode tiles in Connected. Now Disconnect sits at its own width and the tiles take `secondaryContainer` while connected (`home-connected-*`). Two raised another: Paired stayed open while the card was about an unpaired host. Paired now steps aside then too, so that host's Nearby row stays in view (`home-pairing-*`, `home-failed-host-refused-*`). One reviewer raised a third P2, left for the operator: after a second denial, Grant permission opens app settings while the card still says "Android asks first".
- The filmstrips are stable run to run. `verifyRoborazziDebug` once failed on `home-motion-details-50ms`. The cause: Material's ripple is the platform `RippleDrawable`, which animates on wall-clock time, not the held compose clock. `HomeMotionScreenshotTest` now turns the ripple off (`LocalRippleConfiguration provides null`). After a full `testDebugUnitTest`, three runs of `./gradlew -q verifyRoborazziDebug --tests 'dev.arnv.bluke.ui.Home*'` -> `exit 0` each time. Roborazzi's results mark all frames `unchanged`; the two mid-spring Details frames still differ by a few pixels, under its default threshold.
- Easy to miss: the surface call site reads `connectionUi` too, not `connectedDevice` or the Bluetooth state, so "connected" has one source: `isConnected = surfaceHost.status == SurfaceHostStatus.CONNECTED` in `HomeScreen.kt`, mapped by `ConnectionUi.surfaceHost()` (`ConnectionUiPolicyTest.surfacesSeeOneHostStatus`).
- The rig stays green: pending the landing verify on this tree. Two earlier runs failed at pair and connect: the mid-build one on f06ac93 and the first landing verify (t:6300e336). The belief both had assumed was "the rig fails here only when load slows the prompt tap". Tested on the landing run's log: the guest was starved before Bluke started. `ANR in com.android.systemui`, launcher, Google Play services and the phone app all fired between 17:33 and 17:35, with Bluke absent from the CPU lists. Bluke's `createBond` went out at once, but Settings took 12 s to receive the pairing request and 17 s to draw its dialog (`+16s943ms`), and `uiautomator` timed out connecting. The host's 30 s pairing window closed first (`HCI_ERR_AUTH_FAILURE`). Fix at the cause, in `rig.py`. It waits (`App.wait_settled`, up to 600 s) until the emulator's load average drops below twice its core count before it starts the app. The second landing verify (t:e8f3e18d) showed the guest still at `emulator load 46.2 on 4 cores` after 240 s, before Bluke had started. So the first pairing now gets up to three tries (`first_pairing`), each logged. A try lost to a stall ends in a refused bond, which Bluke reports as `lastFailure`, and the next try runs once the guest has caught up. The third landing verify (t:a6b241f6) settled (`emulator load 7.6 on 4 cores`) and still failed all three tries, which ruled out load as the whole cause. In that run's log, the dialog drew within 2 s, but each try ran one `uiautomator dump` and never another. The dump waits for the UI to go idle, so it hung, hit `_run`'s 60 s timeout, and `subprocess.TimeoutExpired`, which the prompt loop didn't catch, ended the task silently. The likely trigger was this branch's card spinning under the prompt while pairing; on main nothing animated there, because the rig's host is in no list. Two fixes. Pairing waits on a person, so the card holds still while pairing (`ConnectionUiPolicyTest.pairingWaitsOnAPersonSoTheCardHoldsStill`). And the rig gives each dump its own 15 s limit and logs and retries on any failure.
- Landing review (Opus, high effort), first pass failed with seven findings, all fixed:
  - The Mirror rig tapped a button that's gone; it now taps the Mirror tile (see Scope grew).
  - The stall clock keyed on the address alone, so a 15 s pairing read "Still connecting" the instant connecting began. The key now carries the phase: `attemptKey`, `ConnectionUiPolicyTest.pairingThatTurnsIntoConnectingRestartsTheStallClock`.
  - A stale "Host refused pairing" outlived a new scan. The manager clears the failure when discovery starts, and scanning reads as scanning: `aNewScanReplacesAStaleFailure`.
  - The C9 test now goes through `hostLists`, the function `HomeViewModel.build` uses, with hide-unlikely on.
  - The rig retries a first pairing only when the bond was refused unanswered.
  - Every adb step of the rig's prompt check logs and retries.
  - Grant permission records "asked" only after a real denial, not a dismissed prompt.
  - The landing verify on 32fdb2a passed, the HID rig included: `bmesh result find verify --commit HEAD` -> `ok 3ba6057f…`.
- Rebased onto main with the mirror spec (002) landed: Mirror is a fifth mode, so the dock shows it with its `DesktopWindows` icon and every home baseline was re-recorded. `./gradlew -q assembleDebug testDebugUnitTest` -> exit 0. `verifyRoborazziDebug` passes every home baseline. Its one failure is `GreetingScreenshotTest`, whose `greeting.png` has been unreadable since the initial commit (`file` -> `data`) and fails the same way on main; the surfaces spec (003) replaces it.
- Not shown: a real phone and host. That's the operator's step below.

## Risks and the alternative considered
- **Real hosts differ from the rig.** Windows, macOS and Linux behave in ways the rig doesn't. `connectionUi` is total over its inputs, so the card can't hide a state the manager reaches. The table test proves that.
- **Asking for the permission inline** replaces onboarding's explanation page. The card's Blocked copy carries that explanation instead.
- **Alternative: fix only the "Ready to Scan" label.** It lost because the string matching is the cause, and four other places would still read English.

## Needs from the operator
- [phone] Install the build, pair, disconnect and reconnect one real host. Say whether the card ever showed the wrong state.
- [phone] On a fresh install, deny the Bluetooth permission twice, then tap Grant permission. It should open Bluke's app settings; allow it and come back to the card leaving the blocked state.
