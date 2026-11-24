//! The platform backends. This slice has one real backend, macOS; elsewhere the binary builds
//! and says it supports macOS only.

use bluke_mirror_core::{Capture, Injector};

#[cfg(target_os = "macos")]
mod macos;

pub const UNSUPPORTED: &str = "bluke-mirror supports macOS only for now; Windows and Linux backends come later";

/// An OS grant the companion needs, with its System Settings pane.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Grant {
    pub name: &'static str,
    /// Opens the pane with `open <url>` on macOS.
    pub pane_url: &'static str,
}

pub const SCREEN_RECORDING: Grant =
    Grant { name: "Screen Recording", pane_url: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture" };
pub const ACCESSIBILITY: Grant =
    Grant { name: "Accessibility", pane_url: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility" };

/// What to tell the user when `missing` grants aren't given yet (after macOS has been asked to
/// list the terminal in their panes, and the panes opened); `None` when nothing is missing.
pub fn permission_advice(missing: &[Grant]) -> Option<String> {
    if missing.is_empty() {
        return None;
    }
    let names: Vec<&str> = missing.iter().map(|g| g.name).collect();
    let panes: Vec<String> = missing.iter().map(|g| format!("System Settings > Privacy & Security > {}", g.name)).collect();
    let (it, them) = if missing.len() == 1 { ("it", "pane") } else { ("each", "panes") };
    Some(format!(
        "bluke-mirror needs {} permission. The {them} should now be open ({}): turn {it} on for the terminal app you run bluke-mirror from \
         (if it isn't listed, add it with +), then quit and reopen that terminal and run bluke-mirror again.",
        names.join(" and "),
        panes.join("; ")
    ))
}

/// Checks the OS permissions capture and injection need. A missing one is requested (so macOS
/// lists the terminal in its pane) and its pane opened; the error says what to turn on, and where.
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
