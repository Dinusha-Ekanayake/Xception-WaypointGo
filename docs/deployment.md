# Deployment

The frontend proxies `/api/*` to Spring using `BACKEND_URL` at runtime. PostgreSQL credentials belong only to Spring. Builds and normal requests never migrate or seed.

## Environment and connection ownership

Run Compose commands from the repository root. Root `.env` supplies Compose substitutions; it is not automatically loaded by Spring when Maven or Java runs directly. For a local frontend, create `frontend/.env.local` containing `BACKEND_URL=http://127.0.0.1:8080`. Export Spring settings in its terminal or configure them on its hosting service.

Spring uses `DATABASE_URL` for every Java command, including `migrate`. To use a direct connection for a Java migration, supply that URL as `DATABASE_URL` for the command. `DATABASE_URL_UNPOOLED` is consumed by the backup scripts and legacy Node migration script only. Keep secrets out of frontend configuration except the non-public backend address.

## Competition / local demo

```sh
cp .env.example .env
# Set POSTGRES_PASSWORD and a private SEED_PASSWORD (at least 12 characters).
docker compose up --build -d
```

The `init` service applies migrations and seeds the demo before the backend starts. Existing records are preserved. PostgreSQL persists in `waypoint-postgres`; never use `down -v` to redeploy. Demo mode uses the supplied historical calendar and February 2026 fixtures. Open http://localhost:3000 and follow the [judge walkthrough](../README.md#judge-walkthrough). Local Compose binds PostgreSQL to host port 5432 and Spring to `BACKEND_PORT` (default 8080); ensure those ports are available.

For a fresh-install check, use a separate Compose project name with free host ports and a new volume; do not reset an existing deployment volume. A successful configuration check does not prove images build or services initialize.

## Judge deployment on the VPS

The competition instance runs the same `compose.yaml` a judge runs, with one overlay, [deploy/vps/compose.vps.yaml](../deploy/vps/compose.vps.yaml): the frontend stops publishing a host port and Caddy becomes the only public listener, obtaining and renewing its own certificate. PostgreSQL and the backend stay on `127.0.0.1`. Docker publishes ports ahead of `ufw`, so never add a published port to the overlay expecting the firewall to cover it.

**Pipeline.** [.github/workflows/ci-deploy.yml](../.github/workflows/ci-deploy.yml) runs the backend tests against a PostgreSQL service and the frontend typecheck, boundary test and build on every pull request into `main`. A push to `main` runs the same checks and then deploys; a failed check means no deploy. The deploy job opens one SSH connection as `deploy`. That key is bound on the server to [deploy/vps/deploy.sh](../deploy/vps/deploy.sh) by a forced command, so the workflow cannot choose what runs or which commit ships: the script always resets the checkout to `origin/main`, builds, runs `init`, replaces the containers and checks HTTPS and backend readiness. There is no automatic rollback; revert the commit on `main` and the pipeline redeploys.

**Host.** Ubuntu 24.04. SSH is key-only (`/etc/ssh/sshd_config.d/00-waypoint-hardening.conf`), `ufw` allows 22 (rate limited), 80 and 443, `fail2ban` watches sshd, and security updates install unattended. `root` is for administration; `deploy` owns `/opt/waypoint/app`, has no password and no sudo, but is in the `docker` group, which is root-equivalent on that host.

**Secrets.** `/opt/waypoint/app/.env` on the server, mode 600, untracked: `SITE_ADDRESS`, `POSTGRES_PASSWORD`, `SEED_PASSWORD`, `COOKIE_SECURE=1`, `WAREHOUSE_API_KEY`. GitHub holds `VPS_HOST`, `VPS_USER`, `VPS_SSH_KEY` and `VPS_KNOWN_HOSTS`. The server reads the repository with a read-only deploy key.

```sh
# Deploy by hand, as root on the server
sudo -u deploy /opt/waypoint/app/deploy/vps/deploy.sh
# Logs and state
cd /opt/waypoint/app && docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml ps
cd /opt/waypoint/app && docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml logs -f backend
```

`SITE_ADDRESS` is `62-171-128-70.sslip.io`, a wildcard DNS name for the server's address, because HTTPS needs a hostname and service workers and Secure cookies need HTTPS. To move to a real domain, point its A record at the server, change `SITE_ADDRESS` and deploy. `SEED_PASSWORD` only applies when an account is first created; changing it later does not rotate the six demo accounts.

## Production host

Configure `DOMAIN` and `DATABASE_URL` in root `.env`; production Compose sets `COOKIE_SECURE=1` on Spring automatically. `compose.prod.yaml` forces real-time mode and never seeds demo accounts or orders. Create real staff accounts using the commands below. The image includes the tracked reference CSVs, migrations and demo proof fixtures; replace synthetic business reference data with validated production records before real operations.

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

For a judge deployment, initialize a separate Spring/PostgreSQL instance with `DEMO_MODE=1`, the documented historical clock and a private seed password. Run Java migrations and seed explicitly before serving requests. `compose.prod.yaml` forces `DEMO_MODE=0` and does not provide this seeded competition experience without a deliberate configuration change. Verify all four accounts through the frontend URL after hosting; configuring Vercel or Neon alone does not verify the deployment.

## Verification

Create the dedicated test database first, using a PostgreSQL administrator or hosting console. `TEST_DATABASE_URL` must point to that separate database and differ from `DATABASE_URL`; changing only the URL spelling is not isolation. The test account needs permission to create/drop schemas. Test helpers create schemas, not the database itself.

For a local PostgreSQL server, an example is `createdb -h 127.0.0.1 -U waypoint waypoint_test` (requires database-creation permission). Supply credentials securely, then export the matching `TEST_DATABASE_URL` and run:

```sh
cd frontend
npm ci
npx playwright install chromium
npm run verify
```

This runs legacy Node regressions, Maven tests, Spring HTTP integration tests, TypeScript checks, the production build and browser tests. The browser runner starts its own Spring backend against a disposable schema, applies Java migrations and seeds through Java. It never connects the tests to an existing backend. Chromium must be installed (`npx playwright install chromium`).

If tests report `database "waypoint_test" does not exist`, create the dedicated database or correct its URL; never substitute the application database.

Docker configuration can be checked with `docker compose config --quiet` and `docker compose -f compose.prod.yaml config --quiet` after supplying the required variables. Actual fresh-volume container startup, public TLS and physical-device trials remain release checks; local browser emulation does not establish those properties.

## Operational limits

Bounded history synchronization, calibrated real-world travel estimates and a managed backup/monitoring setup remain production rollout work. Visible clients refresh every ten seconds; state retrieval scans all plans/events before filtering, so response and database costs grow with retained history. Proof bytes remain in PostgreSQL and are fetched separately from state snapshots. Run a measured fleet pilot before general rollout.

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
