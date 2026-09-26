#!/bin/sh
# Runs after the stock 20-envsubst-on-templates.sh hook. Activates the nginx
# blocks matching certificate state:
#   - no certificate yet -> keep plain-HTTP fallback, drop TLS + redirect
#   - certificate present -> keep TLS + redirect, drop fallback
set -e

CONF="/etc/nginx/conf.d/waypoint.conf"
CERT="/etc/letsencrypt/live/${DOMAIN}/fullchain.pem"

if [ -f "$CERT" ]; then
  sed -i '/# BEGIN-FALLBACK/,/# END-FALLBACK/d' "$CONF"
  echo "nginx: certificate found, serving HTTPS"
else
  sed -i '/# BEGIN-TLS/,/# END-TLS/d' "$CONF"
  sed -i '/# BEGIN-REDIRECT/,/# END-REDIRECT/d' "$CONF"
  echo "nginx: no certificate yet, serving temporary HTTP"
fi
