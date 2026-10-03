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

# One address per role for production, and the same with -preview for preview:
# loader.SITE and loader-preview.SITE. The same names are in
# nginx/templates/10-edge.conf.template and frontend/src/app-shell/hostRole.ts.
ROLE_HOSTS=(dispatcher loader driver store admin auditor)

# Requests one certificate for SITE, www.SITE, preview.SITE and the role
# addresses of both environments, the first time and whenever the one on disk is
# missing a name. Until it succeeds nginx keeps serving what it has (a
# self-signed placeholder at first), which is enough to answer the HTTP-01
# challenge on port 80.
ensure_certificate() {
  local site="$1" name role missing=0
  local live="/etc/letsencrypt/live/$site/fullchain.pem"
  local names=("$site" "www.$site" "preview.$site" "grafana-preview.$site")
  for role in "${ROLE_HOSTS[@]}"; do names+=("$role.$site" "$role-preview.$site"); done

  for name in "${names[@]}"; do
    "${compose[@]}" exec -T nginx sh -c \
      'test -f "$1" && openssl x509 -in "$1" -noout -checkhost "$2" | grep -q "does match"' \
      sh "$live" "$name" || missing=1
  done
  [[ "$missing" == 1 ]] || return 0

  echo "==> requesting a certificate for ${names[*]}"
  if "${compose[@]}" run --rm --no-deps -T --entrypoint certbot certbot certonly \
      --webroot -w /var/www/certbot --cert-name "$site" "${names[@]/#/--domain=}" \
      --key-type ecdsa --non-interactive --agree-tos --register-unsafely-without-email \
      --renew-with-new-domains; then
    "${compose[@]}" exec -T nginx sh -c \
      '/docker-entrypoint.d/25-tls-certificate.sh && nginx -t && nginx -s reload'
  else
    echo "deploy: no certificate issued; check that every name under $site resolves to this server." >&2
    echo "deploy: nginx keeps serving its current certificate until the next deploy." >&2
  fi
}

# Secrets this checkout needs and can make for itself. The first deploy that
# finds one missing writes it to .env, which is untracked and survives the
# reset; it is never changed afterwards. Hex, so it needs no quoting in a
# connection URL.
#
# APP_DB_PASSWORD is what the backend logs in to PostgreSQL with, as
# waypoint_app; `migrate` sets it on the role, and re-sets it if it is ever
# changed here. PROOF_URL_SECRET signs proof-of-delivery links; without one the
# backend makes a key per process and every link dies at a restart.
ensure_secret() {
  local key="$1" secret
  [[ -n "$(env_value "$key")" ]] && return 0
  secret="$(openssl rand -hex 24)" || die "could not generate $key"
  # A final newline may be missing; never join the new key onto the last line.
  [[ -z "$(tail -c1 "$APP_DIR/.env")" ]] || echo >> "$APP_DIR/.env"
  echo "$key=$secret" >> "$APP_DIR/.env" \
    || die "could not write $key to $APP_DIR/.env; nothing was replaced"
  echo "==> generated $key in .env"
}

# How many dumps backup_database keeps for each environment.
BACKUPS_KEPT=14

# Dumps the database as it stands, before `init` can change it, so a migration
# that goes wrong can be undone by hand (docs/deployment.md, Backup and
# recovery). Migrations are forward-only: this is the only way back. The dump is
# read back before it counts, and a deploy that cannot take one stops with
# nothing replaced. Dumps sit beside the checkouts, readable by `deploy` only,
# and they are on this server: they survive a bad migration, not a lost disk.
backup_database() {
  local environment="$1" running dir file old
  running="$("${compose[@]}" ps --status running --services)" \
    || die "could not list the running services; nothing was replaced"
  if ! grep -qx db <<< "$running"; then
    echo "==> no database is running yet; nothing to back up"
    return 0
  fi

  dir="$(dirname "$APP_DIR")/backups/$environment"
  file="$dir/$(date -u +%Y%m%dT%H%M%SZ)-before-$(git rev-parse --short HEAD).dump"
  (umask 077 && mkdir -p "$dir") || die "could not create $dir; nothing was replaced"

  echo "==> backing up the database to $file"
  if ! (umask 077 && "${compose[@]}" exec -T db pg_dump -U waypoint -d waypoint --format=custom > "$file.part") \
      || ! "${compose[@]}" exec -T db pg_restore --list < "$file.part" > /dev/null; then
    rm -f "$file.part"
    die "the database backup failed; nothing was replaced"
  fi
  mv "$file.part" "$file"

  # Names begin with a UTC timestamp, so name order is age order.
  find "$dir" -maxdepth 1 -type f -name '*.dump' | sort -r | tail -n +"$((BACKUPS_KEPT + 1))" \
    | while IFS= read -r old; do rm -f "$old"; done
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

  # The trained model files are in Git LFS (issue #16). Without git-lfs the
  # checkout holds pointer files and the model service refuses to start, so stop
  # here, before anything running is replaced.
  command -v git-lfs >/dev/null 2>&1 \
    || die "git-lfs is not installed; run 'apt-get install git-lfs' once (docs/deployment.md)"
  git lfs pull --include="ml-server/models/*" || die "git lfs pull failed"

  compose=(docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml)
  # nginx runs once, with production, and fronts both environments.
  [[ "$environment" == production ]] && compose+=(--profile edge)

  # The log store (Loki, Alloy, Grafana) runs in preview only. Grafana listens
  # on 127.0.0.1 of the server, so it is reached through an SSH tunnel. Without
  # GRAFANA_ADMIN_PASSWORD it is skipped, never started with the default one.
  # ml serves the trained models (issue #16); the backend does not wait for it.
  services=(db backend mcp ml waypoint)
  if [[ "$environment" == preview ]]; then
    if [[ -n "$(env_value GRAFANA_ADMIN_PASSWORD)" ]]; then
      compose+=(--profile observability)
      services+=(loki alloy grafana)
    else
      echo "deploy: GRAFANA_ADMIN_PASSWORD is not set in .env; the log store is not started." >&2
    fi
  fi

  local site
  site="$(env_value SITE_ADDRESS)"
  [[ -n "$site" ]] || die "SITE_ADDRESS is not set in .env"

  echo "==> deploying $environment: $(git log -1 --format='%h %s')"

  ensure_secret APP_DB_PASSWORD
  ensure_secret PROOF_URL_SECRET

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

  backup_database "$environment"

  # `init` migrates and imports in a container of its own while the running
  # stack keeps serving. A failed migration stops here with nothing replaced.
  "${compose[@]}" run --rm -T init

  # Only now are the changed containers replaced. init has just run, so it is
  # left out, and --wait holds until the backend reports ready and the frontend
  # answers.
  "${compose[@]}" up -d --no-deps --remove-orphans --wait --wait-timeout 600 "${services[@]}"

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
