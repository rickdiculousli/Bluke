//! macOS: capture through ScreenCaptureKit (macOS 14+), scaled by the system to the phone's fit;
//! xcap (CGWindowListCreateImage, full Retina resolution, downscaled on the CPU) as the fallback.
//! Injection through CGEvent on the HID tap.

use super::{ACCESSIBILITY, Grant, SCREEN_RECORDING, permission_advice};
use bluke_mirror_core::frames::{Capture, Image, fit};
use bluke_mirror_core::pointer::{ClickCounter, Display, Injector, InputEvent};
use core_graphics::event::{CGEvent, CGEventTapLocation, CGEventType, CGMouseButton, EventField, ScrollEventUnit};
use core_graphics::event_source::{CGEventSource, CGEventSourceStateID};
use core_graphics::geometry::CGPoint;
use std::sync::Once;
use std::time::Instant;

#[link(name = "CoreGraphics", kind = "framework")]
unsafe extern "C" {
    fn CGPreflightScreenCaptureAccess() -> bool;
    fn CGRequestScreenCaptureAccess() -> bool;
    fn CGPreflightPostEventAccess() -> bool;
}

#[link(name = "ApplicationServices", kind = "framework")]
unsafe extern "C" {
    static kAXTrustedCheckOptionPrompt: core_foundation::string::CFStringRef;
    fn AXIsProcessTrustedWithOptions(options: core_foundation::dictionary::CFDictionaryRef) -> bool;
}

pub fn check_permissions() -> Result<(), String> {
    // SAFETY: both take no arguments and only read the process's TCC state.
    let (screen, post) = unsafe { (CGPreflightScreenCaptureAccess(), CGPreflightPostEventAccess()) };
    let mut missing: Vec<Grant> = Vec::new();
    if !screen {
        // Lists the terminal under Screen Recording and shows macOS's prompt, once per binary.
        // SAFETY: no arguments; it only asks TCC.
        unsafe { CGRequestScreenCaptureAccess() };
        missing.push(SCREEN_RECORDING);
    }
    if !post {
        ask_accessibility();
        missing.push(ACCESSIBILITY);
    }
    for g in &missing {
        // Best effort: the message names the pane either way.
        let _ = std::process::Command::new("open").arg(g.pane_url).status();
    }
    permission_advice(&missing).map_or(Ok(()), Err)
}

/// Lists the terminal under Accessibility and shows macOS's prompt for it.
fn ask_accessibility() {
    use core_foundation::base::TCFType;
    use core_foundation::boolean::CFBoolean;
    use core_foundation::dictionary::CFDictionary;
    use core_foundation::string::CFString;
    // SAFETY: kAXTrustedCheckOptionPrompt is a constant CFString the framework owns; wrapping it
    // under the get rule retains it for the dictionary's lifetime.
    let key = unsafe { CFString::wrap_under_get_rule(kAXTrustedCheckOptionPrompt) };
    let options = CFDictionary::from_CFType_pairs(&[(key, CFBoolean::true_value())]);
    // SAFETY: options is a valid CFDictionary for the call's duration.
    unsafe { AXIsProcessTrustedWithOptions(options.as_concrete_TypeRef()) };
}

fn source() -> Result<CGEventSource, String> {
    CGEventSource::new(CGEventSourceStateID::HIDSystemState).map_err(|_| "can't create a CGEvent source".to_string())
}

pub struct MacCapture {
    monitor: xcap::Monitor,
    /// ScreenCaptureKit while it works; `None` once it was unavailable or failed.
    sck: Option<sck::Screenshots>,
}

impl MacCapture {
    /// The monitor under the mouse cursor, else the primary one; captured through
    /// ScreenCaptureKit when it opens, else through xcap.
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
        let id = monitor.id().map_err(|e| e.to_string())?;
        let sck = match sck::Screenshots::open(id) {
            Ok(s) => Some(s),
            Err(e) => {
                // Said once per run: every reconnect opens the capture again.
                static SAID: Once = Once::new();
                SAID.call_once(|| eprintln!("ScreenCaptureKit isn't usable: {e}. {FALLBACK}"));
                None
            }
        };
        Ok(MacCapture { monitor, sck })
    }
}

const FALLBACK: &str = "Capturing with xcap (CGWindowListCreateImage) instead: full resolution, scaled on the CPU, slower.";

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

    fn grab(&mut self, fit_in: (u16, u16)) -> Result<Image, String> {
        let d = self.display();
        if let Some(s) = &mut self.sck {
            let size = fit(d.width as u32, d.height as u32, fit_in.0, fit_in.1);
            match s.grab(size) {
                Ok(img) => return Ok(img),
                Err(e) => {
                    eprintln!("ScreenCaptureKit capture failed: {e}. {FALLBACK} (until the phone reconnects)");
                    self.sck = None;
                }
            }
        }
        let img = self.monitor.capture_image().map_err(|e| e.to_string())?;
        let (width, height) = (img.width(), img.height());
        Ok(Image { width, height, stride: width as usize * 4, rgba: img.into_raw() })
    }

    fn backend(&self) -> &'static str {
        if self.sck.is_some() { "ScreenCaptureKit" } else { "xcap" }
    }
}

