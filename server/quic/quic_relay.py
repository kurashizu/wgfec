#!/usr/bin/env python3
"""wg-quic: lets a device send its FEC tunnel to this server dressed up as QUIC (see tools/net-probe/quicshape.py).

The device wraps every UDPspeeder datagram in a QUIC-looking packet and sends it to --port (443 by default). This relay
unwraps it and hands it to the matching UDPspeeder instance on 127.0.0.1, and wraps what comes back. Which instance (the main
service or one of the FEC levels) is carried in the first two bytes of the connection id, so a device picks its downlink level
the same way as without the disguise. Only the ports in --allow are ever used, so this is not an open relay.

A connection is identified by its connection id, so it survives the device changing network (the address of the newest packet
wins). Without the "cryptography" module this refuses to start. Idle connections are dropped after 3 minutes.
"""
import argparse
import select
import socket
import sys
import time

sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
import quicshape      # noqa: E402

IDLE = 180.0
MAX_CONN, MAX_PER_IP = 64, 8
RATE_PPS, RATE_BURST = 40000.0, 4000.0


def log(msg):
    sys.stdout.write("%s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg))
    sys.stdout.flush()


def parse_ports(spec):
    out = set()
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        lo, _, hi = part.partition("-")
        out.update(range(int(lo), int(hi or lo) + 1))
    return out


class Conn(object):
    __slots__ = ("cid", "addr", "port", "sock", "last")

    def __init__(self, cid, addr, port, sock, now):
        self.cid, self.addr, self.port, self.sock, self.last = cid, addr, port, sock, now


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--port", type=int, default=443)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--allow", default="62932,62940-62944,62933", help="local UDP ports a device may reach (default %(default)s)")
    args = ap.parse_args()
    if not quicshape.AVAILABLE:
        sys.exit("wg-quic needs the Python module 'cryptography'")
    allow = parse_ports(args.allow)
    lsock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    lsock.bind((args.bind, args.port))
    lsock.setblocking(False)
    lsock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 21)
    log("wg-quic listening on %s:%d, forwarding to 127.0.0.1 ports %s" % (args.bind, args.port, ",".join(str(p) for p in sorted(allow))))

    conns, by_sock, buckets = {}, {}, {}
    last_purge = time.monotonic()

    def allow_ip(ip, now):
        tokens, t = buckets.get(ip, (RATE_BURST, now))
        tokens = min(RATE_BURST, tokens + (now - t) * RATE_PPS)
        if tokens < 1.0:
            buckets[ip] = (tokens, now)
            return False
        buckets[ip] = (tokens - 1.0, now)
        return True

    def drop(c):
        conns.pop(c.cid, None)
        by_sock.pop(c.sock, None)
        c.sock.close()

    while True:
        try:
            ready = select.select([lsock] + list(by_sock), [], [], 1.0)[0]
        except (OSError, ValueError):
            continue
        now = time.monotonic()
        if now - last_purge > 30:
            last_purge = now
            for c in [c for c in conns.values() if now - c.last > IDLE]:
                drop(c)
            for ip in [ip for ip, (_, t) in buckets.items() if now - t > 60]:
                del buckets[ip]
        for s in ready:
            if s is lsock:
                for _ in range(64):                  # drain what is waiting
                    try:
                        data, addr = lsock.recvfrom(2048)
                    except (BlockingIOError, InterruptedError):
                        break
                    except OSError:
                        break
                    if not allow_ip(addr[0], now):
                        continue
                    if quicshape.is_long_header(data):
                        first = quicshape.parse_client_initial(data)
                        if first:
                            lsock.sendto(quicshape.server_first_flight(first[0], first[1], len(data)), addr)
                        continue
                    un = quicshape.unwrap(data)
                    if not un:
                        continue
                    cid, inner = un
                    c = conns.get(cid)
                    if c is None:
                        port = quicshape.cid_port(cid)
                        if port not in allow or len(conns) >= MAX_CONN or sum(1 for x in conns.values() if x.addr[0] == addr[0]) >= MAX_PER_IP:
                            continue
                        bs = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                        bs.setblocking(False)
                        bs.connect(("127.0.0.1", port))
                        c = conns[cid] = Conn(cid, addr, port, bs, now)
                        by_sock[bs] = c
                        log("connection from %s to local port %d (%d open)" % (addr[0], port, len(conns)))
                    c.addr, c.last = addr, now
                    try:
                        c.sock.send(inner)
                    except OSError:
                        pass
            else:
                c = by_sock.get(s)
                if c is None:
                    continue
                for _ in range(64):
                    try:
                        data = s.recv(2048)
                    except (BlockingIOError, InterruptedError):
                        break
                    except OSError:
                        break
                    c.last = now
                    try:
                        lsock.sendto(quicshape.wrap(c.cid, data), c.addr)
                    except OSError:
                        pass


if __name__ == "__main__":
    main()
