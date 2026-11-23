//! The pairing run of PROTOCOL.md, for both ends: the companion (SPAKE2 role A)
//! and the phone (role B). Each side's identities come from its own TLS session.

use crate::codec::{Message, clip_str, pair_status};
use crate::spake2::{AAD, Keys, PakeError, Role, Spake2, code_to_w};

/// SHA-256 of a certificate's DER bytes.
pub type Fingerprint = [u8; 32];

/// How many attempts the phone allows per Mirror session.
pub const ATTEMPTS_PER_SESSION: u8 = 3;
/// A code with no `PakeShare` after this long is discarded without using an attempt.
pub const CODE_LIFETIME_US: u64 = 120_000_000;

/// Draws a six-digit code uniformly from 000000-999999. `fill` must be a secure random source.
pub fn draw_code(fill: &mut dyn FnMut(&mut [u8])) -> String {
    // Rejection sampling over u32 keeps every code equally likely.
    const LIMIT: u32 = u32::MAX - (u32::MAX % 1_000_000);
    loop {
        let mut b = [0u8; 4];
        fill(&mut b);
        let v = u32::from_be_bytes(b);
        if v < LIMIT {
            return format!("{:06}", v % 1_000_000);
        }
    }
}

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
    /// The phone is busy with another pairing.
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
            PairFailure::Unavailable => write!(f, "the phone is busy with another pairing"),
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
    AwaitReply(Spake2),
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
        self.state = CState::AwaitReply(pake);
        Ok(Message::PakeShare { p_a: share })
    }
}

/// What the phone does after one input.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct PhoneOut {
    pub send: Vec<Message>,
    /// Close the connection after sending.
    pub close: bool,
    /// Pin this companion fingerprint.
    pub pin: Option<Fingerprint>,
}

struct Attempt {
    code: String,
    issued_us: u64,
    companion: Fingerprint,
    phone: Fingerprint,
    keys: Option<Keys>,
    share_received: bool,
}

/// The phone's side of pairing across one Mirror session: its attempt budget and the
/// pairing connection in progress, if any.
pub struct PhonePairing {
    attempts_left: u8,
    current: Option<Attempt>,
}

impl Default for PhonePairing {
    fn default() -> Self {
        PhonePairing { attempts_left: ATTEMPTS_PER_SESSION, current: None }
    }
}

impl PhonePairing {
    /// A new Mirror session: 3 attempts, nothing in progress.
    pub fn new() -> PhonePairing {
        PhonePairing::default()
    }

    pub fn attempts_left(&self) -> u8 {
        self.attempts_left
    }

    /// The code on screen, while a run is waiting for it.
    pub fn code(&self) -> Option<&str> {
        self.current.as_ref().map(|a| a.code.as_str())
    }

    /// Handles a `PairRequest` on a connection whose peer presented `companion`; `phone` is
    /// the phone's own fingerprint. `code` is freshly drawn with [`draw_code`].
    pub fn on_pair_request(&mut self, now_us: u64, code: String, companion: Fingerprint, phone: Fingerprint) -> PhoneOut {
        self.expire(now_us);
        if self.attempts_left == 0 {
            return closing(Message::PairResult { status: pair_status::LOCKED });
        }
        if self.current.is_some() {
            return closing(Message::PairResult { status: pair_status::UNAVAILABLE });
        }
        self.current = Some(Attempt { code, issued_us: now_us, companion, phone, keys: None, share_received: false });
        PhoneOut { send: vec![Message::PairCode { attempts_left: self.attempts_left }], ..PhoneOut::default() }
    }

    /// Handles any other message on the pairing connection. `secret` is 32 secure random bytes for y.
    pub fn on_message(&mut self, now_us: u64, m: Message, secret: &[u8; 32]) -> PhoneOut {
        self.expire(now_us);
        let Some(mut attempt) = self.current.take() else { return PhoneOut { close: true, ..PhoneOut::default() } };
        match (m, attempt.keys.take()) {
            (Message::PakeShare { p_a }, None) if !attempt.share_received => {
                attempt.share_received = true;
                let pake = Spake2::new(Role::B, &code_to_w(&attempt.code), secret, &attempt.companion, &attempt.phone, AAD);
                let p_b = pake.share();
                match pake.finish(&p_a) {
                    Ok(keys) => {
                        let c_b = keys.own_confirmation();
                        attempt.keys = Some(keys);
                        self.current = Some(attempt);
                        PhoneOut { send: vec![Message::PakeReply { p_b, c_b }], ..PhoneOut::default() }
                    }
                    Err(_) => self.fail(attempt),
                }
            }
            (Message::PakeConfirm { c_a }, Some(keys)) if keys.verify_peer(&c_a).is_ok() => {
                PhoneOut { send: vec![Message::PairResult { status: pair_status::PAIRED }], close: true, pin: Some(attempt.companion) }
            }
            _ => self.fail(attempt),
        }
    }

    /// The pairing connection closed. A run that got a `PakeShare` and didn't pair uses an attempt.
    pub fn on_close(&mut self) {
        if let Some(a) = self.current.take()
            && a.share_received
        {
            self.attempts_left = self.attempts_left.saturating_sub(1);
        }
    }

    fn expire(&mut self, now_us: u64) {
        if let Some(a) = &self.current
            && !a.share_received
            && now_us.saturating_sub(a.issued_us) >= CODE_LIFETIME_US
        {
            self.current = None;
        }
    }

    fn fail(&mut self, attempt: Attempt) -> PhoneOut {
        if attempt.share_received {
            self.attempts_left = self.attempts_left.saturating_sub(1);
        }
        let status = if self.attempts_left == 0 { pair_status::LOCKED } else { pair_status::WRONG_CODE };
        closing(Message::PairResult { status })
    }
}

fn closing(m: Message) -> PhoneOut {
    PhoneOut { send: vec![m], close: true, pin: None }
}
