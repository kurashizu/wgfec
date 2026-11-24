#!/bin/bash
# configure.sh DIR PUBLIC_HOST[:PORT]
# Points the admin tooling in DIR (server/hub or server/exit) at your server: replaces the wg.example.com
# placeholder with the public host name that clients should connect to.
#   ./configure.sh ../hub vpn.mydomain.net
set -euo pipefail
DIR=${1:?usage: configure.sh DIR PUBLIC_HOST[:PORT]}; HOSTSPEC=${2:?usage: configure.sh DIR PUBLIC_HOST[:PORT]}
HOST=${HOSTSPEC%%:*}
[[ "$HOST" =~ ^[A-Za-z0-9.-]+$ ]] || { echo "invalid host: $HOST"; exit 1; }
if [[ "$HOSTSPEC" == *:* ]]; then PORT=${HOSTSPEC##*:}; else PORT=51820; fi
[[ "$PORT" =~ ^[0-9]+$ ]] || { echo "invalid port: $PORT"; exit 1; }
sed -i "s|wg.example.com:51820|$HOST:$PORT|g; s|\"wg.example.com\"|\"$HOST\"|g" "$DIR/wg-peer" "$DIR/wg-admin" "$DIR/app.py"
grep -n "$HOST" "$DIR/wg-peer" "$DIR/wg-admin" "$DIR/app.py"
