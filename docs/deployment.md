# Testing on Vercel and Neon

Deploy the whole Next.js application to Vercel: pages and `/api/*` share one origin. Use a fresh Neon PostgreSQL database for hosted testing. No browser connects directly to PostgreSQL. Existing SQLite files are not imported, deleted or used by this version.

## 1. Create the testing database

Create a Neon Free project and choose a database region near the Vercel function region you intend to use. Copy its pooled connection string for the application and its direct connection string for migrations. Keep the supplied TLS settings. Free-plan quotas and idle wake-up behavior apply; this setup is for testing, not a capacity guarantee.

Paste credentials into `.env.local` on your own machine; never commit them or put them in a `NEXT_PUBLIC_*` variable:

```dotenv
DATABASE_URL=postgresql://USER:PASSWORD@POOLED_HOST/DATABASE?sslmode=require
DATABASE_URL_UNPOOLED=postgresql://USER:PASSWORD@DIRECT_HOST/DATABASE?sslmode=require
DEMO_MODE=1
DEMO_NOW=2026-02-13T15:30:00+05:30
SEED_PASSWORD=REPLACE_WITH_A_PRIVATE_PASSWORD
COOKIE_SECURE=0
```

Use a password of at least 12 characters. The four demo account emails stay as listed in the README; all receive this password when first seeded. Later seed runs preserve existing accounts and records, and do not rotate passwords. Use a separate empty database for a fresh demo.

## 2. Initialize explicitly

```sh
cd frontend
npm ci
npm run db:migrate
npm run db:seed
npm run typecheck
npm run build
npm start
```

The migration and seed scripts read Next.js environment files. Migrations use `DATABASE_URL_UNPOOLED` when present. Requests and builds never create tables or seed data. Migrations record checksums; do not edit a migration after applying it. Add another migration instead. Never run destructive test commands against the hosted app database.

## 3. Configure Vercel

Import this repository as a Next.js project. Use Node.js 24, the default install command (`npm ci` with this lockfile), and `npm run build`. Deploy the existing API as Node.js functions; do not export the application as a static-only site.

Set these server environment variables for the intended deployment environment:

| Variable | Value |
| --- | --- |
| `DATABASE_URL` | Neon pooled connection string, including TLS settings |
| `DEMO_MODE` | `1` |
| `DEMO_NOW` | `2026-02-13T15:30:00+05:30` |
| `COOKIE_SECURE` | `1` |

`SEED_PASSWORD` and the direct URL are needed by the local initialization commands, not by the running Vercel app. Do not set `DATABASE_SCHEMA` in hosted deployments; it exists for isolated local browser tests. Give preview deployments a separate testing database or Neon branch; never silently share a future production database with previews.

Choose the closest available Vercel function region to the Neon database. Redeploy after changing environment variables. Verify `/api/health`, then sign into all four roles using the private seed password. Verify one order through publication, loading, offline delivery proof, reconnect and receipt. Open a receipt online until it says its proof is saved, then reload offline to check downloaded proof. Cold starts or temporary database outages must leave queued work pending with the same command IDs.

## Local PostgreSQL with Docker

```sh
cp .env.example .env
# Set POSTGRES_PASSWORD and SEED_PASSWORD in .env (PORT defaults to 3000).
docker compose up -d db
docker compose build backend
docker compose run --rm backend java -jar /app/backend.jar migrate
docker compose run --rm backend java -jar /app/backend.jar seed
docker compose up -d backend waypoint
```

Compose binds PostgreSQL to localhost and retains it in `waypoint-postgres`. The previous SQLite volume is not removed. Do not use `docker compose down -v` unless you intend to delete the local PostgreSQL data.

For automated tests, create a separate database once:

```sh
docker compose exec db createdb -U waypoint waypoint_test
# Export the dedicated connection; use the actual password from your .env.
export TEST_DATABASE_URL='postgresql://waypoint:LOCAL_PASSWORD@localhost:5432/waypoint_test'
npx playwright install chromium
npm run verify
```

Tests create random schemas only inside `TEST_DATABASE_URL`, then drop those schemas. They never fall back to `DATABASE_URL`. The browser runner initializes its own schema and starts a production server on port 43219. Run `npm run build` first when running `npm run test:e2e` alone.

## Limits and later production work

Proof images are stored as PostgreSQL bytes for this testing release and fetched individually through an authenticated endpoint. They are excluded from state and plan JSON. The existing command size limit remains 3.5 MB; each proof image response is bounded below Vercel's function payload limit. Cached proof is scoped to the browser account; clearing browser data removes it. Images that have not been downloaded are explicitly unavailable offline.

The state endpoint still returns an account's full operational history. Before large deployments, add bounded history queries/pagination and move proof bytes to private object storage. Stop hidden-tab polling to reduce unnecessary activity; an actively open workspace still polls every 10 seconds and can consume free-tier compute/transfer allowances.

Production requires separate credentials and data, real account provisioning, current operating calendars, backups with restore testing, monitoring, workload testing and a decision on storage retention. `DEMO_MODE=0` alone does not convert historical demo data into production data. Standard PostgreSQL connection strings keep a later database-provider change possible.

Provider references: [Neon plans](https://neon.com/docs/introduction/plans), [Neon connection pooling](https://neon.com/docs/connect/connection-pooling), [Vercel function limits](https://vercel.com/docs/functions/limitations).

## Production (single host, nginx + Docker)

Single origin: nginx terminates TLS and routes `/api/*` to the Spring Boot
backend, everything else to the Next.js frontend. Browsers never touch
PostgreSQL (managed Neon) directly, so session cookies need no CORS handling.

```sh
# On the host: point DNS at it, then set the required values in .env
DOMAIN=dispatch.example.com
CERTBOT_EMAIL=ops@example.com
DATABASE_URL=postgresql://USER:PASSWORD@POOLED_HOST/DATABASE?sslmode=require
SEED_PASSWORD=REPLACE_WITH_A_PRIVATE_PASSWORD
DEMO_MODE=1
DEMO_NOW=2026-02-13T15:30:00+05:30

docker compose -f compose.prod.yaml up -d nginx backend frontend
# Issue the first certificate (nginx serves plain HTTP until this exists):
docker compose -f compose.prod.yaml run --rm certbot \
  certonly --webroot -w /var/www/certbot \
  --email "$CERTBOT_EMAIL" --agree-tos --no-eff-email -d "$DOMAIN"
docker compose -f compose.prod.yaml restart nginx
# Initialize the database explicitly (never on boot or requests):
docker compose -f compose.prod.yaml run --rm backend java -jar /app/backend.jar migrate
docker compose -f compose.prod.yaml run --rm backend java -jar /app/backend.jar seed
```

Notes:

- `COOKIE_SECURE=1` is baked into `compose.prod.yaml`; nginx adds HSTS.
- Renewals run automatically in the `certbot` service; nginx picks renewed
  certificates on container restart (add a cron `docker compose ... restart nginx`
  weekly if you want hands-free rotation).
- Redeploy with `docker compose -f compose.prod.yaml up -d --build`.
- Monitor `https://$DOMAIN/api/health` externally.
