//! What plain `bluke-mirror` does before it mirrors: the grants first, then the paired phone,
//! pairing at the terminal when there is none.

use bluke_mirror::config::Config;
use bluke_mirror::pair::ready_to_mirror;
use std::cell::Cell;
use std::path::Path;

const PHONE: [u8; 32] = [0xb0; 32];

fn config(name: &str) -> Config {
    let d = Path::new(env!("CARGO_TARGET_TMPDIR")).join(format!("startup-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).expect("temp dir");
    Config::new(d)
}

#[test]
fn with_no_phone_paired_it_pairs_then_mirrors_that_phone() {
    let config = config("unpaired");
    let paired = Cell::new(false);
    let got = ready_to_mirror(
        &config,
        || Ok(()),
        || {
            paired.set(true);
            Ok(PHONE)
        },
    );
    assert_eq!(got, Ok(PHONE));
    assert!(paired.get());
}

#[test]
fn with_a_phone_paired_it_does_not_pair() {
    let config = config("paired");
    config.pin_phone(&PHONE).expect("pin");
    let got = ready_to_mirror(&config, || Ok(()), || panic!("must not pair"));
    assert_eq!(got, Ok(PHONE));
}

#[test]
fn missing_grants_stop_it_before_pairing() {
    let config = config("no-grants");
    let got = ready_to_mirror(&config, || Err("needs Screen Recording".into()), || panic!("must not pair"));
    assert_eq!(got, Err("needs Screen Recording".to_string()));
}

#[test]
fn a_failed_pairing_pins_nothing_and_ends_it() {
    let config = config("failed");
    let got = ready_to_mirror(&config, || Ok(()), || Err("pairing failed: the code was wrong".into()));
    assert!(got.is_err());
    assert_eq!(config.paired_phone().expect("read"), None);
}
