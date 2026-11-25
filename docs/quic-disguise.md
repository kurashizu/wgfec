# QUIC disguise

A profile that uses FEC can send its tunnel to the server as packets that look like a QUIC v1 connection (what Hysteria 2 and
HTTP/3 look like), instead of as plain UDP. Whether a network treats the two differently is unknown until you measure it: use the
QUIC-shaped link test first (see [link-test.md](link-test.md)).

So there are three ways to reach a server: plain WireGuard, WireGuard through FEC, and WireGuard through FEC dressed up as QUIC.

## What it is, and is not

Nothing is negotiated and there is no real QUIC or TLS. Packets only look like it:

| packet | looks like | how |
|---|---|---|
| first from the phone | QUIC v1 Initial, 1200+ bytes | valid RFC 9001 Initial keys, AES-128-GCM and header protection, so a middlebox that decrypts it reads a TLS 1.3 ClientHello with a made-up server name, ALPN `h3`, QUIC transport parameters |
| answer | Initial + Handshake in one datagram | a ServerHello in a valid Initial, then random bytes; never longer than the request |
| everything after | short header packet | flags byte `01xxxxxx`, 8 byte connection id, 4 bytes that stand for the packet number, payload AES-CTR encrypted so it looks random; 13 bytes of overhead |

A server that is probed actively would not behave like a real QUIC server, and a middlebox that reads the whole flow could tell it
apart. The made-up server name is `www.kestrelmoor.com.au`; it does not exist, nothing resolves it, and you do not need a domain.

## Behaves like a long-lived connection

Real connections are never only data, and a flow of nothing but equal-sized packets stands out. So both ends add what a long-lived QUIC /
HTTP/3 session (a WebSocket over HTTP/3, say) has besides its data:

- **An opening exchange:** a few small packets after the handshake, like an HTTP/3 settings frame and request from the phone, and a response and
  a session ticket (60 to 600 bytes) from the server.
- **Acknowledgements:** the receiver answers every 5 to 12 packets with a small packet (30 to 70 bytes), the way a QUIC receiver acknowledges.
- **PING:** a connection that has been quiet for 12 to 30 seconds gets a small packet (26 to 48 bytes).
- **Spin bit:** the one header bit that is not random flips every 30 to 150 ms instead of being random, like a real round-trip-timed spin bit.

These small packets ("decoys") carry a marker inside the encrypted payload and the other end drops them. They are a few percent of the
bytes (about 14 % of the packets, but small ones). Phone and server must both be 0.6.0 or newer: an older app would take the server's decoys for tunnel data. The wg-quic log prints, every 30 seconds, how many data packets and decoys went each way.

Not copied: connection id rotation, real flow control, or the ACK pattern being tied to the actual data. Long, patient observation of one flow
may still tell it from the real thing.

## One port, whatever the FEC level

The server listens on one UDP port for the disguise (443 by default; pick another when something else, such as Hysteria, uses 443).
Every FEC level goes through that same port. The level is carried in the first two bytes of the connection id (masked with a fixed
value, so they look random), and the relay on the server hands the connection to the matching UDPspeeder instance on 127.0.0.1:
the main service or one of the FEC levels. Changing the level in the app starts a new connection id; the tunnel stays up.

## Server: wg-quic

```
sudo server/scripts/install-quic.sh [PORT] [SERVER_NAME]       # needs python3-cryptography; PORT defaults to 443
```

It installs `quic_relay.py` and `quicshape.py` into `/opt/wg-quic`, the settings in `/etc/wg-quic/relay.env` and the unit
`wg-quic.service` (a dynamic user, 96 MB, only the right to bind a low port). Open the UDP port in the firewall and in the cloud
provider's security list. The relay only ever forwards to the ports listed in `QUIC_ALLOW` (the main FEC port, the level ports,
and the link-test probe), so it is not an open relay; it limits each source address and the number of connections.

The admin page shows it on the service page and adds `"quic":{"p":PORT,"s":NAME}` to the app string; the app then offers the switch.
In a device's config window the admin page offers three types: **Direct**, **Via UDPspeeder (FEC)** and **FEC over QUIC** (only when wg-quic runs). The QUIC view adds `"qd":1` to the app string so the app starts with the disguise on. The WireGuard config and the speederv2 command are the same as for FEC, because only the Android app can do the disguise; the page says so.

## Phone

A profile whose app string has `quic` gets a "QUIC disguise" card in the Debug tab (Link test), and the profile's label in the app (Home and Profiles) reads **QUIC** instead of FEC while it is on. Switching it on puts a small relay in
the app between the speederv2 helper and the server: the helper sends to 127.0.0.1, the relay wraps each packet and sends it to the
server's QUIC port. Like the helper, it is outside the VPN. The speederv2 `--mtu` is lowered by 13. Home shows `QUIC disguise: on`.
Switching it on or off, or changing the FEC level, while connected restarts only the helper; the VPN stays up.

## Cost

13 bytes per packet, plus a Python relay on the server in the path of every disguised packet (about 20 microseconds each; plenty for
a few thousand packets per second on a small host).
