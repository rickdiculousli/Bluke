//! Wire framing and every message of PROTOCOL.md: `u32 length | u8 type | body`.

/// Largest `length` a header may announce (type byte plus body).
pub const MAX_LEN: u32 = 262_144;
/// Longest `str` field, in bytes.
pub const MAX_STR: usize = 64;
/// TCP port the phone listens on unless it is taken.
pub const DEFAULT_PORT: u16 = 47800;
/// Protocol version carried in `Hello` and `Welcome`.
pub const VERSION: u16 = 1;
/// `Frame.codec` for baseline JPEG.
pub const CODEC_JPEG: u8 = 1;
/// `FrameAck.decode_us` meaning the frame was replaced before it was decoded.
pub const ACK_DROPPED: u32 = u32::MAX;

/// Why bytes were refused. Every one closes the connection.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DecodeError {
    /// `length` was 0.
    Empty,
    /// `length` was above [`MAX_LEN`].
    TooLarge,
    /// The body doesn't match its type's fields.
    Malformed,
}

impl std::fmt::Display for DecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            DecodeError::Empty => "empty message",
            DecodeError::TooLarge => "message over the 256 KiB cap",
            DecodeError::Malformed => "malformed message",
        })
    }
}

impl std::error::Error for DecodeError {}

pub mod pair_status {
    pub const PAIRED: u8 = 0;
    pub const WRONG_CODE: u8 = 1;
    pub const LOCKED: u8 = 2;
    pub const UNAVAILABLE: u8 = 3;
}

pub mod refused_reason {
    pub const NOT_PAIRED: u8 = 1;
    pub const UNSUPPORTED_VERSION: u8 = 2;
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Message {
    PairRequest {
        name: String,
    },
    PairCode {
        attempts_left: u8,
    },
    PakeShare {
        p_a: [u8; 65],
    },
    PakeReply {
        p_b: [u8; 65],
        c_b: [u8; 32],
    },
    PakeConfirm {
        c_a: [u8; 32],
    },
    PairResult {
        status: u8,
    },
    Refused {
        reason: u8,
    },
    Hello {
        version: u16,
        name: String,
    },
    Welcome {
        version: u16,
        codecs: u8,
        max_width: u16,
        max_height: u16,
        fps: u8,
    },
    Display {
        id: u32,
        width: u32,
        height: u32,
        name: String,
    },
    Frame {
        seq: u32,
        codec: u8,
        width: u16,
        height: u16,
        sent_us: u64,
        data: Vec<u8>,
    },
    FrameAck {
        seq: u32,
        decode_us: u32,
    },
    Pointer {
        x: u16,
        y: u16,
        buttons: u8,
    },
    Wheel {
        dx: i16,
        dy: i16,
    },
    Heartbeat,
    Release,
    Ping {
        t_us: u64,
    },
    Pong {
        t_us: u64,
    },
    /// A type this version doesn't know, inside the cap.
    Unknown {
        kind: u8,
    },
}

impl Message {
    pub fn kind(&self) -> u8 {
        match self {
            Message::PairRequest { .. } => 0x01,
            Message::PairCode { .. } => 0x02,
            Message::PakeShare { .. } => 0x03,
            Message::PakeReply { .. } => 0x04,
            Message::PakeConfirm { .. } => 0x05,
            Message::PairResult { .. } => 0x06,
            Message::Refused { .. } => 0x07,
            Message::Hello { .. } => 0x10,
            Message::Welcome { .. } => 0x11,
            Message::Display { .. } => 0x12,
            Message::Frame { .. } => 0x13,
            Message::FrameAck { .. } => 0x14,
            Message::Pointer { .. } => 0x20,
            Message::Wheel { .. } => 0x21,
            Message::Heartbeat => 0x22,
            Message::Release => 0x23,
            Message::Ping { .. } => 0x30,
            Message::Pong { .. } => 0x31,
            Message::Unknown { kind } => *kind,
        }
    }

    /// Pairing messages (types 1-7), the only ones allowed before the peer's pinned key is verified.
    pub fn is_pairing(&self) -> bool {
        (0x01..=0x07).contains(&self.kind())
    }

