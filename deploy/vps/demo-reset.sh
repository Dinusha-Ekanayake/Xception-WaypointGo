#!/usr/bin/env bash
# Runs the demo in a database of its own (R-DEMO-06), for a checkout whose .env
# sets DEMO_RESET=true. Run every few seconds by waypoint-demo@<checkout>.timer
# as the `deploy` user; each run carries out at most one request an administrator
# made in the Demo control room:
#
#   demo:SwitchToDemo  (demo mode on, asked in the real database): restarts the
#       backend on waypoint_demo, creating it first if it is not there.
#   demo:SwitchBack    (demo mode off, asked in the demo database): restarts the
#       backend on the real database, which the demo never wrote to.
#   demo:RequestReset  (Reset demo, asked in the demo database): replaces
#       waypoint_demo with a fresh install.
#
# A fresh install takes minutes, so it is made ahead: with no request waiting,
# the run builds a spare, waypoint_demo_next, and marks it ready with the code
# version and the Colombo date it was built for. A reset (or the first switch)
# then only renames the spare into place, copies the real accounts' passwords
# and PINs into it, and restarts the backend; a spare from older code or another
# day is built again. Without a ready spare, the fresh install runs in place.
#
# Which database is live is kept in .demo-database beside .env, read by
# deploy.sh too, so a deploy during a demo stays on the demo. The only databases
# this script ever drops are waypoint_demo and waypoint_demo_next. The real one
# is read to copy the accounts, and its demo run list records the switch.
set -euo pipefail

APP_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/../.." && pwd)"
cd "$APP_DIR"

REAL=waypoint
DEMO=waypoint_demo
SPARE=waypoint_demo_next
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

# One run at a time, and the log beside the deploy dumps.
exec 9>"/tmp/waypoint-demo-$environment.lock"
flock -n 9 || exit 0
backups="$(dirname "$APP_DIR")/backups/$environment"
mkdir -p "$backups"

# What a ready spare is marked with: the init image that built it and its day.
# The image, not the git commit: during a deploy the checkout moves before the
# image is rebuilt, and a spare built then by the old image must not count.
stamp() {
  # The tag compose builds, <project>-init; not `compose images`, which names the
  # image of the last init container, however old.
  local image
  image="$(docker image inspect -f '{{.Id}}' "$(basename "$APP_DIR")-init" 2>/dev/null || echo none)"
  image="${image#sha256:}"
  echo "ready ${image:0:16} $(TZ=Asia/Colombo date +%F)"
}
spare_ready() {
  [[ "$(sql postgres -c "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = '$SPARE'")" == "$(stamp)" ]]
}

# Drops one of the two demo databases; any other name is refused.
drop_db() {
  [[ "$1" == "$DEMO" || "$1" == "$SPARE" ]] || return 1
  sql postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$1' AND pid <> pg_backend_pid()" >/dev/null
  sql postgres -c "DROP DATABASE IF EXISTS $1"
}

# A fresh install into one demo database: schema, reference data, the accounts
# and the seeded day, as on the first start, with demo mode on. Called in a
# condition, where set -e is off, so each step returns on failure.
build() {
  [[ "$1" == "$DEMO" || "$1" == "$SPARE" ]] || return 1
  sql postgres -c "CREATE DATABASE $1 OWNER waypoint" || return 1
  DB_NAME="$1" "${compose[@]}" run --rm -T init || return 1
  DB_NAME="$1" "${compose[@]}" run --rm --no-deps -T seed || return 1
  sql "$1" -c "UPDATE demo.settings SET enabled = true, clock_offset_seconds = 0, row_version = row_version + 1, updated_at = now()" || return 1
}

# The real accounts' passwords and PINs, as they are now, into the demo database.
copy_credentials() {
  {
    echo "CREATE TEMP TABLE kept (email text, password_hash text, pin_hash text);"
    echo "COPY kept FROM STDIN;"
    sql "$REAL" -c "COPY (SELECT email, password_hash, coalesce(pin_hash, '') FROM iam.users) TO STDOUT"
    echo '\.'
    echo "UPDATE iam.users u SET password_hash = k.password_hash, pin_hash = nullif(k.pin_hash, '') FROM kept k WHERE u.email = k.email;"
  } | sql "$DEMO"
}

