//! The pairing run between the companion's and the phone's state machines, wired message
//! by message as the TLS connection would carry them.

use bluke_mirror_core::codec::{Message, pair_status};
use bluke_mirror_core::pairing::{
    ATTEMPTS_PER_SESSION, CODE_LIFETIME_US, CompanionPairing, CompanionStep, Fingerprint, PairFailure, PhonePairing, draw_code,
    is_valid_code,
};
use bluke_mirror_core::spake2::{AAD, Role, Spake2, code_to_w};

const COMPANION: Fingerprint = [0xc0; 32];
const PHONE: Fingerprint = [0xb0; 32];
const ATTACKER: Fingerprint = [0xee; 32];

fn secret(tag: u8) -> [u8; 32] {
    std::array::from_fn(|i| tag.wrapping_mul(31).wrapping_add(i as u8) | 1)
}

/// What each side pinned at the end of a run.
#[derive(Debug, Default, PartialEq)]
struct Outcome {
    companion_pinned: Option<Fingerprint>,
    phone_pinned: Option<Fingerprint>,
    companion_error: Option<PairFailure>,
    phone_results: Vec<u8>,
}

/// One pairing connection. `companion_sees` is the phone fingerprint the companion's TLS session
/// presents; `phone_sees` the companion fingerprint the phone's presents (they differ under a relay).
fn run(phone: &mut PhonePairing, now: u64, companion_sees: Fingerprint, phone_sees: Fingerprint, typed: impl Fn(&str) -> String) -> Outcome {
    let mut out = Outcome::default();
    let (mut c, request) = CompanionPairing::start(COMPANION, companion_sees, "Mac");
    let shown = draw_code(&mut |b: &mut [u8]| b.fill(0x42));
    let mut to_companion = phone.on_pair_request(now, shown.clone(), phone_sees, PHONE);
    assert!(matches!(request, Message::PairRequest { .. }));
    loop {
        if let Some(pin) = to_companion.pin {
            out.phone_pinned = Some(pin);
        }
        let close_after = to_companion.close;
        let mut to_phone = Vec::new();
        for m in to_companion.send.drain(..) {
            if let Message::PairResult { status } = m {
                out.phone_results.push(status);
            }
            match c.on_message(m) {
                Ok(CompanionStep::NeedCode { .. }) => to_phone.push(c.enter_code(&typed(&shown), &secret(1)).expect("share")),
                Ok(CompanionStep::Send(m)) => to_phone.push(m),
                Ok(CompanionStep::Paired(fp)) => out.companion_pinned = Some(fp),
                Err(e) => {
                    out.companion_error = Some(e);
                }
            }
        }
        if close_after || out.companion_error.is_some() || to_phone.is_empty() {
            phone.on_close();
            return out;
        }
        let mut next = bluke_mirror_core::pairing::PhoneOut::default();
        for m in to_phone {
            let o = phone.on_message(now + 1, m, &secret(2));
            next.send.extend(o.send);
            next.close |= o.close;
            next.pin = next.pin.or(o.pin);
        }
        to_companion = next;
    }
}

fn right(code: &str) -> String {
    code.to_string()
}

fn wrong(code: &str) -> String {
    let d = (code.as_bytes()[5] - b'0' + 1) % 10;
    format!("{}{}", &code[..5], d)
}

#[test]
fn right_code_pins_on_both_sides() {
    let mut phone = PhonePairing::new();
    let o = run(&mut phone, 0, PHONE, COMPANION, right);
    assert_eq!(o.companion_pinned, Some(PHONE));
    assert_eq!(o.phone_pinned, Some(COMPANION));
    assert_eq!(o.phone_results, vec![pair_status::PAIRED]);
    assert_eq!(phone.attempts_left(), ATTEMPTS_PER_SESSION, "a successful attempt uses nothing");
}

#[test]
fn wrong_code_fails_and_pins_nothing_on_either_side() {
    let mut phone = PhonePairing::new();
    let o = run(&mut phone, 0, PHONE, COMPANION, wrong);
    assert_eq!(o.companion_error, Some(PairFailure::WrongCode));
    assert_eq!(o.companion_pinned, None);
    assert_eq!(o.phone_pinned, None);
    assert_eq!(phone.attempts_left(), ATTEMPTS_PER_SESSION - 1);
    assert_eq!(phone.code(), None, "the code is discarded");
}

#[test]
fn three_strikes_lock_the_session_and_a_new_session_re_pairs() {
    let mut phone = PhonePairing::new();
    for left in (0..ATTEMPTS_PER_SESSION).rev() {
        let o = run(&mut phone, 0, PHONE, COMPANION, wrong);
        assert_eq!(o.phone_pinned, None);
        assert_eq!(phone.attempts_left(), left);
    }
    // A fourth try, even with the right code, is refused as locked before any code is drawn.
    let o = run(&mut phone, 0, PHONE, COMPANION, right);
    assert_eq!(o.companion_error, Some(PairFailure::Locked));
    assert_eq!((o.companion_pinned, o.phone_pinned), (None, None));
    // Reopening Mirror starts a new session with 3 attempts; pairing again replaces the pin.
    let mut phone = PhonePairing::new();
    let o = run(&mut phone, 0, PHONE, COMPANION, right);
    assert_eq!((o.companion_pinned, o.phone_pinned), (Some(PHONE), Some(COMPANION)));
    let o = run(&mut phone, 0, PHONE, COMPANION, right);
    assert_eq!((o.companion_pinned, o.phone_pinned), (Some(PHONE), Some(COMPANION)), "re-pair");
}