    /// The whole message on the wire, header included.
    ///
    /// Panics if a `str` field is over [`MAX_STR`] bytes or the message is over the cap:
    /// both are programming errors on the sending side.
    pub fn encode(&self) -> Vec<u8> {
        let mut b = vec![0, 0, 0, 0, self.kind()];
        match self {
            Message::PairRequest { name } => put_str(&mut b, name),
            Message::PairCode { attempts_left } => b.push(*attempts_left),
            Message::PakeShare { p_a } => b.extend_from_slice(p_a),
            Message::PakeReply { p_b, c_b } => {
                b.extend_from_slice(p_b);
                b.extend_from_slice(c_b);
            }
            Message::PakeConfirm { c_a } => b.extend_from_slice(c_a),
            Message::PairResult { status } => b.push(*status),
            Message::Refused { reason } => b.push(*reason),
            Message::Hello { version, name } => {
                b.extend_from_slice(&version.to_be_bytes());
                put_str(&mut b, name);
            }
            Message::Welcome { version, codecs, max_width, max_height, fps } => {
                b.extend_from_slice(&version.to_be_bytes());
                b.push(*codecs);
                b.extend_from_slice(&max_width.to_be_bytes());
                b.extend_from_slice(&max_height.to_be_bytes());
                b.push(*fps);
            }
            Message::Display { id, width, height, name } => {
                b.extend_from_slice(&id.to_be_bytes());
                b.extend_from_slice(&width.to_be_bytes());
                b.extend_from_slice(&height.to_be_bytes());
                put_str(&mut b, name);
            }
            Message::Frame { seq, codec, width, height, sent_us, data } => {
                assert!(!data.is_empty(), "a frame carries at least one byte");
                b.extend_from_slice(&seq.to_be_bytes());
                b.push(*codec);
                b.extend_from_slice(&width.to_be_bytes());
                b.extend_from_slice(&height.to_be_bytes());
                b.extend_from_slice(&sent_us.to_be_bytes());
                b.extend_from_slice(data);
            }
            Message::FrameAck { seq, decode_us } => {
                b.extend_from_slice(&seq.to_be_bytes());
                b.extend_from_slice(&decode_us.to_be_bytes());
            }
            Message::Pointer { x, y, buttons } => {
                b.extend_from_slice(&x.to_be_bytes());
                b.extend_from_slice(&y.to_be_bytes());
                b.push(*buttons);
            }
            Message::Wheel { dx, dy } => {
                b.extend_from_slice(&dx.to_be_bytes());
                b.extend_from_slice(&dy.to_be_bytes());
            }
            Message::Heartbeat | Message::Release | Message::Unknown { .. } => {}
            Message::Ping { t_us } | Message::Pong { t_us } => b.extend_from_slice(&t_us.to_be_bytes()),
        }
        let len = (b.len() - 4) as u32;
        assert!(len <= MAX_LEN, "message over the cap");
        b[..4].copy_from_slice(&len.to_be_bytes());
        b
    }

    /// Decodes one message's type byte and body (the bytes after the header).
    pub fn decode_body(kind: u8, body: &[u8]) -> Result<Message, DecodeError> {
        let mut r = Reader { b: body };
        let m = match kind {
            0x01 => Message::PairRequest { name: r.str()? },
            0x02 => Message::PairCode { attempts_left: r.u8()? },
            0x03 => Message::PakeShare { p_a: r.arr()? },
            0x04 => Message::PakeReply { p_b: r.arr()?, c_b: r.arr()? },
            0x05 => Message::PakeConfirm { c_a: r.arr()? },
            0x06 => Message::PairResult { status: r.u8()? },
            0x07 => Message::Refused { reason: r.u8()? },
            0x10 => Message::Hello { version: r.u16()?, name: r.str()? },
            0x11 => Message::Welcome { version: r.u16()?, codecs: r.u8()?, max_width: r.u16()?, max_height: r.u16()?, fps: r.u8()? },
            0x12 => Message::Display { id: r.u32()?, width: r.u32()?, height: r.u32()?, name: r.str()? },
            0x13 => {
                let (seq, codec, width, height, sent_us) = (r.u32()?, r.u8()?, r.u16()?, r.u16()?, r.u64()?);
                let data = std::mem::take(&mut r.b).to_vec();
                if data.is_empty() {
                    return Err(DecodeError::Malformed);
                }
                Message::Frame { seq, codec, width, height, sent_us, data }
            }
            0x14 => Message::FrameAck { seq: r.u32()?, decode_us: r.u32()? },
            0x20 => Message::Pointer { x: r.u16()?, y: r.u16()?, buttons: r.u8()? },
            0x21 => Message::Wheel { dx: r.u16()? as i16, dy: r.u16()? as i16 },
            0x22 => Message::Heartbeat,
            0x23 => Message::Release,
            0x30 => Message::Ping { t_us: r.u64()? },
            0x31 => Message::Pong { t_us: r.u64()? },
            other => return Ok(Message::Unknown { kind: other }),
        };
        if r.b.is_empty() { Ok(m) } else { Err(DecodeError::Malformed) }
    }

