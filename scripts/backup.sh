#!/bin/sh
set -eu
umask 077
: "${DATABASE_URL_UNPOOLED:?Set the direct database URL}"
: "${1:?Usage: scripts/backup.sh NEW_BACKUP_FILE}"
[ ! -e "$1" ] || { echo 'Refusing to overwrite existing backup' >&2; exit 1; }
part=$(mktemp "${1}.XXXXXX")
trap 'rm -f "$part"' EXIT HUP INT TERM
PGDATABASE="$DATABASE_URL_UNPOOLED" pg_dump --format=custom --no-owner --no-acl --file="$part"
pg_restore --list "$part" >/dev/null
mv "$part" "$1"
echo 'Backup created. Verify restoration on a separate empty database.'
