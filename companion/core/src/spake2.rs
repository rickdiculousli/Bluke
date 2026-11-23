//! SPAKE2 per RFC 9382, suite P256-SHA256-HKDF-HMAC, and Bluke's code-to-w rule.

use hkdf::Hkdf;
use hmac::{Hmac, Mac};
use p256::elliptic_curve::ops::Reduce;
use p256::elliptic_curve::sec1::{FromEncodedPoint, ToEncodedPoint};
use p256::elliptic_curve::{Field, PrimeField};
use p256::{AffinePoint, EncodedPoint, FieldBytes, ProjectivePoint, Scalar, U256};
use sha2::{Digest, Sha256, Sha512};
use subtle::ConstantTimeEq;

/// RFC 9382's M for P-256, compressed SEC1.
const M: [u8; 33] = hex33("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f");
/// RFC 9382's N for P-256, compressed SEC1.
const N: [u8; 33] = hex33("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49");

/// Bluke's AAD for the confirmation keys.
pub const AAD: &[u8] = b"bluke-mirror v1";
const W_PREFIX: &[u8] = b"bluke-mirror pairing v1";

const fn hex33(s: &str) -> [u8; 33] {
    let b = s.as_bytes();
    let mut out = [0u8; 33];
    let mut i = 0;
    while i < 33 {
        out[i] = (nib(b[2 * i]) << 4) | nib(b[2 * i + 1]);
        i += 1;
    }
    out
}

const fn nib(c: u8) -> u8 {
    match c {
        b'0'..=b'9' => c - b'0',
        b'a'..=b'f' => c - b'a' + 10,
        _ => panic!("not hex"),
    }
}

/// The standard M and N, compressed SEC1, as RFC 9382 lists them.
pub fn m_n() -> ([u8; 33], [u8; 33]) {
    (M, N)
}

fn point(compressed: &[u8; 33]) -> ProjectivePoint {
    let ep = EncodedPoint::from_bytes(compressed).expect("constant point encodes");
    ProjectivePoint::from(AffinePoint::from_encoded_point(&ep).expect("constant point is on the curve"))
}

/// Which side of the exchange: A sends pA and checks cB; B sends pB and checks cA.
/// In Bluke, A is the companion and B the phone.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    A,
    B,
}

/// Why a SPAKE2 run failed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PakeError {
    /// The peer's share isn't a valid non-identity point, or K came out as the identity.
    BadPoint,
    /// The peer's confirmation MAC didn't match: wrong code, or the identities differ (a relay).
    BadConfirmation,
}

/// The password scalar w for a six-digit code:
/// SHA-512("bluke-mirror pairing v1" ‖ 0x00 ‖ digits) as a big-endian integer, mod n.
pub fn code_to_w(code: &str) -> [u8; 32] {
    let digest = Sha512::new().chain_update(W_PREFIX).chain_update([0u8]).chain_update(code.as_bytes()).finalize();
    scalar_bytes(&reduce_wide(&digest.into()))
}

/// A big-endian 512-bit integer mod n.
fn reduce_wide(bytes: &[u8; 64]) -> Scalar {
    let hi = reduce(bytes[..32].try_into().expect("32"));
    let lo = reduce(bytes[32..].try_into().expect("32"));
    // 2^256 mod n, as (2^128)^2.
    let mut two128 = [0u8; 32];
    two128[15] = 1;
    let t = Scalar::from_repr(FieldBytes::from(two128)).expect("2^128 < n");
    hi * t * t + lo
}

fn reduce(bytes: &[u8; 32]) -> Scalar {
    <Scalar as Reduce<U256>>::reduce_bytes(&FieldBytes::from(*bytes))
}

fn scalar_bytes(s: &Scalar) -> [u8; 32] {
    s.to_repr().into()
}

fn encode(p: &ProjectivePoint) -> [u8; 65] {
    p.to_affine().to_encoded_point(false).as_bytes().try_into().expect("uncompressed point is 65 bytes")
}

fn decode(bytes: &[u8; 65]) -> Option<ProjectivePoint> {
    let ep = EncodedPoint::from_bytes(bytes).ok()?;
    if ep.is_identity() || ep.is_compressed() {
        return None;
    }
    let p: Option<AffinePoint> = AffinePoint::from_encoded_point(&ep).into();
    p.map(ProjectivePoint::from)
}

