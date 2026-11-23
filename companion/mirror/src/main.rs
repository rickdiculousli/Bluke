//! The `bluke-mirror` command.

use bluke_mirror::config::{self, Config};
use bluke_mirror::{discover, jpeg, link, pair, parse_phone, platform, session};
use std::io::{BufRead, Write};
use std::net::SocketAddr;
use std::process::ExitCode;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

const USAGE: &str = "\
bluke-mirror: mirror this computer's screen to Bluke's Mirror surface, and take touches back.

Usage:
  bluke-mirror [--phone <ip>[:port]]        mirror to the paired phone (reconnects when it goes away)
  bluke-mirror pair [--phone <ip>[:port]]   pair with the phone showing Mirror; type the code it shows
  bluke-mirror --help

Without --phone the phone is found over mDNS on the local network. The default port is 47800.
Forget the paired phone by deleting the paired-phone file in the config directory:
  macOS: ~/Library/Application Support/bluke-mirror/paired-phone
";

/// Between connection attempts while the phone is away (it listens only while Mirror is on screen).
const RETRY: Duration = Duration::from_secs(2);

static STOP: AtomicBool = AtomicBool::new(false);

#[cfg(unix)]
fn catch_interrupt() {
    unsafe extern "C" {
        fn signal(sig: i32, handler: extern "C" fn(i32)) -> usize;
    }
    extern "C" fn on_signal(_: i32) {
        STOP.store(true, Ordering::Relaxed);
    }
    // SAFETY: the handler only stores to an atomic, which is async-signal-safe.
    unsafe {
        signal(2, on_signal); // SIGINT
        signal(15, on_signal); // SIGTERM
    }
}

#[cfg(not(unix))]
fn catch_interrupt() {}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut phone = None;
    let mut pairing = false;
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match a.as_str() {
            "-h" | "--help" | "help" => {
                print!("{USAGE}");
                return ExitCode::SUCCESS;
            }
            "pair" if !pairing => pairing = true,
            "--phone" => match it.next().map(|s| parse_phone(s)) {
                Some(Ok(addr)) => phone = Some(addr),
                Some(Err(e)) => return fail(&e),
                None => return fail("--phone needs an address"),
            },
            other => return fail(&format!("unknown argument: {other}\n\n{USAGE}")),
        }
    }
    let dir = match config::default_dir() {
        Ok(d) => d,
        Err(e) => return fail(&e.to_string()),
    };
    let config = Config::new(dir);
    if pairing {
        pair_command(&config, phone)
    } else {
        catch_interrupt();
        run_command(&config, phone)
    }
}

fn fail(msg: &str) -> ExitCode {
    eprintln!("bluke-mirror: {msg}");
    ExitCode::FAILURE
}

fn computer_name() -> String {
    let from = |cmd: &str, args: &[&str]| {
        std::process::Command::new(cmd)
            .args(args)
            .output()
            .ok()
            .filter(|o| o.status.success())
            .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
            .filter(|s| !s.is_empty())
    };
    let name = if cfg!(target_os = "macos") { from("scutil", &["--get", "ComputerName"]) } else { None };
    let name = name.or_else(|| from("hostname", &[])).unwrap_or_else(|| "Computer".into());
    bluke_mirror_core::codec::clip_str(&name)
}

