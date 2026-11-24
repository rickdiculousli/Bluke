# Bluke Mirror protocol, version 1

Bluke (the phone) shows a low-resolution copy of a computer's screen and sends touches back; `bluke-mirror` (the companion, on the computer) captures the screen and injects the touches. This file is the protocol's one definition. Its byte vectors are [`vectors/protocol.json`](vectors/protocol.json) (messages) and [`vectors/spake2.json`](vectors/spake2.json) (pairing), and the Kotlin tests (`app/src/test/.../mirror/`) and the Rust tests (`companion/core`) both check them.

## Transport

- The phone listens on TCP port **47800** on its Wi-Fi interface's address only, and only while the Mirror surface is visible. If the port is taken it listens on any free port and advertises that one.
- It advertises the service `_bluke-mirror._tcp` over mDNS with TXT records `v=1` and `id=<first 16 hex digits of its certificate fingerprint>`, so a companion can find the phone it paired with among several.
- The companion connects out: to an mDNS result, or to `--phone <ip>[:port]`.
- TCP_NODELAY on both ends. One connection carries one TLS 1.3 session.

## TLS and identity

- Each side has a long-lived key pair and a self-signed X.509 certificate. Its **fingerprint** is SHA-256 of the certificate's DER bytes.
- TLS 1.3 only. The companion is the TLS client, the phone the TLS server, and the phone requires a client certificate.
- During the handshake both sides accept any certificate, but the TLS library still checks the CertificateVerify signature against the presented certificate, so a peer can only present a certificate whose private key it holds.
- Right after the handshake, before reading or writing any message, each side compares the peer's fingerprint with the one it pinned:
  - The companion, holding a pinned phone, closes the connection on a mismatch. It sends `Hello` only to its pinned phone, and `PairRequest` only on a local action by its user: running `bluke-mirror pair`, or plain `bluke-mirror` with no phone paired yet. Either way the user then types the phone's code at the companion.
  - The phone answers a `Hello` from a companion it hasn't pinned with `Refused(1)` and closes.
- Until the peer's pinned fingerprint is verified on a connection, only the pairing messages (types 1-7) are accepted or sent. Any other message on an unverified connection closes it.

## Framing

```
u32 length (big-endian) | u8 type | body (length - 1 bytes)
```

- `length` counts the type byte and the body. It must be 1..=262144 (256 KiB). A reader checks it before it allocates or reads the body: 0 is `empty`, above the cap is `too_large`, and both close the connection.
- All integers are big-endian. `str` is `u8 byte-length | UTF-8 bytes`, at most 64 bytes.
- A body must be exactly as long as its fields (a `malformed` message closes the connection), except the variable tail of `Frame`.
- An unknown type within the cap decodes as Unknown. A session skips it; a pairing connection closes.

## Pairing

A pairing connection is one PAKE run, after which both sides close; the companion then reconnects with `Hello`.

| Type | Name | Direction | Body |
|---|---|---|---|
| 0x01 | PairRequest | C→P | `name: str` |
| 0x02 | PairCode | P→C | `attempts_left: u8` |
| 0x03 | PakeShare | C→P | `pA: [65]` |
| 0x04 | PakeReply | P→C | `pB: [65]`, `cB: [32]` |
| 0x05 | PakeConfirm | C→P | `cA: [32]` |
| 0x06 | PairResult | P→C | `status: u8`: 0 paired, 1 wrong code, 2 locked, 3 unavailable |
| 0x07 | Refused | P→C | `reason: u8`: 1 not paired with this companion, 2 unsupported version |

The run:
1. The companion sends `PairRequest` only after a local action by its user: `bluke-mirror pair`, or plain `bluke-mirror` with no phone paired yet.
2. If the phone can take an attempt, it draws a fresh code, shows it, and answers `PairCode(attempts_left)`. Otherwise it answers `PairResult(2)` when its attempts are used up, or `PairResult(3)` when it is busy with another pairing or within 60 s of the user declining one, and closes.
3. The user types the code at the companion, which sends `PakeShare`.
4. The phone answers `PakeReply`.
5. The companion checks `cB`. If it fails, the companion pins nothing, tells its user the code was wrong, and closes.
6. Otherwise the companion sends `PakeConfirm`.
7. The phone checks `cA`. If it passes, the phone pins the companion's fingerprint and sends `PairResult(0)`; the companion pins the phone's fingerprint only when it reads that `PairResult(0)`. If it fails, the phone answers `PairResult(1)` (or `2` if that was the last attempt) and pins nothing.

Attempts:
- The code is six decimal digits, drawn uniformly from 000000-999999 with a secure random source for each `PairRequest`.
- Every attempt that received a `PakeShare` and doesn't end in `PairResult(0)` discards its code and uses one of the phone's **3 attempts per Mirror session**. That covers a bad `cA`, a malformed message, and a close before `PakeConfirm`.
- A code that hasn't received a `PakeShare` within 120 s is discarded without using an attempt.
- A Mirror session lasts from the surface becoming visible to its leaving the screen.

