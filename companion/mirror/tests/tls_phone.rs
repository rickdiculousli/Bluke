//! The companion against a test phone over real TLS on 127.0.0.1. The phone is built from the
//! core's phone-role pairing machine and a rustls server that requires a client certificate,
//! as Bluke runs it.

use bluke_mirror::config::{Config, Identity};
use bluke_mirror::link::Link;
use bluke_mirror::rig::{Recording, Schedule, Synthetic};
use bluke_mirror::{jpeg, now_us, pair, random32, session};
use bluke_mirror_core::Fingerprint;
use bluke_mirror_core::codec::{Message, refused_reason};
use bluke_mirror_core::pairing::{PhonePairing, draw_code};
use bluke_mirror_core::session::Close;
use std::net::{SocketAddr, TcpListener};
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;
use std::sync::mpsc::{Receiver, Sender, channel};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

/// What the phone does on its next accepted connection.
#[derive(Clone, Copy, Debug)]
enum Script {
    /// A pairing run (or a session refused/served per the pin, if the companion says Hello).
    Pair,
    /// Serve a session: after 3 frames, press, drag and release; after 2 more frames, close.
    PressDragRelease,
    /// Serve a session: after 3 frames, press and drag, then close mid-drag.
    DropMidDrag,
}

/// What the phone saw on one connection.
#[derive(Debug, Default)]
struct Seen {
    peer: Option<Fingerprint>,
    messages: Vec<Message>,
}

struct Phone {
    addr: SocketAddr,
    fingerprint: Fingerprint,
    scripts: Sender<Script>,
    codes: Receiver<String>,
    pinned: Arc<Mutex<Option<Fingerprint>>>,
    seen: Receiver<Seen>,
    _thread: JoinHandle<()>,
}

