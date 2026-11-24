//! The pairing run of PROTOCOL.md, for both ends: the companion (SPAKE2 role A)
//! and the phone (role B). Each side's identities come from its own TLS session.
//! The phone's side exists only to test the companion against (feature `rig`); the shipped
//! companion carries just its own.

use crate::codec::{Message, clip_str, pair_status};
use crate::spake2::{AAD, PakeError, Role, Spake2, code_to_w};

#[cfg(feature = "rig")]
mod phone;
#[cfg(feature = "rig")]
pub use phone::*;

/// SHA-256 of a certificate's DER bytes.
pub type Fingerprint = [u8; 32];

/// True for exactly six ASCII digits.
pub fn is_valid_code(code: &str) -> bool {
    code.len() == 6 && code.bytes().all(|c| c.is_ascii_digit())
}

/// How a companion's pairing run ended without pinning anything.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PairFailure {
    /// The phone's MAC didn't verify: the code was wrong, or someone is relaying.
    WrongCode,
    /// The phone said the code was wrong.
    PhoneRejected,
    /// The phone has used its attempts for this Mirror session.
    Locked,
    /// The phone is busy with another pairing, or a request was declined in the last 60 s.
    Unavailable,
    /// The phone refused with this reason.
    Refused(u8),
    /// The phone's share wasn't a valid point.
    BadPoint,
    /// A message that doesn't belong at this step.
    Unexpected(u8),
}

impl std::fmt::Display for PairFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PairFailure::WrongCode => write!(f, "the code was wrong (nothing was pinned)"),
            PairFailure::PhoneRejected => write!(f, "the phone rejected the code (nothing was pinned)"),
            PairFailure::Locked => write!(f, "the phone has used its 3 attempts; close and reopen Mirror on the phone"),
            PairFailure::Unavailable => write!(
                f,
                "the phone isn't taking a pairing right now (another one is in progress, or one was just declined); try again in a minute"
            ),
            PairFailure::Refused(r) => write!(f, "the phone refused the connection (reason {r})"),
            PairFailure::BadPoint => write!(f, "the phone sent an invalid pairing share"),
            PairFailure::Unexpected(t) => write!(f, "unexpected message type 0x{t:02x} during pairing"),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CompanionStep {
    /// Send this message to the phone.
    Send(Message),
    /// The phone shows a code; ask the user for it and call [`CompanionPairing::enter_code`].
    NeedCode { attempts_left: u8 },
    /// Paired: pin this phone fingerprint, then close and reconnect with `Hello`.
    Paired(Fingerprint),
}

enum CState {
    AwaitCode,
    NeedCode,
    AwaitReply(Box<Spake2>),
    AwaitResult,
    Done,
}

/// The companion's side of one pairing connection.
pub struct CompanionPairing {
    own: Fingerprint,
    phone: Fingerprint,
    state: CState,
}

impl CompanionPairing {
    /// Starts a pairing run on a fresh TLS connection; the returned `PairRequest` goes first.
    /// Call this only from a local action by the companion's user.
    pub fn start(own: Fingerprint, phone: Fingerprint, name: &str) -> (CompanionPairing, Message) {
        let p = CompanionPairing { own, phone, state: CState::AwaitCode };
        (p, Message::PairRequest { name: clip_str(name) })
    }

    /// Feeds one message from the phone. An `Err` ends the run with nothing pinned.
    pub fn on_message(&mut self, m: Message) -> Result<CompanionStep, PairFailure> {
        let state = std::mem::replace(&mut self.state, CState::Done);
        match (state, m) {
            (CState::AwaitCode, Message::PairCode { attempts_left }) => {
                self.state = CState::NeedCode;
                Ok(CompanionStep::NeedCode { attempts_left })
            }
            (CState::AwaitReply(pake), Message::PakeReply { p_b, c_b }) => {
                let keys = pake.finish(&p_b).map_err(|e| match e {
                    PakeError::BadPoint => PairFailure::BadPoint,
                    PakeError::BadConfirmation => PairFailure::WrongCode,
                })?;
                keys.verify_peer(&c_b).map_err(|_| PairFailure::WrongCode)?;
                self.state = CState::AwaitResult;
                Ok(CompanionStep::Send(Message::PakeConfirm { c_a: keys.own_confirmation() }))
            }
            (CState::AwaitResult, Message::PairResult { status: pair_status::PAIRED }) => Ok(CompanionStep::Paired(self.phone)),
            (_, Message::PairResult { status }) => Err(match status {
                pair_status::LOCKED => PairFailure::Locked,
                pair_status::UNAVAILABLE => PairFailure::Unavailable,
                _ => PairFailure::PhoneRejected,
            }),
            (_, Message::Refused { reason }) => Err(PairFailure::Refused(reason)),
            (_, other) => Err(PairFailure::Unexpected(other.kind())),
        }
    }

    /// The user typed the code. `secret` is 32 secure random bytes for x.
    /// Returns the `PakeShare` to send.
    pub fn enter_code(&mut self, code: &str, secret: &[u8; 32]) -> Result<Message, PairFailure> {
        if !matches!(self.state, CState::NeedCode) {
            return Err(PairFailure::Unexpected(0));
        }
        let pake = Spake2::new(Role::A, &code_to_w(code), secret, &self.own, &self.phone, AAD);
        let share = pake.share();
        self.state = CState::AwaitReply(Box::new(pake));
        Ok(Message::PakeShare { p_a: share })
    }
}
