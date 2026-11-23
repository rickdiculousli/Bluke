//! Captured images, the fast downscale, change detection, frame credit, and the capture trait.

use crate::pointer::Display;
use std::collections::VecDeque;

/// A captured image: RGBA8 rows of `stride` bytes.
#[derive(Clone, Debug)]
pub struct Image {
    pub width: u32,
    pub height: u32,
    pub stride: usize,
    pub rgba: Vec<u8>,
}

/// A downscaled frame, RGB8 tightly packed, with the hash that decides whether it is new.
#[derive(Clone, Debug)]
pub struct Scaled {
    pub width: u16,
    pub height: u16,
    pub rgb: Vec<u8>,
    pub hash: u64,
}

/// A screen source. Implementations report their display's rect in global coordinates;
/// the captured image may be larger (Retina pixels) and is scaled to fit.
pub trait Capture {
    fn display(&self) -> Display;
    fn grab(&mut self) -> Result<Image, String>;
}

/// The output size that fits `w`×`h` inside `max_w`×`max_h`, keeping the aspect ratio,
/// never upscaling, at least 1×1.
pub fn fit(w: u32, h: u32, max_w: u16, max_h: u16) -> (u16, u16) {
    let (max_w, max_h) = (u64::from(max_w.max(1)), u64::from(max_h.max(1)));
    let (w, h) = (u64::from(w.max(1)), u64::from(h.max(1)));
    if w <= max_w && h <= max_h {
        return (w as u16, h as u16);
    }
    // Scale by the tighter of the two limits.
    if w * max_h >= h * max_w {
        (max_w as u16, ((h * max_w + w / 2) / w).max(1) as u16)
    } else {
        (((w * max_h + h / 2) / h).max(1) as u16, max_h as u16)
    }
}

/// Samples per axis inside each output pixel's source box; bounds the work per output pixel.
const GRID: u32 = 3;

/// Downscales to fit `max_w`×`max_h`, averaging a bounded grid of samples inside each
/// output pixel's source box rather than every source pixel, and hashes the result.
pub fn downscale(img: &Image, max_w: u16, max_h: u16) -> Scaled {
    let (ow, oh) = fit(img.width, img.height, max_w, max_h);
    let (ow32, oh32) = (u32::from(ow), u32::from(oh));
    let offsets = |src: u32, out: u32| -> Vec<[usize; GRID as usize]> {
        (0..out)
            .map(|o| {
                let lo = u64::from(o) * u64::from(src) / u64::from(out);
                let hi = (u64::from(o + 1) * u64::from(src) / u64::from(out)).max(lo + 1);
                let span = hi - lo;
                std::array::from_fn(|k| (lo + (2 * k as u64 + 1) * span / (2 * u64::from(GRID))) as usize)
            })
            .collect()
    };
    let xs = offsets(img.width, ow32);
    let ys = offsets(img.height, oh32);
    let mut rgb = Vec::with_capacity(ow as usize * oh as usize * 3);
    for row in &ys {
        for col in &xs {
            let mut acc = [0u32; 3];
            for &sy in row {
                let line = &img.rgba[sy * img.stride..];
                for &sx in col {
                    let p = &line[sx * 4..sx * 4 + 3];
                    acc[0] += u32::from(p[0]);
                    acc[1] += u32::from(p[1]);
                    acc[2] += u32::from(p[2]);
                }
            }
            let n = GRID * GRID;
            rgb.extend(acc.iter().map(|&c| ((c + n / 2) / n) as u8));
        }
    }
    let hash = hash_pixels(ow, oh, &rgb);
    Scaled { width: ow, height: oh, rgb, hash }
}

/// FNV-1a over the size and pixels, eight bytes at a time.
pub fn hash_pixels(w: u16, h: u16, rgb: &[u8]) -> u64 {
    const PRIME: u64 = 0x0000_0100_0000_01b3;
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325 ^ (u64::from(w) << 16 | u64::from(h));
    let (words, rest) = rgb.as_chunks::<8>();
    for c in words {
        hash = (hash ^ u64::from_le_bytes(*c)).wrapping_mul(PRIME);
    }
    for &b in rest {
        hash = (hash ^ u64::from(b)).wrapping_mul(PRIME);
    }
    hash
}

/// At most this many frames are sent and not yet acknowledged.
pub const MAX_IN_FLIGHT: usize = 2;

/// Frames in flight and their send times.
#[derive(Debug, Default)]
pub struct FrameCredit {
    in_flight: VecDeque<(u32, u64)>,
}

impl FrameCredit {
    pub fn in_flight(&self) -> usize {
        self.in_flight.len()
    }

    pub fn can_send(&self) -> bool {
        self.in_flight.len() < MAX_IN_FLIGHT
    }

    /// Records a frame sent. Panics if there was no credit: the caller checks first.
    pub fn sent(&mut self, seq: u32, now_us: u64) {
        assert!(self.can_send(), "frame sent without credit");
        self.in_flight.push_back((seq, now_us));
    }

    /// Acknowledges every frame up to and including `seq`; returns each one's send-to-ack time
    /// in µs. An ack for a frame not in flight changes nothing.
    pub fn ack(&mut self, seq: u32, now_us: u64) -> Vec<u64> {
        if !self.in_flight.iter().any(|&(s, _)| s == seq) {
            return Vec::new();
        }
        let mut done = Vec::new();
        while let Some(&(s, t)) = self.in_flight.front() {
            self.in_flight.pop_front();
            done.push(now_us.saturating_sub(t));
            if s == seq {
                break;
            }
        }
        done
    }
}

/// Turns a downscaled frame into codec bytes (JPEG in the shipped companion).
pub trait FrameEncoder {
    fn codec(&self) -> u8;
    fn encode(&mut self, frame: &Scaled) -> Vec<u8>;
}
