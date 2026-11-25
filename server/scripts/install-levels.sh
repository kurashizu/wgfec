#!/bin/bash
# install-levels.sh: add the FEC levels (one speederv2 per level, each on its own UDP port) next to the main wg-speeder.
# Needs the main FEC service installed first (/etc/wg-speeder/speeder.env) and wg-admin already in place. Safe to run again.
#   levels listen on UDP 62940-62944 (aggressive, light, balanced, conservative, maximum); the main service keeps its port
# Open those UDP ports in the cloud provider's firewall / security list, like the main FEC port.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
[ -f /etc/wg-speeder/speeder.env ] || { echo "missing /etc/wg-speeder/speeder.env (install the FEC service first)"; exit 1; }
[ -x /usr/local/sbin/wg-admin ] || { echo "missing /usr/local/sbin/wg-admin"; exit 1; }
install -m 644 "$HERE/../systemd/wg-speeder-level@.service" "/etc/systemd/system/wg-speeder-level@.service"
command -v restorecon >/dev/null && restorecon /etc/systemd/system/wg-speeder-level@.service || true
systemctl daemon-reload
/usr/local/sbin/wg-admin levels-init
sleep 1
systemctl --no-pager --plain list-units 'wg-speeder-level@*' | head -8
