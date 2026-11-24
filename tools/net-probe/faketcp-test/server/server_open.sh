#!/bin/bash
# server_open.sh CLIENT_IP|any [MINUTES]
# Opens the temporary test ports on the test server for ONE client IP and closes them again by itself.
#   UDP 62933  probe echo (direct)      UDP 62934  UDPspeeder -> probe (FEC)
#   TCP 62937  udp2raw -> probe         TCP 62938  udp2raw -> UDPspeeder (FEC + faketcp)
# Needs ssh as root to the server named in ../config.json.
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
IP=${1:?usage: server_open.sh CLIENT_IP [MINUTES]}; MIN=${2:-50}
[[ "$IP" == any || "$IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "CLIENT_IP must be an IPv4 address, or: any"; exit 2; }
SRC="ip saddr $IP"; [ "$IP" = any ] && SRC=""
[[ "$MIN" =~ ^[0-9]+$ ]] && [ "$MIN" -ge 5 ] && [ "$MIN" -le 240 ] || { echo "MINUTES must be 5-240"; exit 2; }
cfg() { python3 -c "import json,sys;print(json.load(open('$HERE/config.json'))['$1'])"; }
SRV=$(cfg server); KU=$(cfg u2r_key); KS=$(cfg fec_key)
DOWN='1:10,2:13,3:16,5:21,8:28,12:36,16:43,24:57,32:70,48:96,64:121,80:145'
ssh -o BatchMode=yes root@$SRV bash -s <<EOF
set -e
systemctl stop cn-probe cn-spd cn-u2r1 cn-u2r2 cn-safety.timer cn-safety.service 2>/dev/null || true
nft delete table inet cntest 2>/dev/null || true
for h in \$(nft -a list chain inet wgfw input | awk '/comment "cn-test"/ {print \$NF}'); do nft delete rule inet wgfw input handle \$h; done
nft add table inet cntest
nft add chain inet cntest input '{ type filter hook input priority -300; policy accept; }'
nft add rule inet cntest input tcp dport '{ 62937, 62938 }' drop
nft insert rule inet wgfw input $SRC udp dport '{ 62933, 62934 }' accept comment \"cn-test\"
R='-p RuntimeMaxSec=$((MIN * 60))'
systemd-run --quiet --unit=cn-probe --uid=nobody \$R -p ProtectSystem=strict -p PrivateTmp=yes -p NoNewPrivileges=yes python3 -I /opt/net-probe/probe_server.py --bind 0.0.0.0 --port 62933
systemd-run --quiet --unit=cn-spd --uid=nobody \$R /usr/local/bin/speederv2-wg -s -l0.0.0.0:62934 -r127.0.0.1:62933 -k $KS -f$DOWN --timeout 40 -i 5 --mode 0 --mtu 1350 --decode-buf 12000 --sock-buf 4096 --disable-color --log-level 3
for x in '1 62937 62933' '2 62938 62934'; do set -- \$x
 systemd-run --quiet --unit=cn-u2r\$1 --uid=nobody \$R -p AmbientCapabilities=CAP_NET_RAW -p CapabilityBoundingSet=CAP_NET_RAW /usr/local/bin/udp2raw -s -l0.0.0.0:\$2 -r127.0.0.1:\$3 -k $KU --raw-mode faketcp --cipher-mode aes128cbc --auth-mode hmac_sha1 --log-level 3 --disable-color
done
systemd-run --quiet --unit=cn-safety --on-active=${MIN}m /bin/sh -c 'systemctl stop cn-probe cn-spd cn-u2r1 cn-u2r2; nft delete table inet cntest; for h in \$(nft -a list chain inet wgfw input | awk "/comment \\"cn-test\\"/ {print \\\$NF}"); do nft delete rule inet wgfw input handle \$h; done'
sleep 1; echo "services: \$(systemctl is-active cn-probe cn-spd cn-u2r1 cn-u2r2 | tr '\n' ' ')"
EOF
echo "open for $IP for $MIN minutes (closes by itself). Close now: server/server_close.sh"
