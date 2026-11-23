---
id: 002
title: Mirror the host screen onto the phone at low resolution, so cards in Slay the Spire can be dragged by touching them
kind: feature
size: L
judge: opus
status: proposed
lens: eng
proposed_by: coordinator on ricks-macbook, from the operator's request
proposed_on: 2026-11-23
---

## Opportunity
The touchpad moves the cursor relative to where it is. To drag a card in Slay the Spire you steer an unseen cursor through host acceleration, overshoot, and correct. The operator wants a low-resolution copy of the host screen on the phone, so they can put a finger on the thing they want.

Research (`docs/proposals/assets/002-mirror-research.md`, with measurements and sources) found two things. Every touch-on-mirror product uses a host companion over the LAN. A 480x270 Slay the Spire frame is 17-26 KB as JPEG at quality 50.

This spec supersedes three of the research's choices: placing Mirror as a toggle on the touchpad, its one-sided pairing confirmation, and its RFCOMM fallback.

## Proposal
Bluke gets a Mirror input surface, and the host gets a small companion, `bluke-mirror`, in a new `companion/` Cargo workspace. In this slice the companion runs on macOS, where the operator plays.

In:

**The Mirror surface on the phone.**
- Mirror is a new `InputMode` with id 5. Id 3 belonged to the removed `KEYBOARD_TOUCHPAD` mode, so a saved `launch_mode` of 3 must not open Mirror.
- Mirror is not in the default mode cycle; the user adds it.
- It opens even with no Bluetooth host connected, and it stays open when Bluetooth goes off.
- If a shared surface toolbar exists on `main` by the time this lands, Mirror uses it.

**Transport.**
- Frames and touches go over Wi-Fi on the LAN.
- While Mirror is visible, the phone listens on its Wi-Fi interface's address only and advertises `_bluke-mirror._tcp` over mDNS. The companion connects out to it, or to `bluke-mirror --phone <ip>`; the phone shows its IP for that.
- Bluke gains `INTERNET`, `WAKE_LOCK` and `ACCESS_NETWORK_STATE`. This is its first network access, and the README says so.
- A low-latency Wi-Fi lock is held only while Mirror is visible.

**Pairing.**
- The link is TLS 1.3 with mutual self-signed certificates: the platform's `SSLSocket` on the phone, `rustls` in the companion.
- First pairing is a PAKE (SPAKE2 or CPace) keyed by a fresh random six-digit code. The phone shows it, the user types it into the companion, and a key-confirmation MAC binds it to both certificate fingerprints.
- A failed attempt discards the code. The phone allows 3 attempts per Mirror session.
- The companion enters pairing only through a local action by its user.
- Neither side pins a key that wasn't confirmed on that side.
- Until the peer's pinned key is verified, nothing but pairing messages is accepted or sent: no display list, no frames, no input.
- Every message length is checked against a fixed cap of 256 KB before anything is allocated, on both sides.
- A paired phone is forgotten by deleting the key file the README names.

**Frames.**
- The companion polls the capture at a cap (default 20 fps) and sends a frame only when a hash of the downscaled frame differs from the last one sent. It downscales to the width the phone asks for (default 480) and encodes JPEG at quality about 60.
- At most 2 frames are in flight. A stale frame is dropped, never queued.
- The protocol has a codec field, so H.264 can be added later without breaking it.
- The phone decodes off the main thread into a reused pool of bitmaps and draws without recomposing the screen.

**Input.**
- The phone sends absolute points normalised to the mirrored display. Letterboxing and rotation are handled on the phone.
- The companion injects only pointer moves, the left button and the wheel, never keys. Points are clamped to the mirrored display's rect.
- On macOS the companion injects with CGEvent. A move while the button is held must be a dragged event.
- Moves and button events travel in one ordered stream.
- Direct mode is the default: down presses, move drags, up releases. Hover mode moves without pressing, so tooltips and enemy intents show.
- The companion releases every held button when:
  - the link drops;
  - the link goes quiet: while a button is down the phone sends a heartbeat every 250 ms, and the companion releases after 1 s of silence;
  - the phone sends Release from `ON_STOP`.
- Each touch-down on Mirror calls `holdLinkAwake()`, as the touchpad does, so a keystroke after Mirror play isn't late. Incoming frames never count as activity.
- The HID descriptor doesn't change, so nobody re-pairs.

**Companion structure.**
- `companion/core` holds the protocol, pairing, mapping math, frame credit, and the `Capture` and `Injector` traits. It uses no platform crate.
- The binary crate holds thin backends. This slice has one real backend, macOS: ScreenCaptureKit, through xcap or directly, plus CGEvent.
- The synthetic capture source and the recording injector live in a test-only example or cargo feature, not as flags on the shipped CLI.
- The Rust version is pinned in a committed `companion/rust-toolchain.toml`.

