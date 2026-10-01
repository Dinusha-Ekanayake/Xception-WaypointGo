# Repository Guidelines

Waypoint Dispatch is a delivery planning and execution system: a Next.js App Router frontend and a Spring Boot backend over PostgreSQL, serving four field roles across ordering, planning, loading, delivery and receipt.

**Three documents answer three questions.** What are we building and why: [SYSTEM-ARCHITECTURE.md](SYSTEM-ARCHITECTURE.md). What next and in what order: [docs/architecture/FOUNDATION-PLAN.md](docs/architecture/FOUNDATION-PLAN.md). What is already done: the top entries of [docs/development-docs/development-log.md](docs/development-docs/development-log.md). Before any structural change also read [docs/architecture/MODULES.md](docs/architecture/MODULES.md) for module contracts and [docs/architecture/RULES-AND-POLICIES.md](docs/architecture/RULES-AND-POLICIES.md) for the rule a change might break. The prototype documents were deleted; they remain at tag `prototype-v0`.

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

`frontend/app/` holds routing only (pages, layout, styles); every `/api/*` request proxies to the backend via `frontend/app/api/[...path]/route.ts` and runtime `BACKEND_URL`. Application code is under `frontend/src/`: `src/app-shell/` for the session gate and role routing, `src/shared/api/` for the client, command envelope and RFC 9457 parsing, `src/shared/offline/` for the tiered write queue, `src/shared/ui/` for the design system once Figma lands, and `src/roles/{dispatcher,loader,driver,store}/` which hold placeholders until then. Cross-folder imports use the `@app-shell/*`, `@roles/*` and `@shared/*` aliases; same-directory imports stay relative. Offline capability is tiered by role, not uniform: driver full, loader and store manager resilient, dispatcher online only. The reasoning is in `src/shared/offline/tiers.ts`.

`backend/` is a Maven Spring Boot 3 service (`com.waypoint.dispatch`). The prototype was removed at tag `prototype-v0`; what exists now is the foundation skeleton. `shared/` is the framework-free kernel, `platform/` holds config, the `Database` seam, web plumbing and telemetry, `referencedata/` and `identity/` are built, and `ordering/`, `planning/`, `loading/`, `execution/`, `receipt/`, `issues/`, `notification/`, `sync/`, `warehouse/` and `intelligence/` so far hold only their `contract` package: the views, query interfaces, command payloads and events other modules build against. Each module grows `domain`, `application`, `infrastructure` and `web` beside it. `ModuleBoundaryTest` discovers modules by package, so a module may import another only through its `contract`, and `EventCatalogueTest` holds the event records to the catalogue in MODULES.md. Every transaction opens with `SET LOCAL ROLE waypoint_<module>` and `SET LOCAL app.actor_id`, via `platform/db/Database`.

Static assets in `frontend/public/`; `frontend/scripts/build-sw.mjs` generates the service worker during production builds. Reference CSVs are read from tracked `data/` at the repo root by the backend `import-reference` command. Java tests (unit, architecture and integration) in `backend/src/test/`, frontend static boundary tests in `frontend/tests/`, browser tests in `frontend/tests/e2e/`.

Documentation: `SYSTEM-ARCHITECTURE.md` at the root; `docs/architecture/` for module specs, the rule catalogue, the assumption and parameter registers, the data model review and the edge case register; `docs/` for design rationale, deployment and submission evidence; `docs/development-docs/` for local setup and the development log; `docs/issues/` for one folder per GitHub issue (see Issue Documents below).

Versioned SQL migrations live in root `migrations/`. The target schema under `docs/architecture/schema/migrations/` is a design under review and is deliberately not applied: the migrator reads `*.sql` directly inside `migrations/` and does not recurse. Spring defaults to `../migrations` from `backend/` and accepts `MIGRATIONS_DIR`. Migrations apply atomically through the backend `migrate` command with a transaction-scoped advisory lock. New migration files are named `YYYYMMDDTHHMM_<module>_<what>.sql` (they sort after `001`-`009`); each module writes its own, and because modules share no foreign keys, one module's migration never waits for another's.

