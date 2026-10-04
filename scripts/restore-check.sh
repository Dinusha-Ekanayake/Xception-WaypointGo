#!/bin/sh
set -eu
: "${RESTORE_TEST_DATABASE_URL:?Set a separate EMPTY restore-test database}"
: "${DATABASE_URL_UNPOOLED:?Set the source URL for the safety check}"
: "${1:?Usage: scripts/restore-check.sh BACKUP_FILE}"
[ "$RESTORE_TEST_DATABASE_URL" != "$DATABASE_URL_UNPOOLED" ] && [ "$RESTORE_TEST_DATABASE_URL" != "${DATABASE_URL:-}" ] || { echo 'Refusing to restore into source database' >&2; exit 1; }
count=$(PGDATABASE="$RESTORE_TEST_DATABASE_URL" psql -XAt -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema')")
[ "$count" = 0 ] || { echo 'Restore target must be empty' >&2; exit 1; }
PGDATABASE="$RESTORE_TEST_DATABASE_URL" pg_restore --dbname='' --single-transaction --exit-on-error --no-owner --no-acl "$1"
PGDATABASE="$RESTORE_TEST_DATABASE_URL" psql -X -v ON_ERROR_STOP=1 -c 'SELECT count(*) AS users FROM iam.users; SELECT count(*) AS reference_versions FROM ref.reference_versions; SELECT count(*) AS audit_rows FROM integration.audit_log;'
echo 'Restore completed on the test database; exercise the app against it before recording recovery approval.'
