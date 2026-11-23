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
use std::time::Duration;

/// How long a TCP connect and the TLS handshake may take.
pub const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

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
        let sock = TcpStream::connect_timeout(&addr, HANDSHAKE_TIMEOUT)?;
        let config = tls::client_config(id).map_err(io::Error::other)?;
        let name = ServerName::try_from("bluke-phone").expect("valid name").to_owned();
        let conn = ClientConnection::new(config, name).map_err(io::Error::other)?;
        Link::handshake(Connection::Client(conn), sock)
    }

    /// Accepts one connection as the TLS server, requiring a client certificate (the phone's role).
    pub fn accept(sock: TcpStream, id: &Identity) -> io::Result<Link> {
        let config = tls::server_config(id).map_err(io::Error::other)?;
        let conn = ServerConnection::new(config).map_err(io::Error::other)?;
        Link::handshake(Connection::Server(conn), sock)
    }

    fn handshake(mut tls: Connection, mut sock: TcpStream) -> io::Result<Link> {
        sock.set_nodelay(true)?;
        sock.set_read_timeout(Some(HANDSHAKE_TIMEOUT))?;
        sock.set_write_timeout(Some(HANDSHAKE_TIMEOUT))?;
        while tls.is_handshaking() {
            tls.complete_io(&mut sock)?;
        }
        // Flush the client's last flight (its Finished) before anything else happens.
        while tls.wants_write() {
            tls.write_tls(&mut sock)?;
        }
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
