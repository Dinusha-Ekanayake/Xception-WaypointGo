#!/usr/bin/env bash
# Start (and stop) the whole Waypoint Dispatch stack with one command.
#
#   ./start.sh          start backend + frontend in the foreground (Ctrl+C stops all)
#   ./start.sh stop     stop anything started by ./start.sh (or left on ports 8080/3000)
#   ./start.sh restart  stop, then start
#
# Loads root .env safely (DATABASE_URL contains '&', so plain `source` breaks
# in bash/zsh background semantics) and ensures frontend/.env.local exists.
# Never migrates or seeds: run those explicitly once per database.
set -euo pipefail

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${PORT:-3000}"
BACKEND_LOG="${BACKEND_LOG:-/tmp/waypoint-backend.log}"
FRONTEND_LOG="${FRONTEND_LOG:-/tmp/waypoint-frontend.log}"
BACKEND_PIDFILE="${BACKEND_PIDFILE:-/tmp/waypoint-backend.pid}"
FRONTEND_PIDFILE="${FRONTEND_PIDFILE:-/tmp/waypoint-frontend.pid}"

backend_pid=""
frontend_pid=""

die() { echo "start.sh: $*" >&2; exit 1; }

# Load KEY=VALUE lines without shell-evaluating values (keeps '&', '!' intact).
load_env() {
  local env_file="$root/.env"
  [[ -f "$env_file" ]] || die "Missing $env_file. Copy .env.example to .env first."
  local line key value
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    # trim leading whitespace
    line="${line#"${line%%[![:space:]]*}"}"
    case "$line" in ""|\#*) continue ;; esac
    [[ "$line" == *"="* ]] || continue
    key="${line%%=*}"
    value="${line#*=}"
    # trim whitespace around key
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    export "$key=$value"
  done < "$env_file"
  [[ -n "${DATABASE_URL:-}" ]] || die "DATABASE_URL is not set in $env_file."
}

ensure_frontend_env() {
  local env_local="$root/frontend/.env.local"
  if [[ ! -f "$env_local" ]]; then
    printf 'BACKEND_URL=http://127.0.0.1:%s\n' "$BACKEND_PORT" > "$env_local"
    echo "Created frontend/.env.local with BACKEND_URL=http://127.0.0.1:$BACKEND_PORT"
  fi
}

check_prereqs() {
  command -v java >/dev/null || die "java not found (need Java 17+)."
  command -v node >/dev/null || die "node not found (need Node 22.13+)."
  command -v npm >/dev/null || die "npm not found."
  [[ -d "$root/frontend/node_modules" ]] || {
    echo "frontend/node_modules missing, running 'npm ci' in frontend/..."
    (cd "$root/frontend" && npm ci)
  }
  if ! ls "$root"/backend/target/waypoint-dispatch-backend-*.jar >/dev/null 2>&1; then
    die "Backend jar missing. Build once with: npm run backend:build (from frontend/) or mvn -f backend/pom.xml package"
  fi
}

port_busy() {
  (command -v ss >/dev/null && ss -tln 2>/dev/null | grep -q ":$1 ") || \
  (command -v curl >/dev/null && curl -fsS --max-time 2 "http://127.0.0.1:$1/api/health" >/dev/null 2>&1)
}

wait_for_health() {
  local url="$1" name="$2" tries="${3:-60}"
  local i
  for ((i = 1; i <= tries; i++)); do
    if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
      echo "$name is up ($url)"
      return 0
    fi
    sleep 2
  done
  return 1
}

stop_pidfile() {
  local pidfile="$1" name="$2"
  if [[ -f "$pidfile" ]]; then
    local pid
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
      echo "Stopping $name (pid $pid)..."
      kill "$pid" 2>/dev/null || true
      sleep 2
      kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$pidfile"
  fi
}

