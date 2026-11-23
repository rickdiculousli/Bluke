//! Pointer mapping and the button-state machine of PROTOCOL.md's Pointer section.

/// A display's rect in the platform's global coordinates (points on macOS).
#[derive(Clone, Debug, PartialEq)]
pub struct Display {
    pub id: u32,
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    pub name: String,
}

impl Display {
    /// Maps a normalised value (0..=65535) on one axis to `origin + v/65535 × (size − 1)`,
    /// clamped to the rect.
    fn map_axis(origin: f64, size: f64, v: u16) -> f64 {
        let last = (size - 1.0).max(0.0);
        (origin + f64::from(v) / 65535.0 * last).clamp(origin, origin + last)
    }

    /// Maps a phone point to global coordinates inside this display.
    pub fn map(&self, x: u16, y: u16) -> (f64, f64) {
        (Display::map_axis(self.x, self.width, x), Display::map_axis(self.y, self.height, y))
    }

    /// Clamps any global point into this display's rect.
    pub fn clamp(&self, x: f64, y: f64) -> (f64, f64) {
        (x.clamp(self.x, self.x + (self.width - 1.0).max(0.0)), y.clamp(self.y, self.y + (self.height - 1.0).max(0.0)))
    }
}

/// One injected input event, at global coordinates.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum InputEvent {
    /// Move with no button held.
    Move { x: f64, y: f64 },
    /// Press the left button.
    Down { x: f64, y: f64 },
    /// Move with the left button held (on macOS `LeftMouseDragged`).
    Drag { x: f64, y: f64 },
    /// Release the left button after a touch lifted.
    Up { x: f64, y: f64 },
    /// Scroll by pixels, signed as PROTOCOL.md's Wheel says.
    Wheel { x: f64, y: f64, dx: i16, dy: i16 },
    /// Release a held button because the link dropped, went quiet, or the phone asked.
    Release { x: f64, y: f64 },
}

impl InputEvent {
    pub fn kind(&self) -> &'static str {
        match self {
            InputEvent::Move { .. } => "move",
            InputEvent::Down { .. } => "down",
            InputEvent::Drag { .. } => "drag",
            InputEvent::Up { .. } => "up",
            InputEvent::Wheel { .. } => "wheel",
            InputEvent::Release { .. } => "release",
        }
    }

    pub fn point(&self) -> (f64, f64) {
        match *self {
            InputEvent::Move { x, y }
            | InputEvent::Down { x, y }
            | InputEvent::Drag { x, y }
            | InputEvent::Up { x, y }
            | InputEvent::Wheel { x, y, .. }
            | InputEvent::Release { x, y } => (x, y),
        }
    }
}

/// Where pointer events go. Only moves, the left button and the wheel exist; never keys.
pub trait Injector {
    fn inject(&mut self, event: InputEvent);
}

impl<T: Injector + ?Sized> Injector for &mut T {
    fn inject(&mut self, event: InputEvent) {
        (**self).inject(event)
    }
}

impl Injector for Vec<InputEvent> {
    fn inject(&mut self, event: InputEvent) {
        self.push(event)
    }
}

/// Turns full button states into events. Remembers whether the left button is held and
/// the last injected point.
#[derive(Clone, Debug, Default)]
pub struct Buttons {
    held: bool,
    last: Option<(f64, f64)>,
}

impl Buttons {
    pub fn held(&self) -> bool {
        self.held
    }

    pub fn last_point(&self) -> Option<(f64, f64)> {
        self.last
    }

    /// Applies one `Pointer` message on `display`.
    pub fn pointer(&mut self, display: &Display, x: u16, y: u16, buttons: u8, inj: &mut dyn Injector) {
        let (x, y) = display.map(x, y);
        let down = buttons & 1 != 0;
        match (self.held, down) {
            (false, false) => inj.inject(InputEvent::Move { x, y }),
            (false, true) => {
                inj.inject(InputEvent::Move { x, y });
                inj.inject(InputEvent::Down { x, y });
            }
            (true, true) => inj.inject(InputEvent::Drag { x, y }),
            (true, false) => {
                if self.last != Some((x, y)) {
                    inj.inject(InputEvent::Drag { x, y });
                }
                inj.inject(InputEvent::Up { x, y });
            }
        }
        self.held = down;
        self.last = Some((x, y));
    }

    /// Applies one `Wheel` message at the last point (or the display's centre before any pointer).
    pub fn wheel(&mut self, display: &Display, dx: i16, dy: i16, inj: &mut dyn Injector) {
        let (x, y) = self.last.unwrap_or_else(|| display.map(32768, 32768));
        inj.inject(InputEvent::Wheel { x, y, dx, dy });
    }

    /// Releases the button if held and forgets the button state.
    pub fn release(&mut self, display: &Display, inj: &mut dyn Injector) {
        if self.held {
            let (x, y) = self.last.unwrap_or_else(|| display.map(32768, 32768));
            inj.inject(InputEvent::Release { x, y });
        }
        self.held = false;
    }
}