# waypoint_demo fresh: the ready spare renamed into place, else a fresh install.
# The backend is stopped by the caller, so nothing holds either database.
fresh_demo() {
  drop_db "$DEMO" || return 1
  if spare_ready; then
    echo "    using the ready spare"
    sql postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$SPARE' AND pid <> pg_backend_pid()" >/dev/null
    sql postgres -c "ALTER DATABASE $SPARE RENAME TO $DEMO" || return 1
    sql postgres -c "COMMENT ON DATABASE $DEMO IS NULL" || return 1
  else
    echo "    no ready spare; installing in place"
    drop_db "$SPARE" || true
    build "$DEMO" || return 1
  fi
  copy_credentials || return 1
}

# The backend on one database. The name is one of two literals, never input.
# The live database is recorded only once the backend runs on it, and the start
# is tried twice, because a container another command just replaced can make the
# first attempt fail ("No such container").
start_on() {
  [[ "$1" == "$REAL" || "$1" == "$DEMO" ]] || return 1
  DB_NAME="$1" "${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 600 backend \
    || { sleep 5; DB_NAME="$1" "${compose[@]}" up -d --no-deps --force-recreate --wait --wait-timeout 600 backend; } \
    || return 1
  echo "$1" > "$STATE"
}

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

# Nothing asked: keep a ready spare, so the next reset is a rename.
if [[ -z "$request" ]]; then
  spare_ready && exit 0
  exec >>"$backups/demo.log" 2>&1
  marked="$(stamp)"
  echo "==> $(date -u +%Y%m%dT%H%M%SZ) building the spare $SPARE ($marked)"
  drop_db "$SPARE"
  # Marked with the image read before the build: if a deploy brings a newer one
  # meanwhile, the mark no longer matches and the next run builds again.
  if build "$SPARE" && sql postgres -c "COMMENT ON DATABASE $SPARE IS '$marked'"; then
    echo "==> $(date -u +%Y%m%dT%H%M%SZ) spare ready"
  else
    echo "!!  the spare could not be built; the next reset installs in place"
    drop_db "$SPARE" || true
  fi
  exit 0
fi

read -r request_id kind requested_by <<<"$request"
exec >>"$backups/demo.log" 2>&1
echo "==> $(date -u +%Y%m%dT%H%M%SZ) $kind $request_id by $requested_by (live: $current)"

# Records the outcome of a request in one database's run list.
finish() {
  sql "$1" -c "UPDATE demo.scenario_runs SET outcome = '$3', details = details || jsonb_build_object('note', '$4'), row_version = row_version + 1 WHERE id = '$2'"
}
log_run() {
  sql "$1" -c "INSERT INTO demo.scenario_runs(id, scenario_key, actor, reason, outcome, details) SELECT '$2'::uuid, '$3', u.user_id, '$4', 'completed', '{}' FROM iam.users u WHERE u.email = '$requested_by' ON CONFLICT (id) DO NOTHING"
}

case "$kind" in
  demo:SwitchToDemo)
    finish "$REAL" "$request_id" completed "switched to the demo database"
    if ! exists "$DEMO"; then
      "${compose[@]}" stop backend
      if ! fresh_demo; then
        echo "!!  could not create $DEMO; staying on $REAL"
        drop_db "$DEMO" || true
        start_on "$REAL"
        finish "$REAL" "$request_id" failed "the demo database could not be created"
        exit 1
      fi
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
    if ! fresh_demo; then
      echo "!!  reset failed; back on $REAL"
      drop_db "$DEMO" || true
      start_on "$REAL"
      exit 1
    fi
    start_on "$DEMO"
    log_run "$DEMO" "$request_id" demo:RequestReset "Fresh start of the demo"
    ;;
esac
echo "==> $(date -u +%Y%m%dT%H%M%SZ) $kind done; live: $(live)"
