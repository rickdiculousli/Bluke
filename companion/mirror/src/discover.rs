//! Finding the phone over mDNS: `_bluke-mirror._tcp`, TXT `v=1`, `id=<16 hex digits>`.

use mdns_sd::{ServiceDaemon, ServiceEvent};
use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};

pub const SERVICE: &str = "_bluke-mirror._tcp.local.";

/// One phone advertising Mirror.
#[derive(Clone, Debug)]
pub struct Found {
    pub addr: SocketAddr,
    pub id: String,
    pub name: String,
}

/// Browses for `wait`. With `want_id`, returns as soon as the phone with that `id` shows up;
/// without, returns every phone seen in that time.
pub fn browse(want_id: Option<&str>, wait: Duration) -> std::io::Result<Vec<Found>> {
    let daemon = ServiceDaemon::new().map_err(std::io::Error::other)?;
    let events = daemon.browse(SERVICE).map_err(std::io::Error::other)?;
    let deadline = Instant::now() + wait;
    let mut found: Vec<Found> = Vec::new();
    while let Some(left) = deadline.checked_duration_since(Instant::now()) {
        let Ok(event) = events.recv_timeout(left) else { break };
        let ServiceEvent::ServiceResolved(s) = event else { continue };
        if s.get_property_val_str("v") != Some("1") {
            continue;
        }
        let id = s.get_property_val_str("id").unwrap_or_default().to_ascii_lowercase();
        // Prefer IPv4: the phone listens on its Wi-Fi address.
        let mut ips: Vec<IpAddr> = s.get_addresses_v4().into_iter().map(IpAddr::V4).collect();
        ips.sort();
        let Some(ip) = ips.first().copied() else { continue };
        let name = s.fullname.trim_end_matches(SERVICE).trim_end_matches('.').to_string();
        let f = Found { addr: SocketAddr::new(ip, s.port), id, name };
        if want_id.is_some_and(|w| w.eq_ignore_ascii_case(&f.id)) {
            let _ = daemon.shutdown();
            return Ok(vec![f]);
        }
        if want_id.is_none() && !found.iter().any(|g| g.id == f.id) {
            found.push(f);
        }
    }
    let _ = daemon.shutdown();
    Ok(found)
}
