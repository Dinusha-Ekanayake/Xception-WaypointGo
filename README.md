# Waypoint Dispatch

**One delivery. Every handoff accounted for.**

A working Designathon/Hackathon solution for Waypoint Group's four roles: store manager, dispatcher, loader and driver. It connects confirmed demand, explainable allocation, loading checks, offline delivery proof and independent store receipt. Datathon models and submissions are outside this build.

## Repository and runtime

### Admin console UI preview

Run the frontend and open `/super-admin/demo` or `/admin/demo`. The responsive console includes account forms, role permission toggles, individual overrides, depot/outlet/vehicle assignments and an activity log. Demo changes persist only in the current browser tab; no real accounts or invitations are created.

Only the super admin preview can create or manage admins. The authenticated `/admin` and `/super-admin` routes explicitly show that live account administration is not connected yet. The [permission map and integration notes](docs/architecture/ADMIN-CONSOLE.md) distinguish booklet requirements from proposed additions and describe the backend work still required.

Run UI checks with `npx playwright test --config playwright.admin.config.ts` from `frontend/` (installed Chrome required).

`main` is the current integration branch; `dev` replaces the former `master` branch and may lag behind `main`. The repository is [Xception-WaypointGo](https://github.com/kavindamihiran/Xception-WaypointGo).

- `frontend/`: Next.js UI, same-origin API proxy, offline storage and browser tests.
- `backend/`: Spring Boot REST API, authentication, planning, account administration and PostgreSQL access.
- `data/` and `migrations/`: tracked synthetic reference/seed data and shared versioned SQL.
- `docs/`: architecture, deployment, design rationale and submission evidence.
- `docs/development-docs/`: how to work on the repository. Local setup, the architecture plan and the development log.

Maven `target/` directories and compiled Java classes are ignored and are rebuilt locally.

## Run a fresh copy

The quickest fresh demo uses Docker:

```sh
cp .env.example .env
# Set private POSTGRES_PASSWORD and SEED_PASSWORD values.
docker compose up --build -d
```

For day to day development the database runs in Docker and your code runs natively, which keeps hot reload and the debugger fast. Full setup is in [docs/development-docs/development.md](docs/development-docs/development.md); the short version is three terminals:

```sh
docker compose up -d db   # PostgreSQL alone on 127.0.0.1:5432
cd backend && mvn spring-boot:run
cd frontend && npm run dev
```

`compose.yaml` builds production images and is the path judges follow, not a development environment. Run the full stack before pushing anything that touches configuration, and twice before each deadline.

You need Node.js 22.13+, Java 17+ and Maven. PostgreSQL 16+ is only needed if you prefer a local install over the Compose database. Export DATABASE_URL, DEMO_MODE=1 and a private SEED_PASSWORD in the backend terminal (Spring does not read Next.js .env files):

```sh
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="migrate"
mvn spring-boot:run -Dspring-boot.run.arguments="seed"
mvn spring-boot:run
```

In a second terminal:

```sh
cd frontend
npm ci
npm run build
npm start
```

Open http://localhost:3000. For development use `npm run dev` (with the Spring Boot backend running from `backend/`, see `backend/README.md`); use the production build for offline testing. Builds and requests never initialize the database. Repeated migration/seed commands preserve existing records.

[Vercel + Neon setup and local Docker commands](docs/deployment.md) cover pooled connections, initialization, environment variables and the deployment smoke test. The required CSVs are included in tracked `data/`; the ignored original competition folder is not required. SQLite is no longer a runtime dependency; existing SQLite files remain untouched and are not imported.

For an isolated demonstration, point Spring's `DATABASE_URL` at a separate empty PostgreSQL database and run the initialization commands. Spring uses that URL for migrations as well as runtime access. `DATABASE_URL_UNPOOLED` is used by the backup scripts and legacy Node migration command, not automatically by Spring. Use a separate database for future production data.

## Accounts and configuration

| Role | Email | Assignment |
| --- | --- | --- |
| Dispatcher | dispatcher@waypoint.local | Both depots |
| Loader | loader@waypoint.local | Peliyagoda |
| Driver | driver@waypoint.local | Vehicle allocated to the original OUT001 walkthrough order |
| Store manager | store@waypoint.local | OUT001 |

The example local demo password is `Waypoint2026!`; use the private `SEED_PASSWORD` chosen during hosted database initialization.

For Compose, copy root `.env.example` to root `.env`. For local Next.js, create `frontend/.env.local` with `BACKEND_URL=http://127.0.0.1:8080`; keep database and seed credentials in the backend environment. Spring does not automatically load either file. `SEED_PASSWORD` only applies when accounts are first created. Set a private password before exposing a fresh instance; changing the variable does not rotate existing passwords. Use `COOKIE_SECURE=1` behind HTTPS. HTTPS or localhost is required for service workers. Keep deployment credentials private and share the judge accounts through the competition's intended channel.

`DEMO_MODE=1` uses `DEMO_NOW`, defaulting to February 13, 2026, 15:30 Sri Lanka time. The supplied calendar ends June 28, 2026. In Spring, `DEMO_MODE=0` uses actual time and extends the operating calendar at startup from one year back to two years ahead, using Monday-Saturday operations. Supplied dates and optional `CALENDAR_FILE` overrides take precedence. See [calendar configuration](docs/deployment.md) before real use. Demo mode is shown in the dispatcher workspace.

## Judge walkthrough

Use four browser profiles or independent private sessions. Driver and loader screens work at phone width. The walkthrough uses **2026-02-14**; dates on the source dataset are intentionally historical.

1. **Store:** choose Place order, enter cases, kg and m³, and submit. Wait for the confirmed order ID and eligible run date. Ambient and chilled goods are separate orders.
2. **Dispatcher:** select February 14 and Generate draft. Expand a trip to inspect both load limits, stop order, handling, receiving-window margins, fuel and return/turnaround. The sidebar lists actual deferred orders.
3. Open **Explore alternatives** on a deferred order. Feasible choices show arrival and incremental fuel/distance; rejected choices state failed constraints. Applying a choice revalidates the complete plan. A no-fit result does not claim global impossibility.
4. On `OVERLOAD-001`, record a concrete follow-up. This labelled fixture is a 1,150 kg chilled order for a van-only outlet, just above the 1,040 kg refrigerated van capacity. It has already been skipped, so publication requires an explanation. Splitting goods is a dispatcher follow-up, not an implemented automatic split feature.
5. Choose **Publish plan**, review the totals for all depots, then **Confirm publication**. The depot filter does not limit publication. The plan is now locked. Deferred orders enter the next unpublished operating run, retaining identity, skip count and history. February 14 keeps the original decision snapshot. The store sees queue eligibility, not a guaranteed arrival.
6. **Driver:** note its assigned vehicle and first OUT001 stop. **Loader:** choose that dated trip with Show trip. Flag a shortfall on the OUT001 order, with quantity and reason. Departure is blocked.
7. **Dispatcher:** Progress -> Record replacement. Explain how the damaged/missing goods were replaced. **Loader:** refresh, recheck and Mark loaded for every order in that trip, in reverse stop order. Wait for Ready for departure.
8. **Driver:** refresh online and allow the run to download. Switch the browser offline. Start the stop, wait for **Saved on this device**, and reload offline. Choose I've arrived, then Record delivery. Supply receiver, photo (PNG/JPEG under 1 MB), and drawn signature. Save and wait for acknowledgment before reloading again. Use only while safely stopped.
9. Reconnect and open Sync queue. Accepted records disappear only after server confirmation. Expired sessions can sign back into the same account without clearing local work. A stale version stays in Needs review; inspect the shared record before explicitly discarding/re-entering the action. Sign-out is blocked with pending work.
10. **Store:** refresh, open View receipt to inspect the driver's proof, then Confirm receipt or Report issue. Proof images download separately; wait for the device-save acknowledgment to view them offline later. Receipt confirmation is a separate event. **Dispatcher:** Progress and the shared history show the resulting state.

## Scenarios and planning policy

The collapsed Judge scenarios selector lists historical mixed-brand days, simulated outcomes, workshop shortage, weekly fuel pressure and labelled boundary cases. Source provenance is in [data/README.md](data/README.md) and `data/provenance.json`.

- Priority: previous skips, Fresh, chilled, then earliest closing window.
- Trips serve one brand/district and the vehicle's home depot. Weight and volume, temperature, van-only access, receiving windows, availability, two trips per day and fuel are enforced.
- Travel uses district free-flow times plus a disclosed 25% buffer. Vehicles depart no earlier than 03:30. Early arrivals wait; Fresh arrives by 08:00 or the outlet's earlier close; mall handling must finish within access hours. Return travel and a 20-minute turnaround precede another trip.
- Weekly fuel reservations include other published plans in the same Monday-Sunday week, this draft, return travel and the fuel-pressure fixture where applicable. The simulated opening consumption becomes effective February 17 and persists through February 22 before the weekly reset.
- Alternative assignments test insertion positions and revalidate later trips. Draft revision and order version checks reject stale decisions. Published coverage must match the current queue.
- Shortfall resolution means replacement and a full loader recheck. No silent reduction of the store's order.
- Dispatcher exception resolution records redelivery, returned goods or closure with a note. Redelivery links a new order while preserving the original proof and history.
- There is no trained lateness probability, forecast, live GPS, automatic SMS or in-app navigation claim. A district map search is not outlet-level navigation.

## Verification

```sh
cd frontend
# First export TEST_DATABASE_URL for a dedicated test database.
npm test
npm run typecheck
npm run build
npx playwright install chromium
npm run test:e2e
```

The dedicated database named by `TEST_DATABASE_URL` must already exist and must differ from the application database; tests create disposable schemas, not databases. `npm test` runs legacy Node regressions, Maven tests and Spring HTTP integration tests. `npm run verify` adds typechecking, the production build and browser tests. The legacy `db:migrate` and `db:seed` npm scripts remain available for regression work; use Spring commands for the running application. Browser tests start a production server on port 43219 and use independent sessions. See [verification.md](docs/verification.md) for observed results and limits. The Playwright runner uses its documented [web-server lifecycle](https://playwright.dev/docs/test-webserver) and [offline emulation](https://playwright.dev/docs/emulation#offline).

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

**Predating the rewrite**, and describing the prototype removed at tag `prototype-v0` rather than the current system: [docs/architecture.md](docs/architecture.md), [docs/data-model.md](docs/data-model.md), [docs/code-structure.md](docs/code-structure.md) and [docs/development-docs/enterprise-architecture-plan.md](docs/development-docs/enterprise-architecture-plan.md). Read them as history, not as a description of what is built.

The refinement preserves the existing role flows, Instrument Sans / IBM Plex Mono and teal action palette. It replaces static operational placeholders with real state, adds validated assignment alternatives and actual deferral carryover, exposes all loading manifests, and supports reauthentication for every role. No Figma file was changed or claimed to be submitted; the team must compare these refinements to its actual Day 5 design export.

The application and supporting documents do not constitute an uploaded competition entry. Public deployment, design-file export, video recording/upload and form submission remain team actions.
