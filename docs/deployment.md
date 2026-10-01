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

**Certificates.** Let's Encrypt over HTTP-01, one certificate for `SITE_ADDRESS`, `www.SITE_ADDRESS`, `preview.SITE_ADDRESS` and the role addresses of both environments. Each production deploy requests it again when a name that resolves is not yet on it, and never drops a name already there; until the first one nginx serves a self-signed placeholder, which is enough to answer the challenge. The `certbot` service renews through the webroot and nginx reloads every twelve hours. A hostname with no DNS record is left off with a warning in the deploy output and joins on the first production deploy after the record exists. Five failed requests an hour is Let's Encrypt's limit.

**Cloudflare.** Create proxied A records for the hostname and for `www.`, `preview.`, `dispatcher.`, `loader.`, `driver.`, `store.`, `admin.` and `auditor.` of it, plus the six preview role names (`dispatcher-preview.` and so on), and set SSL/TLS mode to **Full (strict)**. Flexible sends plain HTTP to the server, which redirects it, and the visitor loops. nginx trusts `CF-Connecting-IP` only from Cloudflare's published ranges ([conf.d/00-cloudflare.conf](../deploy/vps/nginx/conf.d/00-cloudflare.conf)), so limits and logs see the visitor; from anyone else the header is ignored. Once the records are proxied, set `CLOUDFLARE_ONLY=1` in the production `.env` and deploy: nginx then drops any request that did not come through Cloudflare, so the server's address cannot be used to go around it. Leave it `0` while a record is DNS-only, or that hostname is unreachable.

**Pipeline.** [checks.yml](../.github/workflows/checks.yml) runs the backend tests against a PostgreSQL service and the frontend typecheck, boundary test and build on every pull request into `main` or `dev`. A push to `dev` runs the same checks and then deploys the preview ([preview-deploy.yml](../.github/workflows/preview-deploy.yml)); a push to `main` does the same for production ([ci-deploy.yml](../.github/workflows/ci-deploy.yml)). A failed check means no deploy. So the order is: merge into `dev`, look at the preview, then merge `dev` into `main`.

Each deploy job opens one SSH connection as `deploy`. Each environment has its own key, bound on the server to that checkout's [deploy/vps/deploy.sh](../deploy/vps/deploy.sh) by a forced command, so a workflow cannot choose what runs or which commit ships and the preview key cannot deploy production. The script resets the checkout to the tip of its branch, builds, runs `init`, replaces the containers and checks the public URL. There is no automatic rollback; revert the commit and the pipeline redeploys.

**Preview is not production.** It runs unreviewed `dev` code against its own data, with the same demo accounts. Migrations are forward-only and checksummed, so a migration edited after it reached `dev` stops the preview `init`; that is the preview doing its job. Fix it with a new migration, or if the preview data does not matter, reset the preview alone:

```sh
cd /opt/waypoint/preview
docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml down -v   # preview volume only
sudo -u deploy /opt/waypoint/preview/deploy/vps/deploy.sh
```

Never run `down -v` in `/opt/waypoint/app`.

**Host.** Ubuntu 24.04. SSH accepts keys and the shared root password (`/etc/ssh/sshd_config.d/00-waypoint-hardening.conf`); the password stays on so the team can log in, and `fail2ban` (four failures, one hour ban, growing on repeats) and the `ufw` rate limit on 22 are what guard it. `ufw` allows 22, 80 and 443 only, and security updates install unattended. `root` is for administration; `deploy` owns `/opt/waypoint`, has no password and no sudo, but is in the `docker` group, which is root-equivalent on that host.

**Role addresses.** `dispatcher.`, `loader.`, `driver.`, `store.`, `admin.` and `auditor.` in front of `SITE_ADDRESS` serve production, each showing one role: sign-in opens that role's workspace with no role switcher, and an account that does not hold the role is pointed at its own address ([hostRole.ts](../frontend/src/app-shell/hostRole.ts)). The address chooses the screen only; what an account may do is still decided by the server. Each address is its own origin, so it has its own session and its own offline queue. The bare name keeps every role and the switcher. Preview has the same six with `-preview` in the first label, such as `loader-preview.waypointgo.live`, served by the preview stack; the dash is there because Cloudflare's universal certificate does not cover a name two levels below the domain. nginx is deployed with production, so a new hostname merged to `dev` answers only after the proxy itself is next deployed.

**Secrets.** Each checkout has an untracked `.env`, mode 600: `SITE_ADDRESS`, `POSTGRES_PASSWORD`, `SEED_PASSWORD`, `COOKIE_SECURE=1`, `WAREHOUSE_API_KEY`, `DEPLOY_ENV=preview` in the preview one, and `CLOUDFLARE_ONLY` in the production one. GitHub holds `VPS_HOST`, `VPS_USER`, `VPS_KNOWN_HOSTS`, `VPS_SSH_KEY` (production) and `VPS_PREVIEW_SSH_KEY`. The server reads the repository with a read-only deploy key.

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

`SITE_ADDRESS` is `waypointgo.live`, the preview is always `preview.` in front of it, and `www.` redirects to the bare name. To move to another domain, point all fifteen A records at the server, change `SITE_ADDRESS` in both `.env` files and deploy production, then preview; the previous hostnames stop answering at once. `SEED_PASSWORD` only applies when an account is first created; changing it later does not rotate the six demo accounts.

## Production host

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

Configure an external monitor for the backend's `/health/readiness` (HTTP 200 with `"status":"UP"`), alert on failures, and scrape `/prometheus` for the detection signals in EDGE-CASES.md. Keep logs in the log store (see Logs). A healthy process alone does not verify the entire role workflow.
