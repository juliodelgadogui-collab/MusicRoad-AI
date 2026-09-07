#!/usr/bin/env bash
set -euo pipefail

# Estrada Play production TURN installer for Ubuntu/Debian VPS.
# Usage:
#   sudo TURN_REALM=turn.example.com TURN_USER=estradaplay TURN_PASSWORD='strong-password' bash server/install_coturn.sh

: "${TURN_REALM:?Defina TURN_REALM}"
: "${TURN_USER:?Defina TURN_USER}"
: "${TURN_PASSWORD:?Defina TURN_PASSWORD}"
TURN_PORT="${TURN_PORT:-3478}"
TLS_PORT="${TURN_TLS_PORT:-5349}"
EXTERNAL_IP="${TURN_EXTERNAL_IP:-$(curl -fsS https://api.ipify.org || true)}"

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y coturn

cat >/etc/turnserver.conf <<EOF
listening-port=${TURN_PORT}
tls-listening-port=${TLS_PORT}
fingerprint
lt-cred-mech
realm=${TURN_REALM}
user=${TURN_USER}:${TURN_PASSWORD}
no-multicast-peers
no-loopback-peers
stale-nonce=600
channel-lifetime=600
permission-lifetime=300
max-bps=0
total-quota=0
bps-capacity=0
verbose
EOF

if [[ -n "${EXTERNAL_IP}" ]]; then
  echo "external-ip=${EXTERNAL_IP}" >>/etc/turnserver.conf
fi

sed -i 's/^#\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn || true
systemctl enable coturn
systemctl restart coturn
systemctl --no-pager --full status coturn || true

echo
echo "TURN instalado. Configure no painel/DB do Estrada Play:"
echo "radio_turn_url=turn:${TURN_REALM}:${TURN_PORT}?transport=udp"
echo "radio_turn_username=${TURN_USER}"
echo "radio_turn_password=<a senha definida>"
echo "Abra UDP/TCP ${TURN_PORT}, TCP ${TLS_PORT} e a faixa UDP 49152:65535 no firewall/cloud."
