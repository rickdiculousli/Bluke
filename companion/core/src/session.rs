//! The companion's side of a session connection, without I/O: feed it messages, time and
//! downscaled frames; it returns the messages to send and drives the injector.

use crate::codec::{CODEC_JPEG, Message, VERSION, clip_str};
use crate::frames::{FrameCredit, Prepared};
use crate::pairing::Fingerprint;
use crate::pointer::{Buttons, Display, Injector};

/// While a button is held, this much silence releases it. Only the phone's main-thread traffic
/// (Pointer, Wheel, Heartbeat, Release, Ping) counts: `FrameAck`s come from its decoder thread,
/// so they keep flowing while a hung main thread can't lift the finger.
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

/// Whether `m` shows the phone's main thread is alive: what it sends from touches and its tick.
fn is_live(m: &Message) -> bool {
    matches!(m, Message::Pointer { .. } | Message::Wheel { .. } | Message::Heartbeat | Message::Release | Message::Ping { .. })
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
    /// Capture-start-to-sent time of every frame sent (grab, downscale, encode, wait), µs.
    pub capture_to_sent_us: Vec<u64>,
    /// For each touch that a capture then showed: time from the first Pointer or Wheel not yet
    /// captured to the start of the capture that showed it, µs.
    pub input_to_capture_us: Vec<u64>,
}

pub struct Session {
    display: Display,
    welcome: Option<Welcome>,
    buttons: Buttons,
    credit: FrameCredit,
    last_sent_hash: Option<u64>,
    next_seq: u32,
    /// When the phone's main thread last spoke: the clock of the held-button silence rule.
    last_live_us: u64,
    /// When the next poll is due: one frame interval after the last capture was offered.
    next_capture_us: u64,
    /// When the last capture counted against the frame cap started.
    last_capture_start_us: Option<u64>,
    /// When the first Pointer or Wheel that no capture has shown yet was injected.
    input_since_us: Option<u64>,
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
            last_live_us: now_us,
            next_capture_us: 0,
            last_capture_start_us: None,
            input_since_us: None,
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
        if is_live(&m) {
            self.last_live_us = now_us;
        }
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
                self.input_since_us.get_or_insert(now_us);
                Ok(Vec::new())
            }
            Message::Wheel { dx, dy } => {
                self.buttons.wheel(&self.display, dx, dy, inj);
                self.input_since_us.get_or_insert(now_us);
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

    /// When a capture could next be sent, if one could at all now: welcomed and with credit.
    /// Without credit it waits for a `FrameAck`, not for time.
    ///
    /// Polls come one frame interval after the last capture was offered. A Pointer or Wheel that
    /// no capture has shown yet pulls the next capture to the earliest the frame cap allows: one
    /// interval after the last capture *started*, so capture starts are never closer than one
    /// interval (at most `fps` a second) and the grab and encode time isn't waited twice.
    pub fn next_frame_at(&self) -> Option<u64> {
        let welcome = self.welcome?;
        if !self.credit.can_send() {
            return None;
        }
        let pulled = self.input_since_us.map(|_| self.last_capture_start_us.map_or(0, |s| s + welcome.interval_us()));
        Some(pulled.map_or(self.next_capture_us, |p| p.min(self.next_capture_us)))
    }

    /// True when a capture now could be sent: welcomed, at the frame cap, with credit.
    pub fn wants_frame(&self, now_us: u64) -> bool {
        self.next_frame_at().is_some_and(|t| now_us >= t)
    }

    /// The hash of the last frame sent, which a capture matching it needn't be encoded for.
    pub fn last_sent_hash(&self) -> Option<u64> {
        self.last_sent_hash
    }

    /// Offers one prepared capture. Returns the `Frame` to send, or `None` when there is no
    /// `Welcome` yet, no credit (the capture is skipped, never queued), or nothing changed.
    /// A capture left unencoded that no longer matches the last frame sent (the display changed
    /// meanwhile) is not counted against the frame cap, so the caller can capture again at once.
    pub fn offer_frame(&mut self, now_us: u64, frame: Prepared) -> Option<Message> {
        let welcome = self.welcome?;
        let changed = self.last_sent_hash != Some(frame.hash);
        let Some((codec, data)) = frame.encoded.filter(|_| changed) else {
            if changed {
                return None;
            }
            self.captured(now_us, frame.captured_us, welcome, true);
            self.stats.skipped_unchanged += 1;
            return None;
        };
        let sendable = self.credit.can_send();
        self.captured(now_us, frame.captured_us, welcome, sendable);
        if !sendable {
            self.stats.skipped_no_credit += 1;
            return None;
        }
        let seq = self.next_seq;
        self.next_seq = self.next_seq.wrapping_add(1);
        self.credit.sent(seq, now_us);
        self.last_sent_hash = Some(frame.hash);
        self.stats.frames_sent += 1;
        self.stats.capture_to_sent_us.push(now_us.saturating_sub(frame.captured_us));
        Some(Message::Frame { seq, codec, width: frame.width, height: frame.height, sent_us: now_us, data })
    }

    /// Books a capture that counts against the frame cap, offered at `now_us`. `shown` is true
    /// when the phone will see what it captured (sent, or the same as the frame it already has):
    /// then the touches injected before the grab started are shown.
    fn captured(&mut self, now_us: u64, started_us: u64, welcome: Welcome, shown: bool) {
        self.next_capture_us = now_us + welcome.interval_us();
        self.last_capture_start_us = Some(started_us);
        if shown && let Some(since) = self.input_since_us.filter(|&t| t <= started_us) {
            self.stats.input_to_capture_us.push(started_us - since);
            self.input_since_us = None;
        }
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

    /// Time passes: after 1 s with no main-thread message (see [`SILENCE_RELEASE_US`]) a held
    /// button is released.
    pub fn tick(&mut self, now_us: u64, inj: &mut dyn Injector) {
        if self.buttons.held() && now_us.saturating_sub(self.last_live_us) >= SILENCE_RELEASE_US {
            self.buttons.release(&self.display, inj);
        }
    }

    /// The connection closed, for any reason: release every held button.
    pub fn close(&mut self, inj: &mut dyn Injector) {
        self.buttons.release(&self.display, inj);
    }
}

/// A session bound to the injector its touches go to. Dropping it releases a held button, so a
/// panic unwinding out of the session loop never leaves the computer's button down.
pub struct Bound<I: Injector> {
    pub session: Session,
    pub inj: I,
}

impl<I: Injector> Bound<I> {
    /// Releases a held button now (see [`Session::close`]); dropping does it again, harmlessly.
    pub fn close(&mut self) {
        self.session.close(&mut self.inj);
    }
}

impl<I: Injector> Drop for Bound<I> {
    fn drop(&mut self) {
        self.close();
    }
}
