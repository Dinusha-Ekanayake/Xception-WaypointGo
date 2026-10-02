# Deployment

The frontend proxies `/api/*` to Spring using `BACKEND_URL` at runtime. PostgreSQL credentials belong only to Spring. Builds and normal requests never migrate or import.

## Environment and connection ownership

Run Compose commands from the repository root. Root `.env` supplies Compose substitutions; it is not automatically loaded by Spring when Maven or Java runs directly. For a local frontend, create `frontend/.env.local` containing `BACKEND_URL=http://127.0.0.1:8080`. Export Spring settings in its terminal or configure them on its hosting service.

Spring uses `DATABASE_URL` for every Java command, including `migrate`. To use a direct connection for a Java migration, supply that URL as `DATABASE_URL` for the command. `DATABASE_URL_UNPOOLED` is consumed by the backup scripts only. Keep secrets out of frontend configuration except the non-public backend address.

## Competition / local demo

```sh
cp .env.example .env
# Set POSTGRES_PASSWORD and a private ADMIN_PASSWORD (at least 12 characters).
docker compose up --build -d
```

The `init` service runs `migrate`, `import-reference` and `account-create` (the administrator from `ADMIN_EMAIL`/`ADMIN_PASSWORD`) before the backend starts. Each is idempotent, so restarts preserve existing records and never reset the administrator's password. PostgreSQL persists in `waypoint-postgres`; never use `down -v` to redeploy. Open http://localhost:3000 and sign in as the administrator. Local Compose binds PostgreSQL to host port `DB_PORT` (default 5432) and Spring to `BACKEND_PORT` (default 8080); ensure those ports are available.

For a fresh-install check, use a separate Compose project name with free host ports and a new volume; do not reset an existing deployment volume. A successful configuration check does not prove images build or services initialize.

## Judge deployment on the VPS

The server runs two environments from the same `compose.yaml` a judge runs, with one overlay, [deploy/vps/compose.vps.yaml](../deploy/vps/compose.vps.yaml):

| Environment | Branch | URL | Checkout | Compose project |
| --- | --- | --- | --- | --- |
| Production | `main` | `https://waypointgo.live` | `/opt/waypoint/app` | `app` |
| Preview | `dev` | `https://preview.waypointgo.live` | `/opt/waypoint/preview` | `preview` |

Each has its own database volume, accounts and `.env`. The overlay removes every published host port, so the two stacks do not collide and nothing but nginx is reachable from outside. Docker publishes ports ahead of `ufw`, so never add a published port to the overlay expecting the firewall to cover it. nginx runs once, in the production stack, and reaches each frontend over the shared `waypoint-edge` network by an alias named after its environment. Its configuration is baked into an image built from [deploy/vps/nginx/](../deploy/vps/nginx/), so a change there takes effect on a production deploy, not a preview one. The deploy tests the new configuration in a throwaway container before replacing the running one.

