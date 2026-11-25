#!/usr/bin/env python3
"""net-probe client: measures UDP loss, latency and jitter to a net-probe server, per direction.

    python3 probe.py SERVER_IP [--port 62933] [--phase PPS:SIZE:SECONDS ...] [--json FILE] [--quick]

Default plan (about 2.5 minutes):
    30 pps  x 200 B  x 90 s   - like interactive traffic (small packets, low rate)
    300 pps x 1200 B x 45 s   - like a stream or a download (about 2.9 Mbit/s each way)
Pure standard library; runs on Linux, macOS and Windows (Python 3.6+).
"""
import argparse
import json
import math
import os
import platform
import random
import select
import socket
import struct
import sys
import time

def _now_ns():
    """perf_counter_ns needs Python 3.7; older systems (3.6) use the float clock."""
    f = getattr(time, "perf_counter_ns", None)
    return f() if f else int(time.perf_counter() * 1e9)


try:
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import quicshape
except Exception:       # the plain test works without it
    class quicshape(object):
        AVAILABLE = False

MAGIC = b"NPR1"
HDR = struct.Struct(">4sBII")
DATA_FMT = struct.Struct(">4sBIIQI")          # header + t_send_ns + server_rx  (25 bytes)
DATA, ECHO, FIN, SUMMARY = 1, 2, 3, 4
CHUNK_BYTES = 1000
CHUNK_SEQS = CHUNK_BYTES * 8
GRACE = 2.0                                  # seconds to wait for late echoes after the last packet
FEC_TIERS = [(1, 3), (2, 4), (5, 6), (10, 10), (20, 20), (40, 40)]


def runs_of(flags):
    """Lengths of runs of False in an iterable of booleans."""
    out, cur = [], 0
    for f in flags:
        if f:
            if cur:
                out.append(cur)
                cur = 0
        else:
            cur += 1
    if cur:
        out.append(cur)
    return out


def run_hist(runs):
    return {"1": runs.count(1), "2": runs.count(2), "3": runs.count(3), "4": runs.count(4),
            "5-9": sum(1 for r in runs if 5 <= r <= 9), "10+": sum(1 for r in runs if r >= 10),
            "longest": max(runs) if runs else 0}


def pct(sorted_vals, p):
    if not sorted_vals:
        return None
    k = min(len(sorted_vals) - 1, max(0, int(math.ceil(p / 100.0 * len(sorted_vals))) - 1))
    return sorted_vals[k]


def group_fail(x, y, p):
    """Probability that a FEC group of x data + y parity shards cannot be repaired at loss rate p."""
    n = x + y
    return sum(math.comb(n, l) * p ** l * (1 - p) ** (n - l) for l in range(y + 1, n + 1)) if hasattr(math, "comb") else None


