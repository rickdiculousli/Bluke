//! The byte vectors of PROTOCOL.md, read from `companion/vectors/` at test time.

use bluke_mirror_core::codec::{DecodeError, MAX_LEN, Message, body_len, read_message};
use bluke_mirror_core::spake2::{AAD, Role, Spake2, code_to_w, m_n};
use serde_json::Value;

fn load(name: &str) -> Value {
    let path = format!("{}/../vectors/{name}", env!("CARGO_MANIFEST_DIR"));
    serde_json::from_str(&std::fs::read_to_string(&path).expect("vector file")).expect("vector JSON")
}

fn unhex(s: &str) -> Vec<u8> {
    hex::decode(s.replace(' ', "")).expect("hex")
}

fn arr<const N: usize>(v: &Value) -> [u8; N] {
    unhex(v.as_str().expect("hex string")).try_into().expect("length")
}

fn num<T: TryFrom<i64>>(v: &Value) -> T
where
    T::Error: std::fmt::Debug,
{
    T::try_from(v.as_i64().expect("number")).expect("in range")
}

/// The message a vector's `name` and `fields` describe, built independently of decoding.
fn from_fields(name: &str, f: &Value) -> Message {
    let s = |k: &str| f[k].as_str().expect("str").to_string();
    match name {
        "PairRequest" => Message::PairRequest { name: s("name") },
        "PairCode" => Message::PairCode { attempts_left: num(&f["attempts_left"]) },
        "PakeShare" => Message::PakeShare { p_a: arr(&f["pA"]) },
        "PakeReply" => Message::PakeReply { p_b: arr(&f["pB"]), c_b: arr(&f["cB"]) },
        "PakeConfirm" => Message::PakeConfirm { c_a: arr(&f["cA"]) },
        "PairResult" => Message::PairResult { status: num(&f["status"]) },
        "Refused" => Message::Refused { reason: num(&f["reason"]) },
        "Hello" => Message::Hello { version: num(&f["version"]), name: s("name") },
        "Welcome" => Message::Welcome {
            version: num(&f["version"]),
            codecs: num(&f["codecs"]),
            max_width: num(&f["max_width"]),
            max_height: num(&f["max_height"]),
            fps: num(&f["fps"]),
        },
        "Display" => Message::Display { id: num(&f["id"]), width: num(&f["width"]), height: num(&f["height"]), name: s("name") },
        "Frame" => Message::Frame {
            seq: num(&f["seq"]),
            codec: num(&f["codec"]),
            width: num(&f["width"]),
            height: num(&f["height"]),
            sent_us: num(&f["sent_us"]),
            data: unhex(&s("data")),
        },
        "FrameAck" => Message::FrameAck { seq: num(&f["seq"]), decode_us: num(&f["decode_us"]) },
        "Pointer" => Message::Pointer { x: num(&f["x"]), y: num(&f["y"]), buttons: num(&f["buttons"]) },
        "Wheel" => Message::Wheel { dx: num(&f["dx"]), dy: num(&f["dy"]) },
        "Heartbeat" => Message::Heartbeat,
        "Release" => Message::Release,
        "Ping" => Message::Ping { t_us: num(&f["t_us"]) },
        "Pong" => Message::Pong { t_us: num(&f["t_us"]) },
        other => panic!("vector names unknown message {other}"),
    }
}

#[test]
fn every_message_vector_decodes_and_encodes_both_ways() {
    let v = load("protocol.json");
    assert_eq!(v["cap"].as_u64(), Some(u64::from(MAX_LEN)));
    let messages = v["messages"].as_array().expect("messages");
    assert!(messages.len() >= 20);
    for m in messages {
        let bytes = unhex(m["hex"].as_str().expect("hex"));
        let expected = from_fields(m["name"].as_str().expect("name"), &m["fields"]);
        assert_eq!(expected.kind() as i64, m["type"].as_i64().expect("type"), "{m}");
        let (decoded, used) = Message::decode(&bytes).expect("decodes").expect("complete");
        assert_eq!(used, bytes.len(), "{m}");
        assert_eq!(decoded, expected, "{m}");
        assert_eq!(expected.encode(), bytes, "{m}");
        // The blocking reader agrees, and a message cut short is incomplete, not an error.
        assert_eq!(read_message(&mut &bytes[..]).expect("reads"), expected);
        assert_eq!(Message::decode(&bytes[..bytes.len() - 1]), Ok(None));
    }
}

#[test]
fn unknown_types_decode_as_unknown() {
    let v = load("protocol.json");
    for u in v["unknown"].as_array().expect("unknown") {
        let bytes = unhex(u["hex"].as_str().expect("hex"));
        let (m, _) = Message::decode(&bytes).expect("decodes").expect("complete");
        assert_eq!(m, Message::Unknown { kind: num(&u["type"]) });
        assert!(!m.is_pairing());
    }
}

#[test]
fn every_invalid_vector_is_refused_with_its_error() {
    let v = load("protocol.json");
    for i in v["invalid"].as_array().expect("invalid") {
        let bytes = unhex(i["hex"].as_str().expect("hex"));
        let want = match i["error"].as_str().expect("error") {
            "empty" => DecodeError::Empty,
            "too_large" => DecodeError::TooLarge,
            "malformed" => DecodeError::Malformed,
            other => panic!("unknown error {other}"),
        };
        assert_eq!(Message::decode(&bytes), Err(want), "{}", i["about"]);
        let err = read_message(&mut &bytes[..]).expect_err("refused");
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidData, "{}", i["about"]);
    }
}

