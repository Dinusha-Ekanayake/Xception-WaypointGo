#!/usr/bin/env bash
# Local development in one command: database, backend, frontend.
#
#   scripts/dev.sh setup     first time: database, migrate, import-reference, demo accounts
#   scripts/dev.sh           daily: database, backend, frontend
#   scripts/dev.sh sample    same, with store and loader sample data
#
# Always targets the local Docker database, never the DATABASE_URL in .env.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-up}"

export DATABASE_URL='postgresql://waypoint:local-testing-only@127.0.0.1:5432/waypoint'
export DEMO_MODE=1
export SEED_PASSWORD="${SEED_PASSWORD:-Waypoint2026!}"

docker_cmd() {
  if docker info >/dev/null 2>&1; then docker "$@"; else sudo docker "$@"; fi
}

echo "==> database"
(cd "$ROOT" && docker_cmd compose up -d db)
until (cd "$ROOT" && docker_cmd compose exec -T db pg_isready -U waypoint -q); do sleep 1; done

backend_run() {
  (cd "$ROOT/backend" && mvn -q spring-boot:run -Dspring-boot.run.arguments="$1")
}

if [[ "$MODE" == "setup" ]]; then
  (cd "$ROOT" && docker_cmd compose exec -T db createdb -U waypoint waypoint_test 2>/dev/null) || true
  echo "==> migrate";          backend_run migrate
  echo "==> import-reference"; backend_run import-reference
  echo "==> demo accounts (password: $SEED_PASSWORD)"
  for role in dispatcher loader driver store_manager admin auditor; do
    ACCOUNT_EMAIL="$role@waypoint.local" ACCOUNT_NAME="Demo ${role/_/ }" \
      ACCOUNT_PASSWORD="$SEED_PASSWORD" ACCOUNT_ROLE="$role" \
      backend_run account-create >/dev/null 2>&1 \
      && echo "    $role@waypoint.local" \
      || echo "    $role@waypoint.local (already exists or failed)"
  done
  echo "==> depot ${DEMO_DEPOT:=Peliyagoda} for dispatcher, loader and driver"
  for role in dispatcher loader driver; do
    ACCOUNT_EMAIL="$role@waypoint.local" ACCOUNT_DEPOT="$DEMO_DEPOT" \
      backend_run account-grant-depot >/dev/null 2>&1 || echo "    $role: grant failed or already held"
  done
  echo "==> frontend dependencies"
  (cd "$ROOT/frontend" && npm ci)
  [[ -f "$ROOT/frontend/.env.local" ]] || printf 'BACKEND_URL=http://127.0.0.1:8080\n' > "$ROOT/frontend/.env.local"
  echo "Setup done. Run scripts/dev.sh to start."
  exit 0
fi

mkdir -p "$ROOT/backend/target"
LOG="$ROOT/backend/target/dev-backend.log"
echo "==> backend (log: $LOG)"
(cd "$ROOT/backend" && exec mvn -q spring-boot:run) >"$LOG" 2>&1 &
BACKEND=$!
trap 'kill $BACKEND 2>/dev/null || true' EXIT INT TERM

until curl -sf http://127.0.0.1:8080/health/liveness >/dev/null; do
  kill -0 "$BACKEND" 2>/dev/null || { echo "Backend failed to start:"; tail -30 "$LOG"; exit 1; }
  sleep 2
done
echo "    backend up on :8080"

if [[ "$MODE" == "sample" ]]; then
  export NEXT_PUBLIC_STORE_FIXTURES=1 NEXT_PUBLIC_LOADER_FIXTURES=1
fi
echo "==> frontend on http://localhost:3000 (Ctrl+C stops both)"
cd "$ROOT/frontend" && npm run dev
