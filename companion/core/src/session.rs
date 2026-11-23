//! The companion's side of a session connection, without I/O: feed it messages, time and
//! downscaled frames; it returns the messages to send and drives the injector.

use crate::codec::{CODEC_JPEG, Message, VERSION, clip_str};
use crate::frames::{FrameCredit, FrameEncoder, Scaled};
use crate::pairing::Fingerprint;
use crate::pointer::{Buttons, Display, Injector};

/// While a button is held, this much silence releases it.
pub const SILENCE_RELEASE_US: u64 = 1_000_000;

/// The phone's `Welcome` parameters.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Welcome {
    pub codecs: u8,
    pub max_width: u16,
    pub max_height: u16,
    pub fps: u8,
}

impl Welcome {
    /// Time between captures at the phone's frame cap.
    pub fn interval_us(&self) -> u64 {
        1_000_000 / u64::from(self.fps.max(1))
    }
}

/// Why a session closes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Close {
    /// The phone refused (1: not paired with this companion, 2: unsupported version).
    Refused(u8),
    /// The phone speaks another version or offers no codec we send.
    Incompatible,
    /// A message that has no place here, by type.
    Unexpected(u8),
}

impl std::fmt::Display for Close {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Close::Refused(1) => write!(f, "the phone doesn't know this companion; run `bluke-mirror pair`"),
            Close::Refused(r) => write!(f, "the phone refused the session (reason {r})"),
            Close::Incompatible => write!(f, "the phone speaks an incompatible protocol version"),
            Close::Unexpected(t) => write!(f, "unexpected message type 0x{t:02x}"),
        }
    }
}

/// The peer's fingerprint didn't match the pinned one; nothing may be sent.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct NotPinned;

/// Counters and timings for the status line and the rig's summary.
#[derive(Clone, Debug, Default)]
pub struct Stats {
    pub frames_sent: u64,
    pub frames_acked: u64,
    pub frames_dropped_by_phone: u64,
    pub skipped_unchanged: u64,
    pub skipped_no_credit: u64,
    /// Send-to-ack time of every acknowledged frame, µs.
    pub ack_latency_us: Vec<u64>,
}

pub struct Session {
    display: Display,
    welcome: Option<Welcome>,
    buttons: Buttons,
    credit: FrameCredit,
    last_sent_hash: Option<u64>,
    next_seq: u32,
    last_rx_us: u64,
    next_capture_us: u64,
    pub stats: Stats,
}

impl Session {
    /// Starts a session on a connection whose peer presented `peer`. Only a pinned phone gets
    /// a `Hello`; any other peer gets nothing and the connection must close.
    pub fn start(
        name: &str,
        display: Display,
        pinned: Option<Fingerprint>,
        peer: Fingerprint,
        now_us: u64,
    ) -> Result<(Session, Message), NotPinned> {
        if pinned != Some(peer) {
            return Err(NotPinned);
        }
        let s = Session {
            display,
            welcome: None,
            buttons: Buttons::default(),
            credit: FrameCredit::default(),
            last_sent_hash: None,
            next_seq: 0,
            last_rx_us: now_us,
            next_capture_us: 0,
            stats: Stats::default(),
        };
        Ok((s, Message::Hello { version: VERSION, name: clip_str(name) }))
    }

    pub fn welcome(&self) -> Option<Welcome> {
        self.welcome
    }

    pub fn display(&self) -> &Display {
        &self.display
    }

    pub fn in_flight(&self) -> usize {
        self.credit.in_flight()
    }

    pub fn button_held(&self) -> bool {
        self.buttons.held()
    }

    fn display_message(&self) -> Message {
        Message::Display {
            id: self.display.id,
            width: self.display.width.round() as u32,
            height: self.display.height.round() as u32,
            name: clip_str(&self.display.name),
        }
    }

