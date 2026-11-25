package io.github.kurashizu.wgfec;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * UDP loss, latency and jitter to a net-probe server, per direction. Speaks the same wire format as
 * tools/net-probe/probe_server.py and probe.py, so one server serves the phone and the command line client.
 * Pure Java (no Android classes) so it can be tested on a desktop JVM.
 *
 * How the two directions are told apart: every DATA packet is echoed (round trip). At the end the client asks
 * for the server's list of which sequence numbers it received (FIN / SUMMARY). Uplink loss is what the server
 * did not receive; downlink loss is what it received but the client never got back.
 */
final class LinkProbe {
    static final int DATA = 1, ECHO = 2, FIN = 3, SUMMARY = 4;
    static final int HDR = 13, CHUNK_BYTES = 1000, CHUNK_SEQS = CHUNK_BYTES * 8;
    static final int MIN_SIZE = 32, MAX_SIZE = 1500, MAX_PACKETS = 100000;
    private static final byte[] MAGIC = {'N', 'P', 'R', '1'};
    private static final double GRACE_S = 2.0;

    static final class Phase {
        final int pps, size, seconds;
        Phase(int pps, int size, int seconds) { this.pps = pps; this.size = size; this.seconds = seconds; }
        int packets() { return pps * seconds; }
        /** Bytes on the wire in both directions, including IP and UDP headers. */
        long wireBytes() { return 2L * packets() * (size + 28); }
        boolean valid() { return pps >= 1 && pps <= 3000 && size >= MIN_SIZE && size <= MAX_SIZE && seconds >= 1 && seconds <= 600 && packets() <= MAX_PACKETS; }
    }

    static final class Result {
        int pps, size, seconds, sent, echoes, reordered, serverRx = -1;
        boolean complete;
        double roundTripLoss = Double.NaN, upLoss = Double.NaN, downLoss = Double.NaN;
        double rttMin = Double.NaN, rttAvg, rttP50, rttP95, rttP99, rttMax, jitter;
        int spikes;
        /** Runs of consecutive lost packets: 1, 2, 3, 4, 5-9, 10+, longest. -1 when the server record was incomplete. */
        int[] upRuns, downRuns;
        double[] windowRoundTrip, windowUp;   // percent per 10 s window
        String note = "";

        double worstOneWay() {
            double w = 0;
            if (!Double.isNaN(upLoss)) w = Math.max(w, upLoss);
            if (!Double.isNaN(downLoss)) w = Math.max(w, downLoss);
            return w;
        }
    }

    interface Listener {
        /** stage is "sending", "waiting" or "collecting". */
        void onProgress(int phase, int phases, String stage, int sent, int total, int echoes);
        void onPhase(int phase, Result r);
    }

    private volatile boolean cancelled;
    void cancel() { cancelled = true; }

    // ---------------------------------------------------------------------------------------------------------

    List<Result> run(String host, int port, List<Phase> plan, Listener l) throws IOException { return run(host, port, plan, l, null); }

    /** With a non-null {@code sni} the packets are dressed up as QUIC (see QuicShape), naming that server. */
    List<Result> run(String host, int port, List<Phase> plan, Listener l, String sni) throws IOException { return run(host, port, plan, l, sni, 0); }

