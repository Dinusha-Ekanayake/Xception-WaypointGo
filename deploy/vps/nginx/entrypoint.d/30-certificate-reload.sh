#!/bin/sh
# Picks up renewed certificates without giving certbot the Docker socket: every
# twelve hours, re-point at the current certificate and reload if the
# configuration still tests clean.
(
  while sleep 12h; do
    /docker-entrypoint.d/25-tls-certificate.sh && nginx -t && nginx -s reload
  done
) &
