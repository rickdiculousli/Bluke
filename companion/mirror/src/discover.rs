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

/// After the first advertisement of the wanted `id`, how long to keep listening for others with
/// the same `id`: anyone can advertise it, so the companion tries each address it was given.
const SAME_ID_GRACE: Duration = Duration::from_millis(300);

/// Browses for `wait`. With `want_id`, returns every address advertising that `id` seen within
/// a short grace after the first; without, returns every phone seen in that time.
pub fn browse(want_id: Option<&str>, wait: Duration) -> std::io::Result<Vec<Found>> {
    let daemon = ServiceDaemon::new().map_err(std::io::Error::other)?;
    let events = daemon.browse(SERVICE).map_err(std::io::Error::other)?;
    let mut deadline = Instant::now() + wait;
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
        let name = s.fullname.trim_end_matches(SERVICE).trim_end_matches('.').to_string();
        match want_id {
            Some(w) if w.eq_ignore_ascii_case(&id) => {
                for ip in ips {
                    let addr = SocketAddr::new(ip, s.port);
                    if !found.iter().any(|g| g.addr == addr) {
                        found.push(Found { addr, id: id.clone(), name: name.clone() });
                    }
                }
                deadline = deadline.min(Instant::now() + SAME_ID_GRACE);
            }
            Some(_) => {}
            None => {
                let Some(ip) = ips.first().copied() else { continue };
                if !found.iter().any(|g| g.id == id) {
                    found.push(Found { addr: SocketAddr::new(ip, s.port), id, name });
                }
            }
        }
    }
    let _ = daemon.shutdown();
    Ok(found)
}