/// ScreenCaptureKit screenshots: one `SCScreenshotManager` capture per poll (the spec's polled
/// design), with the configuration's size set to the phone's fit so the system scales on the GPU.
/// Its completion handlers run on a framework queue; each call here waits for one with a bound.
mod sck {
    use super::Image;
    use block2::RcBlock;
    use objc2::AnyThread;
    use objc2::rc::Retained;
    use objc2_core_foundation::{CFRetained, CGPoint, CGRect, CGSize};
    use objc2_core_graphics::{
        CGBitmapContextCreate, CGColorSpace, CGContext, CGDirectDisplayID, CGImage, CGImageAlphaInfo, CGImageByteOrderInfo, CGMainDisplayID,
    };
    use objc2_foundation::{NSArray, NSError};
    use objc2_screen_capture_kit::{SCContentFilter, SCScreenshotManager, SCShareableContent, SCStreamConfiguration};
    use std::ptr::NonNull;
    use std::sync::mpsc;
    use std::time::Duration;

    /// The longest a ScreenCaptureKit call may take before it counts as failed.
    const WAIT: Duration = Duration::from_secs(2);

    pub struct Screenshots {
        filter: Retained<SCContentFilter>,
        config: Retained<SCStreamConfiguration>,
        size: (u16, u16),
    }

    // SAFETY: the filter and configuration are only touched by whichever thread owns this value
    // (the session's capture thread, after one move); Objective-C retain and release are
    // thread-safe, and ScreenCaptureKit copies both when a capture starts.
    unsafe impl Send for Screenshots {}

    fn describe(error: *mut NSError) -> String {
        // SAFETY: the framework passes a valid NSError or null.
        match unsafe { error.as_ref() } {
            Some(e) => e.localizedDescription().to_string(),
            None => "no result and no error".into(),
        }
    }

    impl Screenshots {
        /// Finds `display_id` among ScreenCaptureKit's displays and builds its filter.
        pub fn open(display_id: CGDirectDisplayID) -> Result<Screenshots, String> {
            if !objc2::available!(macos = 14.0) {
                return Err("its screenshots need macOS 14 or later".into());
            }
            // Connects to the window server first: ScreenCaptureKit asserts on a process that hasn't.
            let _ = CGMainDisplayID();
            let (tx, rx) = mpsc::sync_channel(1);
            let handler = RcBlock::new(move |content: *mut SCShareableContent, error: *mut NSError| {
                // SAFETY: the framework passes a valid SCShareableContent or null.
                let got = unsafe { Retained::retain(content) }.ok_or_else(|| describe(error));
                let _ = tx.try_send(got);
            });
            // SAFETY: the handler is a valid block taking the documented arguments.
            unsafe { SCShareableContent::getShareableContentWithCompletionHandler(&handler) };
            let content = rx.recv_timeout(WAIT).map_err(|_| format!("no display list within {} s", WAIT.as_secs()))??;
            // SAFETY: plain getters on a valid object.
            let display = unsafe { content.displays() }
                .iter()
                .find(|d| unsafe { d.displayID() } == display_id)
                .ok_or_else(|| format!("display {display_id} isn't among its displays"))?;
            // SAFETY: a fresh allocation, initialised with a valid display and an empty array.
            let filter = unsafe { SCContentFilter::initWithDisplay_excludingWindows(SCContentFilter::alloc(), &display, &NSArray::new()) };
            // SAFETY: a plain constructor and setters on the fresh object.
            let config = unsafe { SCStreamConfiguration::new() };
            unsafe { config.setShowsCursor(true) };
            Ok(Screenshots { filter, config, size: (0, 0) })
        }

        /// One screenshot, `size` pixels (the phone's fit).
        pub fn grab(&mut self, size: (u16, u16)) -> Result<Image, String> {
            if size != self.size {
                // SAFETY: setters on our own configuration; ScreenCaptureKit copies it per capture.
                unsafe {
                    self.config.setWidth(usize::from(size.0));
                    self.config.setHeight(usize::from(size.1));
                }
                self.size = size;
            }
            let (tx, rx) = mpsc::sync_channel(1);
            let handler = RcBlock::new(move |image: *mut CGImage, error: *mut NSError| {
                // SAFETY: the framework passes a valid CGImage or null; retaining keeps it past the handler.
                let got = NonNull::new(image).map(|p| unsafe { CFRetained::retain(p) }).ok_or_else(|| describe(error));
                let _ = tx.try_send(got);
            });
            // SAFETY: valid filter, configuration and block; available since macOS 14, checked in open.
            unsafe {
                SCScreenshotManager::captureImageWithFilter_configuration_completionHandler(&self.filter, &self.config, Some(&handler))
            };
            let image = rx.recv_timeout(WAIT).map_err(|_| format!("no screenshot within {} s", WAIT.as_secs()))??;
            to_rgba(&image)
        }
    }

