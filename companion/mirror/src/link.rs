//! One TLS connection carrying protocol messages, driven by hand over a blocking socket. Read
//! it either with a timeout ([`Link::recv`]), or by handing the socket's reads to a thread
//! ([`Link::spawn_reader`]) whose bytes the caller feeds back in ([`Link::ingest`]), so one
//! channel can wake the caller for phone bytes and other events alike.

use crate::config::{Identity, fingerprint};
use crate::tls;
use bluke_mirror_core::{Fingerprint, Message};
use rustls::{ClientConnection, Connection, ServerConnection};
use rustls_pki_types::ServerName;
use std::collections::VecDeque;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::mpsc;
use std::time::{Duration, Instant};

/// How long a TCP connect and the TLS handshake may take, together.
pub const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

/// The socket, failing every read and write once `until` has passed. It bounds the whole
/// handshake rather than each read, so a peer trickling bytes can't hold the companion in it.
struct Deadline<'a> {
    sock: &'a mut TcpStream,
    until: Instant,
}

impl Deadline<'_> {
    fn left(&self) -> io::Result<Duration> {
        self.until
            .checked_duration_since(Instant::now())
            .filter(|d| !d.is_zero())
            .ok_or_else(|| io::Error::new(io::ErrorKind::TimedOut, "the TLS handshake took too long"))
    }
}

impl Read for Deadline<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let left = self.left()?;
        self.sock.set_read_timeout(Some(left))?;
        self.sock.read(buf)
    }
}

impl Write for Deadline<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let left = self.left()?;
        self.sock.set_write_timeout(Some(left))?;
        self.sock.write(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.sock.flush()
    }
}

/// One read from the socket reader thread: raw TLS bytes for [`Link::ingest`], or how the
/// stream ended (`UnexpectedEof` when the phone closed it).
pub struct Received {
    /// When the bytes were read, on [`crate::now_us`]'s clock.
    pub at_us: u64,
    pub bytes: io::Result<Vec<u8>>,
}

pub struct Link {
    tls: Connection,
    sock: TcpStream,
    plain: Vec<u8>,
    pending: VecDeque<Message>,
    peer: Fingerprint,
}

impl Link {
    /// Connects to the phone as the TLS client and completes the handshake.
    /// Nothing is sent until the caller has checked [`Link::peer`].
    pub fn connect(addr: SocketAddr, id: &Identity) -> io::Result<Link> {
        Link::connect_within(addr, id, HANDSHAKE_TIMEOUT)
    }

    fn connect_within(addr: SocketAddr, id: &Identity, within: Duration) -> io::Result<Link> {
        let until = Instant::now() + within;
        let sock = TcpStream::connect_timeout(&addr, within)?;
        let config = tls::client_config(id).map_err(io::Error::other)?;
        let name = ServerName::try_from("bluke-phone").expect("valid name").to_owned();
        let conn = ClientConnection::new(config, name).map_err(io::Error::other)?;
        Link::handshake(Connection::Client(conn), sock, until)
    }

    /// Accepts one connection as the TLS server, requiring a client certificate (the phone's role).
    pub fn accept(sock: TcpStream, id: &Identity) -> io::Result<Link> {
        let config = tls::server_config(id).map_err(io::Error::other)?;
        let conn = ServerConnection::new(config).map_err(io::Error::other)?;
        Link::handshake(Connection::Server(conn), sock, Instant::now() + HANDSHAKE_TIMEOUT)
    }

    fn handshake(mut tls: Connection, mut sock: TcpStream, until: Instant) -> io::Result<Link> {
        sock.set_nodelay(true)?;
        let mut io = Deadline { sock: &mut sock, until };
        while tls.is_handshaking() {
            tls.complete_io(&mut io)?;
        }
        // Flush the client's last flight (its Finished) before anything else happens.
        while tls.wants_write() {
            tls.write_tls(&mut io)?;
        }
        sock.set_read_timeout(Some(HANDSHAKE_TIMEOUT))?;
        sock.set_write_timeout(Some(HANDSHAKE_TIMEOUT))?;
        let cert = tls
            .peer_certificates()
            .and_then(|c| c.first())
            .ok_or_else(|| io::Error::new(io::ErrorKind::PermissionDenied, "peer presented no certificate"))?;
        let peer = fingerprint(cert);
        let mut link = Link { tls, sock, plain: Vec::new(), pending: VecDeque::new(), peer };
        // The peer's first message can arrive in the same read as its last handshake flight;
        // the handshake loop then left it inside rustls, where a socket read would never see it.
        let mut early = Vec::new();
        link.absorb(&mut early)?;
        link.pending.extend(early);
        Ok(link)
    }

    /// The fingerprint of the certificate the peer presented in this session.
    pub fn peer(&self) -> Fingerprint {
        self.peer
    }

    pub fn send(&mut self, m: &Message) -> io::Result<()> {
        self.tls.writer().write_all(&m.encode())?;
        while self.tls.wants_write() {
            self.tls.write_tls(&mut self.sock)?;
        }
        Ok(())
    }

