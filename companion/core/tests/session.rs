//! The companion's session logic with a fake clock, the synthetic screen and a recording injector.

use bluke_mirror_core::codec::{CODEC_JPEG, Message};
use bluke_mirror_core::frames::{Capture, FrameCredit, FrameEncoder, MAX_IN_FLIGHT, Prepared, Scaled, downscale, fit};
use bluke_mirror_core::pairing::Fingerprint;
use bluke_mirror_core::pointer::{ClickCounter, Display, InputEvent};
use bluke_mirror_core::session::{Bound, Close, MAX_FRAME_DATA, NotPinned, SILENCE_RELEASE_US, Session};
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
    let frame = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
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
    // Every point lands inside the display's rect, the extremes on its edges.
    for e in &rec {
        let (x, y) = e.point();
        assert!((-1920.0..=-1.0).contains(&x) && (0.0..=1079.0).contains(&y), "{e:?}");
    }
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
fn frame_acks_alone_dont_keep_a_held_button() {
    // A hung phone main thread: its decoder still acknowledges frames, but nothing else arrives.
    let mut s = started(0);
    let mut rec = Vec::new();
    mid_drag(&mut s, &mut rec, 1_000);
    let t0 = 1_000;
    let mut cap = Synthetic::new(Schedule::Every(1));
    let mut acked_at = t0;
    for t in (t0 + 50_000..t0 + SILENCE_RELEASE_US).step_by(50_000) {
        let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
        if let Some(Message::Frame { seq, .. }) = offer(&mut s, t, &f) {
            s.on_message(t + 10_000, Message::FrameAck { seq, decode_us: 3_000 }, &mut rec).expect("ack");
            acked_at = t + 10_000;
        }
        s.tick(t + 10_000, &mut rec);
    }
    assert!(acked_at > t0 + SILENCE_RELEASE_US / 2, "frames kept flowing");
    assert!(s.button_held(), "not yet a second");
    s.tick(t0 + SILENCE_RELEASE_US, &mut rec);
    assert!(matches!(rec.last(), Some(InputEvent::Release { .. })), "acks don't count as the phone being alive");
    assert!(!s.button_held());
}

