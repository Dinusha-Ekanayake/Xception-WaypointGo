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

Each deploy job opens one SSH connection as `deploy`. Each environment has its own key, bound on the server to that checkout's [deploy/vps/deploy.sh](../deploy/vps/deploy.sh) by a forced command, so a workflow cannot choose what runs or which commit ships and the preview key cannot deploy production. The script resets the checkout to the tip of its branch, builds, dumps the database, runs `init`, replaces the containers and checks the public URL. Once the site answers it removes dangling images and trims the Docker build cache to the 10 GB most recently used, which the two environments otherwise grow by about 10 GB a day. The dump is taken before `init` can migrate and is read back before it counts; a deploy that cannot take one stops with nothing replaced. There is no automatic rollback: revert the commit and the pipeline redeploys, and if a migration has to be undone as well, restore the dump (Backup and recovery, below).

**Preview is not production.** It runs unreviewed `dev` code against its own data, with the same demo accounts. Migrations are forward-only and checksummed, so a migration edited after it reached `dev` stops the preview `init`; that is the preview doing its job. Fix it with a new migration, or if the preview data does not matter, reset the preview alone:

```sh
cd /opt/waypoint/preview
docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml down -v   # preview volume only
sudo -u deploy /opt/waypoint/preview/deploy/vps/deploy.sh
```

Never run `down -v` in `/opt/waypoint/app`.

**Host.** Ubuntu 24.04. SSH accepts keys and the shared root password (`/etc/ssh/sshd_config.d/00-waypoint-hardening.conf`); the password stays on so the team can log in, and `fail2ban` (four failures, one hour ban, growing on repeats) and the `ufw` rate limit on 22 are what guard it. `ufw` allows 22, 80 and 443 only, and security updates install unattended. `root` is for administration; `deploy` owns `/opt/waypoint`, has no password and no sudo, but is in the `docker` group, which is root-equivalent on that host.

**Role addresses.** `dispatcher.`, `loader.`, `driver.`, `store.`, `admin.` and `auditor.` in front of `SITE_ADDRESS` serve production, each opening on one role: an account that holds the role gets that workspace with no role switcher, and an account that does not hold it gets its own workspace on the same address ([hostRole.ts](../frontend/src/app-shell/hostRole.ts)). The address chooses the screen only; what an account may do is still decided by the server. Each address is its own origin, so it has its own session and its own offline queue. Preview has the same six with `-preview` in the first label, such as `loader-preview.waypointgo.live`, served by the preview stack; the dash is there because Cloudflare's universal certificate does not cover a name two levels below the domain. nginx is deployed with production, so a new hostname merged to `dev` answers only after the proxy itself is next deployed. The address every role shares, the bare name on production and `preview.` on preview, has no sign-in or workspace of its own: it shows what Waypoint is and four buttons, one per field role, each opening that role's own address ([RoleLanding.tsx](../frontend/src/app-shell/RoleLanding.tsx)). Administrators and auditors open their address directly. The preview name is recognised by itself; a bare name is a landing only in a build made with `NEXT_PUBLIC_ROLE_ADDRESSES=1`, which the VPS overlay sets, so local development and the plain `docker compose up` path keep every role and the switcher on one address. A session or an offline queue left on the bare production name from before this change stays in that browser and is no longer reachable from it.

**Speech.** Spoken messages (R-MSG-10) need the `speech` service, which is opt-in per environment: set `SPEECH_URL=http://speech:8090` in that checkout's `.env` and deploy, and `deploy.sh` builds and starts it on the stack's own network with no host port. Without the line messages stay text and voice notes still play. The first build downloads the three voices (about 200 MB) and checks their SHA-256.

