#!/usr/bin/env python3
"""net-probe server: a UDP echo that also remembers which sequence numbers arrived.

Pure standard library. A client sends numbered DATA packets at a fixed rate; every one is echoed back
(so the client sees round-trip loss and latency). When the client is done it asks, with FIN packets,
for the server's view of which sequence numbers arrived. That splits the loss into an uplink part
(client -> server, known only here) and a downlink part (server -> client, known only to the client).

Wire format (all integers big endian):
    header   : "NPR1", type u8, session u32, seq u32                       (13 bytes)
    DATA 1   : header, t_send_ns u64, server_rx u32 (0 in requests), padding   (32..1500 bytes)
    ECHO 2   : the DATA packet again, type=2 and server_rx filled in            (same length)
    FIN  3   : header (seq = chunk index), total_sent u32, padding              (>= 1100 bytes)
    SUMMARY 4: header (seq = chunk index), total_sent u32, rx_unique u32, chunks u16, bitmap (<= 1000 B)

Abuse protection: a reply is never longer than the request that caused it (no amplification); a FIN is
only answered for a session that has already sent DATA; per-source rate limit and session limits;
malformed packets are dropped silently.

Test hooks (never set in normal use): PROBE_DROP_RX / PROBE_DROP_TX = percentage of packets to drop on
purpose, PROBE_BURST_RX = "percent,length" to drop packets in bursts (a burst starts with that chance per
packet and swallows `length` packets); they exist to check the client's statistics.
"""
import argparse
import os
import random
import select
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))     # python -I does not do this
try:
    import quicshape
    QUIC = quicshape.AVAILABLE
except Exception:
    QUIC = False

MAGIC = b"NPR1"
HDR = struct.Struct(">4sBII")
DATA, ECHO, FIN, SUMMARY = 1, 2, 3, 4
MIN_DATA, MAX_DATA = 32, 1500
MIN_FIN = 1100
MAX_SEQ = 100000
CHUNK_BYTES = 1000
CHUNK_SEQS = CHUNK_BYTES * 8
RATE_PPS, RATE_BURST = 6000.0, 1500.0
MAX_SESSIONS, MAX_PER_IP, IDLE_SECONDS = 256, 16, 600


class Session(object):
    __slots__ = ("bm", "rx", "last", "logged")

    def __init__(self, now):
        self.bm = bytearray()
        self.rx = 0
        self.last = now
        self.logged = False


