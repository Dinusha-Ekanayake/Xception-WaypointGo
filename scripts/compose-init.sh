#!/bin/sh
# One-shot initialisation for the Compose stack: schema, reference data and the
# demo accounts. Runs inside the backend image as the `init` service, before the
# backend serves. Mirrors `scripts/dev.sh setup`.
#
# Safe to repeat: migrations and the reference import are no-ops when nothing
# changed, and an account that already exists is left alone, so SEED_PASSWORD
# only applies the first time an account is created.
set -eu

: "${SEED_PASSWORD:?SEED_PASSWORD must be set to create the demo accounts}"

# One start of the application for all three steps. Any failure exits non-zero,
# and the stack does not start on it.
java -jar /app/backend.jar migrate import-reference demo-accounts
echo "==> init done"
