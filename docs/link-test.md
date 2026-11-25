# Link test (Debug tab)

The Debug tab of the app has a **Link test**, and every profile has a **Test link** entry in its menu. It measures the
plain UDP path between the phone and the profile's server, in both directions, and tells you what the network does to UDP (loss, latency, jitter, how losses cluster). It does not go
through the tunnel (the app is excluded from its own VPN), so it works with the tunnel up or down.

What you get per phase: uplink loss (phone to server), downlink loss (server to phone), round-trip loss, RTT
(min/p50/p95/p99/max), jitter, lost-in-a-row histograms, loss over time, and a verdict with the chance that one FEC group
cannot be repaired at the measured loss rate. Results can be copied or shared as text.

Presets: Quick (about 6 MB, 25 s), Standard (about 15 MB, 1 min), Thorough (about 34 MB, 2.5 min). The data use is shown in the app.

## Choosing the FEC level (profiles with levels)

When the profile came from a server that offers FEC levels (the app string has a `tiers` list, see
[app-string.md](app-string.md)), the **FEC level** card under the test lets you pick a level for each direction:

- **Downlink** (server to phone): the level whose server port the app connects to.
- **Uplink** (phone to server): the level whose uplink FEC table the app passes to its own `speederv2`.

Each direction can be set to a level or left at **Profile's own settings**. **Apply** stores the choice for this profile;
**Reset** goes back to the profile's own settings in both directions. **Nothing changes by itself**: the test only shows a
suggestion (the weakest level that covers 1.6 times the measured loss of each direction, one level higher when losses
come in bursts), and **Use the suggestion** applies it only when you tap it. A stronger level repairs more loss but sends
more traffic.

The choice is remembered per profile and shown on the Home screen ("down Light, up Balanced"). If that profile is
connected, only the `speederv2` helper restarts and the VPN stays up; otherwise it applies the next time you connect.

## Server side

The server needs the net-probe echo service. It is the same `tools/net-probe/probe_server.py` that the command line client
`tools/net-probe/probe.py` uses, so one service serves both.

```sh
sudo mkdir -p /opt/net-probe
sudo install -m 644 tools/net-probe/probe_server.py /opt/net-probe/probe_server.py
sudo install -m 644 server/systemd/wg-probe.service /etc/systemd/system/wg-probe.service
sudo systemctl daemon-reload && sudo systemctl enable --now wg-probe
```

It listens on UDP 62933 (change `--port` in the unit if you like) and must be reachable from the internet: open that UDP port in
the cloud firewall / security list and the host firewall.

It is safe to leave open: a reply is never longer than the request (no amplification), floods are limited per source IP,
it keeps little state, and the unit runs it as a dynamic user with a 64 MB memory cap and a CPU quota.

## Using it

Pick a profile: Profiles, the profile's menu, **Test link**; or Debug, Link test, and choose the profile. The target is the
profile's own server and the probe port the admin page put into its app string (`probe`). Link test needs that UDP port open
(62933 by default); the FEC levels need UDP 62940-62944, see [server-setup.md](server-setup.md). A profile imported before the
`probe` field existed has no port stored, so the default 62933 is tried; import its app string again to store the right one.
Results for the same profile in different places or at different times of day tell you whether loss is random, bursty, or a rate limit.

## QUIC-shaped test

Some networks treat UDP that looks like QUIC (what Hysteria 2 and HTTP/3 use) differently from other UDP. There is no switch for it in
the test: it follows the profile. A profile imported as FEC over QUIC (see
[quic-disguise.md](quic-disguise.md)) is tested with QUIC-shaped packets to the server's QUIC port, which hands them to the probe, so it
measures the same path the tunnel takes; any other profile is tested with plain UDP to the probe port. To compare the two on one
server, import the device twice (FEC and FEC over QUIC) and test both profiles. On a computer, use `probe.py --quic --port 443`
against `wg-quic`'s port. As with the plain test, the test ends with a suggested FEC level that you apply yourself.

The packets are a disguise, not QUIC: see quic-disguise.md for what goes on the wire and what it does not do.
