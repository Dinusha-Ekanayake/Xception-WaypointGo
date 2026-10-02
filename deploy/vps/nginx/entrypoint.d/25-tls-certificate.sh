#!/bin/sh
# Points nginx at the Let's Encrypt certificate when there is one, and at a
# self-signed placeholder when there is not, so nginx can start and answer the
# HTTP-01 challenge that produces the real certificate. Runs at container start
# and again from 30-certificate-reload.sh and deploy/vps/deploy.sh.
set -e

LIVE="/etc/letsencrypt/live/${SITE_ADDRESS}"
TLS=/etc/nginx/tls
mkdir -p "$TLS"

if [ -f "$LIVE/fullchain.pem" ] && [ -f "$LIVE/privkey.pem" ]; then
  ln -sfn "$LIVE/fullchain.pem" "$TLS/fullchain.pem"
  ln -sfn "$LIVE/privkey.pem" "$TLS/privkey.pem"
  echo "nginx: serving the Let's Encrypt certificate for ${SITE_ADDRESS}"
else
  if [ ! -f "$TLS/placeholder.key" ]; then
    SAN="DNS:${SITE_ADDRESS},DNS:www.${SITE_ADDRESS},DNS:preview.${SITE_ADDRESS},DNS:grafana-preview.${SITE_ADDRESS}"
    for role in dispatcher loader driver store admin auditor; do
      SAN="$SAN,DNS:$role.${SITE_ADDRESS},DNS:$role-preview.${SITE_ADDRESS}"
    done
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 30 \
      -subj "/CN=${SITE_ADDRESS}" \
      -addext "subjectAltName=$SAN" \
      -keyout "$TLS/placeholder.key" -out "$TLS/placeholder.crt" 2>/dev/null
  fi
  ln -sfn "$TLS/placeholder.crt" "$TLS/fullchain.pem"
  ln -sfn "$TLS/placeholder.key" "$TLS/privkey.pem"
  echo "nginx: no certificate for ${SITE_ADDRESS} yet, serving a self-signed placeholder"
fi