    /// Draws `image` into an RGBA8 buffer of its own size (ScreenCaptureKit hands BGRA).
    fn to_rgba(image: &CGImage) -> Result<Image, String> {
        let (w, h) = (CGImage::width(Some(image)), CGImage::height(Some(image)));
        if w == 0 || h == 0 {
            return Err("an empty screenshot".into());
        }
        let stride = w * 4;
        let mut rgba = vec![0u8; stride * h];
        let space = CGColorSpace::new_device_rgb().ok_or("no device RGB color space")?;
        let info = CGImageAlphaInfo::PremultipliedLast.0 | CGImageByteOrderInfo::Order32Big.0;
        // SAFETY: `rgba` is `stride * h` bytes and outlives the context, which is dropped before it
        // is read; 8 bits per component, 4 components, matches `stride`.
        let ctx = unsafe { CGBitmapContextCreate(rgba.as_mut_ptr().cast(), w, h, 8, stride, Some(&space), info) }
            .ok_or("can't make a bitmap context")?;
        CGContext::draw_image(Some(&ctx), CGRect::new(CGPoint::new(0.0, 0.0), CGSize::new(w as f64, h as f64)), Some(image));
        drop(ctx);
        Ok(Image { width: w as u32, height: h as u32, stride, rgba })
    }
}

/// How far apart, in points, two presses may land and still count as one double-click: about a
/// fingertip's wobble between two taps once the phone's picture is scaled up to the display.
const DOUBLE_CLICK_RADIUS: f64 = 16.0;

#[link(name = "AppKit", kind = "framework")]
unsafe extern "C" {}

/// The user's double-click interval (System Settings), µs; 500 ms if AppKit can't say.
fn double_click_interval_us() -> u64 {
    use objc2::msg_send;
    use objc2::runtime::AnyClass;
    let secs = AnyClass::get(c"NSEvent").map(|c| {
        // SAFETY: `+[NSEvent doubleClickInterval]` is a class method returning an NSTimeInterval (f64).
        let s: f64 = unsafe { msg_send![c, doubleClickInterval] };
        s
    });
    secs.filter(|s| s.is_finite() && *s > 0.0).map_or(500_000, |s| (s * 1e6) as u64)
}

pub struct MacInjector {
    source: CGEventSource,
    clicks: ClickCounter,
    epoch: Instant,
}

impl MacInjector {
    pub fn new() -> Result<MacInjector, String> {
        Ok(MacInjector {
            source: source()?,
            clicks: ClickCounter::new(double_click_interval_us(), DOUBLE_CLICK_RADIUS),
            epoch: Instant::now(),
        })
    }

    /// Posts one left-button event; `clicks` is its click state (presses and releases only).
    fn mouse(&self, kind: CGEventType, x: f64, y: f64, clicks: Option<i64>) {
        let Ok(e) = CGEvent::new_mouse_event(self.source.clone(), kind, CGPoint::new(x, y), CGMouseButton::Left) else { return };
        if let Some(n) = clicks {
            e.set_integer_value_field(EventField::MOUSE_EVENT_CLICK_STATE, n);
        }
        e.post(CGEventTapLocation::HID);
    }
}

impl Injector for MacInjector {
    fn inject(&mut self, event: InputEvent) {
        match event {
            InputEvent::Move { x, y } => self.mouse(CGEventType::MouseMoved, x, y, None),
            InputEvent::Down { x, y } => {
                // Two quick taps in one place are a double-click, as the system counts them.
                let n = self.clicks.press(self.epoch.elapsed().as_micros() as u64, x, y);
                self.mouse(CGEventType::LeftMouseDown, x, y, Some(n));
            }
            // A move while the button is held must be a dragged event, or apps miss the drag.
            InputEvent::Drag { x, y } => self.mouse(CGEventType::LeftMouseDragged, x, y, None),
            InputEvent::Up { x, y } | InputEvent::Release { x, y } => self.mouse(CGEventType::LeftMouseUp, x, y, Some(self.clicks.count())),
            InputEvent::Wheel { dx, dy, .. } => {
                if let Ok(e) = CGEvent::new_scroll_event(self.source.clone(), ScrollEventUnit::PIXEL, 2, i32::from(dy), i32::from(dx), 0) {
                    e.post(CGEventTapLocation::HID);
                }
            }
        }
    }
}
