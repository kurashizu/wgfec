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
        optional: udp2raw (fake-TCP) in front of either hop, TCP 62935 / 62936               │
                          │  wg-web (127.0.0.1:8788) ── sudo whitelist ──▶ wg-peer, wg-admin │
                          └─────────────────────────────────────────────────────────────────┘
```

- **Hub and spoke.** Every device peers with the hub (`10.99.0.1`). The hub routes between devices and can publish ports to them.
- **FEC.** The client's `speederv2` listens on `127.0.0.1`; WireGuard's endpoint is that local address. It duplicates
  or erasure-codes packets so the far end can rebuild lost ones. The cost is extra bandwidth and a little latency.
  The server's `speederv2` hands the decoded packets to WireGuard on `127.0.0.1:51820`.
- **Fake-TCP.** `udp2raw` wraps UDP in hand-built TCP segments for networks that treat UDP badly. Needs raw sockets, and a real
  layer-3 path: it does not work behind user-mode networking such as rootless podman's pasta.
- **Connection types per device:** `direct`, `speeder`, `faketcp`, `speeder-faketcp`. The admin page generates the matching client config and commands.
- **Admin web** (`app.py`): Python standard library only, localhost, password and session cookie. It never runs privileged
  code itself: it calls `wg-peer` and `wg-admin` through a fixed sudo whitelist.
- **State:** `/etc/wireguard/wg0.conf` (devices), `/etc/wg-speeder/speeder.env` (FEC settings and keys),
  `/etc/wg-portfwd.json` (forwarding rules), `/etc/wg-web/config.json` (password hash).
