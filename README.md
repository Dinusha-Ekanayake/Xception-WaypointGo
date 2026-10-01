# Waypoint Dispatch

**One delivery. Every handoff accounted for.**

A working Designathon/Hackathon solution for Waypoint Group's four roles: store manager, dispatcher, loader and driver. It connects confirmed demand, explainable allocation, loading checks, offline delivery proof and independent store receipt. Datathon models and submissions are outside this build.

## Repository and runtime

`main` is the current integration branch; `dev` replaces the former `master` branch and may lag behind `main`. The repository is [Xception-WaypointGo](https://github.com/kavindamihiran/Xception-WaypointGo).

- `frontend/`: Next.js UI, same-origin API proxy, offline storage and browser tests.
- `backend/`: Spring Boot REST API, authentication, planning, account administration and PostgreSQL access.
- `data/` and `migrations/`: tracked reference data CSVs and shared versioned SQL.
- `docs/`: architecture, deployment, design rationale and submission evidence.
- `docs/development-docs/`: how to work on the repository. Local setup, the architecture plan and the development log.

Maven `target/` directories and compiled Java classes are ignored and are rebuilt locally.

## Run a fresh copy

Everything in Docker (Docker Engine 24+ with Compose v2):

```sh
cp .env.example .env        # set POSTGRES_PASSWORD and ADMIN_PASSWORD (12+ characters)
docker compose up --build
```

The one-shot `init` service runs `migrate`, `import-reference` and `account-create` against the fresh database. Each step is idempotent, so a restart changes nothing that is already there. Then open http://localhost:3000 and sign in as `ADMIN_EMAIL` with `ADMIN_PASSWORD` (defaults `admin@waypoint.local` / `local-testing-only`). Health is at http://localhost:8080/health/readiness and metrics at http://localhost:8080/prometheus.

For day-to-day work run PostgreSQL in Docker and the application natively: see [development.md](docs/development-docs/development.md). Production deployment is in [deployment.md](docs/deployment.md).

## Backend commands

The backend jar serves by default. Operational commands run explicitly, never as a side effect of a build or a request:

| Command | Does | Needs |
| --- | --- | --- |
| `migrate` | Applies pending SQL in `migrations/`, atomically, under an advisory lock | `DATABASE_URL` |
| `import-reference` | Stages, validates and publishes the CSVs in `data/` as a reference version; identical content is a no-op | `DATABASE_URL`, `DATA_DIR` |
| `account-create` | Creates one account; an existing email is left unchanged | `ACCOUNT_EMAIL`, `ACCOUNT_NAME`, `ACCOUNT_PASSWORD`, `ACCOUNT_ROLE` |

Roles: `admin`, `dispatcher`, `loader`, `driver`, `store_manager`, `auditor`. Every later account change is a command through `POST /api/commands`.

## Configuration

Typed and validated in `backend/.../platform/config/AppProperties`; the process refuses to start when misconfigured. The full list, with defaults, is `backend/src/main/resources/application.properties` and [.env.example](.env.example). The ones you are most likely to set:

| Variable | Meaning |
| --- | --- |
| `DATABASE_URL` | Required. May point at a database that is down: that is an outage, not a misconfiguration |
| `COOKIE_SECURE` | `1` behind HTTPS |
| `LOG_FORMAT` | `ecs` for JSON logs (set in both compose files) |
| `OTLP_EXPORT`, `OTLP_ENDPOINT`, `TRACE_SAMPLE` | Trace export, off unless both of the first two are set |
| `SESSION_ABSOLUTE_LIFETIME`, `SESSION_IDLE_LIFETIME` | Default `12h`, `2h` |
| `LOGIN_MAX_FAILURES`, `LOGIN_THROTTLE_WINDOW` | Default `8`, `15m` |
| `MAX_BODY_BYTES` | Default 4 MiB |
| `WAREHOUSE_BASE_URL`, `WAREHOUSE_API_KEY` | External warehouse API; the key never reaches a browser |

## Verification

```sh
cd backend && mvn verify             # unit, architecture and integration tests
cd frontend && npm test              # frontend boundary tests
cd frontend && npm run typecheck && npm run build
cd frontend && npm run test:e2e      # browser smoke tests, after npm run build
cd frontend && npm run verify        # all of the above except e2e
```

Integration tests use `TEST_DATABASE_URL` when it is set, a throwaway PostgreSQL 16 container when Docker is available, and are skipped with a stated reason otherwise. `TEST_DATABASE_URL` must name a dedicated database, never the application one. CI (`.github/workflows/checks.yml`) runs all of this on every pull request to `dev` and `main`, and before every deploy.

## Documentation

**Start here.** Three questions, three documents:

| Question | Document |
| --- | --- |
| **What are we building, and why this shape?** | [SYSTEM-ARCHITECTURE.md](SYSTEM-ARCHITECTURE.md) |
| **What do we build next, in what order?** | [docs/architecture/FOUNDATION-PLAN.md](docs/architecture/FOUNDATION-PLAN.md) |
| **What is already done, and what is in flight?** | [docs/development-docs/development-log.md](docs/development-docs/development-log.md) |

`SYSTEM-ARCHITECTURE.md` sits at the root on purpose: it is the entry point, the way `README.md` is. `docs/architecture/` holds the detail behind it.

**The design, in `docs/architecture/`:**

- [MODULES.md](docs/architecture/MODULES.md), every module: its layers, owned data, commands, events, invariants and failure modes
- [RULES-AND-POLICIES.md](docs/architecture/RULES-AND-POLICIES.md), every operational rule with its source and status, and the conflicts between sources
- [ASSUMPTIONS.md](docs/architecture/ASSUMPTIONS.md), what we treat as true but have not proved, plus the parameter register
- [EDGE-CASES.md](docs/architecture/EDGE-CASES.md), each case with its behaviour, enforcement point, detection signal and test
- [DATA-MODEL-REVIEW.md](docs/architecture/DATA-MODEL-REVIEW.md), the schema findings and the corrected model
- [schema/](docs/architecture/schema/README.md), the target schema design, superseded for `ref` and `iam` by the live `migrations/`

**Working on the repository, in `docs/development-docs/`:**

- [development.md](docs/development-docs/development.md), local setup: PostgreSQL in Docker, the application native
- [development-log.md](docs/development-docs/development-log.md), what changed and why, newest first

**Submission material, in `docs/`:** [design rationale](docs/design-rationale.md), [design mapping](docs/design-mapping.md), [AI disclosure](docs/ai-disclosure.md), [deployment](docs/deployment.md), [verification](docs/verification.md) and the [submission checklist](docs/submission-checklist.md).

The application and supporting documents do not constitute an uploaded competition entry. Public deployment, design-file export, video recording/upload and form submission remain team actions.