## Local Development

PostgreSQL runs in Docker, the application runs natively: `docker compose up -d db`, then `mvn spring-boot:run` from `backend/` and `npm run dev` from `frontend/`. `compose.yaml` builds production images and is the deployment path, not a development environment; do not add source bind mounts to it. Full setup and the map of which process reads which configuration file is in [docs/development-docs/development.md](docs/development-docs/development.md).

## Build and Test Commands

Node.js 22.13+, Java 17+ with Maven, PostgreSQL 16+. From `frontend/`: `npm ci` to install, `npm run typecheck`, `npm run build` (production build plus service worker), `npm start` to serve that build when checking offline behaviour. Initialize the database explicitly through the backend: `migrate`, then `import-reference` to stage, validate and publish a reference version. **Builds and requests must never migrate, import or seed.** An import either publishes completely or changes nothing, and identical content is a no-op by content hash rather than a new version.

`mvn verify` from `backend/` runs the Java unit, architecture and integration tests. From `frontend/`, `npm test` runs the boundary tests, `npm run test:e2e` runs Playwright browser tests (install Chromium first with `npx playwright install chromium`; they need a production build and start an isolated server on port 43219), and `npm run verify` runs test, typecheck, build and `mvn verify` in sequence. CI runs the same on every pull request to `dev`.

## Testing Requirements

Tests require an already-created dedicated database in `TEST_DATABASE_URL`, and `FoundationIntegrationTest` refuses to run when it equals `DATABASE_URL`. Unit tests run without it; integration tests are skipped when it is unset, so `mvn package` alone does **not** prove the database paths work. Run `TEST_DATABASE_URL=... mvn package` before claiming a database change is verified.

| Level | Covers | Required for |
| --- | --- | --- |
| Domain unit | Constraints, state transitions, no database, clock injected | every rule change |
| Integration | Command bus, transactions, version guards | every command |
| Authorization | One denied-scope case | every command |
| Concurrency | Parallel commands on the same aggregate | version guards, allocation |
| Browser | Offline save, reload, sync, conflict | offline changes |

Every edge case in [docs/architecture/EDGE-CASES.md](docs/architecture/EDGE-CASES.md) has a test column. **A case without a test is a case that is not handled**: when you fix one, add its row and its test together.

Naming: `*Test.java` under `backend/src/test/`, `*.test.ts` for Node tests, `*.spec.ts` under `tests/e2e/`. Before submitting, run `npm run typecheck` and `npm run build`, then exercise any affected role flow by hand.

## Coding Style

TypeScript with the repository's strict settings, two-space indentation, and the surrounding file's quote style. React components and types in `PascalCase`, functions and variables in `camelCase`. Frontend mirrors of every backend contract (views, command payloads, events) live in `frontend/src/shared/domain/`, one file per module, re-exported from `types.ts`; a contract change on the backend is a change there. A component file over roughly 300 lines gets split into a container and a view. There is no configured formatter or lint command.

## Platform Conventions

Configuration is typed and validated in `platform/config/`; the process refuses to start rather than run misconfigured. `DATABASE_URL` must be set, but may point at a database that is down: that is an outage, not a misconfiguration.

Every command goes through `platform/messaging/CommandBus`, which authorizes, checks the idempotency receipt, runs the handler, and commits the state change, the receipt and the audit row together. **It fails closed**: with no `CommandAuthorizer` wired, every command is denied. Never reach around it.

Events are published with `platform/messaging/EventPublisher` inside the transaction that made the change, and delivered afterwards by `OutboxRelay`, at least once, to every `EventSubscriber` of the type, each in its own transaction under its module role. A subscriber needs only to exist as a bean: never call one directly, and write it so that applying an event twice is harmless. One aggregate's events arrive in write order; there is no order between aggregates. An event that keeps failing is dead-lettered and replayed with `platform:ReplayEvent`. Tests run with the relay worker off (`app.relay.enabled=false`) and deliver explicitly.

Metrics go through `platform/observability/Metrics`, never Micrometer directly, so the backend can change without touching business code. Every edge case in EDGE-CASES.md names a detection signal, and that signal belongs here.

