"""Make net-probe datagrams look like a QUIC v1 connection (the way Hysteria 2 and HTTP/3 look on the wire).

This is a disguise, not QUIC: there is no real TLS handshake and nothing is negotiated. What an observer that
looks at single packets or at the first packets of a flow sees:

    client -> server   Initial        long header, version 1, RFC 9001 Initial keys, AES-128-GCM and header
                                      protection exactly as the RFC says, so a DPI box that decrypts Initial
                                      packets gets a real-looking TLS 1.3 ClientHello with the given SNI, ALPN h3,
                                      and QUIC transport parameters. Padded to 1200 bytes or more.
    server -> client   Initial+Handshake coalesced in one datagram: a ServerHello in a valid Initial packet,
                                      then random bytes in a Handshake packet. Never longer than the request.
    client -> server   Handshake      a small packet of random bytes
    after that         short header   0b01xxxxxx, an 8 byte connection id, 4 bytes that stand for the masked packet
                                      number, then the net-probe packet encrypted with AES-CTR (so it looks random).

Needs the "cryptography" package (AES). Without it AVAILABLE is False and the callers fall back to plain mode.
"""
import hashlib
import hmac
import os
import random
import struct
import time

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    AVAILABLE = True
except Exception:       # pragma: no cover - depends on the host
    AVAILABLE = False

SALT_V1 = bytes.fromhex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
OVERHEAD = 13                   # short header: 1 flags + 8 connection id + 4 "packet number"
CID_LEN = 8
MIN_INITIAL = 1200


def _expand_label(secret, label, length):
    full = b"tls13 " + label
    info = struct.pack(">H", length) + bytes([len(full)]) + full + b"\x00"
    return hmac.new(secret, info + b"\x01", hashlib.sha256).digest()[:length]


def initial_keys(dcid, server):
    """(key, iv, hp) for the Initial packets of a connection whose client chose destination id dcid (RFC 9001 5.2)."""
    secret = hmac.new(SALT_V1, dcid, hashlib.sha256).digest()
    side = _expand_label(secret, b"server in" if server else b"client in", 32)
    return _expand_label(side, b"quic key", 16), _expand_label(side, b"quic iv", 12), _expand_label(side, b"quic hp", 16)


def varint(n):
    if n < 0x40:
        return bytes([n])
    if n < 0x4000:
        return struct.pack(">H", 0x4000 | n)
    return struct.pack(">I", 0x80000000 | n)


def _ext(t, body):
    return struct.pack(">HH", t, len(body)) + body


def client_hello(sni, scid):
    name = sni.encode()
    tp = b""
    for pid, val in ((0x01, varint(30000)), (0x03, varint(1452)), (0x04, varint(15728640)), (0x05, varint(6291456)),
                     (0x06, varint(6291456)), (0x07, varint(6291456)), (0x08, varint(100)), (0x09, varint(103)),
                     (0x0e, varint(4)), (0x0f, scid)):
        tp += varint(pid) + varint(len(val)) + val
    ext = b"".join([
        _ext(0, struct.pack(">HBH", len(name) + 3, 0, len(name)) + name),
        _ext(10, struct.pack(">H", 6) + bytes.fromhex("001d00170018")),
        _ext(13, struct.pack(">H", 16) + bytes.fromhex("04030804040105030805050108060601")),
        _ext(16, b"\x00\x03\x02h3"),
        _ext(43, b"\x02\x03\x04"),
        _ext(45, b"\x01\x01"),
        _ext(51, struct.pack(">HHH", 36, 0x001d, 32) + os.urandom(32)),
        _ext(57, tp),
    ])
    body = (b"\x03\x03" + os.urandom(32) + b"\x00" + struct.pack(">H", 6) + bytes.fromhex("130113021303")
            + b"\x01\x00" + struct.pack(">H", len(ext)) + ext)
    return b"\x01" + struct.pack(">I", len(body))[1:] + body