#[test]
fn phone_reports_locked_on_the_last_failed_confirmation() {
    // A companion that sends a bad cA (it skipped checking cB) gets wrong-code, then locked on the third.
    let mut phone = PhonePairing::new();
    for expected in [pair_status::WRONG_CODE, pair_status::WRONG_CODE, pair_status::LOCKED] {
        phone.on_pair_request(0, "111111".into(), COMPANION, PHONE);
        let pake = Spake2::new(Role::A, &code_to_w("222222"), &secret(3), &COMPANION, &PHONE, AAD);
        let reply = phone.on_message(1, Message::PakeShare { p_a: pake.share() }, &secret(4));
        let Message::PakeReply { p_b, .. } = reply.send[0] else { panic!("reply") };
        let keys = pake.finish(&p_b).expect("keys");
        let o = phone.on_message(2, Message::PakeConfirm { c_a: keys.own_confirmation() }, &secret(5));
        assert_eq!(o.send, vec![Message::PairResult { status: expected }]);
        assert!(o.close && o.pin.is_none());
        phone.on_close();
    }
    assert_eq!(phone.attempts_left(), 0);
}

#[test]
fn close_before_confirm_uses_an_attempt_and_expired_code_does_not() {
    let mut phone = PhonePairing::new();
    phone.on_pair_request(0, "123456".into(), COMPANION, PHONE);
    let pake = Spake2::new(Role::A, &code_to_w("123456"), &secret(3), &COMPANION, &PHONE, AAD);
    phone.on_message(1, Message::PakeShare { p_a: pake.share() }, &secret(4));
    phone.on_close();
    assert_eq!(phone.attempts_left(), 2);

    phone.on_pair_request(10, "123456".into(), COMPANION, PHONE);
    let late = phone.on_message(10 + CODE_LIFETIME_US, Message::PakeShare { p_a: pake.share() }, &secret(4));
    assert!(late.close && late.pin.is_none() && late.send.is_empty());
    phone.on_close();
    assert_eq!(phone.attempts_left(), 2, "an expired code uses no attempt");
}

#[test]
fn a_second_pairing_while_one_runs_is_unavailable() {
    let mut phone = PhonePairing::new();
    phone.on_pair_request(0, "123456".into(), COMPANION, PHONE);
    let o = phone.on_pair_request(1, "654321".into(), ATTACKER, PHONE);
    assert_eq!(o.send, vec![Message::PairResult { status: pair_status::UNAVAILABLE }]);
    assert!(o.close);
}

#[test]
fn man_in_the_middle_relay_fails_on_both_sides() {
    // The attacker terminates both TLS sessions with its own certificate and relays every pairing
    // message unchanged, and the user types the right code. The companion sees the attacker's
    // certificate as the phone's; the phone sees it as the companion's.
    let mut phone = PhonePairing::new();
    let o = run(&mut phone, 0, ATTACKER, ATTACKER, right);
    assert_eq!(o.companion_error, Some(PairFailure::WrongCode));
    assert_eq!((o.companion_pinned, o.phone_pinned), (None, None));

    // Even a companion that skipped its check and sent cA computed over what it saw is refused by the phone.
    phone.on_pair_request(0, "123456".into(), ATTACKER, PHONE);
    let pake = Spake2::new(Role::A, &code_to_w("123456"), &secret(3), &COMPANION, &ATTACKER, AAD);
    let reply = phone.on_message(1, Message::PakeShare { p_a: pake.share() }, &secret(4));
    let Message::PakeReply { p_b, c_b } = reply.send[0] else { panic!("reply") };
    let keys = pake.finish(&p_b).expect("keys");
    assert!(keys.verify_peer(&c_b).is_err(), "companion refuses cB");
    let o = phone.on_message(2, Message::PakeConfirm { c_a: keys.own_confirmation() }, &secret(5));
    assert_eq!(o.pin, None, "phone refuses cA");
    assert_eq!(o.send, vec![Message::PairResult { status: pair_status::WRONG_CODE }]);
}

#[test]
fn messages_out_of_order_end_the_run_without_pinning() {
    let (mut c, _) = CompanionPairing::start(COMPANION, PHONE, "Mac");
    assert_eq!(c.on_message(Message::PairResult { status: 0 }), Err(PairFailure::PhoneRejected));
    let (mut c, _) = CompanionPairing::start(COMPANION, PHONE, "Mac");
    assert!(c.on_message(Message::Welcome { version: 1, codecs: 1, max_width: 1, max_height: 1, fps: 1 }).is_err());
    let (mut c, _) = CompanionPairing::start(COMPANION, PHONE, "Mac");
    assert_eq!(c.on_message(Message::Refused { reason: 1 }), Err(PairFailure::Refused(1)));

    let mut phone = PhonePairing::new();
    phone.on_pair_request(0, "123456".into(), COMPANION, PHONE);
    let o = phone.on_message(1, Message::PakeConfirm { c_a: [0; 32] }, &secret(1));
    assert!(o.close && o.pin.is_none());
    phone.on_close();
    assert_eq!(phone.attempts_left(), ATTEMPTS_PER_SESSION, "no share, no attempt used");
}

#[test]
fn codes_are_six_digits() {
    let mut n = 0u8;
    for _ in 0..200 {
        let code = draw_code(&mut |b: &mut [u8]| {
            for x in b.iter_mut() {
                n = n.wrapping_mul(13).wrapping_add(7);
                *x = n;
            }
        });
        assert!(is_valid_code(&code), "{code}");
    }
    assert_eq!(draw_code(&mut |b: &mut [u8]| b.fill(0)), "000000");
    assert!(!is_valid_code("12345") && !is_valid_code("12345a"));
}
