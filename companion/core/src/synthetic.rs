//! A synthetic screen for tests and the rig: a fake 1920x1080 display at (-1920, 0) whose
//! pixels change on a schedule the caller sets.

use crate::frames::{Capture, Image};
use crate::pointer::Display;

/// How the synthetic screen's pixels change between grabs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Schedule {
    /// Never changes: change-only sending sends one frame and then nothing.
    Static,
    /// The picture changes on every `n`th grab (1: every grab).
    Every(u32),
}

pub struct Synthetic {
    schedule: Schedule,
    grabs: u64,
}

pub const WIDTH: u32 = 1920;
pub const HEIGHT: u32 = 1080;

impl Synthetic {
    pub fn new(schedule: Schedule) -> Synthetic {
        Synthetic { schedule, grabs: 0 }
    }

    /// Which picture the next grab shows.
    fn generation(&self) -> u64 {
        match self.schedule {
            Schedule::Static => 0,
            Schedule::Every(n) => self.grabs / u64::from(n.max(1)),
        }
    }
}

impl Capture for Synthetic {
    fn display(&self) -> Display {
        Display { id: 1, x: -1920.0, y: 0.0, width: f64::from(WIDTH), height: f64::from(HEIGHT), name: "Synthetic".into() }
    }

    fn grab(&mut self) -> Result<Image, String> {
        let g = self.generation();
        self.grabs += 1;
        let (w, h) = (WIDTH as usize, HEIGHT as usize);
        let mut rgba = vec![0u8; w * h * 4];
        // A gradient background, and a white bar 240 px wide that steps across with each generation.
        let bar = (g as usize * 97) % w;
        for (y, row) in rgba.chunks_exact_mut(w * 4).enumerate() {
            for (x, p) in row.chunks_exact_mut(4).enumerate() {
                let on_bar = (x + w - bar) % w < 240;
                p.copy_from_slice(&if on_bar { [255, 255, 255, 255] } else { [(x * 255 / w) as u8, (y * 255 / h) as u8, (g % 256) as u8, 255] });
            }
        }
        Ok(Image { width: WIDTH, height: HEIGHT, stride: w * 4, rgba })
    }
}
