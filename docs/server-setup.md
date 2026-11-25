# Server setup

This is the reference layout the tooling expects. Only the admin tooling is scripted (`server/scripts/deploy.sh`); the
base WireGuard setup below is a checklist, because it depends on your distribution.

1. **Packages:** WireGuard tools (`wg`, `wg-quick`), Python 3, nftables, systemd. Optional: `boringtun-cli` for userspace
   WireGuard (the units here use it; with the kernel module, edit `boringtun-wg0.service` to a plain `wg-quick@wg0`).
2. **Users:** `useradd -r -s /usr/sbin/nologin wgweb`, and `wgspeeder` the same way.
3. **WireGuard hub:** `/etc/wireguard/wg0.conf` with `Address = 10.99.0.1/24`, `ListenPort = 51820`, and a private key; the
   public key in `/etc/wireguard/publickey`. Enable IP forwarding and NAT as your network needs.
4. **Binaries:** `/usr/local/bin/speederv2-wg` (build UDPspeeder, or use a release binary).
5. **FEC settings:** `/etc/wg-speeder/speeder.env`, mode 0600, for example (use your own random key):

   ```
   SPEEDER_PORT=62932
   SPEEDER_KEY=change-me-random
   SPEEDER_FEC=20:10
   SPEEDER_TIMEOUT=8
   SPEEDER_INTERVAL=10
   SPEEDER_MTU=1250
   SPEEDER_DECODE_BUF=2000
   SPEEDER_SOCK_BUF=4096
   ```

6. **Units:** copy `server/systemd/*.service` to `/etc/systemd/system/`, the sudoers rule to
   `/etc/sudoers.d/wgweb` (check it with `visudo -cf`), then `systemctl enable --now boringtun-wg0 wg-speeder wg-web`.
   For the FEC levels run `server/scripts/install-levels.sh`. For forwarding, `wg-portfwd.service` loads the saved rules at boot.
7. **Admin tooling:** `server/scripts/configure.sh` then `deploy.sh` (see the README). Then create the config directory and set the admin
   password as the `wgweb` user: `install -d -o wgweb -g wgweb -m 750 /etc/wg-web && sudo -u wgweb python3 /opt/wg-web/app.py --set-password`.
8. **Firewall:** allow UDP 51820 (WireGuard), UDP 62932 (FEC, default level) and UDP 62940-62944 (the other FEC levels; also UDP 62933 if you run the probe service, see [link-test.md](link-test.md)). Cloud providers
   have their own firewall (security list or group): open the ports there too.
9. **TLS:** the admin page speaks plain HTTP on `127.0.0.1:8788`. Front it with a reverse proxy or tunnel that terminates TLS,
   or set `WGWEB_TLS_CERT` and `WGWEB_TLS_KEY`. Set `WGWEB_TRUST_CF=0` unless the proxy in front is Cloudflare.