class Phase(object):
    def __init__(self, sock, addr, pps, size, dur, cid=None):
        self.sock, self.addr, self.cid = sock, addr, cid
        self.pps, self.dur = pps, dur
        # in QUIC shape the packet on the wire is `size` bytes too, so the probe packet inside is smaller by the wrapper
        self.size = max(32, size - quicshape.OVERHEAD) if cid else size
        self.n = int(pps * dur)
        self.sid = random.getrandbits(32)
        self.rtt = [None] * self.n            # ms, None = no echo
        self.srv_rx_last = 0
        self.max_seen = -1
        self.reorder = 0
        self.summary = {}                     # chunk -> bitmap bytes
        self.summary_rx = None

    def tx(self, data):
        self.sock.sendto(quicshape.wrap(self.cid, bytes(data)) if self.cid else data, self.addr)

    # ---- receiving -------------------------------------------------------------------------
    def handle(self, data):
        if len(data) < HDR.size:
            return
        magic, typ, sid, seq = HDR.unpack_from(data)
        if magic != MAGIC or sid != self.sid:
            return
        if typ == ECHO and len(data) >= 25 and seq < self.n:
            t_ns = struct.unpack_from(">Q", data, 13)[0]
            if self.rtt[seq] is None:
                self.rtt[seq] = (_now_ns() - t_ns) / 1e6
                if seq < self.max_seen:
                    self.reorder += 1
                self.max_seen = max(self.max_seen, seq)
        elif typ == SUMMARY and len(data) >= HDR.size + 10:
            total, rx, chunks = struct.unpack_from(">IIH", data, HDR.size)
            if total == self.n:
                self.summary[seq] = data[HDR.size + 10:]
                self.summary_rx = rx

    def drain(self, wait):
        r, _, _ = select.select([self.sock], [], [], max(0.0, wait))
        while r:
            try:
                data, _ = self.sock.recvfrom(2048)
            except (BlockingIOError, InterruptedError):
                break
            except (ConnectionResetError, OSError):
                break                           # Windows reports ICMP "port unreachable" this way
            if self.cid:
                un = quicshape.unwrap(data)
                if not un or un[0] != self.cid:
                    r, _, _ = select.select([self.sock], [], [], 0)
                    continue
                data = un[1]
            self.handle(data)
            r, _, _ = select.select([self.sock], [], [], 0)

    # ---- sending ---------------------------------------------------------------------------
    def send_all(self, progress):
        buf = bytearray(os.urandom(self.size))
        interval = 1.0 / self.pps
        start = time.perf_counter()
        sent = 0
        last_print = 0
        while sent < self.n:
            now = time.perf_counter()
            burst = 0
            while sent < self.n and now >= start + sent * interval and burst < 64:
                DATA_FMT.pack_into(buf, 0, MAGIC, DATA, self.sid, sent, _now_ns(), 0)
                try:
                    self.tx(buf)
                except (BlockingIOError, InterruptedError):
                    pass
                except OSError:
                    pass
                sent += 1
                burst += 1
                now = time.perf_counter()
            if progress and now - last_print > 5:
                last_print = now
                got = sum(1 for v in self.rtt if v is not None)
                sys.stdout.write("\r    sent %d/%d, echoes %d  " % (sent, self.n, got))
                sys.stdout.flush()
            self.drain(min(0.02, start + sent * interval - now) if sent < self.n else 0)
        end = time.perf_counter() + GRACE
        while time.perf_counter() < end:
            self.drain(min(0.05, end - time.perf_counter()))
        if progress:
            sys.stdout.write("\r" + " " * 60 + "\r")

    def fetch_summary(self):
        chunks = (self.n + CHUNK_SEQS - 1) // CHUNK_SEQS
        for c in range(chunks):
            for _ in range(12):
                fin = bytearray(1200)
                HDR.pack_into(fin, 0, MAGIC, FIN, self.sid, c)
                struct.pack_into(">I", fin, 13, self.n)
                try:
                    self.tx(fin)
                except OSError:
                    pass
                deadline = time.perf_counter() + 0.7
                while time.perf_counter() < deadline and c not in self.summary:
                    self.drain(min(0.1, deadline - time.perf_counter()))
                if c in self.summary:
                    break
        return len(self.summary) == chunks

    # ---- statistics ------------------------------------------------------------------------
    def stats(self, complete):
        n = self.n
        echoed = [v is not None for v in self.rtt]
        n_echo = sum(echoed)
        res = {"pps": self.pps, "size": self.size, "seconds": self.dur, "sent": n, "echoes": n_echo,
               "roundtrip_loss_pct": round(100.0 * (n - n_echo) / n, 3), "reordered": self.reorder,
               "summary_complete": complete}
        up_ok = None
        if complete:
            up_ok = []
            for i in range(n):
                c, off = divmod(i, CHUNK_SEQS)
                bm = self.summary[c]
                b = off >> 3
                up_ok.append(bool(b < len(bm) and bm[b] & (1 << (off & 7))))
            srv_rx = sum(up_ok)
        else:
            srv_rx = self.summary_rx if self.summary_rx is not None else None
        res["server_received"] = srv_rx
        if srv_rx is None:
            res["note"] = "no reply to the final request: the server did not answer (port closed or filtered?)"
        else:
            res["uplink_loss_pct"] = round(100.0 * (n - srv_rx) / n, 3)
            res["downlink_loss_pct"] = round(100.0 * max(0, srv_rx - n_echo) / srv_rx, 3) if srv_rx else None
        if up_ok is not None:
            res["uplink_runs"] = run_hist(runs_of(up_ok))
            res["downlink_runs"] = run_hist(runs_of(echoed[i] for i in range(n) if up_ok[i]))
        # latency
        vals = sorted(v for v in self.rtt if v is not None)
        if vals:
            seq = [v for v in self.rtt if v is not None]
            jit = sum(abs(seq[i] - seq[i - 1]) for i in range(1, len(seq))) / max(1, len(seq) - 1)
            p50 = pct(vals, 50)
            res["rtt_ms"] = {"min": round(vals[0], 1), "avg": round(sum(vals) / len(vals), 1),
                             "p50": round(p50, 1), "p95": round(pct(vals, 95), 1),
                             "p99": round(pct(vals, 99), 1), "max": round(vals[-1], 1),
                             "jitter": round(jit, 2),
                             "spikes_over_p50_plus_50ms": sum(1 for v in vals if v > p50 + 50)}
        # loss over time (10 s windows)
        win = max(1, int(self.pps * 10))
        wins = []
        for w in range(0, n, win):
            idx = range(w, min(n, w + win))
            m = len(idx)
            rt = 100.0 * sum(1 for i in idx if not echoed[i]) / m
            up = 100.0 * sum(1 for i in idx if not up_ok[i]) / m if up_ok is not None else None
            wins.append({"t": int(w / self.pps), "roundtrip_loss_pct": round(rt, 1),
                         "uplink_loss_pct": round(up, 1) if up is not None else None})
        res["windows_10s"] = wins
        return res


