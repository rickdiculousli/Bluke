# bluke-mirror

The host companion for Bluke's Mirror surface: it captures this computer's screen, sends low-resolution JPEG frames to the phone over TLS on the local network, and injects the touches the phone sends back as mouse events. The wire protocol is [PROTOCOL.md](PROTOCOL.md). This slice runs on macOS only.

## Use

```bash
cargo build --release -p bluke-mirror        # the pinned toolchain is in rust-toolchain.toml
./target/release/bluke-mirror pair            # with Mirror open on the phone; type the code it shows
./target/release/bluke-mirror                 # mirror; reconnects whenever Mirror is opened again
```

- Without `--phone <ip>[:port]` the phone is found over mDNS; Mirror on the phone shows its address for when that fails (guest Wi-Fi with client isolation blocks the link either way).
- macOS asks for two grants, both given to the terminal app that runs `bluke-mirror`: System Settings › Privacy & Security › Screen Recording, and › Accessibility. `bluke-mirror` says which is missing. A rebuilt unsigned binary can lose them.
- State lives in `~/Library/Application Support/bluke-mirror/` (`$XDG_CONFIG_HOME/bluke-mirror/` elsewhere). Deleting `paired-phone` there forgets the phone.

## Layout

- `core/` (`bluke-mirror-core`): the protocol codec, SPAKE2 pairing, pointer mapping, frame credit and the session logic, with no I/O and no platform crate; time, randomness, captures and sockets come from the caller.
- `mirror/` (`bluke-mirror`): the TLS link, mDNS, config files, the macOS backend (`src/platform/macos.rs`) and the CLI.
- `mirror/examples/rig.rs`: the companion side of the Android mirror rig (synthetic screen, recording injector); needs `--features rig`.

## Check

```bash
cargo test --workspace                    # vectors, pairing, session logic, and a real-TLS test phone
cargo clippy --workspace --all-targets
tools/check-macos.sh                      # type-checks the macOS backend from Linux (zig as the C cross compiler)
```
