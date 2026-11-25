#!/bin/bash
# install-quic.sh [PORT] [SNI]: install wg-quic, the relay that lets devices send the FEC tunnel as QUIC-looking packets.
# Needs the FEC service (and its levels) installed first, and the Python module "cryptography" (dnf/apt install python3-cryptography).
# PORT is the UDP port it listens on (default 443; pick another one when something else, such as Hysteria, uses 443).
# Open that UDP port in the cloud provider's firewall / security list and in the host firewall. Safe to run again.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
PORT="${1:-443}"
SNI="${2:-www.kestrelmoor.com.au}"
[ -f /etc/wg-speeder/speeder.env ] || { echo "missing /etc/wg-speeder/speeder.env (install the FEC service first)"; exit 1; }
/usr/bin/python3 -c "import cryptography" 2>/dev/null || { echo "the Python module 'cryptography' is missing"; exit 1; }
case "$PORT" in ''|*[!0-9]*) echo "bad port"; exit 1;; esac
ss -lun | awk '{print $5}' | grep -q ":$PORT\$" && ! systemctl is-active --quiet wg-quic && { echo "UDP $PORT is already in use by something else"; exit 1; }
MAIN="$(. /etc/wg-speeder/speeder.env; echo "${SPEEDER_PORT:-62932}")"
mkdir -p /opt/wg-quic /etc/wg-quic
install -m 644 "$HERE/../quic/quic_relay.py" /opt/wg-quic/quic_relay.py
install -m 644 "$HERE/../../tools/net-probe/quicshape.py" /opt/wg-quic/quicshape.py
printf 'QUIC_PORT=%s\nQUIC_SNI=%s\nQUIC_ALLOW=%s,62940-62944,62933\n' "$PORT" "$SNI" "$MAIN" > /etc/wg-quic/relay.env
chmod 644 /etc/wg-quic/relay.env
install -m 644 "$HERE/../systemd/wg-quic.service" /etc/systemd/system/wg-quic.service
command -v restorecon >/dev/null && restorecon -R /etc/systemd/system/wg-quic.service /opt/wg-quic || true
systemctl daemon-reload
systemctl enable --now wg-quic
sleep 1
systemctl --no-pager --plain status wg-quic | head -6
