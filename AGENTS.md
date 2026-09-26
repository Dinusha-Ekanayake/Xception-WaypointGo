# Repository Guidelines

Waypoint Dispatch is a delivery planning and execution system: a Next.js App Router frontend and a Spring Boot backend over PostgreSQL, serving four field roles across ordering, planning, loading, delivery and receipt.

**Read before changing anything structural:** [SYSTEM-ARCHITECTURE.md](SYSTEM-ARCHITECTURE.md) for the principles and layers, [docs/architecture/MODULES.md](docs/architecture/MODULES.md) for module contracts, [docs/code-structure.md](docs/code-structure.md) for the folder layout, and the top entries of [docs/development-docs/development-log.md](docs/development-docs/development-log.md) for what is in flight.

## Architecture Rules

These are not style preferences. A change that breaks one of them will be rejected in review, and most are enforced by tests.

1. **Package by business capability, layer inside.** Each module has `contract/`, `domain/`, `application/`, `infrastructure/`, `web/`. Layer-first packages are what let one class reach 1780 lines.
2. **`domain/` is pure.** No Spring, no SQL, no Jackson, no clock reads. Time and randomness are parameters. If a rule cannot be tested without a database, it is in the wrong layer.
3. **Only `application/` opens transactions and makes authorization decisions.** One command, one transaction.
4. **Modules connect three ways only:** a domain event through the outbox, a contract query into another module's published interface, or a port for anything outside the process. Never read another module's tables or import its domain.
5. **One definition of every rule.** A constraint is expressed once in the constraint registry and read by the engine, the override path, the publication gate and the UI.
6. **Every write is idempotent and versioned.** Command id plus payload fingerprint, and `expected_version` on every mutation. Never merge, never last-writer-wins.
7. **Scope filtering happens in SQL**, not in application code after a broad read. This is a security property, not an optimisation.
8. **Decisions are recorded.** A deferral, override, rejection or failure carries an actor, a reason and a timestamp.
9. **Degrade visibly.** When a dependency is down, continue with reduced function and say so on screen. Silent degradation is worse than failure.
10. **No new class named `*Service`.** Use `*Handler` for commands, `*Query` for reads, `*Policy` for decisions, `*Repository` for persistence.

Boundaries are enforced by `ModuleBoundaryTest.java` and `frontend/tests/boundaries.test.ts`. Run them before adding any cross-module import.

## Project Structure

`frontend/app/` holds routing only (pages, layout, styles); every `/api/*` request proxies to the backend via `frontend/app/api/[...path]/route.ts` and runtime `BACKEND_URL`. Application code is under `frontend/src/`: `src/app-shell/` for session gating, role routing and shared client state, `src/roles/{dispatcher,loader,driver,store}/` for role screens, `src/shared/{ui,domain,offline}/` for primitives, client types and the offline outbox. Cross-folder imports use the `@app-shell/*`, `@roles/*` and `@shared/*` aliases; same-directory imports stay relative. `frontend/lib/` holds only the legacy Node service used by scripts and tests; it runs under `node --experimental-strip-types`, cannot resolve tsconfig aliases, and must use relative `.ts` paths.

`backend/` is a Maven Spring Boot 3 service (`com.waypoint.dispatch`) owning the REST contract, planning rules, auth and PostgreSQL access. Modules live under `shared/`, `platform/`, `referencedata/`, `identity/`, `planning/`, with `service/DispatchService.java` still being decomposed.

Static assets in `frontend/public/`; `frontend/scripts/build-sw.mjs` generates the service worker during production builds. Reference CSVs are read from tracked `data/` at the repo root; the Node service resolves `data/` from either the repo root or `frontend/`. Java tests in `backend/src/test/`, Spring HTTP integration tests in `frontend/tests/spring/`, legacy Node tests in `frontend/tests/`, browser tests in `frontend/tests/e2e/`.

Documentation: `SYSTEM-ARCHITECTURE.md` at the root; `docs/architecture/` for module specs, the data model review and the edge case register; `docs/` for design rationale, deployment and submission evidence; `docs/development-docs/` for local setup and the development log.

Versioned SQL migrations live in root `migrations/`, shared by both stacks. Node resolves them from the repo root or `frontend/`; Spring defaults to `../migrations` from `backend/` and accepts `MIGRATIONS_DIR`. Migrations apply atomically through the backend `migrate` command with a transaction-scoped advisory lock. Existing SQLite files in `var/` are legacy data and must be preserved.

## Local Development

PostgreSQL runs in Docker, the application runs natively: `docker compose up -d db`, then `mvn spring-boot:run` from `backend/` and `npm run dev` from `frontend/`. `compose.yaml` builds production images and is the deployment path, not a development environment; do not add source bind mounts to it. Full setup and the map of which process reads which configuration file is in [docs/development-docs/development.md](docs/development-docs/development.md).

