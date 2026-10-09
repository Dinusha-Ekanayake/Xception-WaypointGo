#!/usr/bin/env bash
# Daily backup of one environment's database, copied off this server.
#
# Every deploy already dumps the database before it migrates (deploy.sh), but
# those dumps sit on this server's disk: they undo a bad migration, not a lost
# disk. This script takes a daily dump the same way, reads it back before it
# counts, and copies it to a second provider with rclone (roadmap H-05).
#
# Run as `deploy` from cron, one line per environment (docs/deployment.md,
# "Daily off-server backup"):
#   15 2 * * * /opt/waypoint/app/deploy/vps/backup.sh production
#
# The off-server target is an rclone remote configured once by an operator
# (`rclone config`, as `deploy`); its credentials live in rclone's own config,
# never in this repository. BACKUP_REMOTE names it, for example
# `offsite:waypoint-backups`. With BACKUP_REMOTE unset the dump is kept locally
# and the run fails, so a missing copy is noticed rather than assumed.
#
# Proofs, issue photos and voice notes are stored in the database by default
# (PROOF_STORE=database), so the dump covers them. When media moves to an
# object store (roadmap M-01) it gets its own copy.
set -euo pipefail

environment="${1:-}"
[[ "$environment" == production || "$environment" == preview ]] \
  || { echo "usage: $0 production|preview" >&2; exit 2; }

base=/opt/waypoint
case "$environment" in
  production) app_dir="$base/app" ;;
  preview) app_dir="$base/preview" ;;
esac
dir="$base/backups/daily-$environment"
kept_local="${BACKUPS_KEPT_LOCAL:-7}"
kept_days_remote="${BACKUPS_KEPT_DAYS_REMOTE:-30}"
heartbeat="$dir/last-success"

die() { echo "backup $environment: $*" >&2; exit 1; }

cd "$app_dir" || die "no checkout at $app_dir"
compose=(docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml)

(umask 077 && mkdir -p "$dir") || die "could not create $dir"
file="$dir/$(date -u +%Y%m%dT%H%M%SZ)-daily-$(git rev-parse --short HEAD).dump"

echo "==> dumping the $environment database to $file"
if ! (umask 077 && "${compose[@]}" exec -T db pg_dump -U waypoint -d waypoint --format=custom > "$file.part") \
    || ! "${compose[@]}" exec -T db pg_restore --list < "$file.part" > /dev/null; then
  rm -f "$file.part"
  die "the dump failed or could not be read back"
fi
mv "$file.part" "$file"
sha256sum "$file" > "$file.sha256"

# Names begin with a UTC timestamp, so name order is age order.
find "$dir" -maxdepth 1 -type f -name '*.dump' | sort -r | tail -n +"$((kept_local + 1))" \
  | while IFS= read -r old; do rm -f "$old" "$old.sha256"; done

[[ -n "${BACKUP_REMOTE:-}" ]] || die "BACKUP_REMOTE is not set; the dump is only on this server"
command -v rclone > /dev/null || die "rclone is not installed; the dump is only on this server"

target="$BACKUP_REMOTE/$environment"
echo "==> copying to $target"
rclone copy --immutable "$file" "$target" || die "the off-server copy failed"
rclone copy --immutable "$file.sha256" "$target" || die "the checksum copy failed"
# The copy counts only when the remote holds the same bytes.
rclone check --one-way --download --include "$(basename "$file")" "$dir" "$target" \
  || die "the off-server copy does not match the local dump"

rclone delete --min-age "${kept_days_remote}d" "$target" || echo "backup $environment: pruning old remote copies failed" >&2

date -u +%Y-%m-%dT%H:%M:%SZ > "$heartbeat"
echo "==> done: $(basename "$file") is on this server and at $target"