def fmt_runs(h):
    return "1:%d 2:%d 3:%d 4:%d 5-9:%d 10+:%d (longest %d)" % (h["1"], h["2"], h["3"], h["4"], h["5-9"], h["10+"], h["longest"])


def report(i, st):
    print("\n=== Phase %d: %d pps x %d B for %d s  (%d packets)" % (i, st["pps"], st["size"], st["seconds"], st["sent"]))
    if "uplink_loss_pct" not in st:
        print("   " + st.get("note", "no data"))
        print("   round-trip loss: %.2f%%  (echoes %d)" % (st["roundtrip_loss_pct"], st["echoes"]))
        return
    print("   loss    uplink (you -> server) : %6.2f %%" % st["uplink_loss_pct"])
    dl = st.get("downlink_loss_pct")
    print("           downlink (server -> you): %6s %%" % ("%.2f" % dl if dl is not None else "n/a"))
    print("           round trip             : %6.2f %%   (%d of %d echoes)" % (st["roundtrip_loss_pct"], st["echoes"], st["sent"]))
    if "uplink_runs" in st:
        print("   loss runs (how many consecutive packets are lost together)")
        print("           uplink  : " + fmt_runs(st["uplink_runs"]))
        print("           downlink: " + fmt_runs(st["downlink_runs"]))
    else:
        print("   (could not fetch the server's full record, so no per-direction burst statistics)")
    r = st.get("rtt_ms")
    if r:
        print("   RTT ms  min %.1f  avg %.1f  p50 %.1f  p95 %.1f  p99 %.1f  max %.1f   jitter %.2f   spikes(>p50+50ms): %d"
              % (r["min"], r["avg"], r["p50"], r["p95"], r["p99"], r["max"], r["jitter"], r["spikes_over_p50_plus_50ms"]))
    if st["reordered"]:
        print("   reordered echoes: %d" % st["reordered"])
    w = st["windows_10s"]
    if len(w) > 1:
        worst = max(w, key=lambda x: x["roundtrip_loss_pct"])
        best = min(w, key=lambda x: x["roundtrip_loss_pct"])
        print("   over time (10 s windows, round trip loss): best %.1f %% at %ds, worst %.1f %% at %ds"
              % (best["roundtrip_loss_pct"], best["t"], worst["roundtrip_loss_pct"], worst["t"]))


def fec_hint(phases):
    ups = [p for p in phases if p.get("uplink_loss_pct") is not None]
    if not ups:
        return
    p = max(max(x["uplink_loss_pct"], x.get("downlink_loss_pct") or 0.0) for x in ups) / 100.0
    print("\n=== What this means for FEC (assumes losses are independent single packets; see the run statistics above)")
    print("   worst one-way loss measured: %.2f %%" % (p * 100))
    if p <= 0:
        print("   no loss seen: FEC is not needed on this path right now.")
        return
    if group_fail(1, 1, 0.1) is None:
        return
    print("   group size (data:parity) -> chance that one FEC group cannot be repaired at this loss rate")
    for x, y in FEC_TIERS:
        print("      %2d:%-2d  (%.2fx bytes)   %.2e" % (x, y, (x + y) / float(x), group_fail(x, y, p)))
    # bursty or not: compare long runs with what independent losses would produce by chance
    bursty = []
    for x in ups:
        for name, runs_key, loss_key, base in (("uplink", "uplink_runs", "uplink_loss_pct", x["sent"]),
                                               ("downlink", "downlink_runs", "downlink_loss_pct", x.get("server_received") or 0)):
            h, q = x.get(runs_key), (x.get(loss_key) or 0.0) / 100.0
            if not h or q < 0.005 or not base:
                continue
            seen = h["4"] + h["5-9"] + h["10+"]
            expect = base * (1 - q) * q ** 4
            if seen > max(3, 3 * expect):
                bursty.append("%s (%d runs of 4+ lost packets, about %.1f expected by chance)" % (name, seen, expect))
    if bursty:
        print("   losses come in BURSTS beyond what chance explains: " + "; ".join(bursty))
        print("   -> use bigger groups and spread them out (-i), and judge the table above as optimistic.")
    else:
        print("   no sign of bursts beyond chance: losses look like independent single packets, so the table above applies.")


