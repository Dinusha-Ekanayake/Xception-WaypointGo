#!/bin/sh
# One-shot initialisation for the Compose stack: schema, reference data and the
# demo accounts. Runs inside the backend image as the `init` service, before the
# backend serves. Mirrors `scripts/dev.sh setup`.
#
# Safe to repeat: migrations and the reference import are no-ops when nothing
# changed, and an account that already exists is left alone, so SEED_PASSWORD
# only applies the first time an account is created.
set -eu

JAR=/app/backend.jar
DEMO_DEPOT="${DEMO_DEPOT:-Peliyagoda}"
: "${SEED_PASSWORD:?SEED_PASSWORD must be set to create the demo accounts}"

echo "==> migrate"
java -jar "$JAR" migrate
echo "==> import-reference"
java -jar "$JAR" import-reference

echo "==> demo accounts"
for role in dispatcher loader driver store_manager admin auditor; do
  email="$role@waypoint.local"
  name="Demo $(echo "$role" | tr '_' ' ')"
  if output=$(ACCOUNT_EMAIL="$email" ACCOUNT_NAME="$name" ACCOUNT_PASSWORD="$SEED_PASSWORD" \
      ACCOUNT_ROLE="$role" java -jar "$JAR" account-create 2>&1); then
    echo "    $email created"
  elif echo "$output" | grep -q "already exists"; then
    echo "    $email already exists"
  else
    # Anything other than a duplicate is a real failure; do not start the stack on it.
    echo "$output" >&2
    echo "    $email could not be created" >&2
    exit 1
  fi
done

echo "==> depot $DEMO_DEPOT for dispatcher, loader and driver"
for role in dispatcher loader driver; do
  ACCOUNT_EMAIL="$role@waypoint.local" ACCOUNT_DEPOT="$DEMO_DEPOT" \
    java -jar "$JAR" account-grant-depot
done
echo "==> init done"