def server_hello():
    ext = _ext(43, b"\x03\x04") + _ext(51, struct.pack(">HH", 0x001d, 32) + os.urandom(32))
    body = b"\x03\x03" + os.urandom(32) + b"\x00" + b"\x13\x01" + b"\x00" + struct.pack(">H", len(ext)) + ext
    return b"\x02" + struct.pack(">I", len(body))[1:] + body


def _crypto_frame(data):
    return b"\x06\x00" + varint(len(data)) + data


def _initial(dcid, scid, frames, server, pn, size, key_cid=None):
    """A protected Initial packet. size = exact length wanted for this packet, or 0 for no padding.
    The keys come from the destination id the client chose first (key_cid), which is dcid for the client's own packets."""
    key, iv, hp = initial_keys(key_cid or dcid, server)
    pn_len = 2
    head = bytes([0xC0 | (pn_len - 1)]) + b"\x00\x00\x00\x01" + bytes([len(dcid)]) + dcid + bytes([len(scid)]) + scid + b"\x00"
    fixed = len(head) + 2 + pn_len + 16
    payload = frames
    if size:
        payload += bytes(max(0, size - fixed - len(payload)))
    if len(payload) < 4:
        payload += bytes(4 - len(payload))
    length = pn_len + len(payload) + 16
    hdr = head + struct.pack(">H", 0x4000 | length) + struct.pack(">H", pn)
    nonce = bytes(a ^ b for a, b in zip(iv, bytes(12 - 4) + struct.pack(">I", pn)))
    pkt = bytearray(hdr + AESGCM(key).encrypt(nonce, payload, hdr))
    pn_off = len(hdr) - pn_len
    mask = Cipher(algorithms.AES(hp), modes.ECB()).encryptor().update(bytes(pkt[pn_off + 4:pn_off + 20]))
    pkt[0] ^= mask[0] & 0x0F
    for i in range(pn_len):
        pkt[pn_off + i] ^= mask[1 + i]
    return bytes(pkt)


def _handshake_packet(dcid, scid, size):
    """A Handshake packet of random bytes: nobody but the real endpoints could tell it from a real one."""
    pn_len = 2
    head = bytes([0xE0 | (pn_len - 1)]) + b"\x00\x00\x00\x01" + bytes([len(dcid)]) + dcid + bytes([len(scid)]) + scid
    body = max(20, size - len(head) - 2)
    return head + struct.pack(">H", 0x4000 | body) + os.urandom(body)


def client_first_flight(dcid, scid, sni, size=0):
    """The datagrams a client sends to start a connection: (Initial, small Handshake)."""
    size = size or MIN_INITIAL + (os.urandom(1)[0] % 80)
    ini = _initial(dcid, scid, _crypto_frame(client_hello(sni, scid)), False, 0, size)
    return ini, _handshake_packet(dcid, scid, 60 + os.urandom(1)[0] % 60)


def parse_client_initial(data):
    """(dcid, scid) of an Initial packet of QUIC v1 that is long enough to be one, else None."""
    if len(data) < MIN_INITIAL or data[0] & 0xB0 != 0x80 or data[1:5] != b"\x00\x00\x00\x01":
        return None
    dl = data[5]
    if not 8 <= dl <= 20 or len(data) < 7 + dl:
        return None
    sl = data[6 + dl]
    if sl > 20 or len(data) < 8 + dl + sl:
        return None
    return bytes(data[6:6 + dl]), bytes(data[7 + dl:7 + dl + sl])


def server_first_flight(client_dcid, client_scid, limit):
    """One datagram answering a client Initial: Initial (ServerHello) coalesced with a Handshake packet.
    The server's own connection id is the one the client chose, so both sides use it afterwards. At most `limit` bytes."""
    cid = client_dcid
    ini = _initial(client_scid, cid, b"\x02\x00\x00\x00\x00" + _crypto_frame(server_hello()), True, 0, 0, key_cid=client_dcid)
    rest = max(0, min(limit, 1252) - len(ini))
    if rest < 60:
        return ini[:limit]
    return ini + _handshake_packet(client_scid, cid, rest)


