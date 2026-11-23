# Screen mirror on the touchpad: research and recommendation

Goal: show a low-resolution, loosely live copy of the host screen under Bluke's touchpad, and let a finger on that image act on the same point of the host screen, so card games like Slay the Spire can be played by dragging cards directly.

Code read: `CLAUDE.md`, `README.md`, `bluetooth/GamepadDescriptor.kt`, `HidDescriptorRevision.kt`, `HidGetReport.kt`, `LinkKeepAlive.kt`, `BluetoothKeyboardManager.kt` (report sending, `onSetReport`/`onInterruptData`/`onGetReport`, `registerApp`), `ui/TouchpadView.kt`, `ui/Touchpad*.kt`, `ui/InputMode.kt`, `tools/hid-rig/rig.py`, `app/src/debug/.../RigReceiver.kt`, `.bmesh/routines/verify.toml`.

## Recommendation in one paragraph

Build a small host companion, `bluke-mirror`, in Rust, living in this repo. The phone opens a Wi-Fi connection to it while the touchpad's Mirror view is showing. The connection is TLS 1.3, pinned to keys exchanged at a one-time pairing confirmed by a six-digit code. The companion captures one display at the OS's cheapest change-driven API, scaled by the GPU to about 480x270. It sends frames only when something changed, and only when the phone has credit for them: JPEG in phase 1, H.264 in phase 2. The phone sends touches back as absolute points normalized to that frame, and the companion injects them with the OS's own absolute-pointer call, mapped exactly onto the mirrored display. **No HID descriptor change and no re-pair.** The fallback carries the same byte protocol over a Bluetooth RFCOMM socket on the existing bond, at a lower profile, for when phone and host share no LAN. A HID vendor report as the transport and a HID absolute pointer as the input path were both evaluated and rejected for this feature (sections 1 and 3).

## Numbers behind the choice

Frame sizes were measured, not estimated. Four official Slay the Spire 1920x1080 screenshots from Steam were downscaled with area filtering and encoded on this Mac. H.264 used a 4 s, 30 fps clip of screenshot 1 with a 500x500 region bobbing, standing in for monster idle animation. x264 baseline with `zerolatency`, `ultrafast` and CRF 28 stands in for openh264, which will be somewhat larger.

| Encoding | 320x180 | 480x270 | 640x360 |
|---|---|---|---|
| Raw RGB565 | 115 KB | 259 KB | 461 KB |
| JPEG, sips q50 (4 shots) | 9.7–14.2 KB | 17.5–26.3 KB | 27.5–41.9 KB |
| JPEG, sips q70 | 16–23 KB | 30–43 KB | 47–70 KB |
| JPEG, ffmpeg mjpeg q:v 12 (shot 1) | 6.8 KB | 12.5 KB | n/a |
| H.264 baseline, avg per frame at 30 fps | 1.1 KB (263 kbit/s) | 1.9 KB (461 kbit/s) | 2.9 KB (685 kbit/s) |
| H.264 keyframe | 15 KB | 26 KB | 44 KB |
| H.264 P-frame avg | 0.86 KB | 1.5 KB | 2.2 KB |

Slay the Spire art is dense: about 1.4–1.8 bits per pixel at q50 JPEG. A screen that doesn't change costs nothing, because the capture APIs deliver no frame. The game's idle animations keep a region changing almost all the time, so plan for continuous frames during combat.

| Transport | Usable rate (host→phone) | Source of number | Frames it carries | Extra latency | Setup friction |
|---|---|---|---|---|---|
| HID output report (vendor collection) | ~20–60 KB/s, **unmeasured estimate**; Android's HID-device L2CAP MTU is 512 B on Android 13+ (64 B on Pie) | AOSP `HID_DEV_MTU_SIZE` | JPEG 320: 2–6 fps; H.264 320 fits on average, but each 15 KB keyframe stalls 0.25–0.75 s | Shares the interrupt channel with input reports, so frames delay keystrokes | Descriptor change and re-pair; macOS Input Monitoring; Linux udev rule for hidraw |
| RFCOMM on the existing bond | ~100–250 KB/s practical on EDR, **estimate**; measure with Bumble BtBench | ACL 2–3 Mbit/s air rate | JPEG 480 q≈60: 6–15 fps; H.264 480 at 30 fps uses ~58 KB/s | Same ACL link as HID: bursts can queue ahead of HID reports | None on Windows and Linux; macOS IOBluetooth RFCOMM is shaky |
| Wi-Fi LAN, 5 GHz | >10 MB/s; RTT 2–5 ms with the low-latency Wi-Fi lock | general | Anything: JPEG 480 at 30 fps is ~0.5 MB/s | None on BT if 5 GHz; 2.4 GHz shares the radio by time-division | Same LAN; one-time pairing; Android 17 local-network permission once Bluke targets API 37 |
| USB / `adb reverse` | >20 MB/s | general | Anything | Lowest | USB debugging and a cable: dev only |

