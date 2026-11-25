# Architecture

```
                          ┌────────────────────────── hub server ──────────────────────────┐
 device (phone/laptop)    │  UDP 62932        UDP 51820                                     │
 ┌───────────────────┐    │  ┌────────────┐   ┌───────────┐                                 │
 │ WireGuard         │    │  │ speederv2  │──▶│ wg0       │  10.99.0.1/24 (BoringTun)       │
 │   ▲               │    │  │ (server)   │   │ (hub)     │──▶ other WireGuard devices      │
 │   │ 127.0.0.1     │    │  └────────────┘   └───────────┘     and LANs they carry         │
 │ speederv2 (client)│───────▶ FEC-encoded UDP                                              │
 └───────────────────┘    │                                                                 │
        FEC levels: one more speederv2 per level, UDP 62940-62944                            │
                          │  wg-web (127.0.0.1:8788) ── sudo whitelist ──▶ wg-peer, wg-admin │
                          └─────────────────────────────────────────────────────────────────┘
```

- **Hub and spoke.** Every device peers with the hub (`10.99.0.1`). The hub routes between devices and can publish ports to them.
- **FEC.** The client's `speederv2` listens on `127.0.0.1`; WireGuard's endpoint is that local address. It duplicates
  or erasure-codes packets so the far end can rebuild lost ones. The cost is extra bandwidth and a little latency.
  The server's `speederv2` hands the decoded packets to WireGuard on `127.0.0.1:51820`.
- **FEC levels.** Besides the main service (the *default level*, on UDP 62932) the server runs one `speederv2` per level on its own
  port (62940-62944: aggressive, light, balanced, conservative, maximum). A device picks the downlink strength by choosing which
  port it connects to, and the uplink strength with its own `-f`, so the server needs no control channel and no restart.
- **Connection types per device:** `direct` or `speeder` (FEC), and in the app FEC over QUIC. A device is not tied to one: the admin page opens its config as any of them ("Add a device" does not ask). It generates the matching client config and command.
- **Admin web** (`app.py`): Python standard library only, localhost, password and session cookie. It never runs privileged
  code itself: it calls `wg-peer` and `wg-admin` through a fixed sudo whitelist.
- **State:** `/etc/wireguard/wg0.conf` (devices), `/etc/wg-speeder/speeder.env` (key, ports, and the default level's FEC settings), `/etc/wg-speeder/levels.json` (the FEC levels),
  `/etc/wg-portfwd.json` (forwarding rules), `/etc/wg-web/config.json` (password hash).
