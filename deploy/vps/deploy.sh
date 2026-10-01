#!/usr/bin/env bash
# Deploys one environment on the VPS: production (the tip of main) from
# /opt/waypoint/app, or preview (the tip of dev) from /opt/waypoint/preview.
# Which one is decided by the checkout this file sits in and the DEPLOY_ENV line
# in that checkout's .env, never by the caller.
#
# Runs as the unprivileged `deploy` user, by hand or as the forced command of a
# CI key in ~deploy/.ssh/authorized_keys. Each environment has its own key bound
# to its own copy of this script, so the workflow sends no ref, no arguments and
# no environment, and the preview key cannot deploy production.
#
# The checkout is deploy-only and is reset on every run. Secrets live in its
# .env, which is untracked and survives the reset.
set -euo pipefail

APP_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")/../.." && pwd)"

die() { echo "deploy: $*" >&2; exit 1; }

# Last value of KEY in .env, or nothing. Values are never shell-evaluated.
env_value() { sed -n "s/^$1=//p" "$APP_DIR/.env" | tail -1; }

# The body is a function so bash has parsed all of it before `git reset` can
# replace this file underneath the running shell.
main() {
  cd "$APP_DIR"
  [[ -f .env ]] || die "$APP_DIR/.env is missing; see docs/deployment.md"

  local environment branch
  environment="$(env_value DEPLOY_ENV)"
  environment="${environment:-production}"
  case "$environment" in
    production) branch=main ;;
    preview) branch=dev ;;
    *) die "DEPLOY_ENV must be production or preview, not '$environment'" ;;
  esac

  if [[ "${1:-}" != "--fetched" ]]; then
    exec 9>"$APP_DIR/../$(basename "$APP_DIR").deploy.lock"
    flock -n 9 || die "another $environment deploy is already running"
    git fetch --prune --quiet origin
    git reset --hard --quiet "${DEPLOY_REF:-origin/$branch}"
    # Re-run from the commit just checked out so its own deploy steps apply.
    # The lock is held through the exec because fd 9 is inherited.
    exec "$APP_DIR/deploy/vps/deploy.sh" --fetched
  fi

  local compose=(docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml)
  # Caddy runs once, with production, and fronts both environments.
  [[ "$environment" == production ]] && compose+=(--profile edge)

  echo "==> deploying $environment: $(git log -1 --format='%h %s')"

  docker network inspect waypoint-edge >/dev/null 2>&1 || docker network create waypoint-edge >/dev/null

  # Build first: the running stack keeps serving until the images are ready.
  "${compose[@]}" build --quiet
  # `init` migrates and imports before the backend is replaced, and --wait holds
  # until the backend reports ready and the frontend answers. A failed migration
  # stops here with the previous containers still running.
  "${compose[@]}" up -d --remove-orphans --wait --wait-timeout 600

  if [[ "$environment" == production ]]; then
    # Picks up a changed Caddyfile without dropping connections.
    "${compose[@]}" exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
  fi

  local site
  site="$(env_value SITE_ADDRESS)"
  [[ -n "$site" ]] || die "SITE_ADDRESS is not set in .env"

  echo "==> checking https://$site"
  local attempt
  for attempt in $(seq 1 30); do
    if curl -fsS --max-time 10 -o /dev/null "https://$site/"; then
      docker image prune -f >/dev/null
      echo "==> $environment live: https://$site ($(git rev-parse --short HEAD))"
      return 0
    fi
    sleep 4
  done
  "${compose[@]}" ps
  die "https://$site did not answer after the deploy"
}

main "$@"
