//! Test-only pieces (feature `rig`): the synthetic screen and a recording injector.

pub use bluke_mirror_core::synthetic::{Schedule, Synthetic};
use bluke_mirror_core::{InputEvent, Injector};
use std::io::Write;
use std::path::Path;

/// Appends one JSON line per event:
/// `{"t_us":…, "kind":"move|down|drag|up|wheel|release", "x":…, "y":…}` (wheel adds dx, dy).
pub struct Recording {
    file: std::fs::File,
}

impl Recording {
    pub fn create(path: &Path) -> std::io::Result<Recording> {
        Ok(Recording { file: std::fs::OpenOptions::new().create(true).append(true).open(path)? })
    }
}

/// An f64 as JSON: always with a decimal point.
fn num(v: f64) -> String {
    if v.fract() == 0.0 { format!("{v:.1}") } else { format!("{v}") }
}

impl Injector for Recording {
    fn inject(&mut self, event: InputEvent) {
        let (x, y) = event.point();
        let extra = match event {
            InputEvent::Wheel { dx, dy, .. } => format!(", \"dx\": {dx}, \"dy\": {dy}"),
            _ => String::new(),
        };
        let line = format!("{{\"t_us\": {}, \"kind\": \"{}\", \"x\": {}, \"y\": {}{extra}}}\n", crate::now_us(), event.kind(), num(x), num(y));
        // One write per line, so a reader never sees half an event.
        if let Err(e) = self.file.write_all(line.as_bytes()) {
            eprintln!("recording injector: {e}");
        }
    }
}