    /// Waits up to `wait` for bytes and returns every whole message they complete (possibly none).
    /// A closed connection is `UnexpectedEof`; refused bytes are `InvalidData`.
    pub fn recv(&mut self, wait: Duration) -> io::Result<Vec<Message>> {
        let mut out: Vec<Message> = self.pending.drain(..).collect();
        out.extend(self.decode()?);
        if !out.is_empty() {
            return Ok(out);
        }
        self.sock.set_read_timeout(Some(wait.max(Duration::from_millis(1))))?;
        match self.tls.read_tls(&mut self.sock) {
            Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
            Ok(_) => {}
            Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => return Ok(out),
            Err(e) => return Err(e),
        }
        let closed = self.absorb(&mut out)?;
        if out.is_empty() && closed {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        Ok(out)
    }

    /// Processes the TLS records read so far into `out`'s messages; true once the peer sent close_notify.
    fn absorb(&mut self, out: &mut Vec<Message>) -> io::Result<bool> {
        let state = self.tls.process_new_packets().map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        // Alerts or key updates may need an answer.
        while self.tls.wants_write() {
            self.tls.write_tls(&mut self.sock)?;
        }
        let mut chunk = [0u8; 16 * 1024];
        loop {
            match self.tls.reader().read(&mut chunk) {
                Ok(0) => break,
                Ok(n) => {
                    self.plain.extend_from_slice(&chunk[..n]);
                    // Check headers as bytes arrive, so an oversized length is refused before
                    // its body is buffered.
                    out.extend(self.decode()?);
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                Err(e) => return Err(e),
            }
        }
        Ok(state.peer_has_closed())
    }

    /// Hands the socket's reads to a thread that sends each read into `tx` until the stream
    /// ends or `tx`'s receiver is gone. Afterwards read only through [`Link::ingest`].
    pub fn spawn_reader<E: From<Received> + Send + 'static>(&self, tx: mpsc::Sender<E>) -> io::Result<()> {
        let mut sock = self.sock.try_clone()?;
        sock.set_read_timeout(None)?;
        std::thread::Builder::new().name("link-reader".into()).spawn(move || {
            let mut buf = vec![0u8; 16 * 1024];
            loop {
                let (bytes, last) = match sock.read(&mut buf) {
                    Ok(0) => (Err(io::ErrorKind::UnexpectedEof.into()), true),
                    Ok(n) => (Ok(buf[..n].to_vec()), false),
                    Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                    Err(e) => (Err(e), true),
                };
                if tx.send(Received { at_us: crate::now_us(), bytes }.into()).is_err() || last {
                    break;
                }
            }
        })?;
        Ok(())
    }

    /// Feeds bytes the reader thread read and returns every whole message they complete,
    /// after any that arrived with the handshake.
    pub fn ingest(&mut self, mut bytes: &[u8]) -> io::Result<Vec<Message>> {
        let mut out: Vec<Message> = self.pending.drain(..).collect();
        while !bytes.is_empty() {
            self.tls.read_tls(&mut bytes)?;
            self.absorb(&mut out)?;
        }
        Ok(out)
    }

    /// Waits up to `timeout` for exactly the next message.
    pub fn recv_one(&mut self, timeout: Duration) -> io::Result<Message> {
        let deadline = std::time::Instant::now() + timeout;
        loop {
            if let Some(m) = self.pending.pop_front() {
                return Ok(m);
            }
            let got = self.recv(Duration::from_millis(50))?;
            self.pending.extend(got);
            if let Some(m) = self.pending.pop_front() {
                return Ok(m);
            }
            if std::time::Instant::now() >= deadline {
                return Err(io::ErrorKind::TimedOut.into());
            }
        }
    }

    fn decode(&mut self) -> io::Result<Vec<Message>> {
        let mut out = Vec::new();
        while let Some((m, used)) = Message::decode(&self.plain).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))? {
            self.plain.drain(..used);
            out.push(m);
        }
        Ok(out)
    }

    /// Sends close_notify and shuts the socket.
    pub fn close(mut self) {
        self.tls.send_close_notify();
        while self.tls.wants_write() {
            if self.tls.write_tls(&mut self.sock).is_err() {
                break;
            }
        }
        let _ = self.sock.shutdown(std::net::Shutdown::Both);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustls_pki_types::PrivateKeyDer;
    use std::net::TcpListener;

    fn identity() -> Identity {
        let made = rcgen::generate_simple_self_signed(vec!["test".to_string()]).expect("cert");
        Identity { cert: made.cert.der().clone(), key: PrivateKeyDer::Pkcs8(made.signing_key.serialize_der().into()) }
    }

    #[test]
    fn a_peer_trickling_its_handshake_is_cut_off_at_the_deadline() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let addr = listener.local_addr().expect("addr");
        let peer = std::thread::spawn(move || {
            let (mut sock, _) = listener.accept().expect("accept");
            let mut hello = [0u8; 4096];
            let _ = sock.read(&mut hello);
            // A handshake record header promising 16 KiB, then one byte of it every 50 ms: each
            // read succeeds long before a per-read timeout would fire.
            let _ = sock.write_all(&[0x16, 0x03, 0x03, 0x40, 0x00]);
            for _ in 0..100 {
                std::thread::sleep(Duration::from_millis(50));
                if sock.write_all(&[0]).is_err() {
                    break;
                }
            }
        });
        let start = Instant::now();
        let got = Link::connect_within(addr, &identity(), Duration::from_millis(400));
        let took = start.elapsed();
        assert_eq!(got.err().map(|e| e.kind()), Some(io::ErrorKind::TimedOut));
        assert!(took < Duration::from_millis(1500), "cut off after {took:?}");
        drop(peer);
    }
}
