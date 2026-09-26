#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
: "${1:?Usage: scripts/account.sh create|update|password|disable|enable EMAIL [ROLE SCOPE]}"
: "${2:?Email required}"
case "$1" in create|update|password|disable|enable) ;; *) echo 'Unknown account action' >&2; exit 1;; esac
export ACCOUNT_ID="$2" ACCOUNT_ROLE="${3:-}" ACCOUNT_SCOPE="${4:-}"
export ACCOUNT_OPERATOR="${ACCOUNT_OPERATOR:-${USER:-host-admin}}"
if [[ "$1" == create || "$1" == password ]]; then
  read -r -s -p 'New password (12 to 199 characters): ' ACCOUNT_PASSWORD
  echo
  export ACCOUNT_PASSWORD
fi
trap 'unset ACCOUNT_PASSWORD' EXIT
docker compose -f "$root/compose.prod.yaml" run --rm --no-deps \
  -e ACCOUNT_ID -e ACCOUNT_ROLE -e ACCOUNT_SCOPE -e ACCOUNT_OPERATOR -e ACCOUNT_PASSWORD \
  backend java -jar /app/backend.jar "account-$1"