**Secrets.** Each checkout has an untracked `.env`, mode 600: `SITE_ADDRESS`, `POSTGRES_PASSWORD`, `APP_DB_PASSWORD`, `PROOF_URL_SECRET`, `SEED_PASSWORD`, `WAREHOUSE_API_KEY`, `DEPLOY_ENV=preview` in the preview one, and `CLOUDFLARE_ONLY` in the production one. `APP_DB_PASSWORD` and `PROOF_URL_SECRET` are written there by the first deploy that finds them missing and are never changed afterwards. The session cookie is always Secure here; the overlay sets `COOKIE_SECURE=1` whatever `.env` says. Read-only MCP connections ([walkthrough](issues/087-readonly-mcp/WALKTHROUGH.md)) are off unless that checkout's `.env` has `MCP_ENABLED=true` plus the matching `MCP_PUBLIC_URL`; the values are read when the containers are created, so they take effect on the next deploy of that environment. Preview uses `MCP_PUBLIC_URL=https://preview.waypointgo.live/mcp`, production uses `MCP_PUBLIC_URL=https://waypointgo.live/mcp`. Enable preview first, verify a hosted assistant there, then enable production. Use the shared host, never a role address: a token is bound to the one configured resource.

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

## Operating calendar

Past the end of the supplied calendar, operating days come from the extension policy (Monday to Saturday, R-CAL-03). Closures and special operating days are set by an administrator with the `calendar:Override` command, recorded with an actor and a reason; there is no calendar file. The policy does not invent public-holiday closures; operations must supply them. `waypoint_reference_calendar_days_remaining` warns before the supplied calendar runs out.

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

Docker configuration can be checked with `docker compose config --quiet` and `docker compose -f compose.yaml -f deploy/vps/compose.vps.yaml --profile edge config --quiet` with `SITE_ADDRESS` set. Actual fresh-volume container startup, public TLS and physical-device trials remain release checks; local browser emulation does not establish those properties.

## Logs

The backend logs ECS JSON (`LOG_FORMAT=ecs` in both compose files). To store and search them, set `GRAFANA_ADMIN_PASSWORD` and enable the opt-in profile:

```sh
docker compose --profile observability up -d
```

Alloy reads every container labelled `com.waypoint.logs=true` through the read-only Docker socket and ships to Loki, which keeps 14 days on the `loki-data` volume (budget roughly 1 GB per week at pilot volume). Grafana listens on `127.0.0.1:3001` only and is never routed through nginx; reach it with `ssh -L 3001:127.0.0.1:3001 <host>` and open http://127.0.0.1:3001. Grafana refuses to start without `GRAFANA_ADMIN_PASSWORD`. The application does not depend on the log store: with Loki down, Alloy retries and requests are served normally.

The same profile runs Prometheus, which scrapes the backend's `/prometheus` every 15 seconds and keeps 15 days on `prometheus-data`. `/prometheus` is never routed by nginx. On the VPS the stack runs in preview only, as the log store does, and scrapes both environments: each backend joins the `waypoint-edge` network as `production-backend` or `preview-backend` (`deploy/vps/compose.vps.yaml`, scrape config `observability/prometheus/vps.yml`), and a `node-exporter` reads the server's CPU, memory and disk. The production target is down until production has been redeployed with that overlay. Grafana provisions the dashboard Waypoint > Waypoint operations from `observability/grafana/provisioning/dashboards/`: service health (up, request rate, 5xx share, p95 latency from the SLO buckets in `application.properties`), commands and jobs, outbox and circuit breakers, JVM and connection pool, the server, then the log panels.

Alerts (roadmap B-04). Prometheus evaluates `observability/prometheus/rules/waypoint.yml` (backend down, outbox lag and dead letters, failing jobs, pool waits, retention behind, table growth, disk filling, the model trust gates, the assistant connection) and sends what fires to Alertmanager in the same stack (`observability/alertmanager/alertmanager.yml`). It groups alerts per environment, repeats a critical one hourly and the rest every 12 hours, keeps `info` alerts on its page only, and holds back every other alert of an environment whose backend is down. It posts to `ALERT_WEBHOOK_URL` in `.env`, a Slack-compatible incoming webhook (Slack, Mattermost, Rocket.Chat, or a Discord webhook with `/slack` at the end); with it empty, alerts show only in Prometheus and on Grafana's Alerting page. CI runs `promtool test rules` and `amtool check-config` on every change under `observability/`.

## Operational limits