End-to-end latency budget, Wi-Fi path, JPEG 480. These are estimates for the lead to measure in phase 0.
- Capture delivers 0–16 ms after the change (vsync-paced).
- GPU downscale: <1 ms.
- JPEG encode: ~1–2 ms.
- Send at 5 GHz: ~2–5 ms.
- Decode on the phone: ~2–5 ms.
- Display: 0–16 ms.

That totals **~10–45 ms**. The touch path, phone → companion → inject, is ~3–8 ms on Wi-Fi, against ~20–30 ms for a HID report with Bluke's keepalive (README). For reference, scrcpy states 35–70 ms for the reverse direction over adb. For a turn-based card game this is far inside what's needed. The finger itself is the ground truth for where the card goes, so frame latency only affects how fresh the picture is, not aim.

## 1. Transport for host→phone frames

**(a) HID vendor report over the existing link: rejected.**
- On the phone side it works. `BluetoothHidDevice` delivers host→device data as `onSetReport` (control channel, `SET_REPORT`) or `onInterruptData` (interrupt channel `DATA`). Bluke already handles both for the LED report (`BluetoothKeyboardManager.kt` around lines 1290–1313). AOSP strips the HIDP header and the report ID and passes the payload up. The device-side L2CAP MTU is `HID_DEV_MTU_SIZE = 512` on Android 13, 14, 15 and main (64 on Pie). So one output report can carry about 510 bytes, and a 10 KB frame takes ~20 reports.
- On Windows, keyboard (0x01/0x06) and mouse (0x01/0x02) top-level collections are opened **exclusively** by the Raw Input Manager. A vendor-defined collection (usage page 0xFF00) is not in that table, so it would get its own openable interface. `WriteFile` is the reliable path; Microsoft warns that `HidD_SetOutputReport` can hang some devices, and the Wiimote community saw the same.
- On macOS, Bluke is one IOHIDDevice whose primary usage is keyboard. Opening it from user space needs the Input Monitoring permission (`kIOReturnNotPermitted` otherwise), even just to reach a vendor collection.
- On Linux, hidraw nodes are root-only without a udev rule.
- The deciding problems:
  - Throughput is an order of magnitude below Wi-Fi and unmeasured. Each host stack writes one report per call.
  - Frames share the interrupt channel with Bluke's input reports, so they delay keystrokes. Bluke just spent a change winning ~400 ms back from sniff mode.
  - It needs a descriptor change (`CURRENT_HID_DESCRIPTOR_REVISION` bump, re-pair on every host) for a feature that needs a companion anyway.

**(b) Second Bluetooth channel: kept as the fallback, RFCOMM only.**
- Android exposes RFCOMM server sockets (`listenUsingRfcommWithServiceRecord`). Its L2CAP connection-oriented channels (`listenUsingL2capChannel`) are LE only, and Windows has no public user-mode LE CoC API, so RFCOMM is the only cross-platform BT option.
- Host clients:
  - Windows: Winsock `AF_BTH` / `BTHPROTO_RFCOMM`, connecting by service UUID.
  - Linux: BlueZ socket, or the `Profile1` D-Bus API to resolve the channel.
  - macOS: `IOBluetoothDevice.openRFCOMMChannelSync`. This is the risk. Developer reports since Monterey describe RFCOMM channels not opening and channel-ID mismatches, and on Sequoia SDP query delegates not firing for third-party apps. I found no confirmation either way for Sonoma or Sequoia talking to Android.
- The upside is that the bond authenticates and encrypts the channel, with no LAN, discovery or pairing code. The downside is that it shares the ACL link and airtime with HID reports.
- Use: when Wi-Fi isn't available, with a 320x180 H.264 profile at ≤60 KB/s. Windows and Linux first; macOS only after a spike proves it.

**(c) Wi-Fi LAN: primary.**
- Bandwidth is two orders of magnitude above need.
- Latency is lowest, provided the phone holds `WifiManager.WIFI_MODE_FULL_LOW_LATENCY` (Android 10+). That lock disables 802.11 power save, which otherwise adds tens to hundreds of ms.
- It adds no load to the BT link when the phone is on 5 GHz. On 2.4 GHz the combo chip time-shares the radio with BT; Logitech and Intel both say moving Wi-Fi to 5 GHz removes the interference. Keep the stream ≤1–4 Mbit/s and tell the user 5 GHz is preferred.
- Costs:
  - Both devices must be on the same LAN. AP or guest isolation breaks it; offer manual IP entry when mDNS fails.
  - A one-time pairing is needed, because the companion becomes an input-injection server and must never accept an unpaired client.
  - `ACCESS_LOCAL_NETWORK` must be declared and requested at runtime once Bluke targets Android 17 (API 37). Bluke targets 36 today, so it is not needed yet. The permission sits in the `NEARBY_DEVICES` group Bluke already requests for Bluetooth.

