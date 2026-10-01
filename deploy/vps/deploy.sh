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

compose=()

die() { echo "deploy: $*" >&2; exit 1; }

# Last value of KEY in .env, or nothing. Values are never shell-evaluated.
env_value() { sed -n "s/^$1=//p" "$APP_DIR/.env" | tail -1; }

# One address per role, each serving production. The same names are in
# nginx/templates/10-edge.conf.template and frontend/src/app-shell/hostRole.ts.
ROLE_HOSTS=(dispatcher loader driver store admin auditor)

# Keeps one certificate for SITE, www.SITE, preview.SITE and the role addresses.
# A name joins the request once it resolves, so a DNS record added later is
# picked up by the next deploy, and a name already on the certificate is never
# dropped. Until the first request succeeds nginx serves a self-signed
# placeholder, which is enough to answer the HTTP-01 challenge on port 80.
ensure_certificate() {
  local site="$1" name role
  local live="/etc/letsencrypt/live/$site/fullchain.pem"
  local names=("$site" "www.$site" "preview.$site") request=() added=()
  for role in "${ROLE_HOSTS[@]}"; do names+=("$role.$site"); done

  for name in "${names[@]}"; do
    if "${compose[@]}" exec -T nginx sh -c \
        'test -f "$1" && openssl x509 -in "$1" -noout -checkhost "$2" | grep -q "does match"' \
        sh "$live" "$name"; then
      request+=("$name")
    elif getent hosts "$name" >/dev/null; then
      request+=("$name")
      added+=("$name")
    else
      echo "deploy: $name does not resolve; it joins the certificate on the first deploy after it does." >&2
    fi
  done
  [[ ${#added[@]} -gt 0 ]] || return 0

  echo "==> requesting a certificate for ${request[*]}"
  if "${compose[@]}" run --rm --no-deps -T --entrypoint certbot certbot certonly \
      --webroot -w /var/www/certbot --cert-name "$site" "${request[@]/#/--domain=}" \
      --key-type ecdsa --non-interactive --agree-tos --register-unsafely-without-email \
      --renew-with-new-domains; then
    "${compose[@]}" exec -T nginx sh -c \
      '/docker-entrypoint.d/25-tls-certificate.sh && nginx -t && nginx -s reload'
  else
    echo "deploy: no certificate issued for ${added[*]}; check that each points at this server." >&2
    echo "deploy: nginx keeps serving its current certificate until the next deploy." >&2
  fi
}

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

  compose=(docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml)
  # nginx runs once, with production, and fronts both environments.
  [[ "$environment" == production ]] && compose+=(--profile edge)

  local site
  site="$(env_value SITE_ADDRESS)"
  [[ -n "$site" ]] || die "SITE_ADDRESS is not set in .env"

  echo "==> deploying $environment: $(git log -1 --format='%h %s')"

  docker network inspect waypoint-edge >/dev/null 2>&1 || docker network create waypoint-edge >/dev/null

  # Build first: the running stack keeps serving until the images are ready.
  "${compose[@]}" build --quiet

  if [[ "$environment" == production ]]; then
    # Test the new proxy configuration in a throwaway container before the
    # running one is replaced. A typo stops the deploy here, site still up.
    "${compose[@]}" run --rm --no-deps -T nginx nginx -t \
      || die "the nginx configuration does not test clean; nothing was replaced"
    # The proxy goes first and is back within seconds, so the public site does
    # not wait on the database step below.
    "${compose[@]}" up -d --no-deps --remove-orphans --wait nginx certbot
    ensure_certificate "$site"
  fi

  # `init` migrates and imports before the backend is replaced, and --wait holds
  # until the backend reports ready and the frontend answers. A failed migration
  # stops here with the previous containers still running.
  "${compose[@]}" up -d --remove-orphans --wait --wait-timeout 600

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
