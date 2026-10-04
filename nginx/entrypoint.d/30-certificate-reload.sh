#!/bin/sh
# Reload renewed certificates without exposing the Docker socket to certbot.
(
  while sleep 12h; do
    nginx -t && nginx -s reload
  done
) &
