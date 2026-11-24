//! One session connection: the core [`Session`] driven over a [`Link`]. Captures are grabbed,
//! downscaled and encoded on their own thread, and socket reads happen on another, so a slow
//! grab never delays a touch; both wake the session through one channel.

use crate::link::{Link, Received};
use crate::now_us;
use bluke_mirror_core::frames::{Capture, FrameEncoder, Prepared, downscale};
use bluke_mirror_core::pointer::{Display, Injector};
use bluke_mirror_core::session::{Bound, Close, Session, Stats};
use bluke_mirror_core::{Fingerprint, Message};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::time::Duration;

/// The phone pings once a second; this much total silence means the link is gone.
pub const LINK_SILENCE: Duration = Duration::from_secs(3);
/// The longest the session sleeps with nothing arriving; bounds how late a stop, a silence
/// release or a status line is noticed. Phone bytes, finished captures and the next capture
/// time all wake it sooner.
const MAX_WAIT_US: u64 = 50_000;
/// How often the status line prints frames per second and grab times.
const STATUS_EVERY_US: u64 = 5_000_000;

pub struct Options<'a> {
    pub name: String,
    /// Set from another thread (or a signal) to end the session.
    pub stop: &'a AtomicBool,
    /// End the session at this `now_us()` time, if set.
    pub until_us: Option<u64>,
}

/// Why a session ended.
#[derive(Debug)]
pub enum End {
    /// The phone presented a certificate other than the pinned one; nothing was sent.
    NotPinned,
    /// The phone closed the session for a protocol reason, or refused it.
    Closed(Close),
    /// The connection dropped or failed.
    Io(std::io::Error),
    /// Nothing arrived for [`LINK_SILENCE`].
    Silent,
    /// Asked to stop.
    Stopped,
}

impl std::fmt::Display for End {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            End::NotPinned => write!(f, "the phone isn't the paired phone; nothing was sent"),
            End::Closed(c) => write!(f, "{c}"),
            End::Io(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => write!(f, "the phone closed the connection"),
            End::Io(e) => write!(f, "connection lost: {e}"),
            End::Silent => write!(f, "the phone went quiet"),
            End::Stopped => write!(f, "stopped"),
        }
    }
}

/// What the companion's run loop does once a connection to one address ends.
#[derive(Debug, PartialEq, Eq)]
pub enum Next {
    /// Try the next address advertising the paired phone's id, before waiting.
    TryNextAddress,
    /// Wait, then find the phone and connect again.
    Reconnect,
    /// The user asked to stop.
    Stop,
    /// Give up with this message: only the user can fix it.
    Fail(&'static str),
}

/// The rule for [`Next`]. `fixed_address` is a `--phone` address rather than mDNS results; an mDNS
/// result that isn't the pinned phone may be anyone advertising its id, so the others are tried.
pub fn next_after(end: &End, fixed_address: bool) -> Next {
    match end {
        End::Closed(Close::Refused(1)) => Next::Fail("the phone has forgotten this computer. Run `bluke-mirror pair` again."),
        End::NotPinned if fixed_address => {
            Next::Fail("the phone at that address isn't the paired phone. Run `bluke-mirror pair` to pair with it.")
        }
        End::NotPinned => Next::TryNextAddress,
        End::Stopped => Next::Stop,
        _ => Next::Reconnect,
    }
}

pub struct Report {
    pub end: End,
    pub stats: Stats,
    /// For each pointer, wheel or release message: time from its bytes being read to its
    /// events being injected, µs.
    pub inject_latency_us: Vec<u64>,
    /// Releases injected because the link dropped, went quiet, or the phone asked.
    pub releases: u32,
}

/// Counts the events passing through to the real injector.
struct Counting<'a> {
    inner: &'a mut dyn Injector,
    releases: u32,
}

impl Injector for Counting<'_> {
    fn inject(&mut self, event: bluke_mirror_core::InputEvent) {
        if matches!(event, bluke_mirror_core::InputEvent::Release { .. }) {
            self.releases += 1;
        }
        self.inner.inject(event)
    }
}

/// One capture's outcome, with how long the grab alone took and which path took it.
struct Captured {
    result: Result<(Prepared, Display), String>,
    grab_us: u64,
    backend: &'static str,
}

/// What wakes the session.
enum Event {
    Net(Received),
    Captured(Captured),
}

/// The periodic status line: the frame rate since the last line, frames sent in all, and the
/// capture path with its grab times since the last line (`grab_us` is sorted in place).
pub fn status_line(fps: f64, sent: u64, backend: &str, grab_us: &mut [u64]) -> String {
    let head = format!("{fps:.1} frames/s, {sent} sent");
    if grab_us.is_empty() {
        return format!("{head}; {backend}: no grabs");
    }
    grab_us.sort_unstable();
    let ms = |us: u64| us as f64 / 1000.0;
    let (median, max) = (grab_us[grab_us.len() / 2], grab_us[grab_us.len() - 1]);
    format!("{head}; {backend} grab {:.1} ms median, {:.1} ms max", ms(median), ms(max))
}

impl From<Received> for Event {
    fn from(r: Received) -> Self {
        Event::Net(r)
    }
}

/// One capture to take: fit in `max` and skip encoding if the pixels hash to `unchanged_from`.
struct Request {
    max: (u16, u16),
    unchanged_from: Option<u64>,
}

