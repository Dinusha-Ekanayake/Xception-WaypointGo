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
mvn spring-boot:run

# 3. frontend
cd frontend
npm run dev
```

Open http://localhost:3000 and sign in as the administrator from step 5. Create other accounts through the API (`iam:CreateUser`).

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

## Tests

```sh
cd backend
mvn verify         # unit, architecture (module boundaries) and integration tests

cd ../frontend
npm test           # frontend boundary tests
npm run typecheck
npm run build
npx playwright install chromium   # once
npm run test:e2e   # browser smoke tests on port 43219, after a build
npm run verify     # test, typecheck, build and mvn verify in sequence
```

Integration tests pick their database in this order: `TEST_DATABASE_URL` if exported (it must differ from `DATABASE_URL`), else a throwaway PostgreSQL 16 container if Docker is running, else they are skipped with that reason in the report. A green run with them skipped proves nothing about the database. CI always sets `TEST_DATABASE_URL`.

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
