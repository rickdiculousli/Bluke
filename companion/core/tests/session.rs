//! The companion's session logic with a fake clock, the synthetic screen and a recording injector.

use bluke_mirror_core::codec::{CODEC_JPEG, Message};
use bluke_mirror_core::frames::{Capture, FrameCredit, FrameEncoder, MAX_IN_FLIGHT, Prepared, Scaled, downscale, fit};
use bluke_mirror_core::pairing::Fingerprint;
use bluke_mirror_core::pointer::{Display, InputEvent};
use bluke_mirror_core::session::{Close, NotPinned, SILENCE_RELEASE_US, Session};
use bluke_mirror_core::synthetic::{Schedule, Synthetic};

const PHONE: Fingerprint = [0xb0; 32];
const OTHER: Fingerprint = [0xee; 32];

struct FakeJpeg;

impl FrameEncoder for FakeJpeg {
    fn codec(&self) -> u8 {
        CODEC_JPEG
    }
    fn encode(&mut self, f: &Scaled) -> Vec<u8> {
        f.hash.to_be_bytes().to_vec()
    }
}

/// Prepares `f` as the capture thread does, grabbed at `t`, and offers it at `t`.
fn offer(s: &mut Session, t: u64, f: &Scaled) -> Option<Message> {
    let p = Prepared::prepare(f, t, s.last_sent_hash(), &mut FakeJpeg);
    s.offer_frame(t, p)
}

fn display() -> Display {
    Synthetic::new(Schedule::Static).display()
}

fn welcome() -> Message {
    Message::Welcome { version: 1, codecs: 1, max_width: 480, max_height: 480, fps: 20 }
}

/// A session past `Welcome`, as it would be on a pinned connection.
fn started(now: u64) -> Session {
    let (mut s, hello) = Session::start("Mac", display(), Some(PHONE), PHONE, now).expect("pinned");
    assert_eq!(hello, Message::Hello { version: 1, name: "Mac".into() });
    let out = s.on_message(now, welcome(), &mut Vec::new()).expect("welcome");
    assert_eq!(out, vec![Message::Display { id: 1, width: 1920, height: 1080, name: "Synthetic".into() }]);
    s
}

fn pointer(x: u16, y: u16, down: bool) -> Message {
    Message::Pointer { x, y, buttons: u8::from(down) }
}

#[test]
fn unpinned_or_mismatched_peer_gets_nothing() {
    assert_eq!(Session::start("Mac", display(), None, PHONE, 0).err(), Some(NotPinned));
    assert_eq!(Session::start("Mac", display(), Some(PHONE), OTHER, 0).err(), Some(NotPinned));
}

#[test]
fn nothing_is_sent_or_injected_before_welcome() {
    let (mut s, _) = Session::start("Mac", display(), Some(PHONE), PHONE, 0).expect("pinned");
    let mut cap = Synthetic::new(Schedule::Every(1));
    let frame = downscale(&cap.grab().expect("grab"), 480, 480);
    assert!(!s.wants_frame(0));
    assert_eq!(offer(&mut s, 0, &frame), None);
    let mut rec = Vec::new();
    assert_eq!(s.on_message(0, pointer(0, 0, true), &mut rec), Err(Close::Unexpected(0x20)));
    assert!(rec.is_empty());
    // A pairing message on a session connection closes it.
    let mut s = started(0);
    assert_eq!(s.on_message(0, Message::PairCode { attempts_left: 3 }, &mut rec), Err(Close::Unexpected(0x02)));
    let (mut s, _) = Session::start("Mac", display(), Some(PHONE), PHONE, 0).expect("pinned");
    assert_eq!(s.on_message(0, Message::Refused { reason: 1 }, &mut rec), Err(Close::Refused(1)));
    assert!(rec.is_empty());
}

#[test]
fn press_drag_release_in_order_with_mapping_and_clamping() {
    let mut s = started(0);
    let mut rec: Vec<InputEvent> = Vec::new();
    for m in
        [pointer(0, 0, false), pointer(0, 0, true), pointer(32768, 16384, true), pointer(65535, 65535, true), pointer(65535, 65535, false)]
    {
        s.on_message(1, m, &mut rec).expect("ok");
    }
    let x_mid = -1920.0 + 32768.0 / 65535.0 * 1919.0;
    let y_q = 16384.0 / 65535.0 * 1079.0;
    assert_eq!(
        rec,
        vec![
            InputEvent::Move { x: -1920.0, y: 0.0 },
            InputEvent::Move { x: -1920.0, y: 0.0 },
            InputEvent::Down { x: -1920.0, y: 0.0 },
            InputEvent::Drag { x: x_mid, y: y_q },
            InputEvent::Drag { x: -1.0, y: 1079.0 },
            InputEvent::Up { x: -1.0, y: 1079.0 },
        ]
    );
    // Every point lands inside the display's rect; points from outside it are clamped in.
    let d = display();
    for e in &rec {
        let (x, y) = e.point();
        assert!((-1920.0..=-1.0).contains(&x) && (0.0..=1079.0).contains(&y), "{e:?}");
    }
    assert_eq!(d.clamp(-5000.0, 99999.0), (-1920.0, 1079.0));
    assert_eq!(d.clamp(10.0, -3.0), (-1.0, 0.0));
    let one_px = Display { id: 2, x: 5.0, y: 7.0, width: 1.0, height: 1.0, name: String::new() };
    assert_eq!(one_px.map(65535, 65535), (5.0, 7.0));
}

