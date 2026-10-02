# Local development

How to run Waypoint Dispatch while working on it. For deploying it, see [deployment.md](../deployment.md). For the module layout, see [MODULES.md](../architecture/MODULES.md).

## The model: dependencies in Docker, your code native

PostgreSQL runs in Docker. The Spring backend and the Next.js frontend run natively on your machine.

This is the standard split because it puts each tool where it is strongest. Docker is good at giving everyone the identical PostgreSQL 16 with one command and no local install. Docker is bad at the edit loop: on macOS every file operation crosses the VM boundary, bind mounts run several times slower than native, and file watching is exactly the stat-heavy workload that penalty falls on hardest. Next.js hot reload and Spring restarts are what you do hundreds of times a day, so they stay native.

`compose.yaml` is not a development environment. It builds production images with no source mounts, and it exists because the Hackathon brief requires `docker compose up` to start the complete stack with its reference data and an administrator. Use it to verify the judge path, not to write code.

## Prerequisites

- Node.js 22.13 or newer
- Java 17 or newer, and Maven
- Docker Desktop, for the database only

## The short way

```sh
scripts/dev.sh setup    # once: database, migrate, import-reference, a demo account per role, npm ci
scripts/dev.sh          # daily: database, backend on :8080, frontend on :3000, Ctrl+C stops both
scripts/dev.sh sample   # the same, with store and loader sample fixtures in the frontend
```

It always targets the local Docker database, never the `DATABASE_URL` in `.env`, and signs you in as `<role>@waypoint.local` with `SEED_PASSWORD` (default `Waypoint2026!`). `./start.sh` starts the backend and frontend from root `.env` instead, for a database that is not the Docker one; it never migrates or seeds. The two sections below are the same steps by hand, which is what to read when one of them fails.

## First-time setup

```sh
# 1. Environment template
cp .env.example .env

# 2. PostgreSQL only, published on 127.0.0.1:5432 (set DB_PORT in .env if taken)
docker compose up -d db

# 3. Optional: a separate database for tests. Without it, integration tests start
#    a throwaway PostgreSQL container instead. Never the application database.
docker compose exec db createdb -U waypoint waypoint_test

# 4. Frontend dependencies and the proxy target
cd frontend
npm ci
printf 'BACKEND_URL=http://127.0.0.1:8080\n' > .env.local

# 5. Schema, reference data and an administrator. Explicit, never on build or request.
cd ../backend
export DATABASE_URL='postgresql://waypoint:local-testing-only@127.0.0.1:5432/waypoint'
mvn spring-boot:run -Dspring-boot.run.arguments="migrate"
mvn spring-boot:run -Dspring-boot.run.arguments="import-reference"
ACCOUNT_EMAIL=admin@waypoint.local ACCOUNT_NAME='Local Administrator'   ACCOUNT_PASSWORD=local-testing-only ACCOUNT_ROLE=admin   mvn spring-boot:run -Dspring-boot.run.arguments="account-create"
```

All three are idempotent. Re-running `account-create` for an existing email leaves that account, and its password, unchanged.

## Daily loop

Three terminals.

```sh
# 1. database (leave running; survives reboots with `docker compose up -d db`)
docker compose up -d db

# 2. backend
cd backend
export DATABASE_URL='postgresql://waypoint:local-testing-only@127.0.0.1:5432/waypoint'
export COOKIE_SECURE=0   # plain HTTP on localhost; the default is a Secure cookie
mvn spring-boot:run

# 3. frontend
cd frontend
npm run dev
```

Open http://localhost:3000 and sign in as the administrator from step 5. Create other accounts through the API (`iam:CreateUser`), or run the `demo-accounts` command for one account per role.

Use `npm run build && npm start` instead of `npm run dev` when testing offline behaviour, because the service worker is only generated for a production build.

## Searching logs

Optional. Loki stores logs, Alloy collects them, Grafana searches them. Off unless you start the `observability` profile.

```sh
# once per session, beside the database
docker compose --profile observability up -d loki alloy grafana

# backend: also write JSON logs to var/log, which Alloy tails
export LOG_FILE=../var/log/backend.log
mvn spring-boot:run
```

Open http://127.0.0.1:3001 (user `admin`, password `GRAFANA_ADMIN_PASSWORD`, default `local-testing-only`), then Explore. Every container labelled `com.waypoint.logs=true` is collected too.

| Find | LogQL |
| --- | --- |
| Backend errors | `{service="backend", level="ERROR"}` |
| One request, every service | `{service=~".+"} \| correlationId="<X-Correlation-Id from the response>"` |
| One module | `{service="backend"} \| logger=~"com.waypoint.dispatch.ordering.*"` |

`level` is a label. `correlationId`, `traceId` and `logger` are structured metadata, never labels, because one value per request would explode the index. Logs are kept 14 days. The console stays readable; only the file is JSON. Config is in `observability/`; the Alloy pipeline UI is at http://127.0.0.1:12345.

## Which process reads which configuration

This trips people up, so it is worth stating plainly.

