//! The companion's run-loop rules that need no screen, socket or OS grant: the status line, what
//! follows a connection's end, and the advice for missing macOS grants.

use bluke_mirror::platform::{ACCESSIBILITY, SCREEN_RECORDING, permission_advice};
use bluke_mirror::session::{End, Next, next_after, status_line};
use bluke_mirror_core::session::Close;

#[test]
fn the_status_line_names_the_capture_path_and_its_grab_times() {
    let mut grabs = vec![9_000, 3_000, 4_000, 5_000, 20_000];
    assert_eq!(
        status_line(19.96, 120, "ScreenCaptureKit", &mut grabs),
        "20.0 frames/s, 120 sent; ScreenCaptureKit grab 5.0 ms median, 20.0 ms max"
    );
    assert_eq!(status_line(0.0, 0, "xcap", &mut []), "0.0 frames/s, 0 sent; xcap: no grabs");
}

#[test]
fn a_stranger_answering_for_the_phone_on_mdns_moves_on_to_the_next_address() {
    assert_eq!(next_after(&End::NotPinned, false), Next::TryNextAddress);
    // At a --phone address there is no other to try: only pairing again helps.
    assert!(matches!(next_after(&End::NotPinned, true), Next::Fail(m) if m.contains("bluke-mirror pair")));
    assert!(matches!(next_after(&End::Closed(Close::Refused(1)), false), Next::Fail(m) if m.contains("forgotten this computer")));
    assert_eq!(next_after(&End::Stopped, false), Next::Stop);
    assert_eq!(next_after(&End::Silent, false), Next::Reconnect);
    assert_eq!(next_after(&End::Io(std::io::ErrorKind::UnexpectedEof.into()), true), Next::Reconnect);
}

#[test]
fn missing_grants_are_named_with_their_panes() {
    assert_eq!(permission_advice(&[]), None);
    let one = permission_advice(&[ACCESSIBILITY]).expect("advice");
    assert!(one.starts_with("bluke-mirror needs Accessibility permission. The pane should now be open"), "{one}");
    assert!(one.contains("System Settings > Privacy & Security > Accessibility"), "{one}");
    assert!(one.contains("turn it on for the terminal app") && one.contains("quit and reopen that terminal"), "{one}");
    let both = permission_advice(&[SCREEN_RECORDING, ACCESSIBILITY]).expect("advice");
    assert!(both.starts_with("bluke-mirror needs Screen Recording and Accessibility permission. The panes"), "{both}");
    assert!(both.contains("turn each on"), "{both}");
    assert!(SCREEN_RECORDING.pane_url.ends_with("?Privacy_ScreenCapture"));
    assert!(ACCESSIBILITY.pane_url.ends_with("?Privacy_Accessibility"));
}
