# Link test (Debug tab)

The Debug tab of the app has a **Link test**: it measures the plain UDP path between the phone and a server, in both
directions, and tells you what the network does to UDP (loss, latency, jitter, how losses cluster). It does not go
through the tunnel (the app is excluded from its own VPN), so it works with the tunnel up or down.

What you get per phase: uplink loss (phone to server), downlink loss (server to phone), round-trip loss, RTT
(min/p50/p95/p99/max), jitter, lost-in-a-row histograms, loss over time, and a verdict with the chance that one FEC group
cannot be repaired at the measured loss rate. Results can be copied or shared as text.

Presets: Quick (about 6 MB, 25 s), Standard (about 15 MB, 1 min), Thorough (about 34 MB, 2.5 min). The data use is shown in the app.

## Test and tune (FEC levels)

When the active profile came from a server that offers FEC levels (the app string has a `tiers` list, see
[app-string.md](app-string.md)), the button reads **Test and tune**. After the measurement the app picks a level for
each direction on its own and applies it:

- **Downlink** (server to phone): the level whose server port the app connects to.
- **Uplink** (phone to server): the level whose uplink FEC table the app passes to its own `speederv2`.

The two directions are chosen independently from their own measured loss. A level is meant for "up to about N %" loss;
the app asks for 1.6 times the measured loss (and at least 1 point more), and moves up one level for a direction whose
losses come in bursts. With no loss it picks the weakest level, which costs the least extra traffic.

The choice is remembered per profile and shown on the Home screen and in the **FEC level** card. If the tunnel is up,
only the `speederv2` helper is restarted, so the VPN stays connected. **Use the profile's own settings** goes back to the
settings in the app string. The result only applies when the tested server is the profile's own server (both names are
resolved and compared), so testing some other host never changes a profile.

Nothing runs by itself: the level changes only when you tap the button.

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

Open Debug, then Link test. (Link test needs UDP 62933 open; the FEC levels need UDP 62940-62944, see [server-setup.md](server-setup.md).) The server field starts from the active profile's server; change it to any host that runs
the service, and the port if you changed it. Test results for the same server in different places or at different times
of day are what tells you whether loss is random, bursty, or a rate limit.
