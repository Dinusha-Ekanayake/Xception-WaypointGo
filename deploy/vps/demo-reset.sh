#!/usr/bin/env bash
# Runs the demo in a database of its own (R-DEMO-06), for a checkout whose .env
# sets DEMO_RESET=true. Run every few seconds by waypoint-demo-reset@<checkout>.timer
# as the `deploy` user; each run carries out at most one request an administrator
# made in the Demo control room:
#
#   demo:SwitchToDemo  (demo mode on, asked in the real database): creates
#       waypoint_demo from a fresh install if it is not there, with every
#       account's password and PIN copied from the real database, and restarts
#       the backend on it.
#   demo:SwitchBack    (demo mode off, asked in the demo database): restarts the
#       backend on the real database, which the demo never wrote to.
#   demo:RequestReset  (Reset demo, asked in the demo database): drops
#       waypoint_demo and creates it again from a fresh install.
#
# Which database is live is kept in .demo-database beside .env, read by
# deploy.sh too, so a deploy during a demo stays on the demo. The only database
# this script ever drops is waypoint_demo. The real one is read to copy the
# accounts, and its demo run list records the switch; nothing else in it changes.
set -euo pipefail

APP_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/../.." && pwd)"
cd "$APP_DIR"

REAL=waypoint
DEMO=waypoint_demo
STATE="$APP_DIR/.demo-database"

env_value() { sed -n "s/^$1=//p" "$APP_DIR/.env" | tail -1; }
[[ "$(env_value DEMO_RESET)" == "true" ]] || exit 0
# As deploy.sh: no DEPLOY_ENV line means production.
environment="$(env_value DEPLOY_ENV)"
environment="${environment:-production}"
[[ "$environment" == production || "$environment" == preview ]] || exit 0

compose=(docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml)
[[ "$environment" == production ]] && compose+=(--profile edge)
[[ -n "$(env_value SPEECH_URL)" ]] && compose+=(--profile speech)

sql() { "${compose[@]}" exec -T db psql -U waypoint -d "$1" -v ON_ERROR_STOP=1 -qtAX "${@:2}"; }
live() { local name; name="$(cat "$STATE" 2>/dev/null || true)"; [[ "$name" == "$DEMO" ]] && echo "$DEMO" || echo "$REAL"; }
exists() { [[ "$(sql postgres -c "SELECT 1 FROM pg_database WHERE datname = '$1'")" == 1 ]]; }

# One request at a time, and the log beside the deploy dumps.
exec 9>"/tmp/waypoint-demo-$environment.lock"
flock -n 9 || exit 0

# The oldest waiting request in the live database, as "id key email".
pending() {
  sql "$1" -c "SELECT r.id || ' ' || r.scenario_key || ' ' || u.email FROM demo.scenario_runs r JOIN iam.users u ON u.user_id = r.actor WHERE r.outcome = 'requested' AND r.scenario_key IN ($2) ORDER BY r.started_at LIMIT 1" 2>/dev/null || true
}

current="$(live)"
if [[ "$current" == "$REAL" ]]; then
  request="$(pending "$REAL" "'demo:SwitchToDemo'")"
else
  request="$(pending "$DEMO" "'demo:SwitchBack','demo:RequestReset'")"
fi
[[ -n "$request" ]] || exit 0
read -r request_id kind requested_by <<<"$request"

backups="$(dirname "$APP_DIR")/backups/$environment"
mkdir -p "$backups"
exec >>"$backups/demo.log" 2>&1
echo "==> $(date -u +%Y%m%dT%H%M%SZ) $kind $request_id by $requested_by (live: $current)"

# Records the outcome of a request in one database's run list.
finish() {
  sql "$1" -c "UPDATE demo.scenario_runs SET outcome = '$3', details = details || jsonb_build_object('note', '$4'), row_version = row_version + 1 WHERE id = '$2'"
}
log_run() {
  sql "$1" -c "INSERT INTO demo.scenario_runs(id, scenario_key, actor, reason, outcome, details) SELECT '$2'::uuid, '$3', u.user_id, '$4', 'completed', '{}' FROM iam.users u WHERE u.email = '$requested_by' ON CONFLICT (id) DO NOTHING"
}

# The backend on one database. The name is one of two literals, never input.
# The live database is recorded only once the backend runs on it, and the start
# is tried twice, because a container another command just replaced can make the
# first attempt fail ("No such container").
start_on() {
  [[ "$1" == "$REAL" || "$1" == "$DEMO" ]] || return 1
  DB_NAME="$1" "${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 600 backend     || { sleep 5; DB_NAME="$1" "${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 600 backend; }     || return 1
  echo "$1" > "$STATE"
}

# waypoint_demo from a fresh install: schema, reference data, the accounts and
# the seeded day, as on the first start, then the real accounts' passwords.
# Called in a condition, where set -e is off, so each step returns on failure.
create_demo() {
  sql postgres -c "CREATE DATABASE $DEMO OWNER waypoint" || return 1
  DB_NAME="$DEMO" "${compose[@]}" run --rm -T init || return 1
  DB_NAME="$DEMO" "${compose[@]}" run --rm --no-deps -T seed || return 1
  {
    echo "CREATE TEMP TABLE kept (email text, password_hash text, pin_hash text);"
    echo "COPY kept FROM STDIN;"
    sql "$REAL" -c "COPY (SELECT email, password_hash, coalesce(pin_hash, '') FROM iam.users) TO STDOUT"
    echo '\.'
    echo "UPDATE iam.users u SET password_hash = k.password_hash, pin_hash = nullif(k.pin_hash, '') FROM kept k WHERE u.email = k.email;"
    echo "UPDATE demo.settings SET enabled = true, clock_offset_seconds = 0, row_version = row_version + 1, updated_at = now();"
  } | sql "$DEMO" || return 1
}

drop_demo() {
  sql postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DEMO' AND pid <> pg_backend_pid()" >/dev/null
  sql postgres -c "DROP DATABASE IF EXISTS $DEMO"
}

case "$kind" in
  demo:SwitchToDemo)
    finish "$REAL" "$request_id" completed "switched to the demo database"
    if ! exists "$DEMO" && ! create_demo; then
      echo "!!  could not create $DEMO; staying on $REAL"
      drop_demo || true
      finish "$REAL" "$request_id" failed "the demo database could not be created"
      exit 1
    fi
    start_on "$DEMO"
    log_run "$DEMO" "$request_id" demo:SwitchToDemo "Demo mode on, in the demo database"
    ;;
  demo:SwitchBack)
    finish "$DEMO" "$request_id" completed "switched back to the real database"
    # The real database is migrated before the backend returns to it, in case a
    # deploy ran during the demo.
    DB_NAME="$REAL" "${compose[@]}" run --rm -T init
    start_on "$REAL"
    ;;
  demo:RequestReset)
    finish "$DEMO" "$request_id" completed "reset"
    "${compose[@]}" stop backend
    drop_demo
    if ! create_demo; then
      echo "!!  reset failed; back on $REAL"
      drop_demo || true
      start_on "$REAL"
      exit 1
    fi
    start_on "$DEMO"
    log_run "$DEMO" "$request_id" demo:RequestReset "Fresh start of the demo"
    ;;
esac
echo "==> $(date -u +%Y%m%dT%H%M%SZ) $kind done; live: $(live)"