**(d) USB / adb: dev-only.** It needs USB debugging and a cable, which defeats a wireless touchpad. It is useful in the test harness (`adb forward`) and nowhere else.

**Connection direction.** The phone listens and advertises `_bluke-mirror._tcp` through `NsdManager` only while the Mirror view is visible; `registerService` needs no permission. The companion browses mDNS (the `mdns-sd` crate, pure Rust) and connects out. This has three advantages:
- Outbound connections from the host avoid Windows Defender Firewall's first-listen prompt.
- The phone's visibility drives the lifecycle: no view, no capture, no host CPU.
- The companion can idle as a background process.

Manual fallback: `bluke-mirror --phone 192.168.1.23`, with the phone showing its address in the Mirror panel.

**Pairing and security.**
- Each side generates a long-lived key pair and a self-signed certificate.
- On first contact the TLS 1.3 handshake runs with no trust. Both sides show a six-digit code derived from a hash of both certificate fingerprints (numeric comparison, as Bluetooth Secure Simple Pairing does it), and the user confirms on the phone.
- Afterwards each side pins the other's fingerprint.
- Library cost is zero on Android (platform `SSLSocket` with a custom `X509TrustManager`) and small in Rust (`rustls` with a custom verifier).
- Rejected alternatives:
  - Typing a token over the HID keyboard into the companion's prompt. Bluke sends key positions, not characters, so hex digits come out wrong on layouts such as AZERTY, and the token would land in whatever window has focus.
  - A HID vendor feature report for bootstrap. It needs the same re-pair and Input Monitoring as (a).

