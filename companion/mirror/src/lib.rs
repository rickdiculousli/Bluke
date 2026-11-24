//! `bluke-mirror`: Bluke's host companion. Captures the screen for the phone's Mirror surface
//! and injects the touches it sends back. The logic lives in `bluke-mirror-core`; this crate
//! holds the TLS link, mDNS, the config files and the platform backends.

pub mod config;
pub mod discover;
pub mod jpeg;
pub mod link;
pub mod pair;
pub mod platform;
#[cfg(feature = "rig")]
pub mod rig;
pub mod session;
pub mod tls;

use std::sync::OnceLock;
use std::time::Instant;

/// Microseconds on a monotonic clock that starts at the first call.
pub fn now_us() -> u64 {
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_micros() as u64
}

/// 32 bytes from the operating system's secure random source.
pub fn random32() -> [u8; 32] {
    let mut b = [0u8; 32];
    getrandom::getrandom(&mut b).expect("the OS random source works");
    b
}

/// Parses `ip[:port]`, defaulting the port to 47800.
pub fn parse_phone(s: &str) -> Result<std::net::SocketAddr, String> {
    use std::net::{IpAddr, SocketAddr};
    if let Ok(a) = s.parse::<SocketAddr>() {
        return Ok(a);
    }
    s.trim_matches(|c| c == '[' || c == ']')
        .parse::<IpAddr>()
        .map(|ip| SocketAddr::new(ip, bluke_mirror_core::codec::DEFAULT_PORT))
        .map_err(|_| format!("not an address: {s} (expected ip or ip:port)"))
}
