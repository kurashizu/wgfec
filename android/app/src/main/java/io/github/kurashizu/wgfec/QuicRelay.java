package io.github.kurashizu.wgfec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;

/**
 * Sits between the speederv2 helper and the server and dresses the tunnel up as QUIC (see QuicShape). The helper sends to
 * 127.0.0.1:localPort() as if that were the server; this relay wraps each datagram and sends it to the server's QUIC port,
 * where wg-quic unwraps it and hands it to the right UDPspeeder instance. What comes back takes the same way in reverse.
 * Which instance (the main service or one of the FEC levels) is decided by {@link #retarget}.
 * The app is excluded from its own VPN, so these packets go straight out, like the helper's own.
 * Pure Java, no Android classes.
 */
final class QuicRelay {
    private final DatagramSocket local, remote;
    private final InetSocketAddress server;
    private final String sni;
    private volatile QuicShape shape;
    private volatile java.net.SocketAddress helper;     // where the helper sends from: answers go back there
    private volatile boolean closed;
    private final Thread up, down;
    /** Packets handed over in each direction, for the log and tests. */
    volatile long sent, received;

    private QuicRelay(String host, int quicPort, String sni) throws IOException {
        this.sni = sni;
        InetAddress ip = LinkProbe.resolve(host);
        server = new InetSocketAddress(ip, quicPort);
        local = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        remote = new DatagramSocket();
        remote.setSoTimeout(500);
        remote.setReceiveBufferSize(1 << 20);
        up = new Thread(this::upLoop, "wgfec-quic-up");
        down = new Thread(this::downLoop, "wgfec-quic-down");
        up.setDaemon(true); down.setDaemon(true);
    }

    /** Starts a relay to host:quicPort that leads to the server's UDP port {@code backendPort}. */
    static QuicRelay start(String host, int quicPort, String sni, int backendPort) throws IOException {
        QuicRelay r = new QuicRelay(host, quicPort, sni);
        r.retarget(backendPort);
        r.up.start();
        r.down.start();
        return r;
    }

    int localPort() { return local.getLocalPort(); }

    /** Continue as a new connection that leads to another server port (another FEC level). */
    void retarget(int backendPort) throws IOException {
        try {
            QuicShape s = new QuicShape(backendPort);
            byte[][] flight = s.firstFlight(sni);
            shape = s;
            for (byte[] f : flight) remote.send(new DatagramPacket(f, f.length, server));
        } catch (GeneralSecurityException e) {
            throw new IOException("QUIC shape failed: " + e.getMessage());
        }
    }

    void close() {
        closed = true;
        local.close();
        remote.close();
    }

    private void upLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (!closed) {
            try {
                p.setLength(buf.length);
                local.receive(p);
                helper = p.getSocketAddress();
                byte[] w = shape.wrap(buf, p.getLength());
                remote.send(new DatagramPacket(w, w.length, server));
                sent++;
            } catch (IOException | GeneralSecurityException e) {
                if (closed) return;
            }
        }
    }

    private void downLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        while (!closed) {
            try {
                p.setLength(buf.length);
                remote.receive(p);
                if (QuicShape.isLongHeader(buf, p.getLength())) continue;        // the fake handshake answer
                byte[] inner = shape.unwrap(buf, p.getLength());
                java.net.SocketAddress h = helper;
                if (inner == null || h == null) continue;
                local.send(new DatagramPacket(inner, inner.length, h));
                received++;
            } catch (SocketTimeoutException e) {
                // just look at "closed" again
            } catch (IOException | GeneralSecurityException e) {
                if (closed) return;
            }
        }
    }
}
