#!/bin/sh
# Creates the named demo accounts in scripts/demo-accounts.csv with the
# backend's own commands (account-create, account-grant-depot, operator-pin),
# so nothing is written to the database behind the application.
#
# Runs inside the backend image from compose-init.sh, after the schema and the
# reference data exist. By hand, against a database of your own:
#   DATABASE_URL=... SEED_PASSWORD=... BACKEND_JAR=backend/target/<jar> sh scripts/seed-demo-accounts.sh
#
# Safe to repeat: an account that exists is left unchanged, so SEED_PASSWORD
# applies only the first time. Each account is one start of the application.
set -eu

: "${SEED_PASSWORD:?SEED_PASSWORD must be set to create the demo accounts}"
JAR="${BACKEND_JAR:-/app/backend.jar}"
ROSTER="${DEMO_ACCOUNTS_FILE:-$(dirname "$0")/demo-accounts.csv}"

# A Windows checkout may hand the roster over with CRLF line ends.
grep -v '^#' "$ROSTER" | tr -d '\r' | while IFS=, read -r role email name depot pin; do
  [ -n "$role" ] || continue
  commands="account-create"
  [ -z "$depot" ] || commands="$commands account-grant-depot"
  [ -z "$pin" ] || commands="$commands operator-pin"
  echo "==> demo account: $role $name"
  # shellcheck disable=SC2086
  ACCOUNT_EMAIL="$email" ACCOUNT_NAME="$name" ACCOUNT_PASSWORD="$SEED_PASSWORD" ACCOUNT_ROLE="$role" \
    ACCOUNT_DEPOT="$depot" OPERATOR_EMAIL="$email" OPERATOR_PIN="$pin" \
    java -jar "$JAR" $commands
done
echo "==> demo accounts done"