fn dir(name: &str) -> PathBuf {
    let d = Path::new(env!("CARGO_TARGET_TMPDIR")).join(format!("tls_phone-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).expect("temp dir");
    d
}

fn start_phone(name: &str) -> Phone {
    let id: Identity = Config::new(dir(&format!("phone-{name}"))).identity().expect("phone identity");
    let fingerprint = id.fingerprint();
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
    let addr = listener.local_addr().expect("addr");
    let (scripts_tx, scripts_rx) = channel::<Script>();
    let (codes_tx, codes_rx) = channel::<String>();
    let (seen_tx, seen_rx) = channel::<Seen>();
    let pinned = Arc::new(Mutex::new(None));
    let pinned_t = pinned.clone();
    let thread = std::thread::spawn(move || {
        // One Mirror session on the phone: one attempt budget across connections.
        let mut pairing = PhonePairing::new();
        for script in scripts_rx {
            let (sock, _) = listener.accept().expect("accept");
            let mut seen = Seen::default();
            if let Ok(link) = Link::accept(sock, &id) {
                seen.peer = Some(link.peer());
                serve(link, script, fingerprint, &mut pairing, &pinned_t, &codes_tx, &mut seen);
            }
            if seen_tx.send(seen).is_err() {
                break;
            }
        }
    });
    Phone { addr, fingerprint, scripts: scripts_tx, codes: codes_rx, pinned, seen: seen_rx, _thread: thread }
}

fn serve(
    mut link: Link,
    script: Script,
    own: Fingerprint,
    pairing: &mut PhonePairing,
    pinned: &Mutex<Option<Fingerprint>>,
    codes: &Sender<String>,
    seen: &mut Seen,
) {
    let peer = link.peer();
    let Ok(first) = link.recv_one(Duration::from_secs(10)) else { return };
    seen.messages.push(first.clone());
    match first {
        Message::PairRequest { .. } => {
            let code = draw_code(&mut |b: &mut [u8]| b.copy_from_slice(&random32()[..b.len()]));
            let mut out = pairing.on_pair_request(now_us(), code.clone(), peer, own);
            let _ = codes.send(code);
            loop {
                for m in &out.send {
                    let _ = link.send(m);
                }
                if let Some(p) = out.pin {
                    *pinned.lock().expect("lock") = Some(p);
                }
                if out.close {
                    break;
                }
                let Ok(m) = link.recv_one(Duration::from_secs(10)) else { break };
                seen.messages.push(m.clone());
                out = pairing.on_message(now_us(), m, &random32());
            }
            pairing.on_close();
            link.close();
        }
        Message::Hello { .. } => {
            // Only a pinned companion gets a session.
            if *pinned.lock().expect("lock") != Some(peer) {
                let _ = link.send(&Message::Refused { reason: refused_reason::NOT_PAIRED });
                link.close();
                return;
            }
            let _ = link.send(&Message::Welcome { version: 1, codecs: 1, max_width: 480, max_height: 480, fps: 60 });
            let mut frames = 0;
            let mut stop_after = None;
            while let Ok(m) = link.recv_one(Duration::from_secs(5)) {
                seen.messages.push(m.clone());
                let Message::Frame { seq, .. } = m else { continue };
                frames += 1;
                let _ = link.send(&Message::FrameAck { seq, decode_us: 1_000 });
                if frames == 3 {
                    let down = [Message::Pointer { x: 0, y: 0, buttons: 1 }, Message::Pointer { x: 32768, y: 32768, buttons: 1 }];
                    for p in &down {
                        let _ = link.send(p);
                    }
                    match script {
                        Script::DropMidDrag => break,
                        _ => {
                            let _ = link.send(&Message::Pointer { x: 65535, y: 65535, buttons: 0 });
                            stop_after = Some(frames + 2);
                        }
                    }
                }
                if stop_after == Some(frames) {
                    break;
                }
            }
            link.close();
        }
        _ => link.close(),
    }
}

impl Phone {
    /// Runs one companion action against the phone with `script`, and returns what the phone saw.
    fn with<T>(&self, script: Script, f: impl FnOnce() -> T) -> (T, Seen) {
        self.scripts.send(script).expect("phone running");
        let t = f();
        let seen = self.seen.recv_timeout(Duration::from_secs(20)).expect("phone finished the connection");
        (t, seen)
    }
}

fn run_session(phone: &Phone, config: &Config, record: &Path) -> session::Report {
    let id = config.identity().expect("identity");
    let link = Link::connect(phone.addr, &id).expect("connect");
    let stop = AtomicBool::new(false);
    let opts = session::Options { name: "test".into(), stop: &stop, until_us: Some(now_us() + 15_000_000) };
    let mut rec = Recording::create(record).expect("record file");
    let pinned = config.paired_phone().expect("read pin");
    session::run(link, pinned, Box::new(Synthetic::new(Schedule::Every(1))), &mut rec, &mut jpeg::Jpeg::default(), &opts, &mut |_| {})
}

fn events(record: &Path) -> Vec<(String, f64, f64)> {
    std::fs::read_to_string(record)
        .unwrap_or_default()
        .lines()
        .map(|l| {
            let v: serde_json::Value = serde_json::from_str(l).expect("JSON line");
            assert!(v["t_us"].is_u64(), "{l}");
            (v["kind"].as_str().expect("kind").to_string(), v["x"].as_f64().expect("x"), v["y"].as_f64().expect("y"))
        })
        .collect()
}

fn frames(seen: &Seen) -> Vec<&Message> {
    seen.messages.iter().filter(|m| matches!(m, Message::Frame { .. })).collect()
}

#[test]
fn pair_reconnect_stream_and_inject_over_tls() {
    let phone = start_phone("main");
    let base = dir("companion");
    let config = Config::new(base.join("config"));
    let companion_fp = config.identity().expect("identity").fingerprint();

    // Before pairing, a session attempt sends nothing: the companion has no pinned phone.
    let (report, seen) = phone.with(Script::Pair, || run_session(&phone, &config, &base.join("none.jsonl")));
    assert!(matches!(report.end, session::End::NotPinned), "{}", report.end);
    assert!(seen.messages.is_empty(), "nothing reached the phone: {:?}", seen.messages);

    // A wrong code pins nothing on either side.
    let (r, _) = phone.with(Script::Pair, || {
        pair::pair(phone.addr, &config, "test", &mut |_| {
            let code = phone.codes.recv_timeout(Duration::from_secs(5)).expect("code shown");
            Ok(format!("{:06}", (code.parse::<u32>().expect("digits") + 1) % 1_000_000))
        })
    });
    assert!(matches!(r, Err(pair::PairError::Failed(_))), "{r:?}");
    assert_eq!(config.paired_phone().expect("read"), None);
    assert_eq!(*phone.pinned.lock().expect("lock"), None);

    // The right code pins both sides.
    let (r, seen) = phone.with(Script::Pair, || {
        pair::pair(phone.addr, &config, "test", &mut |_| Ok(phone.codes.recv_timeout(Duration::from_secs(5)).expect("code shown")))
    });
    assert_eq!(r.expect("paired"), phone.fingerprint);
    assert_eq!(config.paired_phone().expect("read"), Some(phone.fingerprint));
    assert_eq!(*phone.pinned.lock().expect("lock"), Some(companion_fp));
    assert_eq!(seen.peer, Some(companion_fp));

    // Reconnect with Hello: frames stream, and a press-drag-release is injected in order.
    let record = base.join("drag.jsonl");
    let (report, seen) = phone.with(Script::PressDragRelease, || run_session(&phone, &config, &record));
    assert!(matches!(&report.end, session::End::Io(e) if e.kind() == std::io::ErrorKind::UnexpectedEof), "{}", report.end);
    assert!(matches!(seen.messages[0], Message::Hello { version: 1, .. }), "{:?}", seen.messages[0]);
    assert!(matches!(seen.messages[1], Message::Display { width: 1920, height: 1080, .. }), "{:?}", seen.messages[1]);
    let got = frames(&seen);
    assert!(got.len() >= 5, "frames: {}", got.len());
    for (i, f) in got.iter().enumerate() {
        let Message::Frame { seq, codec, width, height, data, .. } = f else { unreachable!() };
        assert_eq!((*seq, *codec, *width, *height), (i as u32, 1, 480, 270));
        assert_eq!(&data[..2], &[0xff, 0xd8], "JPEG SOI");
        assert_eq!(&data[data.len() - 2..], &[0xff, 0xd9], "JPEG EOI");
    }
    assert!(report.stats.frames_acked >= 5);
    assert_eq!(report.inject_latency_us.len(), 3);
    let x_mid = -1920.0 + 32768.0 / 65535.0 * 1919.0;
    let y_mid = 32768.0 / 65535.0 * 1079.0;
    let expected = vec![
        ("move".to_string(), -1920.0, 0.0),
        ("down".to_string(), -1920.0, 0.0),
        ("drag".to_string(), x_mid, y_mid),
        ("drag".to_string(), -1.0, 1079.0),
        ("up".to_string(), -1.0, 1079.0),
    ];
    assert_eq!(events(&record), expected);
    assert_eq!(report.releases, 0, "nothing was held at the end");

    // Reconnect again; the phone drops the link mid-drag and the companion releases.
    let record = base.join("drop.jsonl");
    let (report, _) = phone.with(Script::DropMidDrag, || run_session(&phone, &config, &record));
    let ev = events(&record);
    assert_eq!(ev.iter().map(|e| e.0.as_str()).collect::<Vec<_>>(), ["move", "down", "drag", "release"]);
    assert_eq!((ev[3].1, ev[3].2), (x_mid, y_mid));
    assert_eq!(report.releases, 1);

    // A companion the phone never pinned is refused, even though it pinned the phone.
    let stranger = Config::new(base.join("stranger"));
    stranger.pin_phone(&phone.fingerprint).expect("pin");
    let record = base.join("stranger.jsonl");
    let (report, seen) = phone.with(Script::PressDragRelease, || run_session(&phone, &stranger, &record));
    assert!(matches!(report.end, session::End::Closed(Close::Refused(1))), "{}", report.end);
    assert_eq!(seen.messages.len(), 1, "only Hello reached the phone: {:?}", seen.messages);
    assert_eq!(report.stats.frames_sent, 0);
    assert!(events(&record).is_empty());

    // A companion pinned to another phone sends nothing to this one.
    let elsewhere = Config::new(base.join("elsewhere"));
    elsewhere.pin_phone(&[7; 32]).expect("pin");
    let (report, seen) = phone.with(Script::PressDragRelease, || run_session(&phone, &elsewhere, &base.join("elsewhere.jsonl")));
    assert!(matches!(report.end, session::End::NotPinned));
    assert!(seen.messages.is_empty());
}
