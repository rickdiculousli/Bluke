//! The companion side of the Android end-to-end mirror rig: the synthetic screen, changing every
//! frame, and the recording injector, against a phone reached at `--phone`. Prints one JSON
//! summary line at exit.

use bluke_mirror::config::Config;
use bluke_mirror::rig::{Recording, Schedule, Synthetic};
use bluke_mirror::{jpeg, link::Link, now_us, pair, parse_phone, session};
use bluke_mirror_core::{Injector, InputEvent};
use std::io::BufRead;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

const USAGE: &str = "\
rig: bluke-mirror's synthetic source and recording injector, for the Android mirror rig.

Usage:
  rig --phone <ip>[:port] [--record <file>] [--config-dir <dir>] [--pair] [--seconds <n>] [--stop-on-eof]

  --phone        the phone (e.g. 127.0.0.1:<port> through adb forward)
  --record       append one JSON line per injected event to this file
  --config-dir   where identity.key, identity.crt and paired-phone live (default: cache/rig-companion)
  --pair         first pair: read the six-digit code from stdin, then continue into a session
  --seconds      how long to stream (default 10)
  --stop-on-eof  also stop, and print the summary, when stdin closes (after the code, with --pair)

At exit prints one JSON line: frames sent and acked, frame send-to-ack and pointer
read-to-inject latencies in ms (count, p50, p95, max).
";

const RETRY: Duration = Duration::from_millis(500);

struct Discard;

impl Injector for Discard {
    fn inject(&mut self, _: InputEvent) {}
}

struct Args {
    phone: SocketAddr,
    record: Option<PathBuf>,
    config_dir: PathBuf,
    pair: bool,
    seconds: u64,
    stop_on_eof: bool,
}

fn parse() -> Result<Option<Args>, String> {
    let mut phone = None;
    let mut record = None;
    let mut config_dir = None;
    let mut pair = false;
    let mut seconds = 10;
    let mut stop_on_eof = false;
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        let mut value = || it.next().ok_or(format!("{a} needs a value"));
        match a.as_str() {
            "-h" | "--help" => return Ok(None),
            "--phone" => phone = Some(parse_phone(&value()?)?),
            "--record" => record = Some(PathBuf::from(value()?)),
            "--config-dir" => config_dir = Some(PathBuf::from(value()?)),
            "--pair" => pair = true,
            "--stop-on-eof" => stop_on_eof = true,
            "--seconds" => seconds = value()?.parse().map_err(|_| "--seconds takes a whole number")?,
            other => return Err(format!("unknown argument: {other}")),
        }
    }
    let phone = phone.ok_or("--phone is required")?;
    let config_dir = config_dir.unwrap_or_else(|| PathBuf::from("cache/rig-companion"));
    Ok(Some(Args { phone, record, config_dir, pair, seconds, stop_on_eof }))
}

/// count, p50, p95 and max of µs samples, as a JSON object in ms.
fn summary(samples: &mut [u64]) -> String {
    samples.sort_unstable();
    let pick = |q: f64| -> f64 {
        if samples.is_empty() {
            return 0.0;
        }
        let i = ((samples.len() as f64 - 1.0) * q).round() as usize;
        samples[i] as f64 / 1000.0
    };
    format!(
        "{{\"count\": {}, \"p50\": {:.3}, \"p95\": {:.3}, \"max\": {:.3}}}",
        samples.len(),
        pick(0.5),
        pick(0.95),
        samples.last().map_or(0.0, |&m| m as f64 / 1000.0)
    )
}

/// A JSON string literal.
fn json_str(s: &str) -> String {
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn fail(msg: &str) -> ExitCode {
    eprintln!("rig: {msg}");
    ExitCode::FAILURE
}

fn main() -> ExitCode {
    let args = match parse() {
        Ok(Some(a)) => a,
        Ok(None) => {
            print!("{USAGE}");
            return ExitCode::SUCCESS;
        }
        Err(e) => return fail(&format!("{e}\n\n{USAGE}")),
    };
    let deadline = now_us() + args.seconds * 1_000_000;
    let config = Config::new(&args.config_dir);
    if args.pair {
        let mut ask = |_: u8| -> std::io::Result<String> {
            let mut line = String::new();
            std::io::stdin().lock().read_line(&mut line)?;
            Ok(line.trim().to_string())
        };
        loop {
            match pair::pair(args.phone, &config, "bluke-mirror rig", &mut ask) {
                Ok(_) => {
                    eprintln!("rig: paired");
                    break;
                }
                // The phone may not be listening yet.
                Err(pair::PairError::Io(e)) if e.kind() == std::io::ErrorKind::ConnectionRefused && now_us() < deadline => {
                    std::thread::sleep(RETRY)
                }
                Err(e) => return fail(&format!("pairing failed: {e}")),
            }
        }
    }
    let pinned = match config.paired_phone() {
        Ok(Some(p)) => p,
        Ok(None) => return fail(&format!("no paired phone in {}; pass --pair", args.config_dir.display())),
        Err(e) => return fail(&e.to_string()),
    };
    let id = match config.identity() {
        Ok(i) => i,
        Err(e) => return fail(&e.to_string()),
    };
    let mut injector: Box<dyn Injector> = match &args.record {
        Some(path) => match Recording::create(path) {
            Ok(r) => Box::new(r),
            Err(e) => return fail(&format!("{}: {e}", path.display())),
        },
        None => Box::new(Discard),
    };
    static STOP: AtomicBool = AtomicBool::new(false);
    if args.stop_on_eof {
        std::thread::spawn(|| {
            // Drain stdin until it closes; the code line, if any, was read above.
            let _ = std::io::copy(&mut std::io::stdin().lock(), &mut std::io::sink());
            STOP.store(true, Ordering::Relaxed);
        });
    }
    let stop = &STOP;
    let (mut sent, mut acked, mut sessions) = (0u64, 0u64, 0u32);
    let (mut ack_us, mut inject_us) = (Vec::new(), Vec::new());
    let mut last_end = String::from("never connected");
    while now_us() < deadline && !stop.load(Ordering::Relaxed) {
        let link = match Link::connect(args.phone, &id) {
            Ok(l) => l,
            Err(e) => {
                last_end = format!("connect: {e}");
                std::thread::sleep(RETRY);
                continue;
            }
        };
        sessions += 1;
        let opts = session::Options { name: "bluke-mirror rig".into(), stop, until_us: Some(deadline) };
        let capture = Box::new(Synthetic::new(Schedule::Every(1)));
        let report =
            session::run(link, Some(pinned), capture, injector.as_mut(), &mut jpeg::Jpeg::default(), &opts, &mut |s| eprintln!("rig: {s}"));
        sent += report.stats.frames_sent;
        acked += report.stats.frames_acked;
        ack_us.extend(report.stats.ack_latency_us);
        inject_us.extend(report.inject_latency_us);
        last_end = report.end.to_string();
        eprintln!("rig: session ended: {last_end}");
        if matches!(report.end, session::End::NotPinned | session::End::Closed(_)) {
            break;
        }
    }
    println!(
        "{{\"sessions\": {sessions}, \"frames_sent\": {sent}, \"frames_acked\": {acked}, \"frame_ack_ms\": {}, \"inject_ms\": {}, \"end\": {}}}",
        summary(&mut ack_us),
        summary(&mut inject_us),
        json_str(&last_end)
    );
    if sessions == 0 { ExitCode::FAILURE } else { ExitCode::SUCCESS }
}