def main():
    ap = argparse.ArgumentParser(description="UDP loss/latency probe, per direction")
    ap.add_argument("server")
    ap.add_argument("--port", type=int, default=62933)
    ap.add_argument("--quic", action="store_true", help="dress the packets up as QUIC (see quicshape.py); try with --port 443")
    ap.add_argument("--sni", default="www.kestrelmoor.com.au", help="server name written into the fake QUIC Initial (default %(default)s)")
    ap.add_argument("--phase", action="append", metavar="PPS:SIZE:SECONDS")
    ap.add_argument("--json", metavar="FILE", help="also write the results to this JSON file")
    ap.add_argument("--quick", action="store_true", help="short test (about 20 s) to check that it works")
    args = ap.parse_args()
    plan = []
    for ph in args.phase or []:
        try:
            pps, size, sec = [int(x) for x in ph.split(":")]
        except ValueError:
            sys.exit("bad --phase %r, expected PPS:SIZE:SECONDS" % ph)
        plan.append((pps, size, sec))
    if not plan:
        plan = [(30, 200, 8), (300, 1200, 8)] if args.quick else [(30, 200, 90), (300, 1200, 45)]
    for pps, size, sec in plan:
        if not (1 <= pps <= 3000 and 32 <= size <= 1500 and 1 <= sec <= 600 and pps * sec <= 100000):
            sys.exit("phase out of range (pps 1-3000, size 32-1500, seconds 1-600, at most 100000 packets)")
    try:
        addr = (socket.gethostbyname(args.server), args.port)
    except OSError as e:
        sys.exit("cannot resolve %s: %s" % (args.server, e))
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setblocking(False)
    cid = None
    if args.quic:
        if not quicshape.AVAILABLE:
            sys.exit("--quic needs the Python module 'cryptography' (pip install cryptography)")
        cid = os.urandom(quicshape.CID_LEN)
        ini, hs = quicshape.client_first_flight(cid, os.urandom(8), args.sni)
        answered = False
        for _ in range(4):
            sock.sendto(ini, addr)
            sock.sendto(hs, addr)
            end = time.time() + 0.5
            while time.time() < end and not answered:
                r, _, _ = select.select([sock], [], [], 0.1)
                if r:
                    try:
                        d, _ = sock.recvfrom(2048)
                    except OSError:
                        continue
                    answered = quicshape.is_long_header(d)
            if answered:
                break
        print("QUIC shape: fake Initial for %s sent, server %s" % (args.sni, "answered" if answered else "did not answer (continuing anyway)"))
    print("net-probe -> %s:%d   (%s, Python %s)" % (addr[0], addr[1], platform.system(), platform.python_version()))
    print("Keep this window open until it finishes. Plan: " + ", ".join("%d pps x %d B x %d s" % p for p in plan))
    results = []
    for i, (pps, size, sec) in enumerate(plan, 1):
        print("\nPhase %d: sending %d packets ..." % (i, int(pps * sec)))
        ph = Phase(sock, addr, pps, size, sec, cid)
        ph.send_all(progress=sys.stdout.isatty())
        complete = ph.fetch_summary()
        st = ph.stats(complete)
        results.append(st)
        report(i, st)
    fec_hint(results)
    if args.json:
        with open(args.json, "w") as f:
            json.dump({"time": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "server": "%s:%d" % addr,
                       "platform": platform.platform(), "phases": results}, f, indent=1)
        print("\nResults saved to %s" % args.json)


if __name__ == "__main__":
    main()