Liveness is `/health/liveness` and readiness is `/health/readiness`; readiness includes the database, liveness deliberately does not. Prometheus is at `/prometheus`. Structured JSON logging is off locally and enabled with `LOG_FORMAT=ecs`. Never log payloads containing personal data.

## API and Error Contract

Mutations go through the command endpoint with a command id and `expected_version`. Errors cross the API as RFC 9457 `application/problem+json` with a `violations` extension carrying failed constraints; the error body is part of the contract because clients branch on it. Reads are cursor-paginated on a keyset, never `OFFSET`. Additive API changes never break; removals need a new version and a deprecation window.

## External Product Catalogue

The product catalogue and order-to-product mapping come from the external warehouse API. Waypoint caches them in `ref.products`; it does not own them. The catalogue is a reconstruction from order totals, accurate to 1% on weight and volume, with `verified_real_sku = False` on every row and no stock, temperature or pricing.

Therefore, without exception:

1. **Capacity constraints read order-level `order_weight_kg` and `order_volume_m3`.** Never sum product lines to decide whether a load fits. A 1% error on a 5,510 kg truck is 55 kg of invisible overload.
2. **Temperature comes from the order's `temp_requirement`,** never inferred from products.
3. **Never show a candidate product as a real SKU** without labelling it inferred.
4. **Do not encode "at most two product types per order"** in schema, UI or validation. It is an artifact of the reconstruction.
5. When the warehouse is unreachable, fall back to order-level totals and say so on screen.

## Data and Migration Rules

Migrations are forward-only, checksummed, and run as an explicit step. Use expand and contract: add a column nullable, backfill in batches, then add the constraint. Never add `NOT NULL` without a default to a large table in one statement; it takes a full table lock.

Modules reference each other by id with no foreign key; foreign keys point only into `ref` and `iam`. `timestamptz` everywhere, `numeric` with explicit precision for weight, volume and fuel (never floating point in a capacity constraint), UUIDv7 surrogate keys with dataset identifiers kept as unique natural keys. Index every foreign key. Operational records are never deleted; they reach terminal states.

`row_version` is the concurrency revision and `plan_version` is the business plan revision; they are different things and a table may need both. Every mutation is `UPDATE ... WHERE id = ? AND row_version = ?` and fails on zero rows affected: a version column nobody checks is not concurrency control. A published plan and its children are immutable; changes create a new version that supersedes the old one. Status `CHECK` constraints restrict values but not transitions, so the legal state graph is enforced in the domain.

Database roles: `waypoint_migrator` owns the tables and runs migrations; `waypoint_app` owns nothing, holds no `BYPASSRLS`, is `NOINHERIT`, and is what the pool is meant to connect as. Every pooled connection runs as `waypoint_app`: the compose files log in as it, with a password `migrate` sets from `DATABASE_URL`, and a login as the owner (local development, CI) is dropped to it by the pool's `SET ROLE`. Only `migrate` uses the owner, through `MIGRATION_DATABASE_URL` when set. A separate non-superuser `waypoint_migrator` owner is still open on issue #5. Each module owns a schema of the same name and a role `waypoint_<module>` granted only that schema (`SELECT, INSERT, UPDATE`, never `DELETE`), plus read on `ref` and on the iam scope tables, so every transaction begins `SET LOCAL ROLE waypoint_<module>; SET LOCAL app.actor_id = '<uuid>';`. Forgetting the role is a permission error, which is intended. Roles are cluster-wide, so migrations assert attributes with `ALTER ROLE` rather than assuming `CREATE ROLE` ran. Table owners bypass RLS by default, so use `FORCE ROW LEVEL SECURITY`. Mixing `=` with `&&` in a GiST exclusion constraint needs `CREATE EXTENSION btree_gist`. Outbox workers claim batches `FOR UPDATE SKIP LOCKED`, or the relay serialises to one instance.

## Authorization

Authorization is **policy as data**, not code. An administrator authors a document with `Effect`, `Action`, `Resource` and `Condition`, attaches it to a user or a role, and permissions change with no deployment. Evaluation runs in process in `identity/domain/policy/`; an external policy service is still not justified.

