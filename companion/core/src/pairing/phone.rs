//! The phone's side of pairing, for tests and the rig only: the real phone is Bluke's Kotlin.

use super::Fingerprint;
use crate::codec::{Message, pair_status};
use crate::spake2::{AAD, Keys, Role, Spake2, code_to_w};

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
