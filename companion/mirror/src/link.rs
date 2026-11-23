//! One TLS connection carrying protocol messages, driven by hand over a blocking socket with
//! a read timeout, so one thread can wait for messages and still keep time.

use crate::config::{Identity, fingerprint};
use crate::tls;
use bluke_mirror_core::{Fingerprint, Message};
use rustls::{ClientConnection, Connection, ServerConnection};
use rustls_pki_types::ServerName;
use std::collections::VecDeque;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

/// How long a TCP connect and the TLS handshake may take.
pub const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

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
        Ok(Link { tls, sock, plain: Vec::new(), pending: VecDeque::new(), peer })
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
        if out.is_empty() && state.peer_has_closed() {
            return Err(io::ErrorKind::UnexpectedEof.into());
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
