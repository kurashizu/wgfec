#!/bin/bash
# deploy.sh SSH_TARGET DIR [sudo]
# Installs the admin tooling from DIR (server/hub or server/exit) on a server that already runs the base setup
# (see docs/server-setup.md). Backs up what it replaces to /var/lib/wg-peer/ui-backup/ and restarts wg-web.
#   ./deploy.sh root@my-server ../hub
#   ./deploy.sh opc@my-server ../hub sudo
set -euo pipefail
T=${1:?usage: deploy.sh SSH_TARGET DIR [sudo]}; D=${2:?usage: deploy.sh SSH_TARGET DIR [sudo]}; SU=${3:-}
STAMP=$(date +%Y%m%d-%H%M%S)
tar -C "$D" -czf /tmp/wgfec-deploy.tgz wg-peer wg-admin app.py index.html static
scp -q -o BatchMode=yes /tmp/wgfec-deploy.tgz "$T:/tmp/wgfec-deploy.tgz"; rm -f /tmp/wgfec-deploy.tgz
ssh -o BatchMode=yes "$T" "$SU bash -s" <<REMOTE
set -e
B=/var/lib/wg-peer/ui-backup/$STAMP; mkdir -p \$B /opt/wg-web
cp -a /usr/local/sbin/wg-peer /usr/local/sbin/wg-admin /opt/wg-web/app.py /opt/wg-web/index.html /opt/wg-web/static \$B/ 2>/dev/null || true
W=\$(mktemp -d); tar -C \$W -xzf /tmp/wgfec-deploy.tgz
install -m 755 \$W/wg-peer /usr/local/sbin/wg-peer; install -m 755 \$W/wg-admin /usr/local/sbin/wg-admin
install -m 644 \$W/app.py /opt/wg-web/app.py; install -m 644 \$W/index.html /opt/wg-web/index.html
rm -rf /opt/wg-web/static; cp -r \$W/static /opt/wg-web/static; chmod -R a+rX /opt/wg-web/static
rm -rf \$W /tmp/wgfec-deploy.tgz
systemctl restart wg-web; sleep 1; echo "wg-web: \$(systemctl is-active wg-web)   backup: \$B"
REMOTE
