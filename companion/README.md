# bluke-mirror

The host companion for Bluke's Mirror surface: it captures this computer's screen, sends low-resolution JPEG frames to the phone over TLS on the local network, and injects the touches the phone sends back as mouse events. The wire protocol is [PROTOCOL.md](PROTOCOL.md). This slice runs on macOS only.

## Use

```bash
cargo build --release -p bluke-mirror        # the pinned toolchain is in rust-toolchain.toml
./target/release/bluke-mirror                 # with Mirror open on the phone; mirror, reconnecting whenever Mirror is opened again
```

- The first run, with no phone paired yet, pairs first: type the six-digit code the phone shows, and mirroring starts. `bluke-mirror pair` pairs again (another phone, or after Forget companion on the phone).
- Without `--phone <ip>[:port]` the phone is found over mDNS; Mirror on the phone shows its address for when that fails (guest Wi-Fi with client isolation blocks the link either way).
- macOS asks for two grants, both given to the terminal app that runs `bluke-mirror`: System Settings › Privacy & Security › Screen Recording, and › Accessibility. `bluke-mirror` checks both before pairing or mirroring and says which is missing; after granting, quit and reopen the terminal. A rebuilt unsigned binary can lose them.
- State lives in `~/Library/Application Support/bluke-mirror/` (`$XDG_CONFIG_HOME/bluke-mirror/` elsewhere). Deleting `paired-phone` there forgets the phone.

## Layout

- `core/` (`bluke-mirror-core`): the protocol codec, SPAKE2 pairing, pointer mapping, frame credit and the session logic, with no I/O and no platform crate; time, randomness, captures and sockets come from the caller.
- `mirror/` (`bluke-mirror`): the TLS link, mDNS, config files, the macOS backend (`src/platform/macos.rs`) and the CLI.
- `mirror/examples/rig.rs`: the companion side of the Android mirror rig (synthetic screen, recording injector); needs `--features rig`. Its JSON summary gives frame send-to-ack, pointer read-to-inject and frame capture-start-to-sent times.
- The phone's side of pairing in `core` (`pairing::PhonePairing`) exists only under feature `rig`, to test the companion against; the shipped binary doesn't carry it.

## Check

```bash
cargo test --workspace --features bluke-mirror/rig             # vectors, pairing, session logic, and a real-TLS test phone
cargo clippy --workspace --all-targets --features bluke-mirror/rig
tools/check-macos.sh                      # type-checks the macOS backend from Linux (zig as the C cross compiler)
```