**Memory (F-15).** Every container has a ceiling, so production, preview and the log store fit one 16 GB server; CI fails a service without one (`deploy/vps/check-memory-limits.py`). The backend's heap is 75% of its ceiling and it exits on running out, so Docker restarts it. Raise a ceiling in `.env` (`DB_MEM`, `BACKEND_MEM`, `ML_MEM`, `WEB_MEM`, `MCP_MEM`, `INIT_MEM`); scale the server up before adding replicas.

| Per stack | Ceiling | | Shared | Ceiling |
| --- | --- | --- | --- | --- |
| db | 1 GB | | loki, prometheus | 512 MB each |
| backend | 2 GB | | grafana, alloy | 256 MB each |
| ml | 1.5 GB | | nginx | 128 MB |
| waypoint | 512 MB | | node-exporter, certbot | 64 MB each |
| mcp | 256 MB | | init (runs once) | 1 GB |

Two stacks and the log store come to about 12.4 GB at steady state, leaving room for the operating system and the page cache.

What a rollout beyond the competition would have to change, as things stand on 2026-10-02. The full list per module is in [STATUS.md](development-docs/STATUS.md).

- **Proofs live in the database.** Photos and signatures are stored in `execution.proof_content` by `DatabaseProofStore` (`PROOF_STORE=database`, the default) and read back through signed five-minute links, so they are in the database backup and every replica sees them (A-33). They count toward the database's storage: watch `waypoint.execution.proof_bytes_held`. A nightly job clears them past `retain_until` (P-14). Files written to `PROOF_DIR` before the switch are still read from there, so keep that volume until they age out. With `PROOF_URL_SECRET` unset the links die at a restart; the VPS deploy writes one to `.env` so they do not.
- **The audit log is partitioned by month and the last partition ends 2027-07-01.** The job that creates partitions ahead is not built (issue #6); after that date every command fails.
- **No retention jobs.** Expired sessions, old login attempts, command receipts and delivered outbox rows are not purged (issue #6).
- **Nobody is told.** The Notification module is not built (issue #14), so a deferral, a shortfall or a dispute reaches a person only when they open the screen that shows it.
- **Travel and service times are the planning allowances,** not observed durations, by design (R-ML-05): the model scores each published plan for late risk but never changes it. Road conditions in the supplied data end on 2026-06-28, so later dates are scored by the fallback model (log-loss 0.24 or 0.17 instead of 0.15) until a road-condition feed exists (issue #16).
- **The model service needs `git-lfs` on the server.** Its trained models are in Git LFS; `deploy.sh` runs `git lfs pull` and stops before replacing anything if `git-lfs` is missing. Install it once with `apt-get install git-lfs` on the VPS before the first deploy that carries `ml-server`. With the service down or unconfigured, plans and forecasts fall back to the deterministic estimates and say so (ML-01).
- **No automatic rollback, and the only database backups are the ones a deploy takes.** They sit on the server itself, so they undo a bad migration and do not survive a lost disk. Nothing takes one between deploys and nothing copies them off the host. The restore steps under Backup and recovery have not been exercised on the VPS.

Run a measured fleet pilot before general rollout.

## Staff accounts

The first administrator cannot come from an endpoint that requires one, so it is created from the host. On the Compose path the `init` step does this from `ADMIN_EMAIL` and `ADMIN_PASSWORD`. Any other account, from the checkout on the host:

```sh
scripts/account.sh admin@example.com "Full Name" admin
```

The password is prompted without echo and never passed as an argument. Roles are `admin`, `dispatcher`, `loader`, `driver`, `store_manager` and `auditor`.

Every later change is a command an administrator sends through `POST /api/commands`: create and update an account, reset a password, disable it, grant or revoke a depot or outlet, assign a driver to a vehicle for a period. Disabling an account revokes its sessions in the same transaction, and the last active dispatcher cannot be disabled. There is no admin screen for these yet (issue #22).

Three backend commands cover what a fresh instance needs before anyone can send a command: `account-grant-depot`, `operator-pin` for a loader's PIN on a shared dock device, and `demo-accounts` for one account per role. Their variables are in the [README](../README.md#backend-commands).

## Rotating passwords and secrets

Roadmap H-02. A README of an earlier commit (`961d89a`) listed the deployed accounts with their passwords, and the demo accounts were created from a `SEED_PASSWORD` that was public. History keeps them, so the only fix is to change them. Rotate one at a time, check the site works after each, and record in the development log what was rotated and when, never the value. Generate every new value with `openssl rand -base64 32` (passwords for people: at least 16 characters, kept in the team's password manager).

| Secret | Where it lives | How to rotate | What it breaks |
| --- | --- | --- | --- |
| Production administrator password | the account | Sign in as the administrator and send `iam:ResetPassword` for the administrator's own `userId` (below). Do this first | Ends that account's sessions |
| The six demo accounts | the accounts | `iam:ResetPassword` for each, or disable the ones nobody uses (`iam:DisableUser`). Changing `SEED_PASSWORD` alone does nothing to existing accounts | Ends their sessions |
| `SEED_PASSWORD` | `.env` on the VPS | Set a new private value so accounts created later do not get the public one | Nothing |
| `APP_DB_PASSWORD` | `.env` on the VPS | Change it and deploy: `migrate` sets `waypoint_app`'s password on every run | Nothing; the pool reconnects |
| `POSTGRES_PASSWORD` | `.env` on the VPS, and the database | `docker compose exec db psql -U postgres -c "ALTER ROLE postgres PASSWORD '<new>'"`, then change `.env` to match | `init` and `migrate` fail until both match |
| `PROOF_URL_SECRET` | `.env` on the VPS | Set a fixed value of at least 16 characters per environment, then deploy. Blank gives each process its own random key, so proof links break across replicas | Proof links already handed out (they last minutes) |
| `GRAFANA_ADMIN_PASSWORD` | `.env` on the VPS | Change and restart Grafana | Grafana sessions |
| `ALERT_WEBHOOK_URL` | `.env` on the VPS, and the chat workspace | Make a new incoming webhook, change `.env`, `docker compose --profile observability up -d alertmanager`, then delete the old webhook | Alerts between the two steps |
| `WAREHOUSE_API_KEY`, `WAREHOUSE_WEBHOOK_SECRET` | `.env`, and the warehouse provider | Issue new ones with the provider, change `.env`, deploy, then revoke the old ones | Catalogue sync and webhooks between the two steps |
| `GROQ_API_KEY` | `.env`, and the provider | Create a new key, change `.env`, deploy, delete the old key | Nothing |
| `PUSH_VAPID_PUBLIC_KEY`, `PUSH_VAPID_PRIVATE_KEY` | `.env` | Only if exposed: every phone must turn alerts on again | All push subscriptions |
| `VPS_SSH_KEY`, `VPS_PREVIEW_SSH_KEY` | GitHub secrets (H-03: an Environment each), and `authorized_keys` on the VPS | Make a new key pair, add the public key on the VPS, replace the GitHub secret, run a deploy, then remove the old public key | Deploys between the two steps. Needs admin on the repository |

Resetting a password from the host, with the administrator's session cookie (`__Host-waypoint_session` over HTTPS) in `SESSION` and the account's `userId` and row version from `GET /api/accounts`:

```sh
curl -sS https://waypointgo.live/api/commands -H 'content-type: application/json' --cookie "__Host-waypoint_session=$SESSION" \
  -d '{"commandId":"'"$(uuidgen | tr A-Z a-z)"'","kind":"iam:ResetPassword","expectedVersion":<rowVersion>,"payload":{"userId":"<userId>","password":"<new>"}}'
```

The command never echoes the password, writes an audit row, and ends the account's other sessions. Run it from a shell whose history is off (`set +o history`), or type the password into a file read with `$(cat file)` and delete the file.

## Object store (M-01)

Off by default: every file stays in the database. To move files to an S3-compatible bucket (Cloudflare R2, Backblaze B2, AWS S3):

1. **A private bucket.** No public access, no public `r2.dev` address, no website hosting. Files are only ever served through the backend, which applies row-level security and audit.
2. **A token for that bucket only.** Object read and write on the one bucket; no account-wide token, no admin rights. Put its id and secret in `S3_ACCESS_KEY_ID` and `S3_SECRET_ACCESS_KEY`, the endpoint in `S3_ENDPOINT`, the bucket in `S3_BUCKET`.
3. **An encryption key.** `openssl rand -base64 32` into `S3_ENCRYPTION_KEY`. Every file is encrypted with it on the server before upload, so the provider holds only ciphertext. **Without this key the files cannot be read**: keep a copy where the database backups are kept, never in the bucket and never in the repository. Changing it makes every stored file unreadable; there is no rotation yet.
4. `OBJECT_STORE=s3`, then restart the backend. With a value missing it refuses to start and says which.

New files go to the bucket from then on. `MediaBackfillJob` moves what was stored before, 50 files every ten minutes per table, reading each one back and comparing it before recording the move; the database copy is cleared 30 days later (`S3_MOVE_AFTER`). Watch `waypoint_storage_backfill_total{outcome}`: anything but `moved` is a file left in the database for a look. Once files have moved, do not set `OBJECT_STORE` back: the moved files are then in the bucket only.

The daily off-server backup below copies the database; it does not copy the bucket. Give the bucket its own copy to a second provider (rclone, H-05), with the encryption key kept beside the database backups.

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

### Daily off-server backup

The deploy dumps above survive a bad migration, not a lost disk. [deploy/vps/backup.sh](../deploy/vps/backup.sh) takes a daily dump the same way, reads it back, keeps the newest 7 on the server under `/opt/waypoint/backups/daily-<environment>/`, and copies each one with its sha256 to a second provider through rclone, keeping 30 days there. Proofs, issue photos and voice notes are in the database by default, so the dump covers them.

Set it up once, as root, then as `deploy`:

```sh
apt-get install -y rclone
sudo -u deploy rclone config            # create a remote, e.g. "offsite" (Backblaze B2 or a Hetzner Storage Box)
sudo -u deploy crontab -e               # add the two lines below
```

```cron
BACKUP_REMOTE=offsite:waypoint-backups
15 2 * * * /opt/waypoint/app/deploy/vps/backup.sh production >> /opt/waypoint/backups/backup.log 2>&1
45 2 * * * /opt/waypoint/preview/deploy/vps/backup.sh preview >> /opt/waypoint/backups/backup.log 2>&1
```

The rclone credentials stay in `deploy`'s rclone config, never in the repository. A run fails loudly if the dump cannot be read back, if `BACKUP_REMOTE` is unset, or if the remote copy does not match. Each success writes `/opt/waypoint/backups/daily-<environment>/last-success`: a timestamp older than 26 hours means the backup has stopped.

**Restore drill.** Run it on preview first, and time it. The targets are a recovery point of 24 hours or less and a recovery time of 4 hours or less (roadmap H-05).

1. Download the newest preview dump from the remote with `rclone copy`, and check its sha256.
2. Restore it with the steps under "On the VPS" above.
3. Sign in and walk one role.
4. Record the times in the development log.

### Elsewhere

Install PostgreSQL client tools matching the server major version. Export DATABASE_URL_UNPOOLED securely, then:

```sh
scripts/backup.sh /secure-backups/waypoint-2026-09-26.dump
# Set RESTORE_TEST_DATABASE_URL to a separate empty database first.
scripts/restore-check.sh /secure-backups/waypoint-2026-09-26.dump
```

The backup script uses private file permissions and refuses to overwrite a backup. Restore checking refuses known source URLs and a nonempty target; it does not delete existing tables. Keep encrypted off-host copies, schedule daily backups, and record recovery time after exercising restored orders, accounts and proof images. These scripts are provided but have not been restore-tested against your hosting provider.

Configure an external monitor for the backend's `/health/readiness` (HTTP 200 with `"status":"UP"`), alert on failures, and scrape `/prometheus` for the detection signals in EDGE-CASES.md. Keep logs in the log store (see Logs). A healthy process alone does not verify the entire role workflow.
