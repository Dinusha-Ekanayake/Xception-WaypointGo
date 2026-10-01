#!/usr/bin/env bash
# Deploys the tip of main on the VPS.
#
# Runs as the unprivileged `deploy` user, by hand or as the forced command of the
# CI key in ~deploy/.ssh/authorized_keys. Because it is a forced command it takes
# nothing from the caller: no ref, no arguments, no environment. A leaked CI key
# can trigger a deploy of what is already on main and nothing else.
#
# The checkout under APP_DIR is deploy-only and is reset on every run. Secrets
# live in APP_DIR/.env, which is untracked and survives the reset.
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/waypoint/app}"
DEPLOY_REF="${DEPLOY_REF:-origin/main}"
COMPOSE=(docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml)

die() { echo "deploy: $*" >&2; exit 1; }

# The body is a function so bash has parsed all of it before `git reset` can
# replace this file underneath the running shell.
main() {
  cd "$APP_DIR"
  [[ -f .env ]] || die "$APP_DIR/.env is missing; see docs/deployment.md"

  if [[ "${1:-}" != "--fetched" ]]; then
    exec 9>"$APP_DIR/../deploy.lock"
    flock -n 9 || die "another deploy is already running"
    git fetch --prune --quiet origin
    git reset --hard --quiet "$DEPLOY_REF"
    # Re-run from the commit just checked out so its own deploy steps apply.
    # The lock is held through the exec because fd 9 is inherited.
    exec "$APP_DIR/deploy/vps/deploy.sh" --fetched
  fi

  echo "==> deploying $(git log -1 --format='%h %s')"

  # Build first: the running stack keeps serving until the images are ready.
  "${COMPOSE[@]}" build --quiet
  # `init` migrates and imports before the backend is replaced. A failed
  # migration stops here with the previous containers still running.
  "${COMPOSE[@]}" up -d --remove-orphans --wait --wait-timeout 600

  local site
  site="$(sed -n 's/^SITE_ADDRESS=//p' .env | tail -1)"
  [[ -n "$site" ]] || die "SITE_ADDRESS is not set in .env"

  echo "==> checking https://$site"
  local attempt
  for attempt in $(seq 1 30); do
    if curl -fsS --max-time 10 -o /dev/null "https://$site/"; then
      curl -fsS --max-time 10 http://127.0.0.1:8080/health/readiness | grep -q '"status":"UP"' \
        || die "site answers but backend readiness is not UP"
      docker image prune -f >/dev/null
      echo "==> live: https://$site ($(git rev-parse --short HEAD))"
      return 0
    fi
    sleep 4
  done
  "${COMPOSE[@]}" ps
  die "https://$site did not answer after the deploy"
}

main "$@"