    /// Handles one message from the phone. `Err` means close the connection (then call [`Session::close`]).
    pub fn on_message(&mut self, now_us: u64, m: Message, inj: &mut dyn Injector) -> Result<Vec<Message>, Close> {
        self.last_rx_us = now_us;
        let Some(_) = self.welcome else {
            return match m {
                Message::Welcome { version, codecs, max_width, max_height, fps } => {
                    if version != VERSION || codecs & CODEC_JPEG == 0 {
                        return Err(Close::Incompatible);
                    }
                    self.welcome = Some(Welcome { codecs, max_width, max_height, fps });
                    Ok(vec![self.display_message()])
                }
                Message::Refused { reason } => Err(Close::Refused(reason)),
                Message::Unknown { .. } => Ok(Vec::new()),
                other => Err(Close::Unexpected(other.kind())),
            };
        };
        match m {
            Message::FrameAck { seq, decode_us } => {
                let done = self.credit.ack(seq, now_us);
                self.stats.frames_acked += done.len() as u64;
                if decode_us == crate::codec::ACK_DROPPED {
                    self.stats.frames_dropped_by_phone += 1;
                }
                self.stats.ack_latency_us.extend(done);
                Ok(Vec::new())
            }
            Message::Pointer { x, y, buttons } => {
                self.buttons.pointer(&self.display, x, y, buttons, inj);
                Ok(Vec::new())
            }
            Message::Wheel { dx, dy } => {
                self.buttons.wheel(&self.display, dx, dy, inj);
                Ok(Vec::new())
            }
            Message::Release => {
                self.buttons.release(&self.display, inj);
                Ok(Vec::new())
            }
            Message::Heartbeat | Message::Unknown { .. } => Ok(Vec::new()),
            Message::Ping { t_us } => Ok(vec![Message::Pong { t_us }]),
            other => Err(Close::Unexpected(other.kind())),
        }
    }

    /// True when a capture now could be sent: welcomed, at the frame cap, with credit.
    pub fn wants_frame(&self, now_us: u64) -> bool {
        self.welcome.is_some() && now_us >= self.next_capture_us && self.credit.can_send()
    }

    /// Offers one downscaled capture. Returns the `Frame` to send, or `None` when there is no
    /// `Welcome` yet, no credit (the capture is skipped, never queued), or nothing changed.
    pub fn offer_frame(&mut self, now_us: u64, frame: &Scaled, enc: &mut dyn FrameEncoder) -> Option<Message> {
        let welcome = self.welcome?;
        self.next_capture_us = now_us + welcome.interval_us();
        if !self.credit.can_send() {
            self.stats.skipped_no_credit += 1;
            return None;
        }
        if self.last_sent_hash == Some(frame.hash) {
            self.stats.skipped_unchanged += 1;
            return None;
        }
        let data = enc.encode(frame);
        let seq = self.next_seq;
        self.next_seq = self.next_seq.wrapping_add(1);
        self.credit.sent(seq, now_us);
        self.last_sent_hash = Some(frame.hash);
        self.stats.frames_sent += 1;
        Some(Message::Frame { seq, codec: enc.codec(), width: frame.width, height: frame.height, sent_us: now_us, data })
    }

    /// The mirrored display changed (size, place or identity). Returns the `Display` to send,
    /// once welcomed; the next capture is sent even if its pixels hash the same.
    pub fn set_display(&mut self, display: Display) -> Option<Message> {
        if display == self.display {
            return None;
        }
        self.display = display;
        self.last_sent_hash = None;
        self.welcome.map(|_| self.display_message())
    }

    /// Time passes with no message: after 1 s of silence a held button is released.
    pub fn tick(&mut self, now_us: u64, inj: &mut dyn Injector) {
        if self.buttons.held() && now_us.saturating_sub(self.last_rx_us) >= SILENCE_RELEASE_US {
            self.buttons.release(&self.display, inj);
        }
    }

    /// The connection closed, for any reason: release every held button.
    pub fn close(&mut self, inj: &mut dyn Injector) {
        self.buttons.release(&self.display, inj);
    }
}