def log(msg):
    sys.stdout.write("%s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
    sys.stdout.flush()


def run_histogram(bm, total):
    """Lengths of runs of consecutive missing sequence numbers among 0..total-1."""
    runs, cur = [], 0
    for i in range(total):
        b = i >> 3
        got = b < len(bm) and bm[b] & (1 << (i & 7))
        if got:
            if cur:
                runs.append(cur)
                cur = 0
        else:
            cur += 1
    if cur:
        runs.append(cur)
    return runs


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, action="append", help="UDP port to listen on; repeat for several (default 62933)")
    ap.add_argument("--bind", default="0.0.0.0")
    args = ap.parse_args()
    drop_rx = float(os.environ.get("PROBE_DROP_RX", "0"))
    drop_tx = float(os.environ.get("PROBE_DROP_TX", "0"))
    burst_p, _, burst_len = os.environ.get("PROBE_BURST_RX", "0,0").partition(",")
    burst_p, burst_len, burst_left = float(burst_p), int(burst_len or 0), 0

    socks = []
    for port in args.port or [62933]:
        sk = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sk.bind((args.bind, port))
        socks.append(sk)
    log("net-probe listening on %s:%s%s%s" % (args.bind, ",".join(str(s.getsockname()[1]) for s in socks),
        " (QUIC-shaped packets accepted)" if QUIC else " (QUIC shape off: no cryptography module)",
        " (TEST DROP rx=%g%% tx=%g%%)" % (drop_rx, drop_tx) if drop_rx or drop_tx else ""))

    sessions, buckets = {}, {}
    last_purge = time.monotonic()

    def allow(ip, now):
        tokens, t = buckets.get(ip, (RATE_BURST, now))
        tokens = min(RATE_BURST, tokens + (now - t) * RATE_PPS)
        if tokens < 1.0:
            buckets[ip] = (tokens, now)
            return False
        buckets[ip] = (tokens - 1.0, now)
        return True

    while True:
        data = None
        try:
            ready = select.select(socks, [], [], 1.0)[0]
            if ready:
                sock = ready[0]
                data, addr = sock.recvfrom(2048)
        except OSError:
            continue
        now = time.monotonic()
        if now - last_purge > 30:
            last_purge = now
            for k in [k for k, s in sessions.items() if now - s.last > IDLE_SECONDS]:
                del sessions[k]
            for ip in [ip for ip, (_, t) in buckets.items() if now - t > 60]:
                del buckets[ip]
        if data is None or len(data) < HDR.size:
            continue
        ip = addr[0]
        cid = None
        if data[:4] != MAGIC:
            # not a plain probe packet: maybe one dressed up as QUIC (see quicshape.py)
            if not QUIC or not allow(ip, now):
                continue
            if quicshape.is_long_header(data):
                first = quicshape.parse_client_initial(data)
                if first:
                    sock.sendto(quicshape.server_first_flight(first[0], first[1], len(data)), addr)
                continue
            un = quicshape.unwrap(data)
            if not un or len(un[1]) < HDR.size or un[1][:4] != MAGIC:
                continue
            cid, data = un
        elif not allow(ip, now):
            continue
        magic, typ, sid, seq = HDR.unpack_from(data)
        if drop_rx and random.random() * 100 < drop_rx:
            continue
        if burst_len and typ == DATA:
            if burst_left > 0:
                burst_left -= 1
                continue
            if random.random() * 100 < burst_p:
                burst_left = burst_len - 1
                continue

        if typ == DATA:
            if not MIN_DATA <= len(data) <= MAX_DATA or seq >= MAX_SEQ:
                continue
            key = (ip, sid)
            s = sessions.get(key)
            if s is None:
                if len(sessions) >= MAX_SESSIONS or sum(1 for k in sessions if k[0] == ip) >= MAX_PER_IP:
                    continue
                s = sessions[key] = Session(now)
            s.last = now
            byte, bit = seq >> 3, 1 << (seq & 7)
            if byte >= len(s.bm):
                s.bm.extend(bytes(byte + 1 - len(s.bm)))
            if not s.bm[byte] & bit:
                s.bm[byte] |= bit
                s.rx += 1
            out = bytearray(data)
            out[4] = ECHO
            struct.pack_into(">I", out, 21, s.rx)
            if drop_tx and random.random() * 100 < drop_tx:
                continue
            sock.sendto(quicshape.wrap(cid, bytes(out)) if cid else bytes(out), addr)

        elif typ == FIN:
            if len(data) < MIN_FIN:
                continue
            s = sessions.get((ip, sid))
            if s is None:
                continue
            total = struct.unpack_from(">I", data, 13)[0]
            if not 1 <= total <= MAX_SEQ:
                continue
            chunks = (total + CHUNK_SEQS - 1) // CHUNK_SEQS
            if seq >= chunks:
                continue
            s.last = now
            start = seq * CHUNK_BYTES
            want = min(CHUNK_BYTES, (total - seq * CHUNK_SEQS + 7) // 8)
            bm = bytes(s.bm[start:start + want]).ljust(want, b"\x00")
            out = HDR.pack(MAGIC, SUMMARY, sid, seq) + struct.pack(">IIH", total, s.rx, chunks) + bm
            if seq == 0 and not s.logged:
                s.logged = True
                runs = run_histogram(s.bm, total)
                lost = sum(runs)
                log("session %08x from %s: sent=%d received=%d uplink_loss=%.2f%% loss_runs 1:%d 2:%d 3:%d 4:%d 5+:%d longest=%d"
                    % (sid, ip, total, total - lost, 100.0 * lost / total,
                       runs.count(1), runs.count(2), runs.count(3), runs.count(4),
                       sum(1 for r in runs if r >= 5), max(runs) if runs else 0))
            sock.sendto(quicshape.wrap(cid, out) if cid else out, addr)


if __name__ == "__main__":
    main()
