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

## Seeded accounts

`docker compose up` creates one account per role, all with the password in `SEED_PASSWORD` (default `Waypoint2026!` locally). The deployed system uses a private `SEED_PASSWORD`, given in the submission form.

| Role | Email | Scope after the seed | Device |
| --- | --- | --- | --- |
| Dispatcher | `dispatcher@waypoint.local` | Peliyagoda depot | desktop, 1440 px |
| Loader | `loader@waypoint.local` | Peliyagoda depot; dock PIN `DEMO_LOADER_PIN` (default `2468`) | phone or tablet |
| Driver | `driver@waypoint.local` | Peliyagoda depot; every Peliyagoda vehicle on the seeded day | phone |
| Store manager | `store_manager@waypoint.local` | outlet `DEMO_OUTLET` (default `OUT001`, Waypoint Fresh, Colombo) | desktop or phone |

`admin@waypoint.local` and `auditor@waypoint.local` exist too but are not part of the walkthrough. Locally every role is on http://localhost:3000. On the deployed system each role has its own address: `dispatcher.`, `loader.`, `driver.` and `store.` in front of `waypointgo.live`.

## Judge walkthrough

The seed places the Task 2B peak day: 85 confirmed Peliyagoda orders across all three brands on the first operating day still open for ordering, with the scenario's 10 workshop vehicles out. That is more than the fleet can carry, so the plan has to defer orders. Open the driver and loader at phone width (for example 393 px in the browser's device toolbar).

1. **Store manager: see the order.** Sign in as `store_manager@waypoint.local`. Home and Orders show OUT001's two orders for the seeded day (one ambient, one chilled), confirmed and waiting to be planned.
2. **Dispatcher: one queue.** Sign in as `dispatcher@waypoint.local` and open **Orders**. Every order due at Peliyagoda that day is in one table with its brand, temperature and status. **Close orders** is accepted only after the 16:00 cutoff the day before (R-ORD-01); before then the screen shows the refusal and its rule, and planning still works.
3. **Dispatcher: plan the peak day.** Open **Plan**, choose Peliyagoda and **Generate**. The engine allocates against weight and volume limits, refrigerated vehicles for chilled goods, vans for `van_only` outlets, delivery and mall windows, weekly fuel and at most two trips per vehicle.
4. **Dispatcher: explain the deferrals.** In **Decide**, each order the plan could not place shows the rule that stopped it. Open one to see where it could go; only feasible places can be chosen, and a manual placement needs a reason. In **View plan**, open a trip to see its load against capacity, departure and stop order; **Take off** defers an order with a reason.
5. **Dispatcher: publish.** **Publish** lists what the plan leaves undelivered, then **Confirm publish**. **Vehicles** shows each vehicle's planned fuel against its weekly quota, and Overview lists outlets skipped on earlier runs.
6. **Loader: load in stop order.** Sign in as `loader@waypoint.local`, then enter PIN `2468` on the dock screen. The dock board lists the published trips. Open the trip that carries OUT001: items are listed in reverse stop order, so the first stop is loaded last. Check items off one by one.
7. **Loader: flag a shortfall (degradation).** Mark one item missing or damaged. The shortfall is recorded before departure and reaches the dispatcher's **Issues** inbox. Complete the release checklist and **release** the trip.
8. **Driver: follow the run.** Sign in as `driver@waypoint.local`. Home shows the released trip; **Route** lists its stops in order with their windows.
9. **Driver: deliver with no signal (degradation).** In the browser's developer tools set the network to Offline. Open the OUT001 stop, record the delivery with receiver name, count, photo and signature, and finish. The record is kept on the phone and the screen says it is waiting to sync. Reload the page: it is still there. Set the network back to Online and the queue drains on its own; the server applies each record once.
10. **Dispatcher: watch progress.** **Live** lists vehicles most urgent first, with the delivered stop and anything that still needs the dispatcher.
11. **Store manager: confirm receipt.** Back as the store manager, the delivery shows as arrived. **Receive this delivery**, confirm what arrived per item, or report a problem (missing, damaged, wrong item) with a photo. The report reaches the dispatcher's **Issues** inbox, where it can be taken, resolved and closed.

To start again from an empty database: `docker compose down -v && docker compose up --build`.

## Departures from the Designathon design

The Figma file (pages 04 to 17) is the specification. Where the design shows something no backend module provides yet, it is left out rather than faked:

- **Driver:** vehicle pick-up by QR code, the notifications inbox, fuel logging, call and voice notes, driving mode and the map. The store's handover PIN is shown to the store manager but no driver screen asks for it yet. English only.
- **Dispatcher:** the Forecast and late-risk screens (the Intelligence backend exists, the screens do not), snapshots and compare, regenerate with locked orders, contact store manager, global search and the map. Vehicle interchange approval waits on Loading.
- **Loader:** vehicle interchange and dispatcher handover. Sinhala and Tamil are drafts awaiting a native speaker.
- **Store manager:** notifications, call options, the live map and draft orders.
- **All roles:** the notification inbox and live badge are built in the backend but not yet placed in the role screens.

Role by role detail is in [design-mapping.md](docs/design-mapping.md).

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
