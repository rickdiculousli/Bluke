# bluke-mirror

The host companion for Bluke's Mirror surface: it captures this computer's screen, sends low-resolution JPEG frames to the phone over TLS on the local network, and injects the touches the phone sends back as mouse events. The wire protocol is [PROTOCOL.md](PROTOCOL.md). This slice runs on macOS only.

## Use

```bash
cargo build --release -p bluke-mirror        # the pinned toolchain is in rust-toolchain.toml
./target/release/bluke-mirror                 # with Mirror open on the phone; mirror, reconnecting whenever Mirror is opened again
```

- On the phone, Mirror is off by default: open any input surface, long-press its mode button to open Settings › Input modes, tick Mirror, then cycle to it (or tap Launch Mirror on the home screen).
- The first run, with no phone paired yet, pairs first: type the six-digit code the phone shows, and mirroring starts. `bluke-mirror pair` pairs again (another phone, or after Forget <computer name> on the phone).
- Without `--phone <ip>[:port]` the phone is found over mDNS; Mirror on the phone shows its address for when that fails (guest Wi-Fi with client isolation blocks the link either way).
- macOS asks for two grants, both given to the terminal app that runs `bluke-mirror`: System Settings › Privacy & Security › Screen Recording, and › Accessibility. `bluke-mirror` checks both before pairing or mirroring; for a missing one it asks macOS to list the terminal in that pane (macOS shows its own prompt), opens the pane, and says to turn it on, then quit and reopen the terminal. A rebuilt unsigned binary can lose them.
- Capture is ScreenCaptureKit (macOS 14+): one screenshot per poll at the phone's frame cap (a touch brings the next one forward, never closer than the cap allows), scaled on the GPU to the size the phone asked for. If ScreenCaptureKit is missing or fails, `bluke-mirror` says why and captures through xcap (CGWindowListCreateImage) instead: full resolution, scaled on the CPU. The connect line and the status line every 5 s name the path in use; the status line also gives the median and longest grab time.
- State lives in `~/Library/Application Support/bluke-mirror/` (`$XDG_CONFIG_HOME/bluke-mirror/` elsewhere). Deleting `paired-phone` there forgets the phone.

## Layout

- `core/` (`bluke-mirror-core`): the protocol codec, SPAKE2 pairing, pointer mapping, frame credit and the session logic, with no I/O and no platform crate; time, randomness, captures and sockets come from the caller.
- `mirror/` (`bluke-mirror`): the TLS link, mDNS, config files, the macOS backend (`src/platform/macos.rs`: ScreenCaptureKit through the `objc2-screen-capture-kit` bindings, xcap as the fallback, CGEvent injection) and the CLI.
- `mirror/examples/rig.rs`: the companion side of the Android mirror rig (synthetic screen, recording injector); needs `--features rig`. Its JSON summary gives frame send-to-ack, pointer read-to-inject, frame capture-start-to-sent and touch-to-capture (`input_to_capture_ms`) times.
- The phone's side of pairing in `core` (`pairing::PhonePairing`) exists only under feature `rig`, to test the companion against; the shipped binary doesn't carry it.

## Check

```bash
cargo test --workspace --features bluke-mirror/rig             # vectors, pairing, session logic, and a real-TLS test phone
cargo clippy --workspace --all-targets --features bluke-mirror/rig
tools/check-macos.sh                      # type-checks the macOS backend from Linux (zig as the C cross compiler)
```