    /// Decodes one whole message from the front of `bytes`.
    /// `Ok(None)` means more bytes are needed; the header is checked first, so an
    /// oversized length is refused before any body arrives.
    pub fn decode(bytes: &[u8]) -> Result<Option<(Message, usize)>, DecodeError> {
        let Some(header) = bytes.first_chunk::<4>() else { return Ok(None) };
        let len = body_len(*header)?;
        let total = 4 + len;
        if bytes.len() < total {
            return Ok(None);
        }
        let m = Message::decode_body(bytes[4], &bytes[5..total])?;
        Ok(Some((m, total)))
    }
}

/// Checks a header and returns the `length` it announces (type byte plus body),
/// before anything is allocated for it.
pub fn body_len(header: [u8; 4]) -> Result<usize, DecodeError> {
    match u32::from_be_bytes(header) {
        0 => Err(DecodeError::Empty),
        n if n > MAX_LEN => Err(DecodeError::TooLarge),
        n => Ok(n as usize),
    }
}

/// Reads one message from a blocking reader: the header, its check, then exactly the body.
pub fn read_message(r: &mut impl std::io::Read) -> std::io::Result<Message> {
    let mut header = [0u8; 4];
    r.read_exact(&mut header)?;
    let len = body_len(header).map_err(invalid)?;
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf)?;
    Message::decode_body(buf[0], &buf[1..]).map_err(invalid)
}

fn invalid(e: DecodeError) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, e)
}

fn put_str(b: &mut Vec<u8>, s: &str) {
    assert!(s.len() <= MAX_STR, "str field over {MAX_STR} bytes");
    b.push(s.len() as u8);
    b.extend_from_slice(s.as_bytes());
}

/// Truncates `s` to at most [`MAX_STR`] bytes on a character boundary.
pub fn clip_str(s: &str) -> String {
    let mut end = s.len().min(MAX_STR);
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    s[..end].to_string()
}

struct Reader<'a> {
    b: &'a [u8],
}

impl Reader<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8], DecodeError> {
        if self.b.len() < n {
            return Err(DecodeError::Malformed);
        }
        let (head, rest) = self.b.split_at(n);
        self.b = rest;
        Ok(head)
    }
    fn arr<const N: usize>(&mut self) -> Result<[u8; N], DecodeError> {
        Ok(self.take(N)?.try_into().expect("length checked"))
    }
    fn u8(&mut self) -> Result<u8, DecodeError> {
        Ok(self.arr::<1>()?[0])
    }
    fn u16(&mut self) -> Result<u16, DecodeError> {
        Ok(u16::from_be_bytes(self.arr()?))
    }
    fn u32(&mut self) -> Result<u32, DecodeError> {
        Ok(u32::from_be_bytes(self.arr()?))
    }
    fn u64(&mut self) -> Result<u64, DecodeError> {
        Ok(u64::from_be_bytes(self.arr()?))
    }
    fn str(&mut self) -> Result<String, DecodeError> {
        let n = self.u8()? as usize;
        if n > MAX_STR {
            return Err(DecodeError::Malformed);
        }
        let bytes = self.take(n)?;
        std::str::from_utf8(bytes).map(str::to_string).map_err(|_| DecodeError::Malformed)
    }
}