**Edge.** nginx terminates TLS (1.2 and 1.3 only), redirects HTTP, and answers only for the two hostnames; the bare IP and unknown names get no response and no certificate. It rate-limits per visitor address: 10 sign-in attempts a minute (POSTs to `/api/session`, on top of the backend's per-account throttle), 30 API requests a second, 50 page requests a second, 60 connections. It rejects bodies over 4 MB, methods outside the usual seven and any dotfile path, and sets HSTS, `nosniff`, frame denial, a referrer policy and a permissions policy; the same headers from the application are dropped so they are decided in one place. There is no Content-Security-Policy yet: Next.js needs inline scripts, and a wrong policy breaks the app.

**Certificates.** Let's Encrypt over HTTP-01, one certificate for `SITE_ADDRESS`, `www.SITE_ADDRESS`, `preview.SITE_ADDRESS` and the role addresses of both environments. The production deploy requests it when none exists for the current `SITE_ADDRESS` or the one on disk is missing a name; until then nginx serves a self-signed placeholder, which is enough to answer the challenge. The `certbot` service renews through the webroot and nginx reloads every twelve hours. Every name must resolve to the server before the request, which the wildcard record below takes care of, and five failed requests an hour is Let's Encrypt's limit.

**Cloudflare.** Create two proxied A records, one for the hostname and one wildcard (`*`), and set SSL/TLS mode to **Full (strict)**. Flexible sends plain HTTP to the server, which redirects it, and the visitor loops. nginx trusts `CF-Connecting-IP` only from Cloudflare's published ranges ([conf.d/00-cloudflare.conf](../deploy/vps/nginx/conf.d/00-cloudflare.conf)), so limits and logs see the visitor; from anyone else the header is ignored. Once the records are proxied, set `CLOUDFLARE_ONLY=1` in the production `.env` and deploy: nginx then drops any request that did not come through Cloudflare, so the server's address cannot be used to go around it. The wildcard sends every name under the domain to the server; nginx answers only the ones it lists and drops the rest, so a new address is a change to nginx and the certificate, not to DNS. Leave it `0` while a record is DNS-only, or that hostname is unreachable.

**Pipeline.** [checks.yml](../.github/workflows/checks.yml) runs the backend tests against a PostgreSQL service, the official Task 2B validator on the planning engine's peak-day output, and the frontend typecheck, boundary test and build on every pull request into `main` or `dev`. A push to `dev` runs the same checks and then deploys the preview ([preview-deploy.yml](../.github/workflows/preview-deploy.yml)); a push to `main` does the same for production ([ci-deploy.yml](../.github/workflows/ci-deploy.yml)). A failed check means no deploy. So the order is: merge into `dev`, look at the preview, then merge `dev` into `main`.

Each deploy job opens one SSH connection as `deploy`. Each environment has its own key, bound on the server to that checkout's [deploy/vps/deploy.sh](../deploy/vps/deploy.sh) by a forced command, so a workflow cannot choose what runs or which commit ships and the preview key cannot deploy production. The script resets the checkout to the tip of its branch, builds, dumps the database, runs `init`, replaces the containers and checks the public URL. The dump is taken before `init` can migrate and is read back before it counts; a deploy that cannot take one stops with nothing replaced. There is no automatic rollback: revert the commit and the pipeline redeploys, and if a migration has to be undone as well, restore the dump (Backup and recovery, below).

**Preview is not production.** It runs unreviewed `dev` code against its own data, with the same demo accounts. Migrations are forward-only and checksummed, so a migration edited after it reached `dev` stops the preview `init`; that is the preview doing its job. Fix it with a new migration, or if the preview data does not matter, reset the preview alone:

```sh
cd /opt/waypoint/preview
docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml down -v   # preview volume only
sudo -u deploy /opt/waypoint/preview/deploy/vps/deploy.sh
```

Never run `down -v` in `/opt/waypoint/app`.

**Host.** Ubuntu 24.04. SSH accepts keys and the shared root password (`/etc/ssh/sshd_config.d/00-waypoint-hardening.conf`); the password stays on so the team can log in, and `fail2ban` (four failures, one hour ban, growing on repeats) and the `ufw` rate limit on 22 are what guard it. `ufw` allows 22, 80 and 443 only, and security updates install unattended. `root` is for administration; `deploy` owns `/opt/waypoint`, has no password and no sudo, but is in the `docker` group, which is root-equivalent on that host.

**Role addresses.** `dispatcher.`, `loader.`, `driver.`, `store.`, `admin.` and `auditor.` in front of `SITE_ADDRESS` serve production, each showing one role: sign-in opens that role's workspace with no role switcher, and an account that does not hold the role is pointed at its own address ([hostRole.ts](../frontend/src/app-shell/hostRole.ts)). The address chooses the screen only; what an account may do is still decided by the server. Each address is its own origin, so it has its own session and its own offline queue. Preview has the same six with `-preview` in the first label, such as `loader-preview.waypointgo.live`, served by the preview stack; the dash is there because Cloudflare's universal certificate does not cover a name two levels below the domain. nginx is deployed with production, so a new hostname merged to `dev` answers only after the proxy itself is next deployed. The address every role shares, the bare name on production and `preview.` on preview, has no sign-in or workspace of its own: it shows what Waypoint is and four buttons, one per field role, each opening that role's own address ([RoleLanding.tsx](../frontend/src/app-shell/RoleLanding.tsx)). Administrators and auditors open their address directly. The preview name is recognised by itself; a bare name is a landing only in a build made with `NEXT_PUBLIC_ROLE_ADDRESSES=1`, which the VPS overlay sets, so local development and the plain `docker compose up` path keep every role and the switcher on one address. A session or an offline queue left on the bare production name from before this change stays in that browser and is no longer reachable from it.

**Secrets.** Each checkout has an untracked `.env`, mode 600: `SITE_ADDRESS`, `POSTGRES_PASSWORD`, `APP_DB_PASSWORD`, `PROOF_URL_SECRET`, `SEED_PASSWORD`, `WAREHOUSE_API_KEY`, `DEPLOY_ENV=preview` in the preview one, and `CLOUDFLARE_ONLY` in the production one. `APP_DB_PASSWORD` and `PROOF_URL_SECRET` are written there by the first deploy that finds them missing and are never changed afterwards. The session cookie is always Secure here; the overlay sets `COOKIE_SECURE=1` whatever `.env` says. Read-only MCP connections ([walkthrough](issues/087-readonly-mcp/WALKTHROUGH.md)) are off unless that checkout's `.env` has `MCP_ENABLED=true`; the value is read when the backend container is created, so it takes effect on the next deploy of that environment.

**Database logins.** The backend logs in as `waypoint_app`, which owns nothing, with `APP_DB_PASSWORD`. The schema is owned by `waypoint_migrator`, which is not a superuser. Only the `init` step holds `POSTGRES_PASSWORD`: `migrate` logs in with it on a connection of its own, because it is the only account a new volume has, but applies every migration as `waypoint_migrator`, so a migration cannot do what only a superuser can. The first deploy after issue #5 moves the ownership of every existing table in one transaction; if it cannot get a table's lock within 15 seconds it changes nothing and the deploy is run again. `migrate` also sets `waypoint_app`'s password from `APP_DB_PASSWORD` on every run, so changing the value in `.env` and deploying rotates it. `deploy.sh` generates `APP_DB_PASSWORD` the first time it is missing. GitHub holds `VPS_HOST`, `VPS_USER`, `VPS_KNOWN_HOSTS`, `VPS_SSH_KEY` (production) and `VPS_PREVIEW_SSH_KEY`. The server reads the repository with a read-only deploy key.

```sh
# Deploy by hand, as root on the server
sudo -u deploy /opt/waypoint/app/deploy/vps/deploy.sh
sudo -u deploy /opt/waypoint/preview/deploy/vps/deploy.sh
# Logs and state (same commands in /opt/waypoint/preview)
cd /opt/waypoint/app && docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml ps
cd /opt/waypoint/app && docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml logs -f backend
# The database is no longer on a host port; reach it through the container
cd /opt/waypoint/app && docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml exec db psql -U waypoint waypoint
```

`SITE_ADDRESS` is `waypointgo.live`, the preview is always `preview.` in front of it, and `www.` redirects to the bare name. To move to another domain, point its two A records at the server, change `SITE_ADDRESS` in both `.env` files and deploy production, then preview; the previous hostnames stop answering at once. `SEED_PASSWORD` only applies when an account is first created; changing it later does not rotate the six demo accounts.

## Production host

Not the running deployment. This is the single-host path with `compose.prod.yaml` and the root `nginx/` folder, which predates the VPS setup above. CI keeps the file parsing, but nobody has deployed with it since the VPS went live, so treat the steps as unverified.

Configure `DOMAIN` and `DATABASE_URL` in root `.env`; production Compose sets `COOKIE_SECURE=1` and `LOG_FORMAT=ecs` on Spring automatically. Its `init` service runs `migrate` and `import-reference` before the backend starts; it never creates accounts. Create staff accounts with `scripts/account.sh` (below). The image includes the tracked reference CSVs and migrations; replace synthetic business reference data with validated production records before real operations.

```sh
docker compose -f compose.prod.yaml build
docker compose -f compose.prod.yaml up -d nginx backend frontend   # init runs first
docker compose -f compose.prod.yaml run --rm --entrypoint certbot certbot \
  certonly --webroot -w /var/www/certbot \
  --email "$CERTBOT_EMAIL" --agree-tos --no-eff-email -d "$DOMAIN"
docker compose -f compose.prod.yaml restart nginx
docker compose -f compose.prod.yaml --profile renewal up -d certbot
```

Export DOMAIN and CERTBOT_EMAIL in the shell for the issuance command. Nginx validates and reloads its configuration every 12 hours to pick up renewed certificates. Check `/health/readiness` on the backend and sign in after deployment. Take a database backup before migrations and verify a restore on a separate database. Monitor HTTP failures, database availability, disk space and unsynchronized device work.

Past the end of the supplied calendar, operating days come from the extension policy (Monday to Saturday, R-CAL-03). Closures and special operating days are set by an administrator with the `calendar:Override` command, recorded with an actor and a reason; there is no calendar file. The policy does not invent public-holiday closures; operations must supply them. `waypoint_reference_calendar_days_remaining` warns before the supplied calendar runs out.

## Vercel frontend

Not in use. Kept as the outline of a split deployment; it has not been tried against the current build.

Deploy `frontend/` and set BACKEND_URL to the separately hosted Spring service. Database settings on Vercel do not configure Spring. Use HTTPS for the backend link and COOKIE_SECURE=1 on Spring. The browser continues to call the frontend's same-origin `/api` routes. The earlier all-Node Vercel deployment instructions no longer apply.

For a judge deployment, run `migrate`, `import-reference` and `account-create` explicitly against the hosted Spring/PostgreSQL instance before serving requests, then verify sign-in through the frontend URL; configuring Vercel or Neon alone does not verify the deployment.

## Verification

Create the dedicated test database first, using a PostgreSQL administrator or hosting console. `TEST_DATABASE_URL` must point to that separate database and differ from `DATABASE_URL`; changing only the URL spelling is not isolation. The test account needs permission to create/drop schemas. Test helpers create schemas, not the database itself.

For a local PostgreSQL server, an example is `createdb -h 127.0.0.1 -U waypoint waypoint_test` (requires database-creation permission). Supply credentials securely, then export the matching `TEST_DATABASE_URL` and run:

```sh
cd frontend
npm ci
npx playwright install chromium
npm run verify
```

This runs the frontend boundary tests, TypeScript checks, the production build and `mvn verify` (unit, architecture and integration tests). Browser smoke tests are separate: `npm run test:e2e` after a build.

If tests report `database "waypoint_test" does not exist`, create the dedicated database or correct its URL; never substitute the application database.

Docker configuration can be checked with `docker compose config --quiet` and `docker compose -f compose.prod.yaml config --quiet` after supplying the required variables. Actual fresh-volume container startup, public TLS and physical-device trials remain release checks; local browser emulation does not establish those properties.

## Logs

The backend logs ECS JSON (`LOG_FORMAT=ecs` in both compose files). To store and search them, set `GRAFANA_ADMIN_PASSWORD` and enable the opt-in profile:

```sh
docker compose -f compose.prod.yaml --profile observability up -d
```

Alloy reads every container labelled `com.waypoint.logs=true` through the read-only Docker socket and ships to Loki, which keeps 14 days on the `loki-data` volume (budget roughly 1 GB per week at pilot volume). Grafana listens on `127.0.0.1:3001` only and is never routed through nginx; reach it with `ssh -L 3001:127.0.0.1:3001 <host>` and open http://127.0.0.1:3001. Grafana refuses to start without `GRAFANA_ADMIN_PASSWORD`. The application does not depend on the log store: with Loki down, Alloy retries and requests are served normally.

## Operational limits

What a rollout beyond the competition would have to change, as things stand on 2026-10-02. The full list per module is in [STATUS.md](development-docs/STATUS.md).

- **Proofs live in the database.** Photos and signatures are stored in `execution.proof_content` by `DatabaseProofStore` (`PROOF_STORE=database`, the default) and read back through signed five-minute links, so they are in the database backup and every replica sees them (A-33). They count toward the database's storage: watch `waypoint.execution.proof_bytes_held`. A nightly job clears them past `retain_until` (P-14). Files written to `PROOF_DIR` before the switch are still read from there, so keep that volume until they age out. With `PROOF_URL_SECRET` unset the links die at a restart; the VPS deploy writes one to `.env` so they do not.
- **The audit log is partitioned by month and the last partition ends 2027-07-01.** The job that creates partitions ahead is not built (issue #6); after that date every command fails.
- **No retention jobs.** Expired sessions, old login attempts, command receipts and delivered outbox rows are not purged (issue #6).
- **Nobody is told.** The Notification module is not built (issue #14), so a deferral, a shortfall or a dispute reaches a person only when they open the screen that shows it.
- **Travel and service times are the planning allowances,** not observed durations. Every plan is marked as planned without a predictor (issue #16).
- **No automatic rollback, and the only database backups are the ones a deploy takes.** They sit on the server itself, so they undo a bad migration and do not survive a lost disk. Nothing takes one between deploys and nothing copies them off the host. The restore steps under Backup and recovery have not been exercised on the VPS.

Run a measured fleet pilot before general rollout.

## Staff accounts

The first administrator cannot come from an endpoint that requires one, so it is created from the host. On the local Compose path the `init` step does this from `ADMIN_EMAIL` and `ADMIN_PASSWORD`. On the `compose.prod.yaml` path, which creates no accounts:

```sh
scripts/account.sh admin@example.com "Full Name" admin
```

The password is prompted without echo and never passed as an argument. Roles are `admin`, `dispatcher`, `loader`, `driver`, `store_manager` and `auditor`.

Every later change is a command an administrator sends through `POST /api/commands`: create and update an account, reset a password, disable it, grant or revoke a depot or outlet, assign a driver to a vehicle for a period. Disabling an account revokes its sessions in the same transaction, and the last active dispatcher cannot be disabled. There is no admin screen for these yet (issue #22).

Three backend commands cover what a fresh instance needs before anyone can send a command: `account-grant-depot`, `operator-pin` for a loader's PIN on a shared dock device, and `demo-accounts` for one account per role. Their variables are in the [README](../README.md#backend-commands).

## Backup and recovery

### On the VPS

Every deploy dumps the database before `init` runs, into `/opt/waypoint/backups/production/` or `/opt/waypoint/backups/preview/`, readable by `deploy` only. The name is the UTC time and the commit being deployed, `20261002T110505Z-before-21e3db3.dump`, and the newest 14 of each environment are kept. Migrations are forward-only, so this dump is the only way back from one that did damage.

To go back, as root on the server. The damaged database is renamed, never dropped, and the dump carries the database's own grants, which is why it is restored with `--create`:

```sh
cd /opt/waypoint/app
C="docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml"
$C stop backend
$C exec -T db psql -U waypoint -d postgres -c 'ALTER DATABASE waypoint RENAME TO waypoint_damaged'
$C exec -T db pg_restore -U waypoint -d postgres --create --exit-on-error \
  < /opt/waypoint/backups/production/20261002T110505Z-before-21e3db3.dump
# The code must match the schema just restored: deploy the commit that was
# running before, then revert on main so the next push does not migrate again.
sudo -u deploy env DEPLOY_REF=<commit> /opt/waypoint/app/deploy/vps/deploy.sh
```

Everything written after the dump was taken is in `waypoint_damaged` and not in the restored database; drop that database only once nothing is needed from it. Roles are cluster-wide and are not in the dump, so it restores into the cluster it came from, not into an empty one. These steps have not been exercised on the VPS: rehearse them on preview before relying on them.

### Elsewhere

Install PostgreSQL client tools matching the server major version. Export DATABASE_URL_UNPOOLED securely, then:

```sh
scripts/backup.sh /secure-backups/waypoint-2026-09-26.dump
# Set RESTORE_TEST_DATABASE_URL to a separate empty database first.
scripts/restore-check.sh /secure-backups/waypoint-2026-09-26.dump
```

The backup script uses private file permissions and refuses to overwrite a backup. Restore checking refuses known source URLs and a nonempty target; it does not delete existing tables. Keep encrypted off-host copies, schedule daily backups, and record recovery time after exercising restored orders, accounts and proof images. These scripts are provided but have not been restore-tested against your hosting provider.

Configure an external monitor for the backend's `/health/readiness` (HTTP 200 with `"status":"UP"`), alert on failures, and scrape `/prometheus` for the detection signals in EDGE-CASES.md. Keep logs in the log store (see Logs). A healthy process alone does not verify the entire role workflow.