    /** {@code backendPort}: with the QUIC shape, the port a wg-quic relay should forward to (the probe's own port); 0 for none. */
    List<Result> run(String host, int port, List<Phase> plan, Listener l, String sni, int backendPort) throws IOException {
        InetAddress ip = resolve(host);
        List<Result> out = new ArrayList<>();
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(40);
            sock.setReceiveBufferSize(1 << 20);
            sock.connect(new InetSocketAddress(ip, port));
            Run run = new Run(sock);
            if (sni != null) {
                try { run.shape = new QuicShape(backendPort); } catch (java.security.GeneralSecurityException e) { throw new IOException("QUIC shape is not available on this device: " + e.getMessage()); }
                run.openQuic(sni);
            }
            Thread rx = new Thread(run::receiveLoop, "linkprobe-rx");
            rx.setDaemon(true);
            rx.start();
            try {
                for (int i = 0; i < plan.size() && !cancelled; i++) {
                    Result r = run.phase(plan.get(i), i + 1, plan.size(), l);
                    if (cancelled) break;
                    out.add(r);
                    if (l != null) l.onPhase(i + 1, r);
                }
            } finally {
                run.stop = true;
                try { rx.join(500); } catch (InterruptedException ignored) { }
            }
        }
        return out;
    }

    static InetAddress resolve(String host) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress a : all) if (a instanceof Inet4Address) return a;   // the server listens on IPv4
        return all[0];
    }

    private final class Run {
        final DatagramSocket sock;
        volatile boolean stop, refused, answered;
        QuicShape shape;
        volatile Cur cur;

        Run(DatagramSocket sock) { this.sock = sock; }

        final class Cur {
            final int sid, n;
            final float[] rtt;
            final AtomicInteger echoes = new AtomicInteger();
            final ConcurrentHashMap<Integer, byte[]> summary = new ConcurrentHashMap<>();
            volatile int summaryRx = -1;
            int maxSeen = -1, reordered;       // touched only by the receive thread

            Cur(int sid, int n) {
                this.sid = sid; this.n = n;
                rtt = new float[n];
                Arrays.fill(rtt, Float.NaN);
            }
        }

        void receiveLoop() {
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (!stop) {
                try {
                    p.setLength(buf.length);
                    sock.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                } catch (java.net.PortUnreachableException e) {
                    refused = true;        // an ICMP "port unreachable" came back: nothing is listening there
                    continue;
                } catch (IOException e) {
                    if (stop) return;
                    continue;      // e.g. ICMP port unreachable on a connected socket; the final summary reports it
                }
                int n = p.getLength();
                if (shape != null) {
                    if (QuicShape.isLongHeader(buf, n)) { answered = true; continue; }
                    byte[] inner;
                    try { inner = shape.unwrap(buf, n); } catch (java.security.GeneralSecurityException e) { continue; }
                    if (inner == null) continue;
                    handle(inner, inner.length);
                } else {
                    handle(buf, n);
                }
            }
        }

        /** Starts the fake connection: an Initial and a Handshake packet, resent a few times until the server answers. */
        void openQuic(String sni) throws IOException {
            try {
                byte[][] flight = shape.firstFlight(sni);
                for (int i = 0; i < 4 && !answered && !cancelled; i++) {
                    for (byte[] f : flight) { try { sock.send(new DatagramPacket(f, f.length)); } catch (IOException ignored) { } }
                    long end = System.nanoTime() + 500_000_000L;
                    while (System.nanoTime() < end && !answered && !cancelled) sleep(20);
                }
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException("QUIC shape failed: " + e.getMessage());
            }
        }

        /** Sends one probe packet, inside the QUIC wrapper when that is on. */
        void tx(byte[] buf, int len) throws IOException {
            if (shape == null) { sock.send(new DatagramPacket(buf, len)); return; }
            try {
                byte[] w = shape.wrap(buf, len);
                sock.send(new DatagramPacket(w, w.length));
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException(e.getMessage());
            }
        }

        void handle(byte[] d, int len) {
            Cur c = cur;
            if (c == null || len < HDR || d[0] != MAGIC[0] || d[1] != MAGIC[1] || d[2] != MAGIC[2] || d[3] != MAGIC[3]) return;
            ByteBuffer b = ByteBuffer.wrap(d, 0, len);
            int type = b.get(4) & 0xff, sid = b.getInt(5), seq = b.getInt(9);
            if (sid != c.sid) return;
            if (type == ECHO && len >= 25 && seq >= 0 && seq < c.n) {
                if (Float.isNaN(c.rtt[seq])) {
                    long t = b.getLong(13);
                    c.rtt[seq] = (System.nanoTime() - t) / 1e6f;
                    c.echoes.incrementAndGet();
                    if (seq < c.maxSeen) c.reordered++;
                    c.maxSeen = Math.max(c.maxSeen, seq);
                }
            } else if (type == SUMMARY && len >= HDR + 10) {
                int total = b.getInt(HDR), rx = b.getInt(HDR + 4);
                if (total == c.n) {
                    c.summary.put(seq, Arrays.copyOfRange(d, HDR + 10, len));
                    c.summaryRx = rx;
                }
            }
        }

        Result phase(Phase ph, int idx, int phases, Listener l) throws IOException {
            int n = ph.packets();
            Cur c = new Cur(new Random().nextInt(), n);
            // with the QUIC wrapper the packet on the wire is ph.size bytes too, so the probe packet inside is smaller
            byte[] buf = new byte[shape != null ? Math.max(MIN_SIZE, ph.size - QuicShape.OVERHEAD) : ph.size];
            new Random().nextBytes(buf);
            System.arraycopy(MAGIC, 0, buf, 0, 4);
            ByteBuffer b = ByteBuffer.wrap(buf);
            b.put(4, (byte) DATA).putInt(5, c.sid);
            cur = c;

            long interval = 1_000_000_000L / ph.pps, start = System.nanoTime(), lastReport = 0;
            int sent = 0, errors = 0;
            while (sent < n && !cancelled) {
                long now = System.nanoTime();
                int burst = 0;
                while (sent < n && now >= start + sent * interval && burst < 64) {
                    b.putInt(9, sent).putLong(13, System.nanoTime()).putInt(21, 0);
                    try { tx(buf, buf.length); } catch (IOException e) { errors++; }
                    sent++; burst++;
                    now = System.nanoTime();
                }
                if (errors > 50 && errors == sent) throw new IOException("Cannot send: is there a network connection?");
                if (c.echoes.get() == 0 && sent >= 20 && now - start > 3_000_000_000L)
                    throw new IOException(refused ? "The server refused the packets (nothing is listening on that port)."
                            : "No reply from the server after 3 s. Check the host and port, that the probe service is running and that its UDP port is open.");
                if (l != null && now - lastReport > 250_000_000L) {
                    lastReport = now;
                    l.onProgress(idx, phases, "sending", sent, n, c.echoes.get());
                }
                if (sent < n) LockSupport.parkNanos(Math.min(2_000_000L, Math.max(100_000L, start + sent * interval - System.nanoTime())));
            }
            long end = System.nanoTime() + (long) (GRACE_S * 1e9);
            while (System.nanoTime() < end && !cancelled) {
                if (l != null) l.onProgress(idx, phases, "waiting", n, n, c.echoes.get());
                sleep(100);
            }
            if (cancelled) return null;
            if (l != null) l.onProgress(idx, phases, "collecting", n, n, c.echoes.get());
            boolean complete = fetchSummary(c);
            Result r = stats(ph, c, complete);
            cur = null;
            return r;
        }

        boolean fetchSummary(Cur c) {
            int chunks = (c.n + CHUNK_SEQS - 1) / CHUNK_SEQS;
            byte[] fin = new byte[1200];
            System.arraycopy(MAGIC, 0, fin, 0, 4);
            ByteBuffer b = ByteBuffer.wrap(fin);
            b.put(4, (byte) FIN).putInt(5, c.sid).putInt(13, c.n);
            for (int ch = 0; ch < chunks && !cancelled; ch++) {
                b.putInt(9, ch);
                for (int attempt = 0; attempt < 12 && !c.summary.containsKey(ch) && !cancelled; attempt++) {
                    try { tx(fin, fin.length); } catch (IOException ignored) { }
                    long deadline = System.nanoTime() + 700_000_000L;
                    while (System.nanoTime() < deadline && !c.summary.containsKey(ch)) sleep(15);
                }
            }
            return c.summary.size() == chunks;
        }

        Result stats(Phase ph, Cur c, boolean complete) {
            int n = c.n;
            Result r = new Result();
            r.pps = ph.pps; r.size = ph.size; r.seconds = ph.seconds; r.sent = n;
            boolean[] echoed = new boolean[n];
            int ne = 0;
            for (int i = 0; i < n; i++) if (!Float.isNaN(c.rtt[i])) { echoed[i] = true; ne++; }
            r.echoes = ne; r.reordered = c.reordered; r.complete = complete;
            r.roundTripLoss = 100.0 * (n - ne) / n;

            boolean[] upOk = null;
            int srvRx = -1;
            if (complete) {
                upOk = new boolean[n];
                srvRx = 0;
                for (int i = 0; i < n; i++) {
                    byte[] bm = c.summary.get(i / CHUNK_SEQS);
                    int off = i % CHUNK_SEQS, by = off >> 3;
                    upOk[i] = bm != null && by < bm.length && (bm[by] & (1 << (off & 7))) != 0;
                    if (upOk[i]) srvRx++;
                }
            } else if (c.summaryRx >= 0) {
                srvRx = c.summaryRx;
            }
            r.serverRx = srvRx;
            if (srvRx < 0) {
                r.note = "The server did not answer the final request. Is the port open, and is the probe service running?";
            } else {
                r.upLoss = 100.0 * (n - srvRx) / n;
                r.downLoss = srvRx > 0 ? 100.0 * Math.max(0, srvRx - ne) / srvRx : Double.NaN;
            }
            if (upOk != null) {
                r.upRuns = runs(upOk, null);
                r.downRuns = runs(echoed, upOk);
            }

            float[] seq = new float[ne];
            int k = 0;
            for (int i = 0; i < n; i++) if (echoed[i]) seq[k++] = c.rtt[i];
            if (ne > 0) {
                float[] sorted = seq.clone();
                Arrays.sort(sorted);
                double sum = 0, jit = 0;
                for (float v : sorted) sum += v;
                for (int i = 1; i < ne; i++) jit += Math.abs(seq[i] - seq[i - 1]);
                r.rttMin = sorted[0]; r.rttMax = sorted[ne - 1]; r.rttAvg = sum / ne;
                r.rttP50 = pct(sorted, 50); r.rttP95 = pct(sorted, 95); r.rttP99 = pct(sorted, 99);
                r.jitter = ne > 1 ? jit / (ne - 1) : 0;
                for (float v : sorted) if (v > r.rttP50 + 50) r.spikes++;
            }
            int win = Math.max(1, ph.pps * 10), nw = (n + win - 1) / win;
            r.windowRoundTrip = new double[nw];
            r.windowUp = new double[nw];
            for (int w = 0; w < nw; w++) {
                int a = w * win, z = Math.min(n, a + win), lostRt = 0, lostUp = 0;
                for (int i = a; i < z; i++) {
                    if (!echoed[i]) lostRt++;
                    if (upOk != null && !upOk[i]) lostUp++;
                }
                r.windowRoundTrip[w] = 100.0 * lostRt / (z - a);
                r.windowUp[w] = upOk != null ? 100.0 * lostUp / (z - a) : Double.NaN;
            }
            return r;
        }
    }

    /** Runs of consecutive false values (restricted to positions where {@code only} is true, if given). */
    static int[] runs(boolean[] ok, boolean[] only) {
        int[] h = new int[7];
        int cur = 0;
        for (int i = 0; i < ok.length; i++) {
            if (only != null && !only[i]) continue;
            if (ok[i]) { addRun(h, cur); cur = 0; } else cur++;
        }
        addRun(h, cur);
        return h;
    }

    private static void addRun(int[] h, int len) {
        if (len <= 0) return;
        if (len <= 4) h[len - 1]++; else if (len <= 9) h[4]++; else h[5]++;
        h[6] = Math.max(h[6], len);
    }

    static double pct(float[] sorted, double p) {
        int k = (int) Math.min(sorted.length - 1, Math.max(0, Math.ceil(p / 100.0 * sorted.length) - 1));
        return sorted[k];
    }

    /** Chance that one FEC group of x data + y parity packets cannot be repaired when each packet is lost with probability p. */
    static double groupFail(int x, int y, double p) {
        int n = x + y;
        double total = 0, c = 1;       // c = C(n, l)
        for (int l = 0; l <= n; l++) {
            if (l > 0) c = c * (n - l + 1) / l;
            if (l > y) total += c * Math.pow(p, l) * Math.pow(1 - p, n - l);
        }
        return total;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