## Build and Test Commands

Node.js 22.13+, Java 17+ with Maven, PostgreSQL 16+. From `frontend/`: `npm ci` to install, `npm run typecheck`, `npm run build` (production build plus service worker), `npm start` to serve that build when checking offline behaviour. Initialize the database explicitly through the backend (`mvn spring-boot:run -Dspring-boot.run.arguments="migrate"` then `"seed"`). **Builds and requests must never migrate or seed.**

`npm test` runs legacy Node regressions, Maven tests and Spring HTTP integration tests. `npm run test:e2e` runs Playwright browser tests (install Chromium first with `npx playwright install chromium`; they need a production build and start an isolated server on port 43219). `npm run verify` runs the sequence.

## Testing Requirements

Tests require an already-created dedicated database in `TEST_DATABASE_URL` and use disposable PostgreSQL schemas. Never fall back to the application database.

| Level | Covers | Required for |
| --- | --- | --- |
| Domain unit | Constraints, state transitions, no database, clock injected | every rule change |
| Integration | Command bus, transactions, version guards | every command |
| Authorization | One denied-scope case | every command |
| Concurrency | Parallel commands on the same aggregate | version guards, allocation |
| Browser | Offline save, reload, sync, conflict | offline changes |

Every edge case in [docs/architecture/EDGE-CASES.md](docs/architecture/EDGE-CASES.md) has a test column. **A case without a test is a case that is not handled**: when you fix one, add its row and its test together.

Naming: `*Test.java` under `backend/src/test/`, `*.test.ts` for Node and Spring HTTP tests, `*.spec.ts` under `tests/e2e/`. Before submitting, run `npm run typecheck` and `npm run build`, then exercise any affected role flow by hand.

## Coding Style

TypeScript with the repository's strict settings, two-space indentation, and the surrounding file's quote style. React components and types in `PascalCase`, functions and variables in `camelCase`. Frontend validation schemas live in `frontend/src/shared/domain/types.ts`. A component file over roughly 300 lines gets split into a container and a view. There is no configured formatter or lint command.

## API and Error Contract

Mutations go through the command endpoint with a command id and `expected_version`. Errors cross the API as RFC 9457 `application/problem+json` with a `violations` extension carrying failed constraints; the error body is part of the contract because clients branch on it. Reads are cursor-paginated on a keyset, never `OFFSET`. Additive API changes never break; removals need a new version and a deprecation window.

## Data and Migration Rules

Migrations are forward-only, checksummed, and run as an explicit step. Use expand and contract: add a column nullable, backfill in batches, then add the constraint. Never add `NOT NULL` without a default to a large table in one statement; it takes a full table lock.

`timestamptz` everywhere, `numeric` with explicit precision for weight, volume and fuel (never floating point in a capacity constraint), UUIDv7 surrogate keys with dataset identifiers kept as unique natural keys. Index every foreign key. Operational records are never deleted; they reach terminal states.

## Security Rules

Deny by default: an unlisted command or unmatched scope is `403` plus an audit entry, never an empty result. Authorization is re-checked inside the transaction. The database actor is set with `SET LOCAL` inside the transaction, never plain `SET`, because a pooled connection would leak it into the next request. The application database role must not hold `BYPASSRLS`. Denied attempts are audited. Never log payloads containing personal data.

## Development Log

Several people and agents work here in parallel. Read the top entries of [docs/development-docs/development-log.md](docs/development-docs/development-log.md) before starting. Append an entry when you finish a unit of work that changes code, structure, configuration or a decision, using the template at the top of that file. Keep entries to a few terse lines and link to the relevant document for detail. Skip typo and formatting fixes. Credit the GitHub user who owns the work; never record agent, tool or model names.

Markdown is ignored by default in `.gitignore` because agent sessions scatter scratch `.md` files. Maintained documents are allow-listed there; if you add one that belongs in the repository, add its exception in the same commit.

## Commit and Pull Requests

`main` is the integration branch; `dev` replaces `master`. Keep Maven `target/` and `*.class` untracked. Short, imperative subjects such as `fix: reject stale delivery commands`. Do not add co-author, agent or AI attribution trailers to commits or pull request descriptions. Branches are short-lived and rebased daily; long-lived branches across a schema change lose weekends. In pull requests, explain the behaviour changed, link the issue, list verification performed, and include screenshots for visible UI changes.

## Configuration and Data Safety

Copy root `.env.example` to root `.env` for Compose. Put local frontend `BACKEND_URL` in `frontend/.env.local` and export backend settings separately; Spring does not read Next.js env files. Do not commit secrets or database files. Set a private `SEED_PASSWORD` before creating accounts on an exposed instance; changing it does not rotate existing passwords. Use `COOKIE_SECURE=1` behind HTTPS. Preserve tracked `data/` and a persistent PostgreSQL volume for deployed instances.
