//! macOS: capture through xcap (ScreenCaptureKit), injection through CGEvent on the HID tap.

use bluke_mirror_core::frames::{Capture, Image};
use bluke_mirror_core::pointer::{Display, Injector, InputEvent};
use core_graphics::event::{CGEvent, CGEventTapLocation, CGEventType, CGMouseButton, EventField, ScrollEventUnit};
use core_graphics::event_source::{CGEventSource, CGEventSourceStateID};
use core_graphics::geometry::CGPoint;

#[link(name = "CoreGraphics", kind = "framework")]
unsafe extern "C" {
    fn CGPreflightScreenCaptureAccess() -> bool;
    fn CGPreflightPostEventAccess() -> bool;
}

pub fn check_permissions() -> Result<(), String> {
    // SAFETY: both take no arguments and only read the process's TCC state.
    let (screen, post) = unsafe { (CGPreflightScreenCaptureAccess(), CGPreflightPostEventAccess()) };
    let mut missing = Vec::new();
    if !screen {
        missing.push("System Settings › Privacy & Security › Screen Recording");
    }
    if !post {
        missing.push("System Settings › Privacy & Security › Accessibility");
    }
    if missing.is_empty() {
        return Ok(());
    }
    Err(format!(
        "bluke-mirror needs permission. Open {} and turn it on for the terminal app you run bluke-mirror from, then quit and reopen that terminal.",
        missing.join(", and ")
    ))
}

fn source() -> Result<CGEventSource, String> {
    CGEventSource::new(CGEventSourceStateID::HIDSystemState).map_err(|_| "can't create a CGEvent source".to_string())
}

pub struct MacCapture {
    monitor: xcap::Monitor,
}

impl MacCapture {
    /// The monitor under the mouse cursor, else the primary one.
    pub fn open() -> Result<MacCapture, String> {
        let cursor = CGEvent::new(source()?).map(|e| e.location()).ok();
        let under = cursor.and_then(|p| xcap::Monitor::from_point(p.x as i32, p.y as i32).ok());
        let monitor = match under {
            Some(m) => m,
            None => xcap::Monitor::all()
                .map_err(|e| e.to_string())?
                .into_iter()
                .find(|m| m.is_primary().unwrap_or(false))
                .ok_or("no display found")?,
        };
        Ok(MacCapture { monitor })
    }
}

impl Capture for MacCapture {
    /// The monitor's rect in global points (CGDisplayBounds), read fresh so a size change shows.
    fn display(&self) -> Display {
        let m = &self.monitor;
        Display {
            id: m.id().unwrap_or(0),
            x: f64::from(m.x().unwrap_or(0)),
            y: f64::from(m.y().unwrap_or(0)),
            width: f64::from(m.width().unwrap_or(1)),
            height: f64::from(m.height().unwrap_or(1)),
            name: m.name().unwrap_or_default(),
        }
    }

    fn grab(&mut self) -> Result<Image, String> {
        let img = self.monitor.capture_image().map_err(|e| e.to_string())?;
        let (width, height) = (img.width(), img.height());
        Ok(Image { width, height, stride: width as usize * 4, rgba: img.into_raw() })
    }
}

pub struct MacInjector {
    source: CGEventSource,
}

impl MacInjector {
    pub fn new() -> Result<MacInjector, String> {
        Ok(MacInjector { source: source()? })
    }

    fn mouse(&self, kind: CGEventType, x: f64, y: f64, click: bool) {
        let Ok(e) = CGEvent::new_mouse_event(self.source.clone(), kind, CGPoint::new(x, y), CGMouseButton::Left) else { return };
        if click {
            e.set_integer_value_field(EventField::MOUSE_EVENT_CLICK_STATE, 1);
        }
        e.post(CGEventTapLocation::HID);
    }
}

impl Injector for MacInjector {
    fn inject(&mut self, event: InputEvent) {
        match event {
            InputEvent::Move { x, y } => self.mouse(CGEventType::MouseMoved, x, y, false),
            InputEvent::Down { x, y } => self.mouse(CGEventType::LeftMouseDown, x, y, true),
            // A move while the button is held must be a dragged event, or apps miss the drag.
            InputEvent::Drag { x, y } => self.mouse(CGEventType::LeftMouseDragged, x, y, false),
            InputEvent::Up { x, y } | InputEvent::Release { x, y } => self.mouse(CGEventType::LeftMouseUp, x, y, true),
            InputEvent::Wheel { dx, dy, .. } => {
                if let Ok(e) = CGEvent::new_scroll_event(self.source.clone(), ScrollEventUnit::PIXEL, 2, i32::from(dy), i32::from(dx), 0) {
                    e.post(CGEventTapLocation::HID);
                }
            }
        }
    }
}