Rules that are not negotiable, catalogued as `R-IAM-*` in RULES-AND-POLICIES.md:

- Actions are `<module>:<Verb>`, resources are `wpt:<module>:<type>:<id>`, both accepting `*`.
- Order is fixed: default Deny, any matching Deny wins, then Allow, else Deny.
- **A new action needs a catalogue row and a handler.** An action absent from `iam.action_catalogue` is rejected when the policy is written, because a typo would otherwise deny silently forever.
- A policy version is immutable. Changing a policy creates a new version and moves the default.
- **Effective access is `policy AND scope`.** Policies decide actions; the scope tables decide rows, and row-level security enforces them through `app.actor_has_depot` and `app.actor_has_outlet`. A policy can never widen someone's reach past their scope.
- Any policy change clears the whole policy cache. Do not make invalidation clever; a clever cache eventually serves a revoked permission.

Sessions are opaque and server-side, never JWTs: disabling an account and changing a policy must both take effect on the next request, not at token expiry.

## Security Rules

Deny by default: an unlisted command or unmatched scope is `403` plus an audit entry, never an empty result. Authorization is re-checked inside the transaction. The database actor is set with `SET LOCAL` inside the transaction, never plain `SET`, because a pooled connection would leak it into the next request. The application database role must not hold `BYPASSRLS`. Denied attempts are audited. Never log payloads containing personal data.

## Development Log

Several people and agents work here in parallel. Read the top entries of [docs/development-docs/development-log.md](docs/development-docs/development-log.md) before starting. Append an entry when you finish a unit of work that changes code, structure, configuration or a decision, using the template at the top of that file. Keep entries to a few terse lines and link to the relevant document for detail. Skip typo and formatting fixes. Credit the GitHub user who owns the work; never record agent, tool or model names.

Markdown is ignored by default in `.gitignore` because agent sessions scatter scratch `.md` files. Maintained documents are allow-listed there; if you add one that belongs in the repository, add its exception in the same commit.

## Issue Documents

Every GitHub issue that builds a module or changes structure gets a folder `docs/issues/<NNN>-<slug>/`, where `<NNN>` is the issue number padded to three digits and `<slug>` is the module or subject in kebab case, for example `docs/issues/008-ordering/`. It holds two files, both allow-listed by `!docs/issues/**/*.md`:

- `PLAN.md`, written **before code**: current state, which layer owns each dependency, the open decisions with the one chosen, and the PR breakdown. Review the plan, not the diff, when the approach is in question.
- `WALKTHROUGH.md`, written **before the issue closes**: what was built, layer by layer with file paths; each flow (command, event, job) end to end; how to run and verify it locally; decisions taken and the documents they were recorded in; known gaps and which issue owns them.

A walkthrough explains; it does not replace the rule catalogue, the edge case register or the development log. Record the rule in RULES-AND-POLICIES, the case in EDGE-CASES and the entry in the log, and link to them from the walkthrough rather than restating them.

## Commit and Pull Requests

`main` is the integration branch; `dev` replaces `master`. Keep Maven `target/` and `*.class` untracked. Short, imperative subjects such as `fix: reject stale delivery commands`. Do not add co-author, agent or AI attribution trailers to commits or pull request descriptions. Branches are short-lived and rebased daily; long-lived branches across a schema change lose weekends. In pull requests, explain the behaviour changed, link the issue, list verification performed, and include screenshots for visible UI changes.

## Configuration and Data Safety

Copy root `.env.example` to root `.env` for Compose. Put local frontend `BACKEND_URL` in `frontend/.env.local` and export backend settings separately; Spring does not read Next.js env files. Do not commit secrets or database files. Set a private `SEED_PASSWORD` before creating accounts on an exposed instance; changing it does not rotate existing passwords. `COOKIE_SECURE` defaults to on; set `COOKIE_SECURE=0` only for local development over plain HTTP. Preserve tracked `data/` and a persistent PostgreSQL volume for deployed instances.
