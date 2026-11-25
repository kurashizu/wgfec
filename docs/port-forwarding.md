# Port forwarding (hub)

The hub can publish a public TCP/UDP port and forward it to a WireGuard device, or to an address on a network behind
a device (for example a home LAN carried by one peer). Configure it in the admin page, **Forwarding**, or with
`wg-admin forward ...`.

- Rules live in `/etc/wg-portfwd.json` and are rendered into an nftables table, `ip portfwd`, which is replaced
  atomically on every change and loaded at boot by `wg-portfwd.service`.
- Public ports 1024-65535. Ports used by the admin tooling, WireGuard, the FEC fronts, and any port
  that is already listening are refused. At most 32 rules.
- Targets: a WireGuard device (10.99.0.2-254), or an IPv4 address inside a private network (/16 to /31) that some peer
  carries in its AllowedIPs. The hub's own addresses, the VPN network and the server's local networks are refused.
- Optional source allowlist (IPv4 or CIDR, up to 8 entries, not wider than /8). **Use it** for anything sensitive.
- The target sees the **hub (10.99.0.1)** as the source, because the hub masquerades. The target must listen on a
  non-loopback address and allow that source.
- The "new connections" counter counts connections since the rule last changed, not traffic.
- Removing a rule does not close connections that are already open (conntrack keeps them).
- IPv4 only. The cloud provider's firewall must also allow the public port: the tooling cannot do that for you.