The PAKE is SPAKE2 from RFC 9382 with the suite **P256-SHA256-HKDF-HMAC** and its standard M and N:
- **A** is the companion and **B** the phone. The identities are the raw 32-byte fingerprints: A is the companion's certificate fingerprint and B the phone's, each side taking both from its own TLS session. A man in the middle terminates two TLS sessions with its own certificate, so the two sides disagree on A or B, and the confirmation MACs fail.
- **w** = SHA-512(`"bluke-mirror pairing v1"` ‖ 0x00 ‖ the six ASCII digits), read as a big-endian integer, mod the group order n. Encoded in TT as 32 bytes, big-endian.
- **AAD** is the ASCII string `bluke-mirror v1`.
- `pA = x·G + w·M`, `pB = y·G + w·N`, points as 65-byte uncompressed SEC1. A received point must decode to a point on the curve that isn't the identity, and the computed K must not be the identity; otherwise the attempt fails.
- `TT`, `Ke ‖ Ka = SHA-256(TT)`, `KcA ‖ KcB = HKDF-SHA256(salt = empty, ikm = Ka, info = "ConfirmationKeys" ‖ AAD, 32)`, `cA = HMAC-SHA256(KcA, TT)`, `cB = HMAC-SHA256(KcB, TT)`, all exactly as RFC 9382 defines them.
- MACs are compared in constant time. Ke is unused: TLS already encrypts.

## Session

| Type | Name | Direction | Body |
|---|---|---|---|
| 0x10 | Hello | C→P | `version: u16` (1), `name: str` |
| 0x11 | Welcome | P→C | `version: u16`, `codecs: u8` (bit 0 JPEG), `max_width: u16`, `max_height: u16`, `fps: u8` |
| 0x12 | Display | C→P | `id: u32`, `width: u32`, `height: u32`, `name: str` |
| 0x13 | Frame | C→P | `seq: u32`, `codec: u8`, `width: u16`, `height: u16`, `sent_us: u64`, `data: [rest, ≥1 byte]` |
| 0x14 | FrameAck | P→C | `seq: u32`, `decode_us: u32` (0xFFFFFFFF: dropped undecoded) |
| 0x20 | Pointer | P→C | `x: u16`, `y: u16`, `buttons: u8` (bit 0 left) |
| 0x21 | Wheel | P→C | `dx: i16`, `dy: i16` |
| 0x22 | Heartbeat | P→C | empty |
| 0x23 | Release | P→C | empty |
| 0x30 | Ping | P→C | `t_us: u64` |
| 0x31 | Pong | C→P | `t_us: u64` (echoed) |

- **Start.** The companion sends `Hello`. The phone answers `Welcome` (defaults: JPEG, 480x480, 20 fps), or `Refused` and closes. The companion then sends `Display` for the display it mirrors, at start and whenever its size changes. `width` and `height` are the display's size in its own coordinate units (points on macOS).
- **Frames.**
  - The companion polls its capture at most `fps` times a second. It downscales, keeping the aspect ratio, to fit `max_width` x `max_height`, and sends a frame only when a hash of the downscaled pixels differs from the last frame sent.
  - Codec 1 is baseline JPEG. Other values are reserved, H.264 among them.
  - `seq` increases by one per frame sent. `sent_us` is the companion's monotonic clock, echoed nowhere: the companion times a frame from send to its `FrameAck`.
- **Credit.**
  - At most **2** frames are in flight: sent and not yet acknowledged.
  - `FrameAck(seq)` acknowledges every frame up to and including `seq`.
  - With no credit, the companion skips that capture rather than queueing it.
  - The phone acknowledges a frame when it has decoded it, or with `decode_us = 0xFFFFFFFF` when a newer frame replaced it before it was decoded.
- **Pointer.**
  - `x` and `y` are normalised to the mirrored display: 0 is the left or top edge, 65535 the right or bottom edge. The companion maps them to `origin + v / 65535 × (size − 1)` on each axis and clamps the result to the display's rect.
  - `buttons` is the full button state. The companion turns changes into events:
    - up→up is a move;
    - up→down is a move, then a press;
    - down→down is a drag (on macOS a `LeftMouseDragged` event);
    - down→up is a drag to the point if it moved, then a release.
  - Only the left button exists. Keys are never injected.
- **Wheel.** `dx` and `dy` are pixels, with the sign of a macOS `CGEvent` scroll and a Windows wheel delta: positive `dy` is the wheel turned up, so the view moves toward the top of the document; positive `dx` moves it toward the left.
- **Release.** The companion releases every held button and forgets the button state when:
  - the connection closes, for any reason;
  - it reads `Release` (the phone sends it from the surface's `ON_STOP`);
  - while a button is held, no `Pointer`, `Wheel`, `Heartbeat`, `Release` or `Ping` has arrived for **1 s**. These come from the phone's main thread; a `FrameAck` comes from its decoder and doesn't count, so a phone whose main thread hangs with a finger down is released even while frames still flow. While a button is held, the phone sends `Heartbeat` whenever it has sent none of those five for **250 ms** (a `FrameAck` sent meanwhile doesn't reset that wait).
- **Ping.** The phone sends `Ping` once a second and shows the median round trip of the last five `Pong`s as link latency.
- Moves, button changes, wheel turns and releases all travel in this one ordered stream, so a press can never overtake the move before it.

## Files

The companion keeps its state in its config directory: `~/Library/Application Support/bluke-mirror/` on macOS, `$XDG_CONFIG_HOME/bluke-mirror/` (default `~/.config/bluke-mirror/`) elsewhere.
- `identity.key` and `identity.crt`: its key pair and certificate, made on first run.
- `paired-phone`: the pinned phone's fingerprint, as hex. **Deleting this file forgets the phone.**

The phone keeps its key in the Android Keystore and the pinned companion's fingerprint and its computer's name in its preferences. Mirror's menu forgets the computer: Forget <computer name>.