#[test]
fn each_main_thread_message_keeps_a_held_button() {
    for (i, live) in
        [Message::Pointer { x: 9, y: 9, buttons: 1 }, Message::Wheel { dx: 0, dy: 1 }, Message::Heartbeat, Message::Ping { t_us: 5 }]
            .into_iter()
            .enumerate()
    {
        let mut s = started(0);
        let mut rec = Vec::new();
        mid_drag(&mut s, &mut rec, 1_000);
        s.on_message(900_000, live, &mut rec).expect("ok");
        s.tick(1_000 + SILENCE_RELEASE_US, &mut rec);
        assert!(s.button_held(), "message {i} counts as alive");
        s.tick(900_000 + SILENCE_RELEASE_US, &mut rec);
        assert!(!s.button_held(), "message {i}: a second after it, released");
    }
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
        let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
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
        let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
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
        let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
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
    let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
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
    let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
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
    let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
    let Some(Message::Frame { seq, .. }) = offer(&mut s, 0, &f) else { panic!("frame") };
    s.on_message(0, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
    assert_eq!(s.set_display(display(), &mut Vec::new()), None, "same display, nothing to say");
    let bigger = Display { width: 2560.0, height: 1440.0, ..display() };
    assert_eq!(
        s.set_display(bigger, &mut Vec::new()),
        Some(Message::Display { id: 1, width: 2560, height: 1440, name: "Synthetic".into() })
    );
    assert!(offer(&mut s, 100_000, &f).is_some());
}

#[test]
fn display_change_mid_drag_releases_at_the_old_point_before_the_new_display() {
    let mut s = started(0);
    let mut rec = Vec::new();
    mid_drag(&mut s, &mut rec, 1);
    let (x, y) = display().map(2000, 2000);
    let n = rec.len();
    let bigger = Display { width: 2560.0, height: 1440.0, ..display() };
    assert!(s.set_display(bigger.clone(), &mut rec).is_some());
    assert_eq!(rec[n..], [InputEvent::Release { x, y }], "released where the finger last was, in the old display");
    assert!(!s.button_held());
    // The next touch is a fresh press in the new display, not a drag from the old point.
    s.on_message(2, pointer(1000, 1000, true), &mut rec).expect("ok");
    let (nx, ny) = bigger.map(1000, 1000);
    assert_eq!(rec[n + 1..], [InputEvent::Move { x: nx, y: ny }, InputEvent::Down { x: nx, y: ny }]);
    // An identical display releases nothing.
    let mut rec = Vec::new();
    assert_eq!(s.set_display(bigger, &mut rec), None);
    assert!(rec.is_empty() && s.button_held());
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
    // The fastest of several rounds: time spent descheduled on a busy machine isn't the work's.
    let mut per = std::time::Duration::MAX;
    let mut out = None;
    for _ in 0..5 {
        let start = std::time::Instant::now();
        out = Some(downscale(&img, 480, 480));
        per = per.min(start.elapsed());
    }
    let out = out.expect("ran");
    assert_eq!((out.width, out.height), (480, 312));
    assert_eq!(&out.rgb[..3], &[0, 0, 0]);
    assert_eq!(&out.rgb[479 * 3..480 * 3], &[255, 255, 255]);
    // Bounded work per output pixel: even unoptimised test builds stay well under 100 ms.
    assert!(per.as_millis() < 100, "downscale took {per:?}");
}

#[test]
fn a_capture_already_at_the_fit_is_copied_pixel_for_pixel() {
    // What a GPU-scaled (ScreenCaptureKit) grab hands over: every pixel reaches the frame and its
    // hash, so a one-pixel change is a new frame.
    use bluke_mirror_core::frames::Image;
    let (w, h) = (480u32, 312u32);
    let rgba: Vec<u8> = (0..w * h * 4).map(|i| (i * 7 % 251) as u8).collect();
    let img = Image { width: w, height: h, stride: w as usize * 4, rgba: rgba.clone() };
    let out = downscale(&img, 480, 480);
    assert_eq!((out.width, out.height), (480, 312));
    let rgb: Vec<u8> = rgba.as_chunks::<4>().0.iter().flat_map(|p| [p[0], p[1], p[2]]).collect();
    assert_eq!(out.rgb, rgb);
    let mut changed = img.clone();
    changed.rgba[(100 * w as usize + 333) * 4] ^= 1;
    assert_ne!(downscale(&changed, 480, 480).hash, out.hash);
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
    let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
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
    let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
    let Some(Message::Frame { seq, .. }) = offer(&mut s, 0, &f) else { panic!("frame") };
    s.on_message(0, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
    // Requested while the hash still matched, so the capture thread didn't encode it...
    let p = Prepared::prepare(&f, 50_000, s.last_sent_hash(), &mut FakeJpeg);
    // ...but the display changed before it was offered, so it must be sent after all.
    let bigger = Display { width: 2560.0, height: 1440.0, ..display() };
    assert!(s.set_display(bigger, &mut Vec::new()).is_some());
    assert_eq!(s.offer_frame(50_100, p), None);
    assert_eq!(s.stats.skipped_unchanged, 0);
    assert!(s.wants_frame(50_100), "not paced: capture again now");
    assert!(offer(&mut s, 50_200, &f).is_some());
}

/// Prepares `f` as grabbed at `grabbed` and offers it at `offered` (grab and encode in between).
fn offer_late(s: &mut Session, grabbed: u64, offered: u64, f: &Scaled) -> Option<Message> {
    let p = Prepared::prepare(f, grabbed, s.last_sent_hash(), &mut FakeJpeg);
    s.offer_frame(offered, p)
}

/// A session at `t` whose first frame, grabbed at 0, took 30 ms to grab and encode and is acked.
fn polled_once() -> (Session, Synthetic) {
    let mut s = started(0);
    let mut cap = Synthetic::new(Schedule::Every(1));
    let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
    let Some(Message::Frame { seq, .. }) = offer_late(&mut s, 0, 30_000, &f) else { panic!("frame") };
    s.on_message(30_000, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
    (s, cap)
}

#[test]
fn a_touch_pulls_the_next_capture_to_the_frame_cap() {
    let (mut s, _) = polled_once();
    assert_eq!(s.next_frame_at(), Some(80_000), "no touch: poll one interval after the offer");
    s.on_message(40_000, pointer(100, 100, true), &mut Vec::new()).expect("pointer");
    assert_eq!(s.next_frame_at(), Some(50_000), "a touch: one interval after the last grab started");
    assert!(!s.wants_frame(49_999), "never closer than one interval to the last grab");
    assert!(s.wants_frame(50_000));
}

#[test]
fn a_touch_soon_after_a_grab_still_waits_for_the_cap_and_wheel_counts_too() {
    let (mut s, _) = polled_once();
    s.on_message(5_000, Message::Wheel { dx: 0, dy: 3 }, &mut Vec::new()).expect("wheel");
    assert_eq!(s.next_frame_at(), Some(50_000));
}

#[test]
fn the_capture_that_shows_a_touch_records_touch_to_grab_and_polling_resumes() {
    let (mut s, mut cap) = polled_once();
    s.on_message(40_000, pointer(100, 100, false), &mut Vec::new()).expect("pointer");
    s.on_message(45_000, pointer(200, 100, false), &mut Vec::new()).expect("pointer");
    let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
    assert!(offer_late(&mut s, 50_000, 70_000, &f).is_some());
    assert_eq!(s.stats.input_to_capture_us, vec![10_000], "from the first touch not yet captured");
    assert_eq!(s.next_frame_at(), Some(120_000), "shown: back to polling after the offer");
}

#[test]
fn a_touch_during_a_grab_is_shown_by_the_next_one() {
    let (mut s, mut cap) = polled_once();
    // A grab started at 80 ms; the touch lands while it runs.
    s.on_message(85_000, pointer(100, 100, false), &mut Vec::new()).expect("pointer");
    let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
    let Some(Message::Frame { seq, .. }) = offer_late(&mut s, 80_000, 110_000, &f) else { panic!("frame") };
    s.on_message(110_000, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
    assert!(s.stats.input_to_capture_us.is_empty(), "that grab began before the touch");
    assert_eq!(s.next_frame_at(), Some(130_000), "the touch still pulls: one interval after that grab started");
}

#[test]
fn an_unchanged_capture_shows_a_touch_too() {
    let (mut s, mut cap) = polled_once();
    let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
    s.on_message(60_000, pointer(1, 1, false), &mut Vec::new()).expect("pointer");
    let Some(Message::Frame { seq, .. }) = offer_late(&mut s, 60_000, 70_000, &f) else { panic!("frame") };
    s.on_message(70_000, Message::FrameAck { seq, decode_us: 1 }, &mut Vec::new()).expect("ack");
    s.on_message(75_000, pointer(2, 2, false), &mut Vec::new()).expect("pointer");
    assert!(offer_late(&mut s, 110_000, 120_000, &f).is_none(), "unchanged");
    assert_eq!(s.stats.input_to_capture_us, vec![0, 35_000]);
    assert_eq!(s.next_frame_at(), Some(170_000));
}

#[test]
fn a_continuous_drag_never_captures_more_than_fps_a_second() {
    // Touches every 8 ms for 2 s; each grab takes 30 ms to grab and encode; the phone acks at once.
    let mut s = started(0);
    let mut cap = Synthetic::new(Schedule::Every(1));
    let mut rec = Vec::new();
    let mut starts = Vec::new();
    let mut grab: Option<u64> = None;
    let mut t = 0u64;
    while t < 2_000_000 {
        if t.is_multiple_of(8_000) {
            s.on_message(t, pointer((t / 1000) as u16, 100, true), &mut rec).expect("pointer");
        }
        if let Some(g) = grab.filter(|&g| t >= g + 30_000) {
            let f = downscale(&cap.grab((480, 480)).expect("grab"), 480, 480);
            if let Some(Message::Frame { seq, .. }) = offer_late(&mut s, g, t, &f) {
                s.on_message(t, Message::FrameAck { seq, decode_us: 1 }, &mut rec).expect("ack");
            }
            grab = None;
        }
        if grab.is_none() && s.wants_frame(t) {
            starts.push(t);
            grab = Some(t);
        }
        t += 1_000;
    }
    assert!(starts.windows(2).all(|w| w[1] - w[0] >= 50_000), "grab starts at least one interval apart");
    assert_eq!(starts.len(), 40, "20 fps for 2 s, where polling alone would give 2 s / 80 ms = 25");
    assert!(s.stats.input_to_capture_us.iter().all(|&d| d <= 50_000));
}

#[test]
fn dropping_a_bound_session_mid_drag_releases_even_in_a_panic() {
    let mut rec: Vec<InputEvent> = Vec::new();
    let unwound = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut b = Bound { session: started(0), inj: &mut rec };
        b.session.on_message(0, pointer(0, 0, true), &mut b.inj).expect("press");
        assert!(b.session.button_held());
        panic!("the session loop failed");
    }));
    assert!(unwound.is_err());
    assert!(matches!(rec.last(), Some(InputEvent::Release { .. })), "released on unwind: {rec:?}");
}

#[test]
fn a_bound_session_closed_then_dropped_releases_once() {
    let mut rec: Vec<InputEvent> = Vec::new();
    {
        let mut b = Bound { session: started(0), inj: &mut rec };
        b.session.on_message(0, pointer(0, 0, true), &mut b.inj).expect("press");
        b.close();
    }
    let releases = rec.iter().filter(|e| matches!(e, InputEvent::Release { .. })).count();
    assert_eq!(releases, 1);
}

#[test]
fn quick_presses_in_one_place_count_up_and_others_start_again() {
    let mut c = ClickCounter::new(500_000, 16.0);
    assert_eq!(c.press(0, 100.0, 100.0), 1);
    assert_eq!(c.count(), 1, "its release carries the press's count");
    assert_eq!(c.press(300_000, 105.0, 110.0), 2, "within the interval and the radius");
    assert_eq!(c.count(), 2);
    assert_eq!(c.press(700_000, 100.0, 100.0), 3, "the interval runs from the previous press");
    assert_eq!(c.press(1_300_000, 100.0, 100.0), 1, "too late");
    assert_eq!(c.press(1_400_000, 130.0, 100.0), 1, "too far");
    assert_eq!(c.press(1_900_000, 130.0, 100.0), 2, "exactly at the interval still counts");
}

#[test]
fn a_failing_capture_is_retried_once_an_interval_not_at_once() {
    let mut s = started(0);
    assert!(s.wants_frame(0));
    s.capture_failed(1_000);
    assert!(!s.wants_frame(1_000), "a failure is paced like a capture");
    assert!(!s.wants_frame(50_999));
    assert!(s.wants_frame(51_000), "and retried one interval later");
    // A touch waiting to be shown doesn't pull a retry of a failing source forward.
    s.on_message(60_000, Message::Pointer { x: 100, y: 100, buttons: 0 }, &mut Vec::new()).expect("pointer");
    s.capture_failed(60_000);
    assert!(!s.wants_frame(60_001));
    assert!(s.wants_frame(110_000));
}

#[test]
fn a_frame_too_big_for_one_message_is_skipped_not_sent() {
    let mut s = started(0);
    let big = Prepared { width: 480, height: 270, hash: 1, captured_us: 0, encoded: Some((CODEC_JPEG, vec![0; MAX_FRAME_DATA + 1])) };
    assert_eq!(s.offer_frame(0, big), None);
    assert_eq!(s.stats.skipped_oversize, 1);
    assert_eq!(s.in_flight(), 0);
    let fits = Prepared { width: 480, height: 270, hash: 2, captured_us: 60_000, encoded: Some((CODEC_JPEG, vec![0; MAX_FRAME_DATA])) };
    let Some(frame) = s.offer_frame(60_000, fits) else { panic!("a frame at the limit is sent") };
    assert_eq!(frame.encode().len(), bluke_mirror_core::codec::MAX_LEN as usize + 4, "exactly at the cap");
}