#[test]
fn a_tap_in_place_injects_no_drag_and_a_moved_lift_drags_first() {
    let mut s = started(0);
    let mut rec: Vec<InputEvent> = Vec::new();
    // Hover move, then press and lift at the same point: move, down, up — no zero-length drag.
    for m in [pointer(100, 200, false), pointer(100, 200, true), pointer(100, 200, false)] {
        s.on_message(1, m, &mut rec).expect("ok");
    }
    let (x, y) = display().map(100, 200);
    assert_eq!(rec, vec![InputEvent::Move { x, y }, InputEvent::Move { x, y }, InputEvent::Down { x, y }, InputEvent::Up { x, y }]);
    // A lift at a point the finger moved to drags there, then releases.
    rec.clear();
    for m in [pointer(100, 200, true), pointer(300, 400, false)] {
        s.on_message(1, m, &mut rec).expect("ok");
    }
    let (x2, y2) = display().map(300, 400);
    assert_eq!(
        rec,
        vec![InputEvent::Move { x, y }, InputEvent::Down { x, y }, InputEvent::Drag { x: x2, y: y2 }, InputEvent::Up { x: x2, y: y2 }]
    );
}

#[test]
fn wheel_goes_where_the_pointer_is() {
    let mut s = started(0);
    let mut rec = Vec::new();
    s.on_message(1, pointer(65535, 0, false), &mut rec).expect("ok");
    s.on_message(2, Message::Wheel { dx: 0, dy: -120 }, &mut rec).expect("ok");
    assert_eq!(rec.last(), Some(&InputEvent::Wheel { x: -1.0, y: 0.0, dx: 0, dy: -120 }));
}

fn mid_drag(s: &mut Session, rec: &mut Vec<InputEvent>, now: u64) {
    s.on_message(now, pointer(1000, 1000, true), rec).expect("ok");
    s.on_message(now, pointer(2000, 2000, true), rec).expect("ok");
    assert!(s.button_held());
}

#[test]
fn connection_drop_mid_drag_releases() {
    let mut s = started(0);
    let mut rec = Vec::new();
    mid_drag(&mut s, &mut rec, 1);
    s.close(&mut rec);
    let (x, y) = display().map(2000, 2000);
    assert_eq!(rec.last(), Some(&InputEvent::Release { x, y }));
    assert!(!s.button_held());
    // Closing again releases nothing more.
    let n = rec.len();
    s.close(&mut rec);
    assert_eq!(rec.len(), n);
}

#[test]
fn silent_link_mid_drag_releases_after_one_second() {
    let mut s = started(0);
    let mut rec = Vec::new();
    mid_drag(&mut s, &mut rec, 1_000);
    // Heartbeats keep the button held.
    for t in (250_000..=2_000_000).step_by(250_000) {
        s.on_message(t, Message::Heartbeat, &mut rec).expect("ok");
        s.tick(t + 100_000, &mut rec);
    }
    assert!(s.button_held());
    let n = rec.len();
    s.tick(2_000_000 + SILENCE_RELEASE_US - 1, &mut rec);
    assert_eq!(rec.len(), n, "not yet");
    s.tick(2_000_000 + SILENCE_RELEASE_US, &mut rec);
    assert!(matches!(rec.last(), Some(InputEvent::Release { .. })));
    assert!(!s.button_held());
    // With no button held, silence releases nothing.
    let n = rec.len();
    s.tick(10_000_000, &mut rec);
    assert_eq!(rec.len(), n);
}

#[test]
fn release_message_releases_and_forgets_the_button() {
    let mut s = started(0);
    let mut rec = Vec::new();
    mid_drag(&mut s, &mut rec, 1);
    s.on_message(2, Message::Release, &mut rec).expect("ok");
    assert!(matches!(rec.last(), Some(InputEvent::Release { .. })));
    // The next pointer with the button down starts a fresh press.
    s.on_message(3, pointer(5, 5, true), &mut rec).expect("ok");
    assert!(matches!(rec[rec.len() - 2..], [InputEvent::Move { .. }, InputEvent::Down { .. }]));
}

