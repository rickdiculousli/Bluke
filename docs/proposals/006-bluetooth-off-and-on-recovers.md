---
id: 006
title: Turning the phone's Bluetooth off and on leaves Bluke saying "Bluetooth is off" while it's off, and ready again once it's back on, without a manual restart
kind: refinement
size: S
judge: opus
status: proposed
lens: eng
proposed_by: coordinator on ricks-macbook, from the operator's real-phone checklist run
proposed_on: 2026-11-23
---

## Opportunity
The operator's checklist run for spec 004 used a Pixel 10a against a MacBook host, with `main` at 58b63d5. It toggled Bluetooth with `adb shell cmd bluetooth_manager disable/enable` and read the manager's state about once a second through `RigReceiver`'s `state`.

- **The state lies while the radio is off.** About one second after Bluetooth goes off, the manager publishes `serviceState = ReadyDisconnected` with lifecycle `BindingProxy(attempt=1)`, and keeps it until Bluetooth comes back on. This happened in 3 of 3 runs on `main`, whether the phone was connected or only registered. As a result the home card shows "Starting Bluetooth — Getting Bluetooth input ready" while Bluetooth is off.
- **Bluke never recovers on its own.** After Bluetooth comes back on, it tries `Registering(attempt=1)` and `(attempt=2)` while still reporting `BluetoothOff`. Then it lands in `Error(REGISTRATION_REJECTED)` with "Bluetooth HID connection interrupted. Retry connecting or toggle Bluetooth.", and stays there. This happened in 3 of 3 runs on `main` and 2 of 2 on c7e59f6, the build before spec 004, so it predates the home spec. Tapping "Restart Bluetooth input" recovers it in about a second.

Where to look:
- **Adapter receiver.** It calls `checkBluetoothCapabilities()` on every `ACTION_STATE_CHANGED` (`BluetoothKeyboardManager.kt:215-217`), including the transitional states, so registration can start before the stack is up.
- **Profile-proxy and failure paths.** They publish `ReadyDisconnected` without checking that the adapter is on (`:756`, `:847`, `:1062`, `:1456`).

## Proposal
Fix the first wrong decision.

- **Stop registering before Bluetooth is on.** Registration starts only once the adapter reports `STATE_ON`.
- **Let a rejection during the transition retry.** A registration rejected during the off/on transition is a recoverable failure, not a verdict. Bluke retries on the next `STATE_ON` without the user's tap.
- **One rule for what a lifecycle step may publish.** While the adapter is off or turning on or off, `BluetoothOff` is the only published service state. Put that in one small pure function beside `HidLifecycle.kt`, which every `publishServiceState` call site goes through, and test it there.
- **Restart stays the manual way out.** It is still offered for a genuine failure.

Out: the home card (it renders what it is given), and the paired-host names showing as addresses while Bluetooth is off.

Judge: opus, because it sits on the registration path every host depends on.

## What you'll see
Turn Bluetooth off and the card says "Bluetooth is off" for as long as it is off. Turn it back on and, within a few seconds, Bluke is ready again. If it was connected before, it reconnects as reconnect-on-launch does. No red card, no tap.

## Verification
- **Unit tests** under `./gradlew testDebugUnitTest`, for the pure rules:
  - the publish gate: adapter off or transitional yields `BluetoothOff`;
  - the retry decision: a rejection seen during a transition retries on `STATE_ON`.

  Both fail today on the paths named above.
- **The HID rig** (`tools/hid-rig/run.sh`) gains a toggle check. It connects, runs `cmd bluetooth_manager disable` and checks `state` reads `BluetoothOff` throughout, then runs `enable` and checks Bluke reaches `Registered` and reconnects within a bound, with no command from the rig. The check fails on today's `main`.
- **No hill-climb.** This is a behaviour fix with no UI change.

## Shown

## Risks and the alternative considered
- **Retrying a real rejection forever.** A rejection that persists after `STATE_ON` is still the signal for a phone without a usable HID role (`indicatesLikelyDeviceIncompatibility`). So the retry is bounded to the transition, and the terminal `ProfileNotSupported` path keeps working. The rig and unit tests cover both.
- **Alternative: have the card ignore `ReadyDisconnected` while the adapter is off.** It lost because it hides a wrong state instead of not producing it, and the manager would still never re-register.

## Needs from the operator
- [phone] Turn Bluetooth off and on once on the Pixel. Say whether the card read "Bluetooth is off" throughout, and whether Bluke came back without a tap.
