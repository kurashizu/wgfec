#!/bin/bash
# install-u2r.sh: add the udp2raw (fake-TCP) fronts to a WireGuard + UDPspeeder host. Idempotent.
#   wg-u2r.service      TCP $U2R_PORT      -> WireGuard      127.0.0.1:51820
#   wg-u2r-fec.service  TCP $U2R_PORT_FEC  -> speederv2 port 127.0.0.1:$SPEEDER_PORT (UDP)
#   wg-u2r-fw.service   nft table "inet u2r": drops those TCP ports in the input hook so the kernel never
#                       answers the fake-TCP packets with RST. udp2raw reads the packets from a raw socket,
#                       which sees them before netfilter, so it works even though the port looks closed.
# Needs /usr/local/bin/udp2raw already in place and /etc/wg-speeder/speeder.env from the speeder install.
set -eu
ENV=/etc/wg-speeder/speeder.env
[ -x /usr/local/bin/udp2raw ] || { echo "missing /usr/local/bin/udp2raw"; exit 1; }
[ -f "$ENV" ] || { echo "missing $ENV"; exit 1; }
NFT=$(command -v nft)

add_env() {   # add KEY=VALUE only if the key is not there yet
    grep -q "^$1=" "$ENV" || echo "$1=$2" >> "$ENV"
}
add_env U2R_KEY "$(python3 -c 'import secrets,string;print("".join(secrets.choice(string.ascii_letters+string.digits) for _ in range(24)))')"
add_env U2R_PORT 62935
add_env U2R_PORT_FEC 62936
add_env U2R_CIPHER aes128cbc
add_env U2R_AUTH hmac_sha1
# shellcheck disable=SC1090
. "$ENV"

cat > /etc/wg-u2r.nft <<EOF
# udp2raw ports: let the raw socket see the packets, but keep the kernel from answering with RST.
table inet u2r
delete table inet u2r
table inet u2r {
    chain input {
        type filter hook input priority -300; policy accept;
        tcp dport { ${U2R_PORT}, ${U2R_PORT_FEC} } drop comment "udp2raw"
    }
}
EOF
chmod 644 /etc/wg-u2r.nft

cat > /etc/systemd/system/wg-u2r-fw.service <<EOF
[Unit]
Description=Keep the kernel from answering udp2raw fake-TCP ports with RST (nftables)
Before=wg-u2r.service wg-u2r-fec.service
After=network-pre.target wg-fw.service
PartOf=wg-fw.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=${NFT} -f /etc/wg-u2r.nft
ExecStop=-${NFT} delete table inet u2r

[Install]
WantedBy=multi-user.target
EOF

mkunit() {   # name, description, listen port var, target
    cat > "/etc/systemd/system/$1.service" <<EOF
[Unit]
Description=$2
After=network.target wg-u2r-fw.service wg-speeder.service
Requires=wg-u2r-fw.service

[Service]
User=wgspeeder
Group=wgspeeder
EnvironmentFile=$ENV
ExecStart=/usr/local/bin/udp2raw -s -l0.0.0.0:\${$3} -r$4 -k \${U2R_KEY} --raw-mode faketcp --cipher-mode \${U2R_CIPHER} --auth-mode \${U2R_AUTH} --sock-buf 4096 --log-level 3 --disable-color
Restart=on-failure
RestartSec=3
NoNewPrivileges=yes
AmbientCapabilities=CAP_NET_RAW
CapabilityBoundingSet=CAP_NET_RAW
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_PACKET AF_UNIX
LockPersonality=yes

[Install]
WantedBy=multi-user.target
EOF
}
mkunit wg-u2r "udp2raw fake-TCP front for WireGuard (TCP U2R_PORT -> wg0 127.0.0.1:51820)" U2R_PORT 127.0.0.1:51820
mkunit wg-u2r-fec "udp2raw fake-TCP front for the UDPspeeder port (TCP U2R_PORT_FEC -> speederv2)" U2R_PORT_FEC "127.0.0.1:${SPEEDER_PORT}"

systemctl daemon-reload
systemctl enable --now wg-u2r-fw.service wg-u2r.service wg-u2r-fec.service
sleep 1
systemctl is-active wg-u2r-fw wg-u2r wg-u2r-fec
"$NFT" list table inet u2r
