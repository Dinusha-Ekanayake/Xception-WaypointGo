# Deployment

The frontend proxies `/api/*` to Spring using `BACKEND_URL` at runtime. PostgreSQL credentials belong only to Spring. Builds and normal requests never migrate or seed.

## Competition / local demo

```sh
cp .env.example .env
# Set POSTGRES_PASSWORD and a private SEED_PASSWORD (at least 12 characters).
docker compose up --build -d
```

The `init` service applies migrations and seeds the demo before the backend starts. Existing records are preserved. PostgreSQL persists in `waypoint-postgres`; never use `down -v` to redeploy. Demo mode uses the supplied historical calendar and February 2026 fixtures.

## Production host

Configure DOMAIN, DATABASE_URL and COOKIE_SECURE=1. `compose.prod.yaml` forces real-time mode and never seeds demo accounts or orders. Create real staff accounts using the commands below. The image includes the tracked reference CSVs, migrations and demo proof fixtures; replace synthetic business reference data with validated production records before real operations.

```sh
docker compose -f compose.prod.yaml build
docker compose -f compose.prod.yaml run --rm backend java -jar /app/backend.jar migrate
docker compose -f compose.prod.yaml up -d nginx backend frontend
docker compose -f compose.prod.yaml run --rm --entrypoint certbot certbot \
  certonly --webroot -w /var/www/certbot \
  --email "$CERTBOT_EMAIL" --agree-tos --no-eff-email -d "$DOMAIN"
docker compose -f compose.prod.yaml restart nginx
docker compose -f compose.prod.yaml --profile renewal up -d certbot
```

Export DOMAIN and CERTBOT_EMAIL in the shell for the issuance command. Nginx validates and reloads its configuration every 12 hours to pick up renewed certificates. Check HTTPS `/api/health` and sign in after deployment. Take a database backup before migrations and verify a restore on a separate database. Monitor HTTP failures, database availability, disk space and unsynchronized device work.

Production uses Monday-Saturday operating days for a rolling calendar generated at startup (one year back, two years forward). Historical supplied dates retain their original flags. Set `CALENDAR_FILE` to a CSV with `date,is_operating` (`0` or `1`) to override closures and special operating dates; paths are relative to DATA_DIR or absolute inside the container. Mount that file read-only into the backend and restart after changes. This policy does not invent public-holiday closures; operations must supply them.

## Vercel frontend

Deploy `frontend/` and set BACKEND_URL to the separately hosted Spring service. Database settings on Vercel do not configure Spring. Use HTTPS for the backend link and COOKIE_SECURE=1 on Spring. The browser continues to call the frontend's same-origin `/api` routes. The earlier all-Node Vercel deployment instructions no longer apply.

## Verification

Use a dedicated TEST_DATABASE_URL that differs from DATABASE_URL:

```sh
cd frontend
npm ci
npm run verify
```

This runs legacy Node regressions, Maven tests, Spring HTTP integration tests, TypeScript checks, the production build and browser tests. The browser runner starts its own Spring backend against a disposable schema, applies Java migrations and seeds through Java. It never connects the tests to an existing backend. Chromium must be installed (`npx playwright install chromium`).

Docker configuration can be checked with `docker compose config --quiet`. Actual fresh-volume container startup, public TLS and physical-device trials remain release checks; local browser emulation does not establish those properties.

## Operational limits

Bounded history synchronization, calibrated real-world travel estimates and a managed backup/monitoring setup remain production rollout work. Current state snapshots include operational history and proof bytes remain in PostgreSQL. Run a measured fleet pilot before general rollout.

## Staff accounts

After migrations, create an individual account for each staff member. These commands require trusted access to the deployment host; no public account-administration endpoint is exposed. Passwords are prompted without echoing. ACCOUNT_OPERATOR identifies the operator in the audit trail (defaults to the host user).

```sh
scripts/account.sh create dispatcher@example.com dispatcher all
scripts/account.sh create loader@example.com loader Peliyagoda
scripts/account.sh create driver@example.com driver VEH001
scripts/account.sh create store@example.com store OUT001
scripts/account.sh update driver@example.com driver VEH002
scripts/account.sh password store@example.com
scripts/account.sh disable driver@example.com
scripts/account.sh enable driver@example.com
```

Use actual reference IDs. Assignment changes, password resets and disable/enable revoke existing sessions. Disabled users cannot sign in. The final enabled dispatcher cannot be disabled or reassigned to another role. Synchronize field work before planned assignment changes.

Without Docker, export ACCOUNT_ID, ACCOUNT_OPERATOR, ACCOUNT_ROLE, ACCOUNT_SCOPE and (only for create/password) ACCOUNT_PASSWORD, then run `java -jar backend.jar account-create` or the corresponding `account-*` command. CLI commands do not open an HTTP port. Never pass passwords as command-line arguments or commit them.

## Backup and recovery

Install PostgreSQL client tools matching the server major version. Export DATABASE_URL_UNPOOLED securely, then:

```sh
scripts/backup.sh /secure-backups/waypoint-2026-09-26.dump
# Set RESTORE_TEST_DATABASE_URL to a separate empty database first.
scripts/restore-check.sh /secure-backups/waypoint-2026-09-26.dump
```

The backup script uses private file permissions and refuses to overwrite a backup. Restore checking refuses known source URLs and a nonempty target; it does not delete existing tables. Keep encrypted off-host copies, schedule daily backups, and record recovery time after exercising restored orders, accounts and proof images. These scripts are provided but have not been restore-tested against your hosting provider.

Configure an external monitor for HTTPS `/api/health`, alert on failures, and retain backend/nginx error logs. The endpoint must return HTTP 200 with `ok: true`; a healthy process alone does not verify the entire role workflow.
