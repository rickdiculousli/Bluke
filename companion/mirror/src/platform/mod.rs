//! The platform backends. This slice has one real backend, macOS; elsewhere the binary builds
//! and says it supports macOS only.

use bluke_mirror_core::{Capture, Injector};

#[cfg(target_os = "macos")]
mod macos;

pub const UNSUPPORTED: &str = "bluke-mirror supports macOS only for now; Windows and Linux backends come later";

/// Checks the OS permissions capture and injection need; the error says what to grant, and where.
pub fn check_permissions() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    return macos::check_permissions();
    #[cfg(not(target_os = "macos"))]
    Err(UNSUPPORTED.into())
}

/// The screen capture for the display under the mouse cursor (else the primary display).
pub fn open_capture() -> Result<Box<dyn Capture + Send>, String> {
    #[cfg(target_os = "macos")]
    return macos::MacCapture::open().map(|c| Box::new(c) as Box<dyn Capture + Send>);
    #[cfg(not(target_os = "macos"))]
    Err(UNSUPPORTED.into())
}

/// The pointer injector.
pub fn injector() -> Result<Box<dyn Injector>, String> {
    #[cfg(target_os = "macos")]
    return macos::MacInjector::new().map(|i| Box::new(i) as Box<dyn Injector>);
    #[cfg(not(target_os = "macos"))]
    Err(UNSUPPORTED.into())
}