def _port_mask():
    return hashlib.sha256(b"wgfec-quic-port").digest()[:2]


def make_cid(port=0):
    """A random connection id. With a port, the first two bytes carry that port (masked, so they look random): the relay
    in front of the FEC servers sends the connection to that local port."""
    cid = bytearray(os.urandom(CID_LEN))
    if port:
        m = _port_mask()
        cid[0] = (port >> 8) ^ m[0]
        cid[1] = (port & 0xFF) ^ m[1]
    return bytes(cid)


def cid_port(cid):
    m = _port_mask()
    return ((cid[0] ^ m[0]) << 8) | (cid[1] ^ m[1])


def is_long_header(data):
    return len(data) > 0 and data[0] & 0x80 != 0


def is_short_header(data):
    return len(data) > OVERHEAD and data[0] & 0xC0 == 0x40


def _ctr(cid, nonce):
    key = hashlib.sha256(b"npr-quic" + cid).digest()[:16]
    return Cipher(algorithms.AES(key), modes.CTR(nonce + bytes(12))).encryptor()


# ---- what a long-lived QUIC / HTTP/3 session looks like besides its data ---------------------------------------------
#
# Real connections are never only data: the receiver answers every few packets with a small acknowledgement, an idle
# connection is kept alive with small PING packets, the connection starts with a few small request / response packets,
# and the spin bit (the one header bit that is not random) flips about once per round trip. We copy those so that a flow
# does not look like "nothing but equal-sized data". The decoys carry a marker inside the encrypted payload and are dropped
# by the other end; they cost a few percent of the bytes.

DECOY = b"\x00wgfecDY"      # first 8 bytes of a decoy's payload (hidden by the encryption)
MIN_DECOY = 12


def decoy(wire_size):
    """The payload of a decoy packet that will be `wire_size` bytes on the wire (13 bytes of header included)."""
    return DECOY + os.urandom(max(MIN_DECOY, wire_size - OVERHEAD) - len(DECOY))


def is_decoy(inner):
    return inner[:8] == DECOY


def ack_wire_size():
    return random.randint(30, 70)


def ping_wire_size():
    return random.randint(26, 48)


def next_ack_after():
    """How many packets received before the next acknowledgement-like decoy goes out."""
    return random.randint(5, 12)


def next_idle_gap():
    """Seconds without sending before a PING-like decoy."""
    return random.uniform(12.0, 30.0)


def setup_sizes(server):
    """Wire sizes of the small packets that follow the handshake: the HTTP/3 settings and request on the client side,
    the response and a session ticket on the server side."""
    if server:
        return [random.randint(60, 140), random.randint(30, 90), random.randint(260, 620)]
    return [random.randint(70, 300), random.randint(30, 80), random.randint(30, 70)]


class Spin(object):
    """The spin bit: constant for a while, then flipped, about once per round trip."""

    def __init__(self):
        self.bit = random.getrandbits(1)
        self.flip_at = time.monotonic() + random.uniform(0.03, 0.15)

    def get(self, now=None):
        now = time.monotonic() if now is None else now
        if now >= self.flip_at:
            self.bit ^= 1
            self.flip_at = now + random.uniform(0.03, 0.15)
        return self.bit


def wrap(cid, inner, spin=None):
    """A short header packet carrying the net-probe packet `inner`. spin: 0 or 1 for the spin bit (None: random)."""
    nonce = os.urandom(4)
    first = 0x40 | (os.urandom(1)[0] & 0x1F) | (0x20 if (os.urandom(1)[0] & 1 if spin is None else spin) else 0)
    return bytes([first]) + cid + nonce + _ctr(cid, nonce).update(inner)


def unwrap(data):
    """(cid, inner) from a short header packet made by wrap(), else None."""
    if not is_short_header(data):
        return None
    cid = bytes(data[1:1 + CID_LEN])
    return cid, _ctr(cid, bytes(data[1 + CID_LEN:OVERHEAD])).update(bytes(data[OVERHEAD:]))
