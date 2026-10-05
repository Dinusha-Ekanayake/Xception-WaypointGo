#!/usr/bin/env bash
# Creates an account from the host, on the stack of the checkout this script is in
# (run it from /opt/waypoint/app or /opt/waypoint/preview on the VPS). The first
# administrator cannot come from an endpoint that requires one; every later
# account change is a command through the API (iam:CreateUser, iam:UpdateUser...).
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
: "${1:?Usage: scripts/account.sh EMAIL "DISPLAY NAME" ROLE   (role: admin|dispatcher|loader|driver|store_manager|auditor)}"
: "${2:?Display name required}"
: "${3:?Role required}"
export ACCOUNT_EMAIL="$1" ACCOUNT_NAME="$2" ACCOUNT_ROLE="$3"
read -r -s -p 'Password (at least 12 characters): ' ACCOUNT_PASSWORD
echo
export ACCOUNT_PASSWORD
trap 'unset ACCOUNT_PASSWORD' EXIT
docker compose --project-directory "$root" -f "$root/compose.yaml" -f "$root/deploy/vps/compose.vps.yaml" run --rm --no-deps   -e ACCOUNT_EMAIL -e ACCOUNT_NAME -e ACCOUNT_ROLE -e ACCOUNT_PASSWORD   backend java -jar /app/backend.jar account-create
