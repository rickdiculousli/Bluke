---
id: 007
title: Mirror puts translucent left-hand buttons over the picture (touch mode, a held left button, and your own hotkeys), so the right hand only points
kind: feature
size: L
judge: opus
status: proposed
lens: ux
proposed_by: coordinator on ricks-macbook, from the operator's play test of Mirror
proposed_on: 2026-11-23
---

## Opportunity
The operator played Slay the Spire through Mirror (spec 002) with the Pixel and the MacBook. The drag is "smooth and responsive". What's missing is a way to use both hands:

- **Opaque rail.** The controls sit in an opaque rail down the left (`ui/MirrorRail.kt`): Close, host, Direct, Hover, the next-input-mode button (`:186-193`), latency and the menu. The rail takes width from the picture.
- **No hold without Direct.** Hover moves the pointer without pressing and clicks only on a tap (`mirror/MirrorTouchPolicy.kt:5-10`). The only way to hold the button is Direct, where the press happens the instant a finger lands. So you can't aim first in Hover and then press and hold.
- **No keys.** Mirror sends no keys. A game's hotkeys, such as Slay the Spire's E (end turn) or 1-9 (pick a card), need the keyboard surface, which replaces Mirror on screen.

The operator asked for:
- translucent buttons on the left edge, under the left thumb, while the right hand controls the pointer;
- a toggle between touch = click (Direct) and touch = position only (Hover);
- in Hover, a left-button key: holding it presses at the pointer and holds; only lifting it releases;
- configurable extra buttons that send keyboard keys.

What exists already:
- **Held button.** The protocol carries a held button. While one is held, the phone sends `Heartbeat` every 250 ms, and the companion releases it only after 1 s with no liveness message (`companion/PROTOCOL.md:105-109`). A held button with a still pointer therefore needs no companion change.
- **Keys.** `MirrorView` already gets the `BluetoothKeyboardManager` (`ui/MirrorView.kt:102-108`). Bluke is the host's Bluetooth keyboard, so hotkeys can go out as HID keyboard reports. That keeps the companion's rule that it never injects keys (`README.md`, Mirror).

## Proposal
In:

**The picture fills the surface.** The opaque rail goes, and the controls float over the picture's left edge, under the left thumb.
- **Translucent.** Each control has a translucent scrim and an outline, so it's legible over any picture, light or dark.
- **The left column, top to bottom:** Close; the next-input-mode button; the touch-mode toggle; the left-button key (Hover only); the hotkey buttons; the menu. The menu holds the host, the link readout, Edit buttons, and Forget.
- **The next-input-mode button** keeps its long-press. Today that opens `QuickCycleActivity`, which the in-flight Settings spec deletes. Whichever of the two lands second re-points that long-press in `MirrorView.kt` to Settings' input-modes section.
- **Large targets.** Every target is at least 48 dp, and the left-button key is larger, about 72 dp.
- **Clear of Back.** The column sits clear of the system Back gesture zone along the left edge, by inset or by a gesture exclusion over the controls (today's exclusion covers only the bottom 200 dp).
- **Width.** The overlay never covers more than about 15% of the picture's width.

**Touch-mode toggle.** One button switches Direct and Hover and shows the current mode by icon and label. The mode already persists in `mirror_touch_mode` (`mirror/MirrorController.kt:655`). The old Direct/Hover pair goes. Switching mode while the left-button key is held releases first.

**Left-button key (Hover).**
- Its down sends `Pointer buttons=1` at the last point Mirror sent this session, and its lift sends `buttons=0` at the point last sent. Mirror can't know where the host's cursor is, because the Mac's own trackpad may have moved it. Before any point has been sent, the key is inert.
- The heartbeat while it's held comes from the policy's held state (`buttonHeld`, `MirrorTouchPolicy.kt:89-90`), which today covers only a Direct finger and now covers the key too.
- While it's held, a right-hand finger on the picture moves the pointer with the button held (a drag). A finger landing elsewhere moves the pointer straight there, still held.
- While it's held, lifting the right-hand finger doesn't release, a tap doesn't click, and a second finger doesn't scroll. Only the key's own lift releases.
- The key shows pressed for as long as it's held. It gives a haptic on press and on release.
- If the link drops, or Mirror leaves the screen, the key reads released. The companion releases on its own, under its existing rules. A thumb still on the key after a drop stays inert until it lifts, as `abandon()` does for fingers.

**Two hands at once.**
- A finger that lands on an overlay control belongs to that control until it lifts. The picture's gesture logic never sees it.
- A finger that lands on the picture is the pointer finger, as today.
- So with the left thumb on a key, the right finger's moves are one-finger moves, never a two-finger scroll.
- This rule is pure logic in `MirrorTouchPolicy` (or a small pure unit beside it): fingers arrive tagged with where they landed, never as `MotionEvent`s.
- The tag is set where the `MotionEvent` becomes a frame of fingers (`MirrorView.kt:489-510`): each pointer's landing point is tested against the overlay controls' measured bounds. Today one `pointerInteropFilter` on the image (`:457-482`) sees every pointer, so a thumb on a button over the picture would read as a second finger and start a scroll. A Robolectric test drives the real view with two pointers to prove the routing.

**Hotkey buttons.**
- Up to 8 buttons. Each sends a key, optionally with modifiers (Ctrl, Shift, Alt/Option, Cmd/Win), through the Bluetooth keyboard to the connected host.
- A button sends key down on press and key up on lift, so holding it holds the key. The order is modifiers down, key down, key up, modifiers up, through `sendKey(keyCode, isPress)` (`BluetoothKeyboardManager.kt:1598`). The calls go through a small send interface, so the logic is unit-tested without the manager.
- Modifiers are one bitmask with no counting. If two held hotkeys share a modifier, lifting one drops it for the other. That's accepted, because hotkeys are tapped, not chorded.
- A button's label is the key's printed name (E, 1, Esc, ⇧Tab), or a short name you give it.
- Edit buttons, in Mirror's menu, opens an editor over the dimmed picture. You can add a button by choosing a key or by typing a character, and you can remove a button, reorder, and rename.
  - The key list is the HID key codes Bluke's keyboard surface sends: letters, digits, F-keys, Esc, Tab, Space, Enter, arrows and modifiers.
  - A typed character maps to a key position through the selected typing layout, the inverse of `getCharForKeycode` (`KeyboardLayouts.kt:632`).
- The list persists as one preference, `mirror_hotkeys`: a JSON list of {key, modifiers, label}, with a version field so it can migrate.
- When Bluetooth isn't connected to a host, the buttons read disabled. A tap says "Connect Bluetooth to <host> to use hotkeys". Bluke can't tell which computer the picture comes from, so the copy names the Bluetooth host.

**Haptics** use Mirror's own `MirrorHaptics` (`MirrorView.kt:314`), which honours `haptics_enabled`.

**Files this spec owns:**
- `ui/MirrorView.kt`, `ui/MirrorRail.kt` (deleted or rewritten as the overlay), `ui/MirrorCards.kt`, and any new `ui/Mirror*.kt`;
- `mirror/MirrorTouchPolicy.kt`, `mirror/MirrorUiState.kt`;
- `mirror/MirrorController.kt`: it owns the policy, `setTouchMode`, `abandon` and the heartbeat tick, so key presses reach the session through it;
- the Mirror call site in `ui/InputSurface.kt`, which passes the Bluetooth host's name for the disabled-hotkey copy;
- the Mirror commands in `app/src/debug/.../RigReceiver.kt`;
- the Mirror rig (`tools/mirror-rig/`);
- `MirrorRailTest`, `MirrorControlsTest`, `MirrorScreenshotTest` and the baselines under `screenshots/mirror/`;
- the README's Mirror section.

It edits no Settings file. The in-flight one-Settings-screen spec owns those, and Mirror's buttons are edited on the surface.

Out:
- a right-handed (mirrored) layout;
- a right-button or middle-button key;
- sending keys over the mirror link;
- cropping and pinch-zoom (a later spec);
- any companion or protocol change. If the build finds one is needed, it reports that with `bmesh ask` before making it.

Judge: opus, because it is touch logic with two hands at once, plus a taste-heavy overlay.

## What you'll see
The game fills the phone. On the left edge, under your thumb, are faint buttons:
- Direct/Hover;
- a big left-click key;
- the hotkeys you added, such as End turn (E).

In Hover, you rest your right finger on a card and the tooltip shows. You press and hold the left-click key with your thumb, slide the card onto the enemy, and lift your thumb to drop it. Tapping E ends the turn without leaving Mirror.

## Verification
- **Tests that fail today,** under `./gradlew testDebugUnitTest`. They fail by not compiling, since the API doesn't exist yet:
  - **Hover hold:** key down, then right-finger moves, right-finger lift, and another right-finger move produce `buttons=1` moves throughout, and no release until the key lifts.
  - **Two hands:** a finger on an overlay control plus a finger moving on the picture give one-finger moves, never `Wheel`.
  - **Hotkeys:** `mirror_hotkeys` round-trips. A hotkey press and lift call the keyboard send with key down, then key up, with its modifiers.
- **Other unit tests:**
  - the held key survives a 5 s still pointer: `Heartbeat` goes out every 250 ms while it's held;
  - a link drop while held resets the key's state;
  - the hotkeys disable when Bluetooth isn't connected.
- **The rig.** `tools/mirror-rig/run.sh` gains a held-key drag:
  - `RigReceiver` gets key-down and key-up commands that call the controller's key entry, and the rig opens Mirror with `touch=HOVER` (`RigReceiver.kt:207`);
  - the recording injector shows one press at the last point sent, moves with the button held, a 2 s still hold with no release, and a release at the end.

  The rig injects one pointer (`adb shell input motionevent`), so routing the thumb's touch is covered by the unit and Robolectric tests, not the rig. The existing checks stay green, and so does `tools/hid-rig/run.sh`, since keyboard report bytes don't change.
- **Screenshots,** by extending `MirrorScreenshotTest` (landscape, dark, the existing `mirror/` baselines), not `ScreenshotMatrix`, whose light theme Mirror never shows. At font scale 1.0 and 1.3, and in the connection states the rubric scores:
  - the overlay over a light picture and over a dark one;
  - Direct and Hover;
  - the key held;
  - 0, 3 and 8 hotkeys;
  - hotkeys disabled;
  - the editor.
- **Hill-climb** against `docs/proposals/assets/ui-rubric.md`. Each round has three fresh Opus reviewers, with no earlier rounds' text. Fix and re-score until the median reads 9 or higher, fixing every P0 and P1, and every P2 that two or more reviewers raise. Record each round in Shown.

## Shown
- Hill-climb round 1: scores 7/7/6, median 7; fixed: hotkeys page in a fixed last cell instead of an unscrollable list, a cell keyed by its button so an edit never sends the old key, a lift ends its press after a Bluetooth drop, ripples clipped to each control, the key's slot reserved in Direct so hotkeys don't move, the left-button key disabled (with a reject haptic) without a live link and drawn with a click glyph, Direct/Hover and Mirror labels kept at font scale 1.3, TalkBack's long-press on next mode, label insets, the editor's key list moved up with scroll cues, safe-drawing insets, a fade and a shorter placeholder, captures over a full-width light picture plus the hint pills, the open menu and the second hotkey page; left: modifier names differ between the editor's chips, its key list and the button faces
- Hill-climb round 2: scores 8/8/8, median 8; fixed: Add moved into the editor's header so the key list gets the height, modifier chips wrap and carry the glyph the face shows under the key list's names, a slow-link chip (computer and latency at 100 ms or more), the menu names the Bluetooth host hotkeys go to, a disabled hotkey's state says it needs Bluetooth, the editor hides the surface from TalkBack and has a pane title, the editor's faces are the surface's own, control bounds take half the gap so a near miss never clicks the picture, Forget's icon, the pager's up glyph on its last page, "Your buttons"; left: in Direct the empty key slot still belongs to the picture

## Risks and the alternative considered
- **The overlay covers part of the picture.** It sits on the left edge, where a game's menus sometimes live. The scrim is translucent and the column narrow. If that's not enough, a later spec can add dimming or a side swap.
- **Hotkeys go to the Bluetooth host, not the mirrored computer.** They match only when the phone is paired over Bluetooth to the same computer, as it is here. The disabled state and its copy say which host gets them.
- **Alternative: hotkeys over the mirror link.** It lost because it would make the companion inject keys, a wider trust boundary than the pointer-only rule spec 002 set, when a keyboard link already exists.

## Needs from the operator
- [phone] Play a hand of Slay the Spire in Hover with the left-click key, and use a hotkey (E). Say whether the hold-drag and the hotkeys felt right.
