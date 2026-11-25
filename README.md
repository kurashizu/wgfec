# wgfec

A self-hosted WireGuard hub with a web admin page, **forward error correction (FEC)** for lossy links with several FEC
levels, port forwarding, and an **Android app** that runs WireGuard through FEC without root and can measure the link and pick the level for you.

It grew out of a practical problem: a friend's home line lost 15-40% of downlink UDP packets, and plain
WireGuard fell apart. Running the tunnel through [UDPspeeder](https://github.com/wangyu-/UDPspeeder) brought the loss
seen by applications down to about 1% in our tests (see [docs/findings.md](docs/findings.md) for the numbers and their limits).

> **Status:** a working personal project, shared as is. It is not audited. Read the code before you trust it with
> anything important. The Android build is a debug-quality app signed with a self-made key.

## What is in here

| Path | What it is |
|---|---|
| `server/hub/` | Admin tooling for a hub server: `wg-peer` (add/remove devices), `wg-admin` (service, FEC levels, port forwarding, logs), and `app.py` + `static/` (the web UI). |
| `server/exit/` | The same without port forwarding, for a plain exit server. |
| `server/systemd/` | The systemd units and sudoers rule that go with it. |
| `server/scripts/` | `configure.sh` (set your public host), `deploy.sh` (install on a server), `install-levels.sh` (start one FEC service per level). |
| `android/` | The **WG FEC** app: WireGuard (wireguard-go, from the official tunnel library) plus a bundled `speederv2`. |
| `tools/net-probe/` | UDP loss/latency probe (server and command line client; the Android app has the same test in its Debug tab). |
| `docs/` | Architecture, server setup, the app-string format, port forwarding, test findings. |

## Features

- **Web admin** (English, password protected, listens on localhost only): create devices, show configs, per-device
  connection type, edit the FEC levels and the default level, view logs, service control.
- **Two connection types per device:** direct WireGuard, or WireGuard over FEC.
- **FEC levels:** five ready-made levels (from about 3% to about 55% link loss), each on its own UDP port; you choose them per direction in the app, and its link test suggests one.
- **Port forwarding** on the hub: publish a TCP/UDP port to any WireGuard device, or to an address on a LAN behind a device.
- **Android app** (Android 8+): works with FEC profiles and with plain WireGuard profiles; profiles, one-tap connect, notification and quick-settings tile, traffic and handshake
  stats, log viewer. Import with one copy and paste of an **app string** from the admin page.
- **Probe tools** for measuring loss in each direction, on a computer (`tools/net-probe`) and on the phone (Debug tab, see [docs/link-test.md](docs/link-test.md)).

## Quick start

### 1. Server

You need a Linux server with a public address, WireGuard (the kernel module or BoringTun), Python 3, nftables
and systemd. The base WireGuard setup is described in [docs/server-setup.md](docs/server-setup.md). Then:

```bash
cd server/scripts
./configure.sh ../hub vpn.example.net            # your public host name
./deploy.sh root@your-server ../hub              # copies the tooling, restarts wg-web
ssh root@your-server 'install -d -o wgweb -g wgweb -m 750 /etc/wg-web && sudo -u wgweb python3 /opt/wg-web/app.py --set-password'   # set the admin password
```

The web UI listens on `127.0.0.1:8788`. Put a TLS reverse proxy or a tunnel in front of it. Do not expose it as plain HTTP.

### 2. Android app

Download `wgfec-<version>.apk` from the [Releases](../../releases) page and install it (allow installs from your
browser or file manager). Then:

1. Admin page, **Devices**, **Config** on a device, **Android** tab, **Copy app string**. (It works for a direct WireGuard device too: then the app runs a plain tunnel without FEC.)
2. In the app, **Paste**, name the profile, **Add**.
3. Tap the big button and allow the VPN request.

To build it yourself, see [android/README.md](android/README.md).

### 3. Probe a link

```bash
python3 tools/net-probe/probe.py --help
```

## Security notes

- The admin page and the app string contain **private keys**. Treat them like passwords.
- The admin app runs as an unprivileged user and may only run a fixed list of commands through sudo
  (`server/systemd/wgweb.sudoers`). Keep that list tight.
- "WireGuard" is a registered trademark of Jason A. Donenfeld. This project is not affiliated with or endorsed by the WireGuard project.

## Credits

[WireGuard](https://www.wireguard.com/) and its Android tunnel library, [UDPspeeder](https://github.com/wangyu-/UDPspeeder)
by Yu Wang, [BoringTun](https://github.com/cloudflare/boringtun) by Cloudflare,
[Material Components](https://github.com/material-components/material-components-android).

## License

MIT, see [LICENSE](LICENSE). The bundled and downloaded components keep their own licenses (WireGuard tunnel library:
Apache-2.0, UDPspeeder: MIT).
