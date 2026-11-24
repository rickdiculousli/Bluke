//! The core of `bluke-mirror`, Bluke's host companion: the wire protocol, SPAKE2 pairing,
//! pointer mapping, frame credit and the session logic. No platform code and no I/O;
//! time, randomness, captures and sockets come in from the caller.

pub mod codec;
pub mod frames;
pub mod pairing;
pub mod pointer;
pub mod session;
pub mod spake2;
#[cfg(feature = "rig")]
pub mod synthetic;

pub use codec::{DecodeError, Message};
pub use frames::{Capture, FrameEncoder, Image, Scaled};
pub use pairing::Fingerprint;
pub use pointer::{Display, Injector, InputEvent};
