//! One session connection: the core [`Session`] driven over a [`Link`], with captures taken on
//! their own thread so a slow grab never delays a touch.

use crate::link::Link;
use crate::now_us;
use bluke_mirror_core::frames::{Capture, FrameEncoder, Scaled, downscale};
use bluke_mirror_core::pointer::{Display, Injector};
use bluke_mirror_core::session::{Close, Session, Stats};
use bluke_mirror_core::{Fingerprint, Message};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::time::Duration;

/// The phone pings once a second; this much total silence means the link is gone.
pub const LINK_SILENCE: Duration = Duration::from_secs(3);
/// How long one wait for phone messages lasts; bounds how late a capture is picked up.
const POLL: Duration = Duration::from_millis(4);
/// How often the status line prints frames per second.
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

type Captured = Result<(Scaled, Display), String>;

/// Spawns the capture thread: each request `(max_w, max_h)` gets one grab, downscaled.
fn spawn_capture(mut capture: Box<dyn Capture + Send>) -> (mpsc::Sender<(u16, u16)>, mpsc::Receiver<Captured>) {
    let (req_tx, req_rx) = mpsc::channel::<(u16, u16)>();
    let (resp_tx, resp_rx) = mpsc::channel::<Captured>();
    std::thread::Builder::new()
        .name("capture".into())
        .spawn(move || {
            for (w, h) in req_rx {
                let r = capture.grab().map(|img| (downscale(&img, w, h), capture.display()));
                if resp_tx.send(r).is_err() {
                    break;
                }
            }
        })
        .expect("spawn capture thread");
    (req_tx, resp_rx)
}

/// Runs one session on a connected link until it ends. Every held button is released on the
/// way out, whatever the reason.
pub fn run(
    mut link: Link,
    pinned: Option<Fingerprint>,
    capture: Box<dyn Capture + Send>,
    injector: &mut dyn Injector,
    encoder: &mut dyn FrameEncoder,
    opts: &Options<'_>,
    status: &mut dyn FnMut(&str),
) -> Report {
    let mut inj = Counting { inner: injector, releases: 0 };
    let mut latencies = Vec::new();
    let (mut session, hello) = match Session::start(&opts.name, capture.display(), pinned, link.peer(), now_us()) {
        Ok(s) => s,
        Err(_) => {
            link.close();
            return Report { end: End::NotPinned, stats: Stats::default(), inject_latency_us: latencies, releases: 0 };
        }
    };
    let (req_tx, resp_rx) = spawn_capture(capture);
    let mut capture_pending = false;
    let mut last_rx = now_us();
    let mut status_at = (now_us(), 0u64);
    let end = 'session: {
        if let Err(e) = link.send(&hello) {
            break 'session End::Io(e);
        }
        loop {
            if opts.stop.load(Ordering::Relaxed) || opts.until_us.is_some_and(|u| now_us() >= u) {
                break 'session End::Stopped;
            }
            let msgs = match link.recv(POLL) {
                Ok(m) => m,
                Err(e) => break 'session End::Io(e),
            };
            let t_read = now_us();
            if !msgs.is_empty() {
                last_rx = t_read;
            }
            for m in msgs {
                let input = matches!(m, Message::Pointer { .. } | Message::Wheel { .. } | Message::Release);
                match session.on_message(t_read, m, &mut inj) {
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
                    latencies.push(now_us() - t_read);
                }
            }
            let now = now_us();
            let before = inj.releases;
            session.tick(now, &mut inj);
            if inj.releases > before {
                status("released the held button: the phone went quiet");
            }
            if now.saturating_sub(last_rx) >= LINK_SILENCE.as_micros() as u64 {
                break 'session End::Silent;
            }
            if !capture_pending
                && session.wants_frame(now)
                && let Some(w) = session.welcome()
            {
                capture_pending = req_tx.send((w.max_width, w.max_height)).is_ok();
            }
            match resp_rx.try_recv() {
                Ok(Ok((scaled, display))) => {
                    capture_pending = false;
                    let now = now_us();
                    let mut out: Vec<Message> = session.set_display(display).into_iter().collect();
                    out.extend(session.offer_frame(now, &scaled, encoder));
                    for o in out {
                        if let Err(e) = link.send(&o) {
                            break 'session End::Io(e);
                        }
                    }
                }
                Ok(Err(e)) => {
                    capture_pending = false;
                    status(&format!("capture failed: {e}"));
                }
                Err(_) => {}
            }
            if now - status_at.0 >= STATUS_EVERY_US {
                let sent = session.stats.frames_sent;
                let fps = (sent - status_at.1) as f64 * 1e6 / (now - status_at.0) as f64;
                status(&format!("{fps:.1} frames/s, {sent} sent"));
                status_at = (now, sent);
            }
        }
    };
    let before = inj.releases;
    session.close(&mut inj);
    if inj.releases > before {
        status("released the held button: the session ended");
    }
    link.close();
    Report { end, stats: std::mem::take(&mut session.stats), inject_latency_us: latencies, releases: inj.releases }
}
