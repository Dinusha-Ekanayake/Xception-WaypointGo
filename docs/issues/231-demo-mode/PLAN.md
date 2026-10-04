# Demo mode: runtime control room, simulated vehicles and scenario deck

Issue [#231](https://github.com/Rashmika-D-N/Xception-WaypointGo/issues/231), including the scenario-control-panel comment. PR 1 of 6, documentation only. Reviewed against `dev` at `3f46b73` on 2026-10-04. These are proposed implementation decisions, not claims that demo mode is built. Stop after each PR as the issue requests.

## Goal and booklet alignment

A presenter can prepare and guide a genuine four-role workflow from an administrator control room, move operational time, show vehicles moving, and demonstrate failures without editing server configuration. All operational writes retain their owning module's command, policy, scope, version and audit checks. OFF remains today's behaviour.

The source is the root `Challenge Booklet.pdf`:

| Booklet | Demonstration and evidence |
| --- | --- |
| Pages 4-7: ordering closes at 16:00, planning, loading, delivery, receipt | The issue's numbered 12-step rehearsal, with a real order, published plan, shortfall, delivery proof and receipt |
| Page 5: weight and volume, temperature, fuel, two trips, access and windows | Peak-day allocation plus capacity and mall-window scenario cards; reuse the constraint registry and official Task 2B validator |
| Page 6: shared loader device and driver's phone, unreliable coverage | Loader PIN, phone-sized loader and driver screens, real browser offline/reload/reconnect test |
| Pages 12-13: realistic seeded day, public four-role walkthrough, responsive web app, 5-8 minute video | Runbook linked from README; two preview rehearsals; screenshots at 393 px for both driver and loader and 1440 px for dispatcher |
| Page 13: completeness 20%, allocation 20%, recovery 10%, architecture 25% | Prioritize working cross-role handoffs and visible recovery over the number of scenario cards |

Datathon model work is outside this issue. Existing forecasts may be shown, with their actual model/fallback status.

## Current state and verified discrepancies

Paths below are repository-relative. Java paths are under `backend/src/main/java/com/waypoint/dispatch/` unless prefixed otherwise.

- `platform/config/TimeConfig.java` supplies one `Clock.system()`. `ordering/application/CloseOrdersForDayHandler.java` refuses early close; changing the operational clock can use this existing rule unchanged.
- `ordering/application/CutoffJob.java` runs at real `0 0 16 * * *`. `platform/scheduling/ScheduledJobRunner.java` uses a Spring `CronTrigger`; changing `Clock.now()` does not reschedule it or trigger catch-up. Its lease and durable run records must be retained.
- `ordering/application/DeliveryDaySeed.java` imports 85 S1 rows, directly creates confirmed reservations and publishes `order.placed`. It is a CLI bootstrap, not a command handler. Any existing `SEED-WH-*` reservation anywhere makes it return without seeding another day. It is not a runtime reset.
- `ordering/domain/OrderRef.java` derives ordinary `WPO-` references. There is no reliable `WPO-SEED*` selector. Seed identity must use an explicit run manifest plus verified `SEED-WH-*` references, never a broad `WPO-*` match.
- `WaypointApplication.seedDeliveryDay` also calls `FleetDaySeed`, grants OUT001, sets a loader PIN and assigns available vehicles. Demo may not call this CLI orchestrator or another module's application classes.
- Seed reservations were never made at the external warehouse. Cancelling one cannot honestly demonstrate restored external stock. Live placement still needs a working warehouse; without one it remains stock unknown.
- `platform/db/Database.java` opens module transactions; calling the command bus recursively inside a demo handler risks switching transaction role and actor. Multi-step work must be a persisted workflow with separate command dispatches after the initiating command commits.
- `ModuleBoundaryTest.platformCarriesNoBusinessModule` prohibits even a Platform import of a Demo contract. `TimeConfig` cannot import a Demo implementation. Use a Platform-owned extension port implemented by Demo.
- Driver `data/position.ts` flushes at 60 seconds. Store `screens/LiveMapCard.tsx` and dispatcher `data/useDay.ts` poll positions at 15 seconds. Driver `screens/RouteMap.tsx` fetches the trail once per trip and uses `recorder.here` for its marker. It needs a demo-only server-position source; merely shortening polling elsewhere is insufficient.
- Frontend business-time calculations also use the browser clock (store cutoff countdown, dispatcher Live date/age, driver run time). A server banner alone leaves these screens inconsistent.
- `IdentityQuery.driverOn(vehicleId, date)` already finds the assigned driver. `RecordPositionsHandler` checks assignment on each point's date; `PositionPolicy` limits batches to 100, requires increasing timestamps and rejects points more than five minutes into the future.

## Layer ownership and interfaces

| Owner | New work and boundary |
| --- | --- |
| `demo/contract/` | Framework-free command names/payloads, settings, simulation, scenario and run views; `DemoQuery` for authenticated operational state, separate admin detail reads |
| `demo/domain/` | `DemoSettings` validation, simulation transitions, pure `RouteWalker` and data-only `ScenarioRegistry`; time, route and randomness are parameters |
| `demo/application/` | Admin authorization, versioned handlers, settings query, workflow coordinator and simulation job; transactions only here; command dispatch outside coordinator transactions |
| `demo/infrastructure/` | Repositories for Demo-owned rows and implementation of Platform's time-adjustment port; no SQL into operational schemas |
| `demo/web/` | Authenticated reads through application queries; writes remain `/api/commands`; no controller SQL |
| Platform | `ModuleRole.DEMO`, neutral time-adjustment port and clock composition, scheduler trigger seam; no Demo imports |
| Ordering | Additive seed preparation command and scoped closure query; owns all seed order construction and any permitted terminal transitions |
| Identity | Existing grant/assignment commands and contract queries, plus an additive seed-account preparation command if existing commands cannot safely provision the loader PIN; no credential extraction |
| Reference data | Existing reference queries; additive owner command for date-scoped S1 fleet preparation if required; Demo never invokes `FleetDaySeed` directly |
| Warehouse | Demo-only fault decorator at the existing external port boundary, scoped by run targets; preserve existing stock semantics |
| Frontend | `shared/domain/demo.ts`, a shared runtime reader/business-time helper, shell banner, `roles/admin/demo/` container/views, demo-only driver/map adapters |

Proposed Platform interface: `TimeAdjustment.snapshot(Instant realNow)` returns enabled state, offset and revision, with a disabled default. Demo implements it with a bounded, independent read using real time for expiry. It must not depend on `CommandBus` or call the composed clock. A read failure produces disabled state and a metric, never stale enabled state. Wire lazily so clock creation does not require an initialized database.

Demo's schema owns settings, simulations, scenario runs, workflow steps and seed manifests. Every mutable row is versioned; the simulator persists its next point/step identity before dispatch so crash retries reuse the same command id and payload. All operational references are ids without cross-module foreign keys. Add the usual role membership, integration receipt/audit grants, forced RLS and indexes. Read policies separate the public-to-signed-in settings projection from admin-only run details.

## Decisions to review before PR 2

### Runtime switch and time

Choose database state, initially OFF, independently of the existing unused `DEMO_MODE` environment export. No startup seed or environment variable turns it on. Enable, disable, clock and settings changes require a reason and `expectedVersion`. Persist the reason in the Demo action record because the command bus's generic audit reason is only "command applied".

Use a maximum absolute offset of 7 days for the first version and reject dates outside the published reference calendar. Record real audit time and simulated target time separately. A normal clock change cannot go behind the latest committed close time returned by a new Ordering contract query, and cannot occur with a running simulation or unresolved workflow command. Disable always succeeds when authorized, zeros the offset and stops simulations, even if returning to real time is a rewind. It does not reopen closed orders or undo published work.

Keep authentication, policy expiry, OAuth, upload links, retry leases, retention and audit partition scheduling on real time. Apply demo time to operational business rules and their jobs. The current shared injection also drives `SessionRegistry`: a naive global replacement logs presenters out after a jump (the existing ordering integration test explicitly documents this). PR 2 must introduce explicit real-time wiring without changing existing tests or production OFF semantics.

The approximately one-second cache conflicts with "next request" across replicas. Choose an authoritative settings read at each request boundary and immediately before worker dispatch, with request-local reuse only. Do not serve stale enabled settings after a read failure. If later caching is needed, it must preserve these guarantees, including cross-replica disable. Health failures in Demo must not affect application readiness or ordinary work.

A clock update queues operational catch-up after commit, executed through a Platform scheduler port with the existing job lease. Only explicitly allowed business jobs (initially Ordering cutoff and stock retry) run, once per settings revision; retention and partition jobs never catch up on simulated time. Handler acceptance and cron activation are separate tests.

A 15-second banner poll cannot hide UI on the next server request by itself. Refresh runtime state on role navigation, focus and completed API requests as well as the fallback poll; use a shared reader to avoid one request per component. A failed refresh restores normal intervals and stops simulation controls; it must not leave an enabled banner claiming a current clock.

### Safe preparation and reset

Choose additive, command-driven scenario preparation. Reject database snapshot restore, row deletion and rewinding terminal records in the shared application. They conflict with the issue's non-negotiable data safety and immutable published plans.

`demo:ResetDay` means stop the current demo run, retain its history and prepare a new run on the next eligible empty operating date. Replaying the same reset command returns the same run. The result explicitly reports old and new dates. A scoped owner query must reject a target date with unrelated operational work. Repeating the exact same date after delivery remains unavailable, with that reason shown. A disposable database restored outside this application is the alternative if exact same-date snapshots become mandatory.

Ordering's new seed command takes `runId`, depot, date, source S1 and version, derives repeatable per-row ids, validates the tracked dataset and records the manifest. It reuses owning-domain construction and event publication without changing the existing one-time CLI seed. Seed order ids and warehouse references are authoritative membership, not prefix alone. Identity and Reference preparation are separate commands; existing grants/assignments are checked and left alone, never stolen or silently rewritten. Do not reset passwords or a changed PIN.

This runtime clock is database-wide. It cannot isolate normal operations by merely marking seeded rows. Enablement is for the dedicated demo deployment; enabling it on a database serving real operations cannot meet isolation. The control room must clearly show the affected environment and date. Reset's seeded-only guarantee does not imply clock isolation.

### Simulator actor, transactions and disable races

Choose the actual active assigned driver, resolved through `IdentityQuery`, with a distinct demo device identity and a linked initiating administrator/run. Never dispatch as `Actor.SYSTEM`, which has wider rights. Before each step recheck administrator permission, driver activity, policy, assignment, trip state and enabled revision; the real command bus rechecks target policy and scope. Missing or revoked authority stops the run with a visible reason.

`demo:*` remains admin-only. Therefore the issue's driver "Simulate drive" cannot directly call `demo:StartSimulation`. First version starts the hero vehicle in the control room and shows the driver its simulation status. A driver request would need a separately authorized request action with admin approval; no impersonated admin session in the driver app.

Persist a run's allow-listed command, actor, payload fingerprint, expected version and stable command id before dispatch. Each underlying command has its own transaction, then the coordinator records its result. After a crash replay that exact command before advancing. Do not update a failed command's payload under its old id. Wait for outbox projections and queued planning to become ready with a bounded timeout and a retryable visible state, never assumed sleeps or direct subscriber calls.

Serialize disable/stop and each simulator dispatch with the same per-run dispatch fence, acquired outside the business transaction and held until its result is recorded. Disable waits for already-started work to settle, then marks all runs stopped; no later point may commit after the disable response. A second replica uses the same fence and revision check. Do not hold a Demo transaction open while dispatching another module's command.

`RouteWalker` consumes immutable route inputs and elapsed time. Use route geometry where available, otherwise clearly labelled straight legs through existing approximate locations; never invent exact outlet coordinates or navigation. Hero waits for a real terminal stop outcome; auto sends `delivery:RecordArrival` and `delivery:Record` with real versions and supported outcomes. Auto must not fabricate proof photos or receipt confirmation. Cancelled, superseded or unreleased trips stop with a reason. Fleet permits one active simulation per vehicle/date, not overlapping trips for one vehicle.

### Scenario faults and the presentation comment

Choose run-scoped faults, stored in Demo, affecting only the selected demo requests/references. A decorator returns the existing unavailable outcome rather than opening the shared real warehouse circuit. Health shows simulated outage and actual circuit state separately. OFF clears faults and uses the original adapter. Unrelated orders must pass through during an enabled scenario as well as when OFF. A seed reservation never becomes a real warehouse reservation by toggling Demo.

The comment's four top-level cards become guided decks: normal delivery, late delivery/exception, capacity shortfall, mall window conflict. Each shows its dataset/run/date (not "snapshot restored"), roles, steps, expected outcome, current checklist and last command result. Selection is read-only; Start prepares the run with a reason and confirmation. Preparation failures remain visible by step.

Show actual dedicated account emails and copy buttons, plus role links. Password hashes cannot be read back; never expose `SEED_PASSWORD` or server secrets. A presenter may enter a dedicated demo password locally for copying, held only in memory and cleared on disable/navigation, never sent to the backend or persisted. The loader PIN follows the same rule.

Pause/resume initially controls simulations only. Freezing business time requires an anchored clock model rather than an offset; label it unavailable until separately implemented and tested. Do not label a paused truck "time paused". Normal Fresh delivery starts around its morning window; an 11:00 scenario is appropriate for late/mall examples, not every normal run. Post-publication mall resequencing must use a supported revision workflow; never mutate the published plan.

## Scenario audit against current modules

Every card returns `available` and a concrete prerequisite/reason. "Supported" below describes the underlying path, not a completed demo runner. PR 5 adds a new integration test for each entry, including unavailable cases.

| Key | Existing path and actual outcome | Prerequisite or limit; where to watch |
| --- | --- | --- |
| `cancel-before-plan` | `CancelOrderHandler`, `order:Cancel`, then warehouse event handling | Confirmed, unallocated order. A seeded order proves local cancellation only; real stock restoration needs a real reservation and delivered outbox event. Store orders |
| `cancel-after-loading` | `Order.cancel`, ORD-10 refuses after loading begins | Wait for loaded state; keep row/version unchanged on refusal. Store orders, then Issues |
| `amend-after-allocation` | `AmendOrderHandler`, `Order.requireAmendable`, ORD-05 | Allocated order returns conflict requiring dispatcher revalidation, not necessarily a stale-version error. Test stale version separately. Store/dispatcher |
| `order-after-cutoff` | `order:Place`, `DeliveryDateResolver`, `Cutoff` | Move to 16:05, use a real outlet and catalogue lines; show rolled date. Warehouse availability still controls confirmation. Store |
| `holiday-order` | Delivery date resolution uses Reference calendar | Pick a published non-operating date; placement is allowed, delivery rolls. Store confirmation |
| `chilled-and-ambient` | Warehouse reply validation and `StockPort.Rejected` | Mixed temperature is rejected, not automatically split. Need suitable live catalogue/warehouse response or explicit unavailable reason. Store order sheet |
| `out-of-stock` | `StockPort.Insufficient` / partial reservation path | Actual stock shortage with per-line quantities; no invented stock totals. Requires warehouse or a separately labelled contract fixture. Store |
| `warehouse-down` | Demo decorator yields existing unavailable outcome | Run-scoped fault, then a new order becomes stock unknown; existing confirmed reservations remain unchanged. Store and dispatcher |
| `stock-unresolved-at-cutoff` | `CutoffJob` moves unreserved demand with `stock_unresolved` | Explicit clock-change catch-up required; job currently cron-only. Store deferral notice |
| `peak-day-deferrals` | S1 preparation, `order:CloseForDay`, queued `plan:Generate` | Wait for generation and render actual reasons; do not pin a served count from an old engine. Dispatcher and store |
| `loader-shortfall` | `loading:Start`, `loading:Check`, `loading:Release` | Published manifest, current versions and personal loader identity; release gate still applies. Loader and store short counts |
| `driver-offline` | Position ingestion accepts ordered historical fixes | Persist withheld points and replay bounded batches. This proves telemetry catch-up only; actual offline writes need browser disconnect/reload/sync. Dispatcher trail and driver queue |
| `late-arrival` | Execution arrival/lateness policies and real run sheet | Set operational time past the selected window or let time pass; slowing alone is not immediate lateness. Ordinary outlet and mall outcomes differ. Store and dispatcher |
| `delivery-refused-partial` | `delivery:Record` accepts failed and partial outcomes | Use supported failure reason or delivered units/lines, current delivery version; no fake proof. Receipt remains the store's command. Driver, store and Issues |
| `vehicle-breakdown` | `delivery:ReportVehicleStatus` and issue flow | Assigned driver on that date; validate supported status and resulting event/issue. Dispatcher Live and Issues |
| `unauthorized-outlet` | Real scoped read as the dedicated store account | Use a different ungranted outlet and assert 403 plus audit; an admin read would prove nothing. Read probe is audited, not disguised as a business command |
| `duplicate-submit` | Command bus receipt by actor and command id | Replay identical `order:Place`, assert same result and one order/reservation. Changed payload under that id must conflict. Store and audit |

The normal/late/capacity/mall guided cards compose these primitives. A mall card cannot promise a working dispatch revision until its existing command and projection flow has been verified against current `dev`.

## Clock audit and OFF compatibility

The production-source search found these direct wall-clock calls beyond the `Clock.system()` method reference:

| Site | Decision |
| --- | --- |
| `platform/web/AuditController.java:83`, `Instant.now()` | Audit range stays real time; replace direct read with the explicit real-time seam for deterministic tests |
| `sync/application/SubmitBatchHandler.java:181,210`, `Instant.now()` | Queue-age and drain metrics stay real time; inject that seam, never treat simulated timestamps as wall-clock lag |

PR 2 also audits injected-clock consumers and SQL `now()`/`CURRENT_DATE`, not just direct Java calls. Identity temporal scope must use the requested service date for simulator actions. SQL defaults for audit/receipt creation stay real time. `System.nanoTime()` measures duration and is not a business-clock defect. Frontend device timestamps and offline queue ordering remain real; demo route points use the explicit server demo time, and business UI displays derive from that same time.

Required OFF proof: existing test files remain unchanged, new tests are additive, fresh settings are disabled, the real warehouse adapter receives identical calls, driver flush is 60 seconds, map polling is unchanged, and no role banner/simulation controls render. The admin entry for enabling Demo is the necessary exception to "no demo control rendered while off"; hide operational panels, retain the admin-only Enable entry. A literal hidden Enable screen would make runtime enablement impossible.

## PR sequence and implementation checkpoints

### PR 1: this plan

- [x] Read booklet, issue body/comment, architecture/foundation/module/rule documents, status and latest contributor logs.
- [x] Inspect seed, clock, authorization, scheduler, command, position and map paths.
- [x] Record ownership, decisions, contradictions, all 17 scenarios and acceptance boundaries.
- [ ] Review and merge the plan before product code; stop after this PR.

### PR 2: backend core

- [ ] Add pure `demo/domain/DemoSettings.java` and new `DemoSettingsTest`: default OFF, bounds, disable clears offset, invalid intervals and stale version. Proposed interval ranges: point 500-10000 ms, flush 1000-60000 ms, speed 1-60 times, default point 2000 ms and flush 5000 ms.
- [ ] Add `migrations/YYYYMMDDTHHMM_demo_settings.sql`, Demo role/RLS, action catalogue and admin policy version. Settings singleton plus run/step/manifest tables; simulations may be added in PR 4. No edits to merged migrations.
- [ ] Add `DemoSettingsQuery`, `EnableHandler`, `DisableHandler`, `SetClockHandler`, `UpdateSettingsHandler`, `ResetDayHandler` and `DemoController`. Commands use `wpt:demo:settings:global` or run resource, explicit admin policy AND role eligibility AND scope for targets. All except Enable require enabled state.
- [ ] Add neutral Platform time port, composed business clock and explicit real-time wiring; keep domain pure and Platform independent of Demo. Add fallback, startup, multi-replica disable and session-survives-jump tests.
- [ ] Add owning-module preparation commands/queries and persisted coordinator. Test reset replay, foreign data on chosen date, changed PIN/assignment preservation, partial-step retry and completed-day refusal. Any missing owner path returns unavailable, never direct cross-schema SQL.
- [ ] Implement authenticated `GET /api/demo` with `enabled`, `now`, `offsetSeconds`, `banner`, intervals and `rowVersion`; detailed control-room reads admin-only. Require a reason on every mutation and keep it in recorded outcomes.
- [ ] Run new integration/authorization/concurrency tests on a real dedicated database, existing backend suite unchanged, boundary and event catalogue tests. Add R-DEMO and edge-case rows with tests in this PR, not at final closeout.

### PR 3: frontend core

- [ ] Add `frontend/src/shared/domain/demo.ts`, export from `types.ts`, shared runtime reader and business clock; failed/disabled state restores normal behaviour.
- [ ] Add `app-shell/DemoBanner.tsx` and `roles/admin/demo/` container with header, clock, settings and reset views under 300 lines. Include disabled-state Enable entry, reason, confirmation and version-conflict refresh.
- [ ] Cover OFF, enable, presets, custom time, reset refusal, settings failure, disable and role denial in new shell browser specs. Check the existing shell suite unchanged. Use shared wording and 24-hour Colombo time.

### PR 4: simulator

- [ ] Add `RouteWalkerTest` before `RouteWalker`: zero-length legs, missing/approximate coordinates, bounded speed, service wait, strictly ordered points, hero stop and route end.
- [ ] Add persisted simulations and start/pause/resume/stop handlers, fleet coordinator and `SimulationJob`; scheduler uses real ticks and supplies business time. Test duplicate tick, crash after dispatch, revocation, cancelled/superseded trip, concurrent disable and two replicas.
- [x] Add demo-only server-position input to driver `RouteMap`, 5-second position polling (built as 4 s via `livePoll`, trails included; GPS pauses during a simulated drive without an on-screen note) for store/dispatcher and configurable flush. Restore GPS/normal intervals immediately on OFF; prevent simultaneous real GPS and simulator writes for the simulated vehicle while showing why GPS recording is paused.
- [ ] New driver/store/dispatcher browser cases demonstrate the same server point on all maps. Actual offline queue test remains separate from telemetry withholding.

### PR 5: scenario deck and presenter guide

- [ ] Add registry, availability evaluation, run-scoped fault decorator, run/scenario/health reads, `RunScenario` and `QuickStep` workflows; keyset paginate runs and simulations. Health composes published owner queries/Platform ports, never Demo SQL into another schema.
- [ ] Add one integration test per registry row, including unsupported prerequisites, real outcome/refusal, audit and repeated command. No scenario opens policy or bypasses scope.
- [ ] Build guided cards, scenario description, real-time-labelled clock, account links/local credential copy, checklist and bounded run log. Quick steps use current versions and wait for actual generation/outbox state before publish/release.
- [ ] Test warehouse fault isolation, pass-through OFF, recovery, timeout and retry, no-key unavailable cards, admin-only reads and removal of local credentials on exit.

### PR 6: runbook, walkthrough and preview evidence

- [ ] Write and allow-list `docs/demo-runbook.md`; link the judge walkthrough from README. Complete `WALKTHROUGH.md` with paths, command/event/job flows, local verification, decisions and owned gaps.
- [ ] Reconcile MODULES, rule/edge-case registers, STATUS and the owner's log with what actually shipped. Do not close #231 for a foundation PR.
- [ ] Run existing `mvn verify`, official Task 2B validator, `npm test`, typecheck, build, shell and all four role browser suites without editing existing tests. Record counts and skips; skipped database tests do not satisfy acceptance.
- [ ] Rehearse all 12 issue steps on preview twice, each from safe reset onto its reported new date. Record deployed commit, environment, two run ids, timings, role screenshots, OFF restoration, failure recovery and remaining unavailable cards.

## Review focus and completion boundary

The highest-risk cases are clock jumps invalidating sessions, disable racing an in-flight simulation, reset modifying non-seed rows, crash retries repeating warehouse writes, and an unavailable scenario claiming success. Each has a test checkpoint above. No performance or production-readiness claim follows from this docs PR.

The selected defaults that differ from the issue sketch are explicit: safe new-date reset instead of shared database restore, admin-started hero instead of a driver admin command, simulated outage instead of opening the real circuit, real security time beside business time, and unavailable frozen-clock controls. Review these before PR 2. If exact same-date snapshot restore or driver-started control is required, revise the design and acceptance criteria rather than silently weakening the constraints.