#[test]
fn unchanged_frames_send_nothing() {
    let mut s = started(0);
    let mut cap = Synthetic::new(Schedule::Static);
    let mut sent = 0;
    let mut t = 0;
    for _ in 0..10 {
        let f = downscale(&cap.grab().expect("grab"), 480, 480);
        if let Some(Message::Frame { seq, .. }) = offer(&mut s, t, &f) {
            sent += 1;
            s.on_message(t, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
        }
        t += 50_000;
    }
    assert_eq!(sent, 1);
    assert_eq!(s.stats.skipped_unchanged, 9);
}

#[test]
fn changing_frames_send_on_their_schedule() {
    let mut s = started(0);
    let mut cap = Synthetic::new(Schedule::Every(3));
    let mut seqs = Vec::new();
    for i in 0..9u64 {
        let f = downscale(&cap.grab().expect("grab"), 480, 480);
        assert_eq!((f.width, f.height), (480, 270));
        if let Some(Message::Frame { seq, width, height, sent_us, .. }) = offer(&mut s, i * 50_000, &f) {
            assert_eq!((width, height, sent_us), (480, 270, i * 50_000));
            seqs.push(seq);
            s.on_message(i * 50_000 + 10, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
        }
    }
    assert_eq!(seqs, vec![0, 1, 2], "one frame per change, seq increasing by one");
    assert_eq!(s.stats.ack_latency_us, vec![10, 10, 10]);
}

#[test]
fn credit_never_exceeds_two_and_acks_are_cumulative() {
    let mut s = started(0);
    let mut cap = Synthetic::new(Schedule::Every(1));
    let mut t = 0;
    let mut sent = Vec::new();
    for _ in 0..6 {
        let f = downscale(&cap.grab().expect("grab"), 480, 480);
        if let Some(Message::Frame { seq, .. }) = offer(&mut s, t, &f) {
            sent.push(seq);
        }
        assert!(s.in_flight() <= MAX_IN_FLIGHT);
        t += 50_000;
    }
    assert_eq!(sent, vec![0, 1], "no credit: captures skipped, never queued");
    assert_eq!(s.stats.skipped_no_credit, 4);
    assert!(!s.wants_frame(t));
    // One ack for seq 1 frees both.
    s.on_message(t, Message::FrameAck { seq: 1, decode_us: u32::MAX }, &mut Vec::new()).expect("ack");
    assert_eq!(s.in_flight(), 0);
    assert_eq!(s.stats.frames_acked, 2);
    assert!(s.wants_frame(t));
    let f = downscale(&cap.grab().expect("grab"), 480, 480);
    assert!(matches!(offer(&mut s, t, &f), Some(Message::Frame { seq: 2, .. })));

    // The credit counter alone: acks for unknown frames change nothing.
    let mut c = FrameCredit::default();
    c.sent(10, 0);
    assert!(c.ack(99, 5).is_empty());
    assert_eq!(c.ack(10, 5), vec![5]);
}

#[test]
fn frame_cap_paces_captures() {
    let mut s = started(0);
    let mut cap = Synthetic::new(Schedule::Every(1));
    let f = downscale(&cap.grab().expect("grab"), 480, 480);
    assert!(s.wants_frame(0));
    let Some(Message::Frame { seq, .. }) = offer(&mut s, 0, &f) else { panic!("frame") };
    s.on_message(0, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
    assert!(!s.wants_frame(49_999), "20 fps: next capture 50 ms later");
    assert!(s.wants_frame(50_000));
}

#[test]
fn display_change_is_announced_and_resends_the_frame() {
    let mut s = started(0);
    let mut cap = Synthetic::new(Schedule::Static);
    let f = downscale(&cap.grab().expect("grab"), 480, 480);
    let Some(Message::Frame { seq, .. }) = offer(&mut s, 0, &f) else { panic!("frame") };
    s.on_message(0, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
    assert_eq!(s.set_display(display()), None, "same display, nothing to say");
    let bigger = Display { width: 2560.0, height: 1440.0, ..display() };
    assert_eq!(s.set_display(bigger), Some(Message::Display { id: 1, width: 2560, height: 1440, name: "Synthetic".into() }));
    assert!(offer(&mut s, 100_000, &f).is_some());
}

#[test]
fn ping_is_echoed_and_unknown_is_skipped() {
    let mut s = started(0);
    assert_eq!(s.on_message(1, Message::Ping { t_us: 42 }, &mut Vec::new()), Ok(vec![Message::Pong { t_us: 42 }]));
    assert_eq!(s.on_message(1, Message::Unknown { kind: 0x7f }, &mut Vec::new()), Ok(vec![]));
    assert_eq!(s.on_message(1, Message::Hello { version: 1, name: "x".into() }, &mut Vec::new()), Err(Close::Unexpected(0x10)));
}

#[test]
fn incompatible_welcome_closes() {
    let (mut s, _) = Session::start("Mac", display(), Some(PHONE), PHONE, 0).expect("pinned");
    let w = Message::Welcome { version: 2, codecs: 1, max_width: 480, max_height: 480, fps: 20 };
    assert_eq!(s.on_message(0, w, &mut Vec::new()), Err(Close::Incompatible));
}

#[test]
fn fit_keeps_aspect_and_never_upscales() {
    assert_eq!(fit(3024, 1964, 480, 480), (480, 312));
    assert_eq!(fit(1964, 3024, 480, 480), (312, 480));
    assert_eq!(fit(1920, 1080, 480, 270), (480, 270));
    assert_eq!(fit(320, 200, 480, 480), (320, 200));
    assert_eq!(fit(10000, 1, 480, 480), (480, 1));
}

#[test]
fn downscale_averages_and_is_fast_on_a_retina_capture() {
    use bluke_mirror_core::frames::Image;
    // A 3024x1964 capture, left half black, right half white.
    let (w, h) = (3024u32, 1964u32);
    let mut rgba = vec![0u8; (w * h * 4) as usize];
    for row in rgba.chunks_exact_mut(w as usize * 4) {
        row[w as usize * 2..].fill(255);
    }
    let img = Image { width: w, height: h, stride: w as usize * 4, rgba };
    let start = std::time::Instant::now();
    let rounds = 5;
    let mut out = None;
    for _ in 0..rounds {
        out = Some(downscale(&img, 480, 480));
    }
    let per = start.elapsed() / rounds;
    let out = out.expect("ran");
    assert_eq!((out.width, out.height), (480, 312));
    assert_eq!(&out.rgb[..3], &[0, 0, 0]);
    assert_eq!(&out.rgb[479 * 3..480 * 3], &[255, 255, 255]);
    // Bounded work per output pixel: even unoptimised test builds stay well under 100 ms.
    assert!(per.as_millis() < 100, "downscale took {per:?}");
}

/// Counts encodes, to show what is and isn't encoded.
#[derive(Default)]
struct CountingJpeg(u32);

impl FrameEncoder for CountingJpeg {
    fn codec(&self) -> u8 {
        CODEC_JPEG
    }
    fn encode(&mut self, f: &Scaled) -> Vec<u8> {
        self.0 += 1;
        FakeJpeg.encode(f)
    }
}

#[test]
fn an_unchanged_capture_is_not_encoded_and_sent_frames_time_capture_to_send() {
    let mut s = started(0);
    let mut cap = Synthetic::new(Schedule::Static);
    let mut enc = CountingJpeg::default();
    let f = downscale(&cap.grab().expect("grab"), 480, 480);
    // Grabbed at 1000, sent at 4500: 3.5 ms from capture start to sent.
    let p = Prepared::prepare(&f, 1_000, s.last_sent_hash(), &mut enc);
    let Some(Message::Frame { seq, .. }) = s.offer_frame(4_500, p) else { panic!("frame") };
    assert_eq!(s.stats.capture_to_sent_us, vec![3_500]);
    s.on_message(5_000, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
    // The same pixels again: no encode, no frame, and the frame cap still paces the next capture.
    let p = Prepared::prepare(&f, 60_000, s.last_sent_hash(), &mut enc);
    assert!(p.encoded.is_none());
    assert_eq!(s.offer_frame(60_100, p), None);
    assert_eq!((enc.0, s.stats.skipped_unchanged, s.stats.capture_to_sent_us.len()), (1, 1, 1));
    assert!(!s.wants_frame(60_101));
}

#[test]
fn an_unencoded_capture_overtaken_by_a_display_change_is_retaken_at_once() {
    let mut s = started(0);
    let mut cap = Synthetic::new(Schedule::Static);
    let f = downscale(&cap.grab().expect("grab"), 480, 480);
    let Some(Message::Frame { seq, .. }) = offer(&mut s, 0, &f) else { panic!("frame") };
    s.on_message(0, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
    // Requested while the hash still matched, so the capture thread didn't encode it...
    let p = Prepared::prepare(&f, 50_000, s.last_sent_hash(), &mut FakeJpeg);
    // ...but the display changed before it was offered, so it must be sent after all.
    let bigger = Display { width: 2560.0, height: 1440.0, ..display() };
    assert!(s.set_display(bigger).is_some());
    assert_eq!(s.offer_frame(50_100, p), None);
    assert_eq!(s.stats.skipped_unchanged, 0);
    assert!(s.wants_frame(50_100), "not paced: capture again now");
    assert!(offer(&mut s, 50_200, &f).is_some());
}