/// One side of a SPAKE2 run, holding its secret until the peer's share arrives.
pub struct Spake2 {
    role: Role,
    w: Scalar,
    secret: Scalar,
    share: [u8; 65],
    id_a: Vec<u8>,
    id_b: Vec<u8>,
    aad: Vec<u8>,
}

/// Everything a finished run derives. The transcript and K are kept for tests against the RFC.
#[derive(Clone, Debug)]
pub struct Keys {
    pub k: [u8; 65],
    pub tt: Vec<u8>,
    pub ke: [u8; 16],
    pub ka: [u8; 16],
    pub kc_a: [u8; 16],
    pub kc_b: [u8; 16],
    pub c_a: [u8; 32],
    pub c_b: [u8; 32],
    role: Role,
}

impl Keys {
    /// The MAC this side sends: cA for A, cB for B.
    pub fn own_confirmation(&self) -> [u8; 32] {
        match self.role {
            Role::A => self.c_a,
            Role::B => self.c_b,
        }
    }

    /// Checks the peer's MAC in constant time.
    pub fn verify_peer(&self, mac: &[u8; 32]) -> Result<(), PakeError> {
        let expected = match self.role {
            Role::A => &self.c_b,
            Role::B => &self.c_a,
        };
        if bool::from(expected.ct_eq(mac)) { Ok(()) } else { Err(PakeError::BadConfirmation) }
    }
}

impl Spake2 {
    /// Starts a run. `w` is the password scalar (big-endian, already reduced as [`code_to_w`] makes it);
    /// `secret` is 32 random bytes for x (A) or y (B), reduced mod n; a zero secret is refused by panic,
    /// since it can only come from a broken random source.
    pub fn new(role: Role, w: &[u8; 32], secret: &[u8; 32], id_a: &[u8], id_b: &[u8], aad: &[u8]) -> Spake2 {
        let w = reduce(w);
        let secret = reduce(secret);
        assert!(!bool::from(secret.is_zero()), "SPAKE2 secret must be non-zero");
        let blind = match role {
            Role::A => point(&M),
            Role::B => point(&N),
        };
        let share = encode(&(ProjectivePoint::GENERATOR * secret + blind * w));
        Spake2 { role, w, secret, share, id_a: id_a.to_vec(), id_b: id_b.to_vec(), aad: aad.to_vec() }
    }

    /// This side's share: pA for A, pB for B.
    pub fn share(&self) -> [u8; 65] {
        self.share
    }

    /// Takes the peer's share and derives the keys and both confirmation MACs.
    pub fn finish(self, peer: &[u8; 65]) -> Result<Keys, PakeError> {
        let peer_point = decode(peer).ok_or(PakeError::BadPoint)?;
        let unblind = match self.role {
            Role::A => point(&N),
            Role::B => point(&M),
        };
        // P-256 has cofactor 1.
        let k_point = (peer_point - unblind * self.w) * self.secret;
        if k_point == ProjectivePoint::IDENTITY {
            return Err(PakeError::BadPoint);
        }
        let k = encode(&k_point);
        let (p_a, p_b) = match self.role {
            Role::A => (self.share, *peer),
            Role::B => (*peer, self.share),
        };
        let mut tt = Vec::with_capacity(6 * 8 + 3 * 65 + 32 + self.id_a.len() + self.id_b.len());
        for part in [&self.id_a[..], &self.id_b[..], &p_a, &p_b, &k, &scalar_bytes(&self.w)] {
            tt.extend_from_slice(&(part.len() as u64).to_le_bytes());
            tt.extend_from_slice(part);
        }
        let hash = Sha256::digest(&tt);
        let ke: [u8; 16] = hash[..16].try_into().expect("16");
        let ka: [u8; 16] = hash[16..].try_into().expect("16");
        let mut info = b"ConfirmationKeys".to_vec();
        info.extend_from_slice(&self.aad);
        let mut kc = [0u8; 32];
        Hkdf::<Sha256>::new(None, &ka).expand(&info, &mut kc).expect("32 bytes is a valid HKDF length");
        let kc_a: [u8; 16] = kc[..16].try_into().expect("16");
        let kc_b: [u8; 16] = kc[16..].try_into().expect("16");
        let mac = |key: &[u8]| -> [u8; 32] {
            let mut m = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC takes any key length");
            m.update(&tt);
            m.finalize().into_bytes().into()
        };
        let c_a = mac(&kc_a);
        let c_b = mac(&kc_b);
        Ok(Keys { k, tt, ke, ka, kc_a, kc_b, c_a, c_b, role: self.role })
    }
}