stop_all() {
  stop_pidfile "$BACKEND_PIDFILE" "backend"
  stop_pidfile "$FRONTEND_PIDFILE" "frontend"
  # Fallback for processes started before pidfiles existed.
  if port_busy "$BACKEND_PORT" || port_busy "$FRONTEND_PORT"; then
    if command -v fuser >/dev/null; then
      fuser -k "$BACKEND_PORT"/tcp >/dev/null 2>&1 || true
      fuser -k "$FRONTEND_PORT"/tcp >/dev/null 2>&1 || true
    else
      pkill -f "waypoint-dispatch-backend-.*\.jar" 2>/dev/null || true
      pkill -f "next-server.*$FRONTEND_PORT" 2>/dev/null || true
    fi
    sleep 2
  fi
  echo "Stopped. (8080/3000 free if no errors above.)"
}

cleanup() {
  trap - INT TERM EXIT
  if [[ -n "${backend_pid:-}" ]] && kill -0 "$backend_pid" 2>/dev/null; then
    kill "$backend_pid" 2>/dev/null || true
  fi
  if [[ -n "${frontend_pid:-}" ]] && kill -0 "$frontend_pid" 2>/dev/null; then
    kill "$frontend_pid" 2>/dev/null || true
  fi
  wait 2>/dev/null || true
  rm -f "$BACKEND_PIDFILE" "$FRONTEND_PIDFILE"
  echo "Stopped backend + frontend."
}

start_all() {
  load_env
  ensure_frontend_env
  check_prereqs

  if port_busy "$BACKEND_PORT"; then
    die "Port $BACKEND_PORT is busy. Run './start.sh stop' first."
  fi
  if port_busy "$FRONTEND_PORT"; then
    die "Port $FRONTEND_PORT is busy. Run './start.sh stop' first."
  fi

  local jar
  jar="$(ls "$root"/backend/target/waypoint-dispatch-backend-*.jar | head -n 1)"

  echo "Starting backend on :$BACKEND_PORT (log: $BACKEND_LOG)..."
  : > "$BACKEND_LOG"
  export DATA_DIR="${DATA_DIR:-$root/data}"
  export MIGRATIONS_DIR="${MIGRATIONS_DIR:-$root/migrations}"
  export PUBLIC_DIR="${PUBLIC_DIR:-$root/frontend/public}"
  java -jar "$jar" >>"$BACKEND_LOG" 2>&1 &
  backend_pid=$!
  echo "$backend_pid" > "$BACKEND_PIDFILE"

  if ! wait_for_health "http://127.0.0.1:$BACKEND_PORT/api/health" "Backend" 60; then
    echo "Backend failed to become healthy. Last log lines:" >&2
    tail -n 30 "$BACKEND_LOG" >&2 || true
    cleanup
    exit 1
  fi

  echo "Starting frontend on :$FRONTEND_PORT (log: $FRONTEND_LOG)..."
  : > "$FRONTEND_LOG"
  (cd "$root/frontend" && exec npm run dev -- --port "$FRONTEND_PORT" --hostname 0.0.0.0 >>"$FRONTEND_LOG" 2>&1) &
  frontend_pid=$!
  echo "$frontend_pid" > "$FRONTEND_PIDFILE"

  if ! wait_for_health "http://127.0.0.1:$FRONTEND_PORT/api/health" "Frontend" 60; then
    echo "Frontend failed to become healthy. Last log lines:" >&2
    tail -n 30 "$FRONTEND_LOG" >&2 || true
    cleanup
    exit 1
  fi

  echo ""
  echo "All running:"
  echo "  App:     http://localhost:$FRONTEND_PORT"
  echo "  Backend: http://127.0.0.1:$BACKEND_PORT/api/health"
  echo "Press Ctrl+C to stop all."
  echo ""

  trap cleanup INT TERM EXIT
  wait
}

case "${1:-start}" in
  start) start_all ;;
  stop) stop_all ;;
  restart) stop_all; sleep 2; start_all ;;
  *) echo "Usage: ./start.sh [start|stop|restart]" >&2; exit 1 ;;
esac