fn pair_command(config: &Config, phone: Option<SocketAddr>) -> ExitCode {
    let addr = match phone {
        Some(a) => a,
        None => {
            println!("Looking for a phone showing Mirror on this network...");
            match discover::browse(None, Duration::from_secs(3)) {
                Ok(found) if found.len() == 1 => {
                    println!("Found {} at {}.", found[0].name, found[0].addr);
                    found[0].addr
                }
                Ok(found) if found.is_empty() => {
                    return fail("no phone found. Open Mirror on the phone (on the same Wi-Fi), or pass --phone with the address Mirror shows.");
                }
                Ok(found) => {
                    let list: Vec<String> = found.iter().map(|f| format!("  {} at {}", f.name, f.addr)).collect();
                    return fail(&format!("several phones are showing Mirror; pick one with --phone:\n{}", list.join("\n")));
                }
                Err(e) => return fail(&format!("mDNS browse failed: {e}; pass --phone instead")),
            }
        }
    };
    println!("Connecting to {addr}...");
    let mut ask = |attempts_left: u8| -> std::io::Result<String> {
        let stdin = std::io::stdin();
        loop {
            print!("Type the six-digit code the phone shows ({attempts_left} attempts left): ");
            std::io::stdout().flush()?;
            let mut line = String::new();
            if stdin.lock().read_line(&mut line)? == 0 {
                return Err(std::io::ErrorKind::UnexpectedEof.into());
            }
            let code: String = line.chars().filter(|c| !c.is_whitespace()).collect();
            if bluke_mirror_core::pairing::is_valid_code(&code) {
                return Ok(code);
            }
            println!("That isn't six digits.");
        }
    };
    match pair::pair(addr, config, &computer_name(), &mut ask) {
        Ok(fp) => {
            println!("Paired with the phone (key {}).", &config::hex(&fp)[..16]);
            println!("Run `bluke-mirror` to start mirroring.");
            ExitCode::SUCCESS
        }
        Err(e) => fail(&format!("pairing failed: {e}")),
    }
}

fn run_command(config: &Config, phone: Option<SocketAddr>) -> ExitCode {
    if let Err(e) = platform::check_permissions() {
        return fail(&e);
    }
    let pinned = match config.paired_phone() {
        Ok(Some(p)) => p,
        Ok(None) => return fail("no phone is paired yet. Open Mirror on the phone and run `bluke-mirror pair`."),
        Err(e) => return fail(&e.to_string()),
    };
    let id = match config.identity() {
        Ok(i) => i,
        Err(e) => return fail(&e.to_string()),
    };
    let mut injector = match platform::injector() {
        Ok(i) => i,
        Err(e) => return fail(&e),
    };
    let name = computer_name();
    let want_id = config::mdns_id(&pinned);
    let mut waiting_said = false;
    while !STOP.load(Ordering::Relaxed) {
        let addr = match phone {
            Some(a) => Some(a),
            None => discover::browse(Some(&want_id), RETRY).ok().and_then(|f| f.first().map(|f| f.addr)),
        };
        let connected = addr.map(|a| (a, link::Link::connect(a, &id)));
        let (addr, link) = match connected {
            Some((a, Ok(l))) => (a, l),
            _ => {
                if !waiting_said {
                    println!("Waiting for the phone: open Mirror on it.");
                    waiting_said = true;
                }
                // mDNS browsing already waited; a fixed address waits here.
                if phone.is_some() || addr.is_some() {
                    std::thread::sleep(RETRY);
                }
                continue;
            }
        };
        waiting_said = false;
        let capture = match platform::open_capture() {
            Ok(c) => c,
            Err(e) => return fail(&e),
        };
        let d = capture.display();
        println!("Connected to the phone at {addr}; mirroring {} ({}x{} points).", d.name, d.width, d.height);
        let opts = session::Options { name: name.clone(), stop: &STOP, until_us: None };
        let report = session::run(link, Some(pinned), capture, injector.as_mut(), &mut jpeg::Jpeg::default(), &opts, &mut |s| println!("{s}"));
        println!("Session ended: {}.", report.end);
        match report.end {
            session::End::Closed(bluke_mirror_core::session::Close::Refused(1)) => {
                return fail("the phone has forgotten this computer. Run `bluke-mirror pair` again.");
            }
            session::End::NotPinned if phone.is_some() => {
                return fail("the phone at that address isn't the paired phone. Run `bluke-mirror pair` to pair with it.");
            }
            session::End::Stopped => break,
            _ => std::thread::sleep(RETRY),
        }
    }
    ExitCode::SUCCESS
}