/// Spawns the capture thread: each request gets one grab, downscaled and encoded there.
fn spawn_capture(
    mut capture: Box<dyn Capture + Send>,
    mut encoder: Box<dyn FrameEncoder + Send>,
    events: mpsc::Sender<Event>,
) -> mpsc::Sender<Request> {
    let (req_tx, req_rx) = mpsc::channel::<Request>();
    std::thread::Builder::new()
        .name("capture".into())
        .spawn(move || {
            for r in req_rx {
                let t = now_us();
                let grabbed = capture.grab(r.max);
                let grab_us = now_us().saturating_sub(t);
                let backend = capture.backend();
                let result = grabbed.map(|img| {
                    let scaled = downscale(&img, r.max.0, r.max.1);
                    (Prepared::prepare(&scaled, t, r.unchanged_from, encoder.as_mut()), capture.display())
                });
                if events.send(Event::Captured(Captured { result, grab_us, backend })).is_err() {
                    break;
                }
            }
        })
        .expect("spawn capture thread");
    req_tx
}

/// Runs one session on a connected link until it ends. Every held button is released on the
/// way out, whatever the reason, a panic included.
pub fn run(
    mut link: Link,
    pinned: Option<Fingerprint>,
    capture: Box<dyn Capture + Send>,
    injector: &mut dyn Injector,
    encoder: Box<dyn FrameEncoder + Send>,
    opts: &Options<'_>,
    status: &mut dyn FnMut(&str),
) -> Report {
    let mut latencies = Vec::new();
    let (session, hello) = match Session::start(&opts.name, capture.display(), pinned, link.peer(), now_us()) {
        Ok(s) => s,
        Err(_) => {
            link.close();
            return Report { end: End::NotPinned, stats: Stats::default(), inject_latency_us: latencies, releases: 0 };
        }
    };
    let mut bound = Bound { session, inj: Counting { inner: injector, releases: 0 } };
    let Bound { session, inj } = &mut bound;
    let (events_tx, events) = mpsc::channel::<Event>();
    let mut last_rx = now_us();
    let mut status_at = (now_us(), 0u64);
    let mut capture_pending = false;
    let mut backend = capture.backend();
    let mut grab_us: Vec<u64> = Vec::new();
    let end = 'session: {
        if let Err(e) = link.send(&hello).and_then(|()| link.spawn_reader(events_tx.clone())) {
            break 'session End::Io(e);
        }
        let requests = spawn_capture(capture, encoder, events_tx);
        loop {
            let now = now_us();
            if opts.stop.load(Ordering::Relaxed) || opts.until_us.is_some_and(|u| now >= u) {
                break 'session End::Stopped;
            }
            if !capture_pending && session.wants_frame(now) {
                let max = session.welcome().map_or((0, 0), |w| (w.max_width, w.max_height));
                capture_pending = requests.send(Request { max, unchanged_from: session.last_sent_hash() }).is_ok();
            }
            let mut wait = MAX_WAIT_US;
            if !capture_pending && let Some(due) = session.next_frame_at() {
                wait = wait.min(due.saturating_sub(now));
            }
            if let Some(u) = opts.until_us {
                wait = wait.min(u.saturating_sub(now));
            }
            match events.recv_timeout(Duration::from_micros(wait)) {
                Ok(Event::Net(Received { at_us, bytes })) => {
                    let msgs = match bytes.and_then(|b| link.ingest(&b)) {
                        Ok(m) => m,
                        Err(e) => break 'session End::Io(e),
                    };
                    if !msgs.is_empty() {
                        last_rx = at_us;
                    }
                    for m in msgs {
                        let input = matches!(m, Message::Pointer { .. } | Message::Wheel { .. } | Message::Release);
                        match session.on_message(at_us, m, inj) {
                            Ok(out) => {
                                for o in out {
                                    if let Err(e) = link.send(&o) {
                                        break 'session End::Io(e);
                                    }
                                }
                            }
                            Err(c) => break 'session End::Closed(c),
                        }
                        if input {
                            latencies.push(now_us().saturating_sub(at_us));
                        }
                    }
                }
                Ok(Event::Captured(c)) => {
                    capture_pending = false;
                    backend = c.backend;
                    grab_us.push(c.grab_us);
                    match c.result {
                        Ok((frame, display)) => {
                            let mut out: Vec<Message> = session.set_display(display, inj).into_iter().collect();
                            out.extend(session.offer_frame(now_us(), frame));
                            for o in out {
                                if let Err(e) = link.send(&o) {
                                    break 'session End::Io(e);
                                }
                            }
                        }
                        Err(e) => status(&format!("capture failed ({backend}): {e}")),
                    }
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {}
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    break 'session End::Io(std::io::Error::other("the reader and capture threads stopped"));
                }
            }
            let now = now_us();
            let before = inj.releases;
            session.tick(now, inj);
            if inj.releases > before {
                status("released the held button: the phone went quiet");
            }
            if now.saturating_sub(last_rx) >= LINK_SILENCE.as_micros() as u64 {
                break 'session End::Silent;
            }
            if now - status_at.0 >= STATUS_EVERY_US {
                let sent = session.stats.frames_sent;
                let fps = (sent - status_at.1) as f64 * 1e6 / (now - status_at.0) as f64;
                status(&status_line(fps, sent, backend, &mut grab_us));
                grab_us.clear();
                status_at = (now, sent);
            }
        }
    };
    let before = bound.inj.releases;
    bound.close();
    if bound.inj.releases > before {
        status("released the held button: the session ended");
    }
    link.close();
    Report { end, stats: std::mem::take(&mut bound.session.stats), inject_latency_us: latencies, releases: bound.inj.releases }
}