#[test]
fn length_cap_is_checked_from_the_header_alone() {
    let v = load("protocol.json");
    let header: [u8; 4] = unhex(v["cap_header"]["hex"].as_str().expect("hex")).try_into().expect("4");
    assert_eq!(body_len(header), Ok(MAX_LEN as usize));
    assert_eq!(body_len((MAX_LEN + 1).to_be_bytes()), Err(DecodeError::TooLarge));
    assert_eq!(body_len(0u32.to_be_bytes()), Err(DecodeError::Empty));
    // A reader given only an oversized header refuses it without waiting for (or allocating) a body.
    let err = read_message(&mut &u32::MAX.to_be_bytes()[..]).expect_err("refused");
    assert_eq!(err.kind(), std::io::ErrorKind::InvalidData);
    // A message exactly at the cap round-trips.
    let at_cap = Message::Frame { seq: 1, codec: 1, width: 1, height: 1, sent_us: 0, data: vec![7; MAX_LEN as usize - 18] };
    let bytes = at_cap.encode();
    assert_eq!(bytes.len(), 4 + MAX_LEN as usize);
    assert_eq!(Message::decode(&bytes).expect("ok").expect("complete").0, at_cap);
}

fn check_spake2(case: &Value) {
    let a = unhex(case["A"].as_str().expect("A"));
    let b = unhex(case["B"].as_str().expect("B"));
    let aad = unhex(case["aad"].as_str().expect("aad"));
    let w: [u8; 32] = arr(&case["w"]);
    let pa = Spake2::new(Role::A, &w, &arr(&case["x"]), &a, &b, &aad);
    let pb = Spake2::new(Role::B, &w, &arr(&case["y"]), &a, &b, &aad);
    assert_eq!(pa.share(), arr::<65>(&case["pA"]));
    assert_eq!(pb.share(), arr::<65>(&case["pB"]));
    let (share_a, share_b) = (pa.share(), pb.share());
    let ka = pa.finish(&share_b).expect("A finishes");
    let kb = pb.finish(&share_a).expect("B finishes");
    for keys in [&ka, &kb] {
        assert_eq!(keys.k, arr::<65>(&case["K"]));
        assert_eq!(keys.tt, unhex(case["TT"].as_str().expect("TT")));
        assert_eq!(keys.ke, arr::<16>(&case["Ke"]));
        assert_eq!(keys.ka, arr::<16>(&case["Ka"]));
        assert_eq!(keys.kc_a, arr::<16>(&case["KcA"]));
        assert_eq!(keys.kc_b, arr::<16>(&case["KcB"]));
        assert_eq!(keys.c_a, arr::<32>(&case["cA"]));
        assert_eq!(keys.c_b, arr::<32>(&case["cB"]));
    }
    assert_eq!(ka.own_confirmation(), arr::<32>(&case["cA"]));
    assert_eq!(kb.own_confirmation(), arr::<32>(&case["cB"]));
    ka.verify_peer(&kb.own_confirmation()).expect("A accepts cB");
    kb.verify_peer(&ka.own_confirmation()).expect("B accepts cA");
    let mut bad = ka.own_confirmation();
    bad[31] ^= 1;
    assert!(kb.verify_peer(&bad).is_err());
}

#[test]
fn rfc9382_vectors_in_both_roles() {
    let v = load("spake2.json");
    let (m, n) = m_n();
    assert_eq!(m.to_vec(), unhex(v["M"].as_str().expect("M")));
    assert_eq!(n.to_vec(), unhex(v["N"].as_str().expect("N")));
    let cases = v["rfc9382"].as_array().expect("rfc9382");
    assert_eq!(cases.len(), 4);
    cases.iter().for_each(check_spake2);
}

#[test]
fn bluke_vector_with_its_identities_aad_and_code() {
    let v = load("spake2.json");
    let case = &v["bluke"];
    assert_eq!(unhex(case["aad"].as_str().expect("aad")), AAD);
    assert_eq!(code_to_w(case["code"].as_str().expect("code")), arr::<32>(&case["w"]));
    check_spake2(case);
}

#[test]
fn code_to_w_vectors() {
    let v = load("spake2.json");
    for c in v["code_to_w"].as_array().expect("code_to_w") {
        assert_eq!(code_to_w(c["code"].as_str().expect("code")), arr::<32>(&c["w"]), "{c}");
    }
}

#[test]
fn invalid_peer_shares_fail() {
    let w = code_to_w("123456");
    let fresh = || Spake2::new(Role::A, &w, &[7; 32], b"a", b"b", AAD);
    let mut identity = [0u8; 65];
    identity[0] = 0x04;
    assert!(fresh().finish(&identity).is_err(), "all-zero point");
    let mut off_curve = Spake2::new(Role::B, &w, &[9; 32], b"a", b"b", AAD).share();
    off_curve[64] ^= 1;
    assert!(fresh().finish(&off_curve).is_err(), "point off the curve");
    let mut compressed_tag = Spake2::new(Role::B, &w, &[9; 32], b"a", b"b", AAD).share();
    compressed_tag[0] = 0x02;
    assert!(fresh().finish(&compressed_tag).is_err(), "wrong tag");
}
