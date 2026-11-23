//! The companion's config directory: its identity and the pinned phone (PROTOCOL.md, Files).

use bluke_mirror_core::Fingerprint;
use rustls_pki_types::pem::PemObject;
use rustls_pki_types::{CertificateDer, PrivateKeyDer};
use sha2::{Digest, Sha256};
use std::io;
use std::path::{Path, PathBuf};

/// `~/Library/Application Support/bluke-mirror/` on macOS,
/// `$XDG_CONFIG_HOME/bluke-mirror/` (default `~/.config/bluke-mirror/`) elsewhere.
pub fn default_dir() -> io::Result<PathBuf> {
    let home = || std::env::var_os("HOME").map(PathBuf::from).ok_or_else(|| io::Error::other("HOME is not set"));
    if cfg!(target_os = "macos") {
        return Ok(home()?.join("Library/Application Support/bluke-mirror"));
    }
    match std::env::var_os("XDG_CONFIG_HOME").filter(|v| !v.is_empty()) {
        Some(x) => Ok(PathBuf::from(x).join("bluke-mirror")),
        None => Ok(home()?.join(".config/bluke-mirror")),
    }
}

/// The companion's key pair and self-signed certificate.
pub struct Identity {
    pub cert: CertificateDer<'static>,
    pub key: PrivateKeyDer<'static>,
}

impl Identity {
    pub fn fingerprint(&self) -> Fingerprint {
        fingerprint(&self.cert)
    }
}

impl Clone for Identity {
    fn clone(&self) -> Self {
        Identity { cert: self.cert.clone(), key: self.key.clone_key() }
    }
}

/// SHA-256 of a certificate's DER bytes.
pub fn fingerprint(cert: &CertificateDer<'_>) -> Fingerprint {
    Sha256::digest(cert.as_ref()).into()
}

pub struct Config {
    dir: PathBuf,
}

impl Config {
    pub fn new(dir: impl Into<PathBuf>) -> Config {
        Config { dir: dir.into() }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// The pinned phone file; deleting it forgets the phone.
    pub fn paired_phone_path(&self) -> PathBuf {
        self.dir.join("paired-phone")
    }

    /// Loads `identity.key` and `identity.crt`, making them on first run.
    pub fn identity(&self) -> io::Result<Identity> {
        let (key_path, crt_path) = (self.dir.join("identity.key"), self.dir.join("identity.crt"));
        if !key_path.exists() || !crt_path.exists() {
            std::fs::create_dir_all(&self.dir)?;
            let made = rcgen::generate_simple_self_signed(vec!["bluke-mirror".to_string()]).map_err(io::Error::other)?;
            write_private(&key_path, made.signing_key.serialize_pem().as_bytes())?;
            std::fs::write(&crt_path, made.cert.pem())?;
        }
        let cert = CertificateDer::from_pem_file(&crt_path).map_err(|e| io::Error::other(format!("{}: {e}", crt_path.display())))?;
        let key = PrivateKeyDer::from_pem_file(&key_path).map_err(|e| io::Error::other(format!("{}: {e}", key_path.display())))?;
        Ok(Identity { cert, key })
    }

    /// The pinned phone's fingerprint, if one is paired.
    pub fn paired_phone(&self) -> io::Result<Option<Fingerprint>> {
        match std::fs::read_to_string(self.paired_phone_path()) {
            Ok(s) => parse_hex32(s.trim())
                .map(Some)
                .ok_or_else(|| io::Error::other(format!("{} is not a fingerprint", self.paired_phone_path().display()))),
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(None),
            Err(e) => Err(e),
        }
    }

    pub fn pin_phone(&self, fp: &Fingerprint) -> io::Result<()> {
        std::fs::create_dir_all(&self.dir)?;
        let tmp = self.dir.join("paired-phone.tmp");
        std::fs::write(&tmp, format!("{}\n", hex(fp)))?;
        std::fs::rename(tmp, self.paired_phone_path())
    }
}

#[cfg(unix)]
fn write_private(path: &Path, bytes: &[u8]) -> io::Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(path)?.write_all(bytes)
}

#[cfg(not(unix))]
fn write_private(path: &Path, bytes: &[u8]) -> io::Result<()> {
    std::fs::write(path, bytes)
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn parse_hex32(s: &str) -> Option<Fingerprint> {
    if s.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, o) in out.iter_mut().enumerate() {
        *o = u8::from_str_radix(s.get(2 * i..2 * i + 2)?, 16).ok()?;
    }
    Some(out)
}

/// The phone's mDNS `id`: the first 16 hex digits of its fingerprint.
pub fn mdns_id(fp: &Fingerprint) -> String {
    hex(&fp[..8])
}