| Process | Reads | Does not read |
| --- | --- | --- |
| Docker Compose | root `.env` | anything else |
| Spring Boot via Maven or `java -jar` | exported shell variables only | root `.env`, `frontend/.env.local` |
| Next.js dev and build | `frontend/.env.local` | root `.env` |
| Node tests (`npm test`) | nothing; static checks only | |

Spring does not load `.env` files. If the backend cannot find the database, the usual cause is that `DATABASE_URL` was set in the wrong terminal. Keep the export in your shell profile or a small `source`d file that is not committed.

### The external warehouse API

Stock, reservations and the product catalogue come from the warehouse service. Its documentation is at [triathon-warehouse-simple.vercel.app/docs](https://triathon-warehouse-simple.vercel.app/docs), and the contract Waypoint relies on is summarised in [RULES-AND-POLICIES §2](../architecture/RULES-AND-POLICIES.md#2-stock-and-the-external-warehouse).

Create your own key on the service's API keys page; a key per developer keeps usage (`GET /api/v1/usage`) and revocation per person. It is a backend secret: put it in root `.env` for Compose, and export it in the backend shell for a native run:

```bash
export WAREHOUSE_API_KEY=wh_...
# optional, these are the defaults
export WAREHOUSE_BASE_URL=https://triathon-warehouse-simple.vercel.app/api/v1
export WAREHOUSE_TIMEOUT_MS=3000
```

Never put it in `frontend/.env.local` or a `NEXT_PUBLIC_*` variable: the browser never calls the warehouse.

### Web push for notifications

The notification inbox works with no setup. Web push needs a VAPID key pair, one per deployment; both blank turns push off, and `GET /api/notifications/push-config` says so. Generate a pair and export it in the backend shell (root `.env` for Compose):

```bash
npx web-push generate-vapid-keys        # prints a public and a private key, base64url
export PUSH_VAPID_PUBLIC_KEY=B...
export PUSH_VAPID_PRIVATE_KEY=...       # a backend secret, like the warehouse key
export PUSH_SUBJECT=mailto:you@example.com
```

One key without the other, or a private key that does not belong to the public one, refuses to start. Changing the pair invalidates every browser subscription (A-35). The browser only ever sees the public key, from `push-config`. With the key blank the backend still starts, and every order saves as `STOCK_UNKNOWN` with the reason on screen. The warehouse is shared by the whole team, so do not place or cancel orders against it by hand except to test the lifecycle on purpose; a placed order locks real stock.

## Tests

```sh
cd backend
mvn verify         # unit, architecture (module boundaries) and integration tests

cd ../frontend
npm test           # frontend boundary tests
npm run typecheck
npm run build
npx playwright install chromium   # once
npm run test:e2e   # browser tests of the shell on port 43219, after a build
npm run verify     # test, typecheck, build and mvn verify in sequence

# one suite per role, after a build, against a mocked API. Not in CI: run the one you touched
npx playwright test -c playwright.dispatcher.config.ts   # tests/e2e-dispatcher, port 43222
npx playwright test -c playwright.driver.config.ts       # tests/e2e-driver, port 43221, phone width
npx playwright test -c playwright.loader.config.ts       # tests/e2e-loader, port 43220, phone width
```

One test at a time: `mvn test -Dtest=ModuleBoundaryTest` or `-Dtest='SomeTest#method'` from `backend/`; `node --test --experimental-strip-types tests/boundaries.test.ts` from `frontend/`; a spec file name or `-g "title"` after a Playwright config. `playwright.loader.live.config.ts` runs `live.spec.ts` against a running instance named by `LOADER_LIVE_BASE_URL`.

Integration tests pick their database in this order: `TEST_DATABASE_URL` if exported (it must differ from `DATABASE_URL`), else a throwaway PostgreSQL 16 container if Docker is running, else they are skipped with that reason in the report. A green run with them skipped proves nothing about the database. CI always sets `TEST_DATABASE_URL`. Migrations are checksummed, so after editing a migration that has not merged, drop and recreate the test database. If an editor's Java extension compiles into `backend/target/classes`, run `mvn clean` before trusting a result: stale classes surface as "Unresolved compilation problems" at test time.

## Before you push, and twice before the deadline

The judge path is the full container stack, and it is the part that silently rots because nobody runs it daily:

```sh
docker compose down -v        # discard the old volume, start clean
docker compose up --build
```

Then sign in as the administrator and walk the role flows from a fresh database. Treat a failure here as release-blocking, not as a Docker quirk.

## What we deliberately do not have

- No second development compose file with source bind mounts and hot reload. It is a second configuration to keep in sync and it is slow on macOS. If containerized development is ever needed, use Compose's `develop`/`watch` support rather than hand-rolled mounts.
- No devcontainer and no Nix or mise toolchain pinning. Both are reasonable later; neither earns anything before the submission deadlines.
- No Testcontainers-only setup. Testcontainers is the fallback when `TEST_DATABASE_URL` is unset, so a database that is already running is still used.
