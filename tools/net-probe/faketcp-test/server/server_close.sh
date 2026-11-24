#!/bin/bash
# server_close.sh: close the temporary test ports now.
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
SRV=$(python3 -c "import json;print(json.load(open('$HERE/config.json'))['server'])")
ssh -o BatchMode=yes root@$SRV 'systemctl stop cn-probe cn-spd cn-u2r1 cn-u2r2 cn-safety.timer 2>/dev/null; nft delete table inet cntest 2>/dev/null; for h in $(nft -a list chain inet wgfw input | awk "/comment \"cn-test\"/ {print \$NF}"); do nft delete rule inet wgfw input handle $h; done; echo "active: $(systemctl is-active cn-probe cn-spd cn-u2r1 cn-u2r2 | tr "\n" " ") rules left: $(nft list ruleset | grep -c cn-test)"'
