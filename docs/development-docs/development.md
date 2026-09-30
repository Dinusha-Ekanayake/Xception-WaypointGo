# Local development

How to run Waypoint Dispatch while working on it. For deploying it, see [deployment.md](../deployment.md). For the module layout, see [code-structure.md](../code-structure.md).

## The model: dependencies in Docker, your code native

PostgreSQL runs in Docker. The Spring backend and the Next.js frontend run natively on your machine.

This is the standard split because it puts each tool where it is strongest. Docker is good at giving everyone the identical PostgreSQL 16 with one command and no local install. Docker is bad at the edit loop: on macOS every file operation crosses the VM boundary, bind mounts run several times slower than native, and file watching is exactly the stat-heavy workload that penalty falls on hardest. Next.js hot reload and Spring restarts are what you do hundreds of times a day, so they stay native.

`compose.yaml` is not a development environment. It builds production images with no source mounts, and it exists because the Hackathon brief requires `docker compose up` to start the complete stack with seed data. Use it to verify the judge path, not to write code.

## Prerequisites

- Node.js 22.13 or newer
- Java 17 or newer, and Maven
- Docker Desktop, for the database only

## First-time setup

```sh
# 1. Environment template
cp .env.example .env

# 2. PostgreSQL only, published on 127.0.0.1:5432
docker compose up -d db

# 3. A separate database for tests. Tests create disposable schemas inside it
#    and must never touch the application database.
docker compose exec db createdb -U waypoint waypoint_test

# 4. Frontend dependencies and the proxy target
cd frontend
npm ci
printf 'BACKEND_URL=http://127.0.0.1:8080\n' > .env.local

# 5. Schema and demo data. These run once, explicitly, never on build or request.
cd ../backend
export DATABASE_URL='postgresql://waypoint:local-testing-only@127.0.0.1:5432/waypoint'
export SEED_PASSWORD='Waypoint2026!'
mvn spring-boot:run -Dspring-boot.run.arguments="migrate"
mvn spring-boot:run -Dspring-boot.run.arguments="seed"
```

Repeating migrate and seed is safe. Existing records are preserved, and `SEED_PASSWORD` only applies when an account is first created. Changing it later does not rotate existing passwords.

## Daily loop

Three terminals.

```sh
# 1. database (leave running; survives reboots with `docker compose up -d db`)
docker compose up -d db

# 2. backend
cd backend
export DATABASE_URL='postgresql://waypoint:local-testing-only@127.0.0.1:5432/waypoint'
export DEMO_MODE=1
export SEED_PASSWORD='Waypoint2026!'
mvn spring-boot:run

# 3. frontend
cd frontend
npm run dev
```

Open http://localhost:3000. Seeded accounts are listed in the [README](../../README.md#accounts-and-configuration).

Use `npm run build && npm start` instead of `npm run dev` when testing offline behaviour, because the service worker is only generated for a production build.

## Which process reads which configuration

This trips people up, so it is worth stating plainly.

| Process | Reads | Does not read |
| --- | --- | --- |
| Docker Compose | root `.env` | anything else |
| Spring Boot via Maven or `java -jar` | exported shell variables only | root `.env`, `frontend/.env.local` |
| Next.js dev and build | `frontend/.env.local` | root `.env` |
| Node tests and scripts | exported shell variables | root `.env` |

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

Never put it in `frontend/.env.local` or a `NEXT_PUBLIC_*` variable: the browser never calls the warehouse. With the key blank the backend still starts, and every order saves as `STOCK_UNKNOWN` with the reason on screen. The warehouse is shared by the whole team, so do not place or cancel orders against it by hand except to test the lifecycle on purpose; a placed order locks real stock.

## Tests

```sh
cd frontend
export TEST_DATABASE_URL='postgresql://waypoint:local-testing-only@127.0.0.1:5432/waypoint_test'

npm test           # Node regressions, Maven tests, Spring HTTP integration tests
npm run typecheck
npm run build
npx playwright install chromium   # once
npm run test:e2e   # browser tests, isolated server on port 43219
npm run verify     # all of the above in sequence
```

`TEST_DATABASE_URL` must name a database that already exists and must differ from the application database. Tests create and drop disposable schemas inside it; they never create databases and must never fall back to the application database.

Known environment-sensitive case: the browser test `database connection outage retains a queued command and retries the same ID once` depends on the local plaintext proxy and can fail on an otherwise healthy machine. Reproduce it on a clean checkout before treating it as a regression.

## Before you push, and twice before the deadline

The judge path is the full container stack, and it is the part that silently rots because nobody runs it daily:

```sh
docker compose down -v        # discard the old volume, start clean
docker compose up --build
```

Then walk the numbered [judge walkthrough](../../README.md#judge-walkthrough) from a fresh database. Treat a failure here as release-blocking, not as a Docker quirk.

## What we deliberately do not have

- No second development compose file with source bind mounts and hot reload. It is a second configuration to keep in sync and it is slow on macOS. If containerized development is ever needed, use Compose's `develop`/`watch` support rather than hand-rolled mounts.
- No devcontainer and no Nix or mise toolchain pinning. Both are reasonable later; neither earns anything before the submission deadlines.
- No Testcontainers. The disposable-schema approach is faster, at the cost of requiring `TEST_DATABASE_URL` to exist. Revisit after the competition.