**Protocol.** `companion/PROTOCOL.md` writes it down once, with hand-written byte vectors that both the Kotlin and the Rust tests check.

Out:
- the Windows and Linux backends;
- H.264;
- cropping to the game window, and pinch-zoom;
- the RFCOMM fallback;
- a signed macOS `.app`.

A later spec takes these.

Judge: opus, because it spans a new protocol, a new security boundary and two languages.

## What you'll see
1. Run `bluke-mirror` on the Mac and open Mirror on the phone. The first time, type the code the phone shows into the companion.
2. A low-resolution live copy of the screen fills the surface.
3. Put a finger on a card, drag it onto an enemy and lift. The card is played.
4. The toolbar shows frames per second, link latency, and Direct or Hover.
5. If no companion is paired, Mirror explains how to start one.

## Verification
- **`./gradlew testDebugUnitTest`** gains tests for:
  - the protocol vectors;
  - coordinate mapping (letterboxing, rotation, display rect);
  - the frame pool and its credit;
  - pairing: wrong code, three strikes, re-pair, unknown key refused, nothing pinned after a failure;
  - the input mode id and the cycle default.
- **`cargo test -p bluke-mirror-core`** checks:
  - the same vectors;
  - the pairing state machine, including a man-in-the-middle relay that must fail;
  - that frames are never sent to an unpinned peer;
  - the length cap;
  - injection ordering and clamping, with the recording injector;
  - release on a drop and on a silent link, both in the middle of a drag;
  - change-only sending, with the synthetic source.
- **`cargo check --target aarch64-apple-darwin`** passes for the macOS backend.
- **`tools/mirror-rig/run.sh`** runs the companion's synthetic source against the debug app on the emulator.
  - It takes the same lock as `tools/hid-rig/run.sh` and reuses its emulator boot.
  - The phone is reached with `adb forward` and `--phone 127.0.0.1:<port>`.
  - It reads the pairing code through a new `RigReceiver` `mirror` command.
  - It drives a drag with `adb shell input motionevent DOWN/MOVE/UP` and checks, against hand-written expectations:
    - the recorded down/drag/up sequence and its coordinates;
    - that a drawn frame arrives, by a frame counter read through `RigReceiver`.
  - It records touch-to-recorded-inject and frame-sent-to-decoded times over at least 100 runs, as pipeline numbers, not Wi-Fi numbers.
- **The verify line.** Until the operator publishes the new verify line, the lead pastes the output of the cargo test and the mirror rig into Shown. Verify at landing doesn't run them yet.
- **Hill-climb.** Score each round with a fresh Opus subagent that hasn't seen earlier rounds' text. It uses two rubrics:
  - The Mirror surface is scored against `docs/proposals/assets/ui-rubric.md`. It has no `main` baseline, and its feel is listed as not verified.
  - The feature is scored on six items, 0-2 each:
    1. drag mapping correctness;
    2. pipeline latency, with run counts;
    3. security: pairing, pinning, nothing sent or injected before pinning, the button always released;
    4. setup friction;
    5. host coverage: core tested on k16, the macOS backend checked on k16;
    6. small, tested code.

  Keep fixing until the UI rubric reads 9 or higher and the feature reads at least 11/12 with no item at 0.

  Record every round's scores in Shown. If three rounds in a row bring no gain, record the belief that's blocking progress and report it with `bmesh ask`.

## Shown

## Risks and the alternative considered
- **macOS permissions.** The companion needs Screen Recording and Accessibility, and an unsigned binary loses the grants when it's rebuilt. The companion prints which settings pane to open.
- **Network isolation.** Guest Wi-Fi with access-point isolation blocks the link. The README says so.
- **Alternatives rejected:**
  - A vendor HID report as the transport: an unmeasured 20-60 KB/s that competes with keystrokes, and it needs a re-pair and macOS Input Monitoring.
  - An absolute HID pointer: Windows confines it to the primary monitor.
  - A Kotlin/JVM companion using `java.awt.Robot`: capture is slow and polled, and Robot doesn't work on Wayland.
  - Noise instead of TLS: there's no maintained Kotlin Noise library, and TLS needs no new dependency on Android.

## Needs from the operator
- Add the cargo test and the mirror rig to the repo's verify. The lead names the exact line, with Rust run through `mise exec`, and the operator publishes it with `bmesh repos propose` from the admin box.
- [ricks-macbook] Build and run the companion:
  - install the pinned Rust;
  - `cargo build --release -p bluke-mirror`;
  - grant Screen Recording and Accessibility to the terminal;
  - run it and pair.
- [ricks-macbook] Play one Slay the Spire hand through Mirror. Say whether dragging cards feels right and what the latency shown reads on your Wi-Fi.
