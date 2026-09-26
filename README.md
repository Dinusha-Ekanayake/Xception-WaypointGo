# Waypoint Dispatch

**One delivery. Every handoff accounted for.**

A working Designathon/Hackathon solution for Waypoint Group's four roles: store manager, dispatcher, loader and driver. It connects confirmed demand, explainable allocation, loading checks, offline delivery proof and independent store receipt. Datathon models and submissions are outside this build.

## Run a fresh copy

Node.js 22.13+ is required; Node 24 is the Docker runtime.

Use PostgreSQL 16 or newer locally, or a fresh Neon database. Copy `.env.example` to `frontend/.env.local` and set the server-only database connection and seed password before initializing:

```sh
cd frontend
npm ci
npm run db:migrate
npm run db:seed
npm run build
npm start
```

Open http://localhost:3000. For development use `npm run dev` (with the Spring Boot backend running from `backend/`, see `backend/README.md`); use the production build for offline testing. Builds and requests never initialize the database. Repeated migration/seed commands preserve existing records.

[Vercel + Neon setup and local Docker commands](docs/deployment.md) cover pooled connections, initialization, environment variables and the deployment smoke test. The required CSVs are included in tracked `data/`; the ignored original competition folder is not required. SQLite is no longer a runtime dependency; existing SQLite files remain untouched and are not imported.

For an isolated demonstration, point `DATABASE_URL` and `DATABASE_URL_UNPOOLED` at a separate empty PostgreSQL database and run the initialization commands. Use a separate database for future production data.

## Accounts and configuration

| Role | Email | Assignment |
| --- | --- | --- |
| Dispatcher | dispatcher@waypoint.local | Both depots |
| Loader | loader@waypoint.local | Peliyagoda |
| Driver | driver@waypoint.local | Vehicle allocated to the original OUT001 walkthrough order |
| Store manager | store@waypoint.local | OUT001 |

The example local demo password is `Waypoint2026!`; use the private `SEED_PASSWORD` chosen during hosted database initialization.

Copy `.env.example` to `.env.local` for local Next.js configuration, or `.env` for Compose. `SEED_PASSWORD` only applies when accounts are first created. Set a private password before exposing a fresh instance; changing the variable does not rotate existing passwords. Use `COOKIE_SECURE=1` behind HTTPS. HTTPS or localhost is required for service workers. Keep deployment credentials private and share the judge accounts through the competition's intended channel.

`DEMO_MODE=1` fixes the order clock at February 13, 2026, 15:30 Sri Lanka time. The supplied calendar ends June 28, 2026. `DEMO_MODE=0` uses actual time and enforces the planning cutoff, so operating data must be extended before using current dates. Demo mode is clearly shown in the dispatcher workspace.

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

Tests require an explicit `TEST_DATABASE_URL` pointing to a separate PostgreSQL database and create disposable schemas. Browser tests start a production server on port 43219 and use independent sessions. See [verification.md](docs/verification.md) for observed results and limits. The Playwright runner uses its documented [web-server lifecycle](https://playwright.dev/docs/test-webserver) and [offline emulation](https://playwright.dev/docs/emulation#offline).

## Design continuity and submission material

- [Design rationale, personas and degradation journey](docs/design-rationale.md)
- [Design-to-code mapping and departures](docs/design-mapping.md)
- [Architecture diagram](docs/architecture.md)
- [Data model](docs/data-model.md)
- [AI disclosure](docs/ai-disclosure.md)
- [Designathon and Hackathon demo scripts](docs/demo-script.md)
- [Submission readiness](docs/submission-checklist.md)

The refinement preserves the existing role flows, Instrument Sans / IBM Plex Mono and teal action palette. It replaces static operational placeholders with real state, adds validated assignment alternatives and actual deferral carryover, exposes all loading manifests, and supports reauthentication for every role. No Figma file was changed or claimed to be submitted; the team must compare these refinements to its actual Day 5 design export.

The application and supporting documents do not constitute an uploaded competition entry. Public deployment, design-file export, video recording/upload and form submission remain team actions.