**Wire protocol.** Length-prefixed messages over one TLS stream with `TCP_NODELAY`. Over RFCOMM the same stream runs without TLS, since the bond encrypts it.
- `Hello` {version, codecs, max size}.
- `Displays` [{id, name, px w/h, scale, primary}].
- `Frame` {seq, display id, source rect in display px, w, h, codec, keyframe, bytes}.
- `FrameAck` {seq}: credit, at most 2 frames in flight, latest-wins on the host. This is the pattern of `LatestRequestProcessor`, and it means there is never queue bloat.
- `Pointer` {seq, u, v as u16 normalized to the frame's source rect, buttons, phase}.
- `Wheel` {dx, dy}.
- `Config` {display id, max w/h, fps cap, codec}.
- `RequestKeyframe`.
- `Ping`/`Pong`: RTT goes to Developer Logs.

## 2. Host capture and encoding

Each OS's best API delivers frames only on change, and does the downscale on the GPU:

| OS | Capture | Downscale | Notes |
|---|---|---|---|
| macOS 12.3+ | ScreenCaptureKit `SCStream`, set `width`/`height` to the target, `minimumFrameInterval` = 1/30, small `queueDepth`, `showsCursor` false | In the capture pipeline | Needs Screen Recording permission. Idle frames come back with status `.idle`; skip them. Rust: `screencapturekit` crate (or `objc2-screen-capture-kit`). |
| Windows 10 1903+ | Windows.Graphics.Capture (`FrameArrived` on change), cursor capture off; DXGI Desktop Duplication as alternative (gives dirty rects) | D3D11 `VideoProcessorBlt` or a mip/shader pass to 480x270 BGRA, then map | Process must be per-monitor DPI-aware v2. Request `B8G8R8A8` to avoid HDR float surfaces. Rust: `windows-capture` crate or `windows` directly. |
| Linux Wayland | xdg-desktop-portal ScreenCast **combined with RemoteDesktop in one session**, PipeWire stream; `persist_mode=2` and restore token so later sessions skip the dialog (tokens are single-use, so store the new one each time) | PipeWire can negotiate size; else CPU scale (480x270 is cheap) | The same session gives absolute input in stream coordinates (section 3). Rust: `ashpd` (0.14.3, Nov 2026) + `pipewire`. |
| Linux X11 | XShm `XShmGetImage` polled at the fps cap, XDamage to skip unchanged | CPU | Simplest backend. |

The cross-platform crate `xcap` (Apache-2.0, 0.9.10, active Nov 2026) covers Windows (DXGI), macOS (ScreenCaptureKit) and X11, with Wayland partial. `scap` (MIT) is still `0.1.0-beta.1` from Aug 2025. Use `xcap` to get phase 1 running fast if its scaling path is good enough. Write thin per-OS backends behind one `Capture` trait when it isn't, because GPU scaling and change-only delivery are the efficiency wins.

**Encoding.**
- **Phase 1: JPEG**, 480x270, quality ≈60, 4:2:0 (12–26 KB/frame measured). Pure-Rust `jpeg-encoder` or `turbojpeg`.
  - Every frame is independent: no keyframe logic, no decoder quirks, loss-tolerant.
  - It costs ~0.4–0.8 MB/s at 30 fps on Wi-Fi, which is fine.
  - Skip the send when a cheap hash of the scaled frame matches the last one sent.
- **Phase 2: H.264** constrained baseline, no B-frames, 480x272 (pad to the 16-px macroblock and crop), IDR every 2 s plus on `RequestKeyframe`. At this size software encoding is ~1–2 ms/frame, so use **openh264** (`openh264` crate, BSD-2, builds from source; a source build is outside Cisco's binary patent grant, fine for personal use). That gives one code path on all three OSes, no VAAPI, Media Foundation or VideoToolbox per-OS work. Measured 1.9 KB/frame average (5–10x smaller than JPEG), which is what makes the RFCOMM fallback viable and cuts phone radio and CPU use.
- Raw, palettized, or delta+RLE formats were rejected. Even 4-bit grayscale 480x270 is 65 KB a frame, and the idle animations defeat delta coding.

**Language.** Rust: one static binary per OS, no runtime to install, and maintained crates for every piece (capture, `mdns-sd`, `rustls`, `openh264`, `ashpd`, `windows`, `objc2-*`). Python with `mss` and `pyobjc` is fine for the phase 0 spike but a poor thing to distribute. Go needs cgo for ScreenCaptureKit and Windows Graphics Capture (WGC) anyway. License: the companion lives in this repo under AGPL-3.0. Weylus (below) is AGPL-3.0-or-later, so code can be borrowed from it.

## 3. Input mapping

Relative HID mouse reports can't track a finger, because host pointer acceleration breaks the mapping. Two absolute options:

**HID absolute pointer in `SHARED_HID_DESCRIPTOR`: rejected for this feature.**
- Windows: an absolute HID mouse is confined to the **primary monitor**, because `mouhid.sys` doesn't set `MOUSE_VIRTUAL_DESKTOP`, and Microsoft documents that absolute devices are restricted to the primary monitor. A digitizer or touchscreen collection (0x0D/0x04) can be mapped to any display through Tablet PC Settings. But it is opened exclusively, carries touch semantics (press on contact, no hover), and wants Windows' touchscreen feature reports.
- macOS: absolute pointing locks to one display, per Raritan's and TinyPilot's multi-display reports.
- Linux: X maps an absolute pointer across all outputs unless `xinput map-to-output` is set.
- Every host needs the revision bump and a re-pair.
- Clicks would be ordered with moves (same report), which is good. But mapping is wrong whenever the mirrored display isn't the primary or only one, and the companion would still need to tell the phone the layout.

**Companion injection: chosen.** The companion knows the exact rect it captured, so mapping is exact on any display, any arrangement, any DPI. Moves and button presses travel in one ordered stream. That matters for a drag: a button-down sent over HID could overtake the absolute move sent over Wi-Fi and pick up the wrong card. In Mirror mode, every pointer event from the image goes to the companion, and none goes to HID. Per OS:
- **Windows**: `SendInput` with `MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK`, coordinates normalized 0..65535 over the virtual screen (`SM_XVIRTUALSCREEN`… so negative-origin monitors work), then `LEFTDOWN`/`LEFTUP`. UIPI blocks injecting into elevated windows; games normally aren't elevated.
- **macOS**: `CGEventCreateMouseEvent` + `CGEventPost(kCGHIDEventTap)`, in global display points. While the button is held, moves must be `kCGEventLeftMouseDragged`, not `MouseMoved`, or drags don't register. Set the click-state field for double-clicks. Needs the Accessibility / "post event" grant: check `CGPreflightPostEventAccess`, prompt with `CGRequestPostEventAccess`.
- **Wayland**: the RemoteDesktop portal's `NotifyPointerMotionAbsolute(stream, x, y)` takes coordinates *in the captured stream's logical space*, exactly the mapping needed. Buttons go through `NotifyPointerButton` with evdev `BTN_LEFT`. Once `ConnectToEIS` is used, events go only through libei.
- **X11**: XTest `XTestFakeMotionEvent` / `XTestFakeButtonEvent`.
- `enigo` (0.6.1) wraps these, but the calls are short. Write them directly for exact coordinate handling and the macOS drag-event type.

**Multi-monitor.**
- The companion reports `Displays` and starts on the display under the cursor (where the game is).
- The phone offers a display picker.
- Every `Frame` carries `display id` and `source rect`, and the phone maps a touch to (u, v) within *that frame's* rect. Mapping stays right even when phase 3 crops to a game window that moves.

**Touch semantics** (a pure `MirrorTouchPolicy`, tested). The default is **Direct**: finger down presses at that point, moving drags, lifting releases. That is how a card is played in Slay the Spire, which also accepts click-the-card-then-click-the-target. Two options:
- **Hover**: one finger moves the cursor without pressing, so tooltips and enemy intents show; a tap clicks; a second finger held anywhere holds the button.
- A small press delay (~80 ms, under `TouchpadGesturePolicy.TAP_TIMEOUT_MILLIS`) before button-down, so a finger can land and adjust. Measure whether it helps.

Touches outside the letterboxed image are ignored. Two-finger scroll in Mirror mode sends `Wheel` to the companion, keeping one channel. On disconnect or view exit, the companion releases any held button (the equivalent of Bluke's `releaseAllInputs`).

**Keepalive interaction.** Mirror-mode pointer input no longer goes over HID, so `lastInputAt` doesn't refresh and `LinkKeepAlive` lets the BT link sleep after 60 s. The first keystroke after that lands 100–450 ms late. The fix is to count mirror activity as input for `LinkKeepAlive` while the HID host is connected, at the cost of ~25 keepalive reports per second during play. Decide deliberately.

## 4. Prior art

| Project | Direction | Transport / codec | License | Take from it |
|---|---|---|---|---|
| **Weylus** | PC screen → tablet browser, tablet touch/pen → PC | Rust; ffmpeg H.264 in fragmented MP4 over WebSocket to MSE; input over WebSocket; capture XShm, PipeWire+GStreamer, generic on Windows/macOS; injection via uinput (Linux) and generic mouse elsewhere; no encryption by default, optional access code, TLS via proxy | AGPL-3.0+ (compatible with Bluke's AGPL-3.0) | Closest prior art to this feature. Borrow multi-monitor selection and the uinput touch/pen code. Don't copy its "no encryption for latency" choice; TLS costs microseconds at these rates. |
| Deskreen | PC → any browser | Electron, WebRTC, QR/URL to connect, E2E encryption (tweetnacl) | AGPL-3.0 | QR/URL bootstrap pattern; view-only. |
| scrcpy | Android → PC (reverse of ours) | H.264/H.265 over adb socket; 35–70 ms stated | Apache-2.0 | Low-latency MediaCodec configuration on the encoder side; framing. |
| Sunshine / Moonlight | PC → client | RTP/UDP, H.264/HEVC/AV1 hardware encode, FEC | GPL-3.0 | Overkill: built for 60–120 fps HD game streaming. Its injection code (SendInput, CGEvent, uinput/libei) is a good reference. GPLv3 §13 allows combining GPLv3 code with AGPLv3 code, so borrowing is permitted with attribution, though reading is usually enough. |
| spacedesk | PC → device as extra display | Wi-Fi/USB; proprietary; codec unconfirmed (users suspect MJPEG); touch input supported | Proprietary | Confirms touch-on-mirror as a working UX. |
| Duet, Unified Remote, Remote Mouse | Proprietary | Wi-Fi with a host server | Proprietary | Same architecture as recommended: LAN, companion server. |
| KDE Connect | Remote input only, no mirroring | LAN, TLS with pinned certs after pairing | GPL | The TLS-plus-pinned-certificates-after-pairing pattern is exactly the pairing design above. |

Every working "touch your PC's screen from the phone" product uses a LAN connection and a host companion. None uses Bluetooth for frames.

## 5. Phone side

- **Where it sits.** Inside the touchpad's glass surface `Box` (`TouchpadView.kt` ~line 456), the mirror is drawn first, below `TouchGestureLayer` and the numpad overlay. A toolbar toggle beside "Pointer 1.5x" turns Mirror on. When it's on:
  - `TouchGestureLayer` is swapped for a `MirrorGestureLayer` that sends absolute `Pointer` messages.
  - A "Mirror image only" sub-mode keeps the image as a dimmed backdrop with the relative touchpad still active, for "loosely mirror".
  - Mirror state lives in a new `mirror/` package beside `bluetooth/`, with policy in pure files, following the repo's rule that decisions go in testable units.
- **Phase 1, JPEG decode path.** The socket reader runs on its own thread and hands the latest bytes, latest-wins, to one decode thread.
  - `BitmapFactory.decodeByteArray` uses `inBitmap` from a pool of three same-size `RGB_565` bitmaps. That halves memory and upload bandwidth; color fidelity is irrelevant at this size.
  - The decoded bitmap is published through an `AtomicReference` plus a `mutableLongStateOf(seq)` that is read **only inside `drawWithCache`/`drawBehind`**. A new frame then invalidates the draw phase, not recomposition.
  - Draw with `drawImage(bitmap.asImageBitmap(), dstSize = fitted rect, filterQuality = FilterQuality.Low)`. `asImageBitmap()` wraps without copying.
  - A pooled bitmap is reused only after two newer frames were drawn, so the RenderThread never reads a bitmap being decoded.
  - Decode of 480x270 is ~2–5 ms on a mid-range phone (estimate).
  - After `FrameAck`, the decoder thread acks once the frame is decoded, giving end-to-end credit.
- **Phase 2, H.264 path.**
  - `MediaCodec` runs in async mode on a `HandlerThread`. Set `KEY_LOW_LATENCY=1` when the codec advertises `FEATURE_LowLatency` (Android 11+) and `KEY_PRIORITY=0`.
  - The output goes straight to the `Surface` of a Compose `AndroidExternalSurface` (SurfaceView-backed; Bluke's Compose BOM 2025.02 includes it), with `setFixedSize(480, 272)` so the display compositor does the upscale, often in a hardware overlay. No `Bitmap`, no copies, near-zero CPU.
  - Call `releaseOutputBuffer(index, true)` immediately.
  - The SurfaceView sits behind the Compose layer, so gestures and overlays keep working unchanged.
  - Keep the JPEG path as the fallback for decoders that misbehave.
- **Letterboxing.** `MirrorGeometry.fit(frameW, frameH, surfaceW, surfaceH)` gives the image rect. `toNormalized(touch)` returns null outside it. Both are pure and unit-tested, and the same function is used for drawing and mapping, so they can't disagree.
- **Wi-Fi.** Hold `WIFI_MODE_FULL_LOW_LATENCY` only while the Mirror view is visible and connected. Release it on `ON_STOP`, as with the `BluetoothSessionService` visibility rule; the mirror never needs to run in the background.
- **Battery (estimate).** The screen is already on, which dominates (~1–2 W). Added costs:
  - Wi-Fi receiving 0.5 MB/s with power save off: ~100–300 mW.
  - JPEG decode at 20–30 fps: ~5% of a core.
  - H.264 hardware decode: negligible CPU, and 5–10x less radio.
  - Mitigations: 20 fps cap by default, change-only frames, and stop everything when the view hides.

## 6. Testability in this repo

- **Kotlin unit tests (fastTest, no Robolectric):**
  - `MirrorGeometry`: letterbox, outside-image rejection, rounding at edges, orientation.
  - `MirrorTouchPolicy`: Direct and Hover sequences into `Pointer` phases; disconnect mid-drag releases.
  - `MirrorProtocol`: encode/decode of every message, truncated and oversized input rejected at the boundary.
  - `PairingCode`: the six-digit code from two fingerprints.
  - `FrameCredit`: latest-wins, never more than 2 in flight.
- **Rust unit tests (`cargo test`):**
  - Normalized → OS coordinates for each backend's pure mapping function, with layouts including a negative-origin secondary monitor, mixed DPI, and a cropped window rect.
  - Protocol round trip.
  - Change detection.
  - SendInput's 0..65535 virtual-desk math.
- **Shared golden vectors:** protocol messages written out as hex by hand in `companion/vectors/*.json` and read by both test suites. This is the same philosophy as `rig.py`'s hand-written expected bytes, and it catches a Kotlin and a Rust encoder that agree with each other but not with the spec.
- **End-to-end harness:** `tools/mirror-rig/` or a step in `tools/hid-rig/run.sh`.
  - The companion gains `--source synthetic`: a known test pattern whose frame N is a solid color plus a marker at a known spot, on a fake display layout (e.g., 1920x1080 at x = −1920).
  - It also gains `--sink record:<file.jsonl>`: record the OS coordinates it would inject, instead of injecting.
  - The emulator's phone server is reached with `adb forward tcp:47800 tcp:47800` and `--phone 127.0.0.1:47800`; mDNS doesn't cross the emulator NAT.
  - New `RigReceiver` commands: `mirror` (on/off and status: frames decoded, last seq, center pixel color of the last decoded frame, which survives JPEG within ±4), `mirror_touch u v phase`, `mirror_pair` (accept the pairing code in debug, like the rig taps Android's Pair prompt).
  - The check asserts:
    - Frames arrive, with the right colors.
    - A touch at a known (u, v) records the expected virtual-desktop coordinates on the fake secondary monitor.
    - Drag ordering: down, dragged moves, up.
    - Disconnect mid-drag records a release.
  - The HID descriptor doesn't change, so the Bumble checks stay as they are, and `one descriptor across profiles` continues to guard that.
- **Screenshot test:** a Roborazzi baseline of `TouchpadView` in Mirror mode with a fixed decoded frame.
- **Verify routine:** add `cargo test --manifest-path companion/Cargo.toml` (and the mirror rig step) to `.bmesh/routines/verify.toml` and the CLAUDE.md verify line. That is an instruction-file edit, so it goes through the skill-shaper rule.
- **Not provable in the repo:** real-OS capture and injection permissions, and real Wi-Fi/BT coexistence. Those are a manual on-host checklist per OS in phase 1 and 2: run Slay the Spire, play a hand by dragging.

## Phased build plan (one ambitious lead)

**Phase 0: spike, ~1 day, throwaway, decides open numbers.** On the operator's phone and host:
- Phone→host Wi-Fi RTT with and without the low-latency lock.
- Bluke HID input latency (Developer Logs timing) while a 4 Mbit/s stream runs, on 2.4 GHz vs 5 GHz.
- Sustained RFCOMM throughput with HID connected (Bumble BtBench, or a 50-line script), which sizes the fallback.
- Whether `xcap` gives change-only, GPU-scaled frames on the phase 1 host.

**Phase 1: the usable vertical slice, one host OS.** The default host is **macOS**: the lead's machine is a Mac and macOS has the strictest permissions, so it flushes out the hard parts first. Switch to Windows if that's where the operator plays.
- Companion `companion/` (`bluke-mirror`):
  - ScreenCaptureKit capture of the display under the cursor at 480x270, 20 fps cap, change-only.
  - JPEG q≈60.
  - mDNS browse plus `--phone` override; TLS 1.3 with numeric-comparison pairing and pinned keys.
  - CGEvent absolute move, drag and button injection, with release on disconnect.
  - A minimal `.app` bundle with a stable signing identity, so Screen Recording and Accessibility grants survive rebuilds; ad-hoc re-signing changes the code hash and resets TCC.
- Phone: `mirror/` package:
  - `NsdManager` advertise, TLS server, low-latency Wi-Fi lock tied to view visibility.
  - Pure protocol, geometry and touch-policy units.
  - JPEG decode into a pooled bitmap drawn under the touchpad.
  - Mirror toggle (Direct touch default, Hover option), pairing dialog, "companion not found" hint with the phone's IP.
- Tests: all units, golden vectors, the synthetic e2e rig step.
- Done: the operator plays a Slay the Spire hand on the Mac by dragging cards on the phone.
- Size: L, because it adds a second deliverable and a new interface.

**Phase 2: reach and efficiency.**
- Windows backend: WGC, D3D11 downscale, SendInput with `VIRTUALDESK`, DPI-aware v2.
- Linux backends: portal ScreenCast plus RemoteDesktop with restore token, absolute motion in stream coordinates or libei; X11 XShm plus XTest.
- Display picker on the phone.
- H.264 (openh264 on the host; MediaCodec low-latency into `AndroidExternalSurface` on the phone), with JPEG kept as fallback.
- Adaptive fps from the measured RTT and credit stalls.

**Phase 3: polish.**
- Window capture and crop to the game window, for higher effective resolution. Frames already carry the source rect, so mapping follows window moves.
- Pinch-to-zoom: the phone asks the companion for a cropped, sharper region.
- RFCOMM fallback transport: Windows and Linux; macOS only if a spike proves IOBluetooth RFCOMM to Android works on current macOS.
- Optional and separate: a HID absolute or digitizer collection for a no-companion "absolute touchpad" on single-display hosts. It needs a revision bump and re-pair, and is not needed for mirroring.

## Risks

1. **macOS permissions and signing**: Screen Recording, Accessibility, and Local Network for LAN connections on macOS 15+. Grants attach to the signed code identity, or to Terminal for a CLI. Plan the `.app` bundle with a stable identity from day one.
2. **Wi-Fi friction**: AP isolation or guest networks, and mDNS being filtered. Manual IP is the fallback; the RFCOMM transport covers no-LAN in phase 3.
3. **Android 17 `ACCESS_LOCAL_NETWORK`** becomes a runtime prompt once Bluke targets API 37. Blocked TCP connects time out rather than erroring, so surface that state in the Mirror panel.
4. **2.4 GHz coexistence** can add jitter to HID reports. Recommend 5 GHz, keep bitrate low, and measure in phase 0.
5. **Input-ordering bugs** if any pointer path still goes over HID in Mirror mode. Route all image touches through the companion; test the sequence in the rig.
6. **Security**: the companion is a remote-input server. Pairing is mandatory, pinned TLS, LAN-only, injection limited to the mirrored rect, buttons released on disconnect, and nothing listens on the host at all.
7. **Wayland variance**: portal support for combined ScreenCast and RemoteDesktop differs by compositor (good on GNOME and KDE, partial elsewhere). X11 stays supported.
8. **Windows specifics**: DPI virtualization (be per-monitor-v2 aware), HDR capture formats, UIPI against elevated windows, and exclusive-fullscreen titles (WGC/DXGI handle most; Slay the Spire runs borderless).
9. **Keepalive interplay** (section 3): decide whether mirror touches keep the BT link awake.
10. **Maintenance**: a second artifact on three OSes. Keep backends thin behind one trait, put all mapping math in pure tested functions, and add `cargo test` to verify.
11. **Unmeasured numbers**: HID-report and RFCOMM throughput, phone decode times, and battery are estimates. Phase 0 exists to replace them.

## Sources

- AOSP Bluetooth, `HID_DEV_MTU_SIZE` (512 on main, android13/14/15-release; 64 on pie): https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/internal_include/bt_target.h and https://android.googlesource.com/platform/system/bt/+/refs/heads/pie-release/internal_include/bt_target.h
- AOSP HID device connection handling (interrupt DATA strips header, SET_REPORT path): https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/stack/hid/hidd_conn.cc , https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/bta/hd/bta_hd_act.cc
- Windows HID clients and exclusive access table: https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/hid-architecture
- Sending HID reports on Windows (WriteFile vs HidD_SetOutputReport): https://learn.microsoft.com/en-us/windows-hardware/drivers/hid/sending-hid-reports ; Wiimote experience: https://github.com/jloehr/Wiimote-HIDAPI
- macOS Input Monitoring for keyboard-class HID opens: https://developer.apple.com/forums/thread/724608 , https://github.com/libusb/hidapi/discussions/489 , https://developer.apple.com/forums/thread/744577
- Absolute mouse confined to primary monitor on Windows: https://community.osr.com/t/absolute-mouse-data-on-multi-monitors/21698 , https://forum.pjrc.com/threads/25436-Teensy-3-X-absolute-mouse-positioning-stuck-to-primary-monitor-only , https://learn.microsoft.com/en-ie/Windows/Win32/gdi/about-multiple-display-monitors
- Absolute pointer and multi-display on Mac / KVMs: https://help.raritan.com/kx-iii/v3.1.0/en/25345.htm , https://forum.tinypilotkvm.com/-473/mouse-cursor-always-reverts-to-a-single-display-in-a-multi-display-setup , https://docs.pikvm.org/mouse/
- SendInput MOUSEINPUT (ABSOLUTE, VIRTUALDESK): https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-mouseinput
- xdg-desktop-portal RemoteDesktop (NotifyPointerMotionAbsolute in stream space, ConnectToEIS, restore tokens): https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.RemoteDesktop.html
- ScreenCaptureKit configuration and WWDC sessions: https://developer.apple.com/documentation/screencapturekit/scstreamconfiguration , https://developer.apple.com/videos/play/wwdc2022/10156/ , https://developer.apple.com/videos/play/wwdc2022/10155/
- macOS RFCOMM reports: https://developer.apple.com/forums/thread/697032 , https://developer.apple.com/forums/thread/785107 , https://lists.apple.com/archives/bluetooth-dev/2012/Feb/msg00056.html
- Bumble BtBench (RFCOMM/L2CAP throughput on Android): https://google.github.io/bumble/extras/android_bt_bench.html
- Android local network permission (Android 17, ACCESS_LOCAL_NETWORK, NEARBY_DEVICES group): https://developer.android.com/privacy-and-security/local-network-permission
- Android Wi-Fi low-latency mode: https://source.android.com/docs/core/connect/wifi-low-latency
- BT/Wi-Fi coexistence advice: https://hub.sync.logitech.com/mx-anywhere-3/post/lag-and-disconnections-on-bluetooth-devices-can-be-due-to-wifi-interference-PCktmmNFmzvRAry , https://community.intel.com/t5/Wireless/Microsoft-bluetooth-mouse-problems-while-using-wi-fi/m-p/570008
- MediaFormat KEY_LOW_LATENCY: https://developer.android.com/reference/android/media/MediaFormat#KEY_LOW_LATENCY
- Weylus (AGPL-3.0+): https://github.com/H-M-H/Weylus ; Deskreen (AGPL-3.0): https://github.com/pavlobu/deskreen ; scrcpy (Apache-2.0, 35–70 ms): https://github.com/Genymobile/scrcpy ; Sunshine (GPL-3.0): https://github.com/LizardByte/Sunshine
- spacedesk (Wi-Fi/USB, touch): https://www.apkmirror.com/apk/datronicsoft/spacedesk-multi-monitor-display-extension-screen/spacedesk-usb-display-for-pc-2-1-20-release/ ; codec-latency tradeoff write-up: https://dev.to/joduchan/we-switched-simulator-streaming-to-h264-and-it-felt-worse-heres-how-we-fixed-the-latency-pk9
- Rust crates: xcap https://github.com/nashaofu/xcap , scap https://github.com/CapSoftware/scap , ashpd https://crates.io/crates/ashpd , openh264 https://github.com/ralfbiedert/openh264-rs , enigo https://crates.io/crates/enigo
- Slay the Spire screenshots used for frame-size measurement (Steam store API, app 646570): https://store.steampowered.com/api/appdetails?appids=646570
