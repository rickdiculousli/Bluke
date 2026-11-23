//! One pairing connection, driven over a [`Link`]: the user's local `bluke-mirror pair`.

use crate::config::Config;
use crate::link::Link;
use bluke_mirror_core::Fingerprint;
use bluke_mirror_core::pairing::{CompanionPairing, CompanionStep, PairFailure};
use std::io;
use std::net::SocketAddr;
use std::time::Duration;

/// Pairing waits this long for each phone message (the code arrives after a person reads it,
/// but that wait is on our side, not the phone's).
const STEP_TIMEOUT: Duration = Duration::from_secs(15);

#[derive(Debug)]
pub enum PairError {
    Io(io::Error),
    Failed(PairFailure),
}

impl std::fmt::Display for PairError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PairError::Io(e) => write!(f, "{e}"),
            PairError::Failed(p) => write!(f, "{p}"),
        }
    }
}

impl From<io::Error> for PairError {
    fn from(e: io::Error) -> Self {
        PairError::Io(e)
    }
}

/// Pairs with the phone at `addr`. `ask_code` is called with the phone's remaining attempts
/// and returns the six digits the user typed. On success the phone's fingerprint is pinned
/// in `config` and returned; on any failure nothing is pinned.
pub fn pair(addr: SocketAddr, config: &Config, name: &str, ask_code: &mut dyn FnMut(u8) -> io::Result<String>) -> Result<Fingerprint, PairError> {
    let id = config.identity()?;
    let mut link = Link::connect(addr, &id)?;
    let (mut run, request) = CompanionPairing::start(id.fingerprint(), link.peer(), name);
    link.send(&request)?;
    let result = loop {
        let m = match link.recv_one(STEP_TIMEOUT) {
            Ok(m) => m,
            Err(e) => break Err(e.into()),
        };
        match run.on_message(m) {
            Ok(CompanionStep::NeedCode { attempts_left }) => {
                let code = ask_code(attempts_left)?;
                let share = run.enter_code(&code, &crate::random32()).map_err(PairError::Failed)?;
                link.send(&share)?;
            }
            Ok(CompanionStep::Send(m)) => link.send(&m)?,
            Ok(CompanionStep::Paired(phone)) => {
                config.pin_phone(&phone)?;
                break Ok(phone);
            }
            Err(f) => break Err(PairError::Failed(f)),
        }
    };
    link.close();
    result
}
