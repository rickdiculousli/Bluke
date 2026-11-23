//! Baseline JPEG for frames (codec 1).

use bluke_mirror_core::codec::CODEC_JPEG;
use bluke_mirror_core::frames::{FrameEncoder, Scaled};
use jpeg_encoder::{ColorType, Encoder};

/// JPEG quality for frames.
pub const QUALITY: u8 = 60;

pub struct Jpeg {
    pub quality: u8,
}

impl Default for Jpeg {
    fn default() -> Self {
        Jpeg { quality: QUALITY }
    }
}

impl FrameEncoder for Jpeg {
    fn codec(&self) -> u8 {
        CODEC_JPEG
    }

    fn encode(&mut self, f: &Scaled) -> Vec<u8> {
        let mut out = Vec::with_capacity(32 * 1024);
        Encoder::new(&mut out, self.quality)
            .encode(&f.rgb, f.width, f.height, ColorType::Rgb)
            .expect("encoding RGB into memory cannot fail");
        out
    }
}
