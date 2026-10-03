# Waypoint Dispatch

**One delivery. Every handoff accounted for.**

A working Designathon/Hackathon solution for Waypoint Group's four roles: store manager, dispatcher, loader and driver. It connects confirmed demand, explainable allocation, loading checks, offline delivery proof and independent store receipt. Datathon models and submissions are outside this build.

## Repository and runtime

`dev` is the integration branch: work branches from it and merges into it, and every push to it deploys the preview. `main` is what production runs and moves by a release pull request from `dev`, so it lags `dev` between releases. The repository is [Xception-WaypointGo](https://github.com/kavindamihiran/Xception-WaypointGo).

- `frontend/`: Next.js UI, same-origin API proxy, offline storage and browser tests.
- `backend/`: Spring Boot REST API, authentication, planning, account administration and PostgreSQL access.
- `data/` and `migrations/`: tracked reference data CSVs and shared versioned SQL.
- `docs/`: architecture, deployment, design rationale and submission evidence.
- `docs/development-docs/`: how to work on the repository. Local setup, the status page and the development log.
- `docs/issues/`: one folder per GitHub issue, with the plan written before the code and the walkthrough written after.

Maven `target/` directories and compiled Java classes are ignored and are rebuilt locally.

## Run a fresh copy

Everything in Docker (Docker Engine 24+ with Compose v2):

```sh
cp .env.example .env        # set POSTGRES_PASSWORD and ADMIN_PASSWORD (12+ characters)
docker compose up --build
```

The one-shot `init` service runs `migrate`, `import-reference`, `demo-accounts` and `seed-delivery-day` against the fresh database. Each step is idempotent, so a restart changes nothing that is already there. Then open http://localhost:3000 and sign in as `ADMIN_EMAIL` with `ADMIN_PASSWORD` (defaults `admin@waypoint.local` / `local-testing-only`). Health is at http://localhost:8080/health/readiness and metrics at http://localhost:8080/prometheus.

For day-to-day work run PostgreSQL in Docker and the application natively: `scripts/dev.sh setup` once, then `scripts/dev.sh`. The steps behind those two commands are in [development.md](docs/development-docs/development.md). Production deployment is in [deployment.md](docs/deployment.md).

## Backend commands

The backend jar serves by default. Operational commands run explicitly, never as a side effect of a build or a request:

| Command | Does | Needs |
| --- | --- | --- |
| `migrate` | Applies pending SQL in `migrations/`, atomically, under an advisory lock | `DATABASE_URL` |
| `import-reference` | Stages, validates and publishes the CSVs in `data/` as a reference version; identical content is a no-op | `DATABASE_URL`, `DATA_DIR` |
| `account-create` | Creates one account; an existing email is left unchanged | `ACCOUNT_EMAIL`, `ACCOUNT_NAME`, `ACCOUNT_PASSWORD`, `ACCOUNT_ROLE` |
| `account-grant-depot` | Grants an account a depot scope | `ACCOUNT_EMAIL`, `ACCOUNT_DEPOT` |
| `operator-pin` | Sets a loader's four-digit PIN and badge for shared dock devices | `OPERATOR_EMAIL`, `OPERATOR_PIN`, optional `OPERATOR_EMPLOYEE_CODE` |
| `loading-fixture` | Development only: builds a depot-day's loading manifests from confirmed orders without a published plan | `LOADING_FIXTURE_ENABLED=true`, `--depot` and `--date` (or `LOADING_DEPOT`, `LOADING_DATE`) |
| `demo-accounts` | Creates `<role>@waypoint.local` for each role and grants the depot roles a depot; existing accounts are left unchanged | `SEED_PASSWORD`, optional `DEMO_DEPOT` |
| `seed-delivery-day` | Places the Task 2B peak day (85 Peliyagoda orders, more than the fleet can carry) as confirmed orders on the first open operating day, marks its 10 workshop vehicles out, and grants `store_manager@waypoint.local` the outlet `DEMO_OUTLET`. Runs once per database; `docker compose up` runs it | optional `DEMO_OUTLET` (default `OUT001`) |

Commands can be combined in one run, which starts the application once: `migrate import-reference demo-accounts`. They always run in that order.

Roles: `admin`, `dispatcher`, `loader`, `driver`, `store_manager`, `auditor`. Every later account change is a command through `POST /api/commands`.

## Configuration

Typed and validated in `backend/.../platform/config/AppProperties`; the process refuses to start when misconfigured. The full list, with defaults, is `backend/src/main/resources/application.properties` and [.env.example](.env.example). The ones you are most likely to set:

| Variable | Meaning |
| --- | --- |
| `DATABASE_URL` | Required. May point at a database that is down: that is an outage, not a misconfiguration |
| `MIGRATION_DATABASE_URL` | The owner's connection, read only by `migrate`. Unset, `DATABASE_URL` does both. Set, `DATABASE_URL` must log in as `waypoint_app` |
| `COOKIE_SECURE` | On by default. `0` only for local development over plain HTTP |
| `ALLOWED_ORIGINS` | Extra origins a state-changing request may come from. Usually empty |
| `LOG_FORMAT` | `ecs` for JSON logs (set in both compose files) |
| `OTLP_EXPORT`, `OTLP_ENDPOINT`, `TRACE_SAMPLE` | Trace export, off unless both of the first two are set |
| `SESSION_ABSOLUTE_LIFETIME`, `SESSION_IDLE_LIFETIME` | Default `12h`, `2h` |
| `LOGIN_MAX_FAILURES`, `LOGIN_ADDRESS_MAX_FAILURES`, `LOGIN_IDENTITY_MAX_FAILURES`, `LOGIN_THROTTLE_WINDOW` | Default `8`, `40`, `40`, `15m` |
| `MAX_BODY_BYTES` | Default 4 MiB |
| `WAREHOUSE_BASE_URL`, `WAREHOUSE_API_KEY` | External warehouse API; the key never reaches a browser |
| `LOADING_DOCKS_PER_DEPOT` | Docks Loading spreads a depot's trips over, default `4` |
| `LOADING_FIXTURE_ENABLED` | `true` only in development, to allow `loading-fixture` |

## Verification

```sh
cd backend && mvn verify             # unit, architecture and integration tests
cd frontend && npm test              # frontend boundary and role logic tests
cd frontend && npm run typecheck && npm run build
cd frontend && npm run test:e2e      # browser tests of the shell, after npm run build
cd frontend && npm run verify        # all of the above except e2e

# one browser suite per role, against a production build with a mocked API
cd frontend && npx playwright test -c playwright.dispatcher.config.ts
cd frontend && npx playwright test -c playwright.driver.config.ts
cd frontend && npx playwright test -c playwright.loader.config.ts
```

Integration tests use `TEST_DATABASE_URL` when it is set, a throwaway PostgreSQL 16 container when Docker is available, and are skipped with a stated reason otherwise. `TEST_DATABASE_URL` must name a dedicated database, never the application one. CI (`.github/workflows/checks.yml`) runs the backend tests, the typecheck, `npm test` and the build on every pull request to `dev` and `main`, and before every deploy. The browser suites are not in CI and are run by hand.

## Documentation

**Start here.** Four questions, four documents:

| Question | Document |
| --- | --- |
| **What are we building, and why this shape?** | [SYSTEM-ARCHITECTURE.md](SYSTEM-ARCHITECTURE.md) |
| **What is built, what is left, and what do I pick up?** | [docs/development-docs/STATUS.md](docs/development-docs/STATUS.md) |
| **Why did it change, and what did that leave open?** | [docs/development-docs/development-log.md](docs/development-docs/development-log.md) |
| **What is the base that must not change?** | [docs/architecture/FOUNDATION-PLAN.md](docs/architecture/FOUNDATION-PLAN.md), complete |

`SYSTEM-ARCHITECTURE.md` sits at the root on purpose: it is the entry point, the way `README.md` is. `docs/architecture/` holds the detail behind it.

**The design, in `docs/architecture/`:**

- [MODULES.md](docs/architecture/MODULES.md), every module: its layers, owned data, commands, events, invariants and failure modes
- [RULES-AND-POLICIES.md](docs/architecture/RULES-AND-POLICIES.md), every operational rule with its source and status, and the conflicts between sources
- [ASSUMPTIONS.md](docs/architecture/ASSUMPTIONS.md), what we treat as true but have not proved, plus the parameter register
- [EDGE-CASES.md](docs/architecture/EDGE-CASES.md), each case with its behaviour, enforcement point, detection signal and test
- [DATA-MODEL-REVIEW.md](docs/architecture/DATA-MODEL-REVIEW.md), the schema findings and the corrected model
- [schema/](docs/architecture/schema/README.md), the target schema design, superseded for `ref` and `iam` by the live `migrations/`

**Working on the repository, in `docs/development-docs/`:**

- [STATUS.md](docs/development-docs/STATUS.md), every module and screen: built, partial or not started, what is left, and what to pick up next
- [development.md](docs/development-docs/development.md), local setup: PostgreSQL in Docker, the application native
- [docs/issues/](docs/issues/), per issue: `PLAN.md` before the code and `WALKTHROUGH.md` after it, the best way into a module you did not build
- [AGENTS.md](AGENTS.md), the rules a change is reviewed against
- [development-log.md](docs/development-docs/development-log.md), what changed and why, newest first

**Submission material, in `docs/`:** [design rationale](docs/design-rationale.md), [design mapping](docs/design-mapping.md), [AI disclosure](docs/ai-disclosure.md), [deployment](docs/deployment.md), [verification](docs/verification.md) and the [submission checklist](docs/submission-checklist.md).

The application and supporting documents do not constitute an uploaded competition entry. Public deployment, design-file export, video recording/upload and form submission remain team actions.
