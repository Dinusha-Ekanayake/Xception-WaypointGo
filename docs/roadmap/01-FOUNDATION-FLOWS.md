# 01. Foundation flows and baselines

The normal day, end to end, as the system does it today. Each flow lists:

- the role;
- the commands, which are IAM actions and go through `CommandBus`;
- the events it publishes through the outbox;
- the screens;
- the rules that govern it;
- an acceptance walk.

Phase 1 walks every flow on a fresh install and records the baselines. Nothing new is built until these walks pass, because every later item is measured against them.

## The day in one line

```
store places order -> 16:00 cutoff closes the day -> engine drafts plan -> dispatcher decides and publishes
  -> loader loads in stop order, flags shortfalls, releases -> driver delivers offline, records proof and arrival
  -> store confirms receipt with PIN or disputes -> issues raised, assigned, resolved -> every step audited
```

## Flows

### 1. Ordering (store manager)

- **Commands:** `order:Place`, `order:Amend`, `order:Cancel`, `order:AcceptShortfall`.
- **Events:** `order.placed`, `order.amended`, `order.cancelled`.
- **What happens:**
  - The warehouse reservation is made synchronously through `StockPort`.
  - Capacity always uses the order-level weight and volume, never summed lines.
- **Screens:** store Place order (date strip with the 28-day outlook), Orders.
- **Rules:** R-ORD-*, R-ML-07 (outlook is advice).
- **Acceptance:**
  1. Place an ambient and a chilled order for OUT001.
  2. Both show as confirmed.
  3. A cancel after cutoff is refused with its rule shown.

### 2. Cutoff and close (system, dispatcher)

- **Commands:** `order:CloseForDay`, also run by the 16:00 cutoff job (R-ORD-15).
- **Events:** `orders.closed`.
- **What happens:** Planning drafts the plan from it.
- **Acceptance:**
  1. On the business clock, pass 16:00.
  2. Each depot's day closes.
  3. A draft plan exists with the unplaceable orders deferred.

### 3. Planning and publishing (dispatcher)

- **Commands:**

  | Purpose | Commands |
  | --- | --- |
  | Generate | `plan:Generate`, `plan:Replan` |
  | Decide on orders | `plan:Defer`, `plan:KeepDeferred`, `plan:Override` |
  | Edit trips and stops | `plan:Swap`, `plan:EditTrip`, `plan:ReorderStops` |
  | Lock | `plan:Lock`, `plan:Unlock` |
  | Snapshots | `plan:SaveSnapshot`, `plan:RestoreSnapshot` |
  | Publish and change | `plan:Publish`, `plan:Revise` |
  | Talk to stores | `plan:ContactStore` |

- **Events:** `plan.published`, `plan.revised`, `order.deferred`, `order.unservable`.
- **Engine:** the constraint registry, then cheapest insertion, then the reefer re-pass, then the cost stage (OR-Tools ALNS).
- **The constraints:** weight and volume, reefer for chilled goods, van-only outlets, windows, weekly fuel, at most two trips per vehicle per day.
- **Screens:** dispatcher Orders, Plan (Decide, View plan, Publish), Vehicles, Overview.
- **Rules:** R-PLN-*.
- **Acceptance:**
  1. On the seeded Task 2B day, Generate.
  2. About a dozen orders are deferred, each with its rule, and one is unservable.
  3. Publish.
  4. The official validator (`tools/check_allocation`) passes on the engine output, as CI already checks (PLN-17).

### 4. Loading (loader)

- **Commands:** `loading:Start`, `loading:Check`, `loading:Shortfall`, `loading:HandBack`, `loading:Release`, `loading:Handover`; `loading:RequestInterchange` (approval not built).
- **Events:** `loading.started`, `loading.shortfall`, `trip.released`.
- **Screens:** loader dock board, manifest in stop order, issue sheet, PIN pad.
- **Rules:** R-LOD-*.
- **Acceptance:**
  1. On the dock tablet, a loader signs in with a PIN.
  2. The loader checks every line and flags a short line.
  3. The trip releases only when every line is checked.
  4. The shortfall becomes an issue.

### 5. Delivery (driver, offline-first)

- **Commands:**
  - `delivery:ArriveAtDepot`, `delivery:Start`, `delivery:RecordArrival`, `delivery:Record`;
  - `delivery:CaptureProof`, `delivery:LeaveWithoutStoreAnswer`;
  - `delivery:RecordPositions`, `delivery:ReportFault`, `delivery:ReportVehicleStatus`.
- **Events:** `delivery.started`, `delivery.arrived`, `delivery.completed`, `delivery.failed`, `vehicle.fault_reported`, `road.disruption_reported`, `vehicle.at_depot`.
- **Offline:**
  - Writes queue in IndexedDB and drain through `sync:Submit`.
  - A conflict is held for a person: `sync:Discard`, `sync:Resolve`.
- **Screens:** driver Home, run sheet, stop, proof, handover PIN, messages, map.
- **Rules:** R-EXE-*, EXE edge cases.
- **Acceptance:**
  1. Start the run, go offline, and record two stops with proof.
  2. Reload the page.
  3. Come back online.
  4. Each write lands exactly once, in order.

### 6. Receipt (store manager)

- **Commands:** `receipt:Confirm`, `receipt:ConfirmPartial`, `receipt:Dispute`, `receipt:VerifyHandover`, `receipt:ReissueHandoverPin`.
- **Events:** `receipt.confirmed`, `receipt.disputed`, `receipt.handover_confirmed`, `receipt.auto_closed`.
- **Screens:** store Receive (counting, photos, PIN), Deliveries.
- **Rules:** R-RCP-*.
- **Acceptance:**
  1. Count a delivery with one damaged line and a photo.
  2. A partial confirmation raises an investigation issue with the photo linked.

### 7. Issues (all roles, dispatcher decides)

- **Commands:** `issue:Raise`, `issue:AttachPhoto`, `issue:Assign`, `issue:Resolve`, `issue:RecordReplacement`, `issue:ScheduleRedelivery`, `issue:Close`, `issue:Cancel`.
- **Events:** `issue.raised`, `issue.resolved`, `issue.escalated`, `redelivery.requested`, `shortfall.resolved`.
- **Raised automatically:** from shortfalls, failed deliveries, faults, disputes and partial receipts, at most one issue per event.
- **Rules:** R-ISS-01 to R-ISS-08.
- **Acceptance:**
  1. The dispatcher takes an issue.
  2. The dispatcher resolves it with a replacement.
  3. The history shows actor, reason and time.

### 8. Trip messages (all roles)

- **Commands:** `message:Post`, `message:Resolve`.
- **Events:** `message.posted`.
- **Rules:** R-MSG-01 to R-MSG-07 (changed by FF-04).
- **Acceptance:**
  1. A store's voice report reaches the dispatcher only.
  2. The dispatcher resolves it.

### 9. Audit (administrator, auditor)

- **Reads:** `GET /api/audit` with filters, `GET /api/audit/decisions/{commandId}`.
- Every command writes an audit row in its own transaction. So do denied attempts.
- **Acceptance:**
  1. For the walk above, every command appears with actor, device and decision.
  2. A denied attempt appears too.

## Phase 1: verify and baseline

| ID | Item | Done when |
| --- | --- | --- |
| B-01 | `mvn verify` from `backend/` with Docker running | Report saved; skipped integration tests listed and explained (a green run with skips proves nothing about the database) |
| B-02 | `npm run verify` and the five browser suites (shell, dispatcher, driver, loader, store) | Counts recorded; failures filed as issues |
| B-03 | Fresh `docker compose up --build` in a new Compose project, then the full walk above across all four roles | Walk passes, or each break is an issue |
| B-04 | Alert rules and panels **first** (outbox lag and dead letters, Hikari pending, job failures, table growth), then record the technical baseline below | Table filled, alerts pass `promtool test rules` |
| B-05 | Business KPI baseline, below | 4 weeks of data per depot, owned by the depot managers |

**Alert rules first slice, 2026-10-07:** `observability/prometheus/rules/waypoint.yml`, loaded by the local and server Prometheus, tested by `promtool test rules observability/prometheus/tests/waypoint.test.yml`: backend down, outbox lag and dead letters, failing jobs, database pool waits, model calibration, Brier, label coverage and service-minute error (the trust gates), and unread driver messages. Not yet: Alertmanager (alerts show on Prometheus and Grafana only), table growth, panels, and the promtool step in CI.

**Second slice, 2026-10-08:** Alertmanager in the observability profile (`observability/alertmanager/alertmanager.yml`): grouped by alert and environment, critical repeated hourly, `info` kept quiet, every other alert of an environment held back while its backend is down, sent to a Slack-compatible webhook from `ALERT_WEBHOOK_URL` in `.env` (empty until the team names the channel; it goes with the H-03 secrets). Table growth: `TableSizeJob` publishes `waypoint.table.rows` and `waypoint.table.bytes` hourly for the eight tables that grow per command, event, position or job run, from the catalogue, partitions summed; alerts `TableGrowing`, `RetentionBehind` and `DiskFillingUp`, and two dashboard panels. The assistant alerts from `mcp/README.md` are rules now. CI's compose job runs `promtool test rules` and `amtool check-config` with a route test per severity. Fixed: `JobFailing` named the scrape job (`backend`) instead of the failing job, because Prometheus keeps the backend's `job` tag as `exported_job`; its test had used the unrenamed label.

### B-04 technical baseline

| Measure | How | Target after Phase 2 |
| --- | --- | --- |
| Requests per minute per open screen (driver, loader, store, dispatcher) | Browser devtools or nginx logs over 10 minutes | Driver 60 or fewer |
| Hikari active and pending connections, p99 | `/prometheus`, `hikaricp_connections_*` | Pending p99 = 0 |
| Outbox lag and dead count | `waypoint_outbox_*` | Lag p95 under 2 s, dead 0 |
| Command duration p95 by kind | `waypoint.command.duration` | Within SYSTEM-ARCHITECTURE §6.7 SLOs |
| Draft plan generation time per depot-day | planning job metrics | Under 10 s p95 |
| Rows per day in `sync.operations`, `integration.command_receipts`, `integration.audit_log`, `execution.vehicle_positions` | `pg_stat_user_tables` | Retention backlog 0 |
| Test counts (Java, Node, browser) and Task 2B validator result | B-01, B-02, CI | No regression |

### Requests per minute recorded 2026-10-08 (dev stack, demo data, one browser per role, 60 s after 10 s to settle)

| Role | Before | After | What changed |
| --- | --- | --- | --- |
| Driver, nothing released today | 85 (run sheet 46, vehicles 17, plan 16 answered 404) | 37, about 47 averaged with the look-ahead | The week ahead (8 days, 3 reads each) is asked every 2.5 minutes, not every 30 s; a release today still shows on the 2 s run read. Gate: `request-rate.spec.ts` |
| Store manager | 109 (orders, pending receipts, deliveries every 2 s) | 29 | Re-reads when a change names its outlet, else every 15 s (PLT-19) |
| Loader | 34 | | Trips every 2 s; within the driver's budget |
| Dispatcher | 57 | | Live screen on the change stream since F-07b; its 16 404s a minute are a day with no plan yet |

**Live read at 1,280 trips** (`LiveReadLoadTest`, every run of the suite): 1,280 trips spread over every depot in the database, 8 stops each, read by one dispatcher overseeing them all, one request per depot at once as the Live screen sends them, five rounds after a warm-up. On a fresh database (two depots, 640 trips and 5,120 stops each): one depot p95 836 ms and 3.6 MB of JSON, both together 837 ms. With a third depot left by another test (427 trips each): 730 ms and 2.4 MB. The ceilings in the test (3 s for one depot, 5 s together) are regression guards, not SLOs. Where the time goes, on one depot: the query takes 15 ms without row-level security and 338 ms with it (120 ms for the records' policy, about 35 µs a row, the rest for the lines' policy, which checks each line's record again); the other half is mapping and JSON. Evaluating the row-independent role check once per query saved only 42 ms, so the policies are left as they are. The cost is in the per-row depot, driver and outlet checks; rewrite those (a depot list read once per query) only if this read must get faster.

Pool and outbox on the dev stack: `hikaricp_connections_pending` 0 of 8, outbox lag 0 s, 0 dead. The production figures (command p95, generation time, rows per day) come from the server's Prometheus, which now alerts and has the table panels; they wait on access to it (H-03).

### Baseline recorded 2026-10-06 (local `dev`, macOS, Docker)

| Suite | Result | Notes |
| --- | --- | --- |
| Backend `mvn verify` with `TEST_DATABASE_URL` set as in CI | 1,063 run, 1 failure, 0 errors, 0 skipped | `ReceiptHandoverIntegrationTest.parallelWrongEntriesNeverGiveMoreThanFiveGuesses:310`: under parallel wrong PIN entries one request got 503 instead of a refusal |
| Backend `mvn verify` without `TEST_DATABASE_URL` | 88 skipped, 48 errors | Nine integration classes require the variable instead of using the Docker fallback in `support/TestDatabase`, and the planning tests put that condition on an abstract parent class, which JUnit does not inherit, so they start with no database. A test-harness defect, not a product one |
| Official Task 2B validator | PASSED, every rule satisfied | After restoring `tools/check_allocation/data` as a symlink (the handover copy had flattened it) |
| Frontend Node tests | 304 of 304 | Includes the F-01 and F-02 tests |
| Typecheck, production build | pass | Offline shell: 94 immutable assets |
| Browser: shell, loader, store | 17, 31, 59 passed | |
| Browser: dispatcher | 67 passed, 7 failed | Live (needs-you order, minutes left), Plan, suggestions, trip messages. Not a timezone effect: the same 7 fail with the browser in UTC. Present in the handed-over code: the merged tree equals the branch tip |
| Browser: driver | 23 passed, 7 failed, 13 skipped | All messages and voice: the Messages button needs a trip, and the mocked run shows none |

The failures above existed before any roadmap work. Fixed the same day:

| Failure | Cause | Fix |
| --- | --- | --- |
| Backend handover concurrency | A full test pool (5) answered 503; the entry was refused and not counted, as the test intends | Test accepts 503 like the 500 it already allowed; the pool question is F-09 |
| Dispatcher Plan | The mock served the waiting orders for today, but Plan plans tomorrow's run (`scope.ts` `nextRun`) | Mock follows `nextRun` |
| Dispatcher Live (5 tests) | **Product bug:** Live shared Plan's day, so it showed tomorrow's run and minutes left a day out | Live keeps its own day, today |
| Dispatcher search | **Product gap** exposed by the mock fix: search read only today's orders and missed those waiting for the plan | Search reads both days, each order once |
| Dispatcher Needs you | The test expected a proof-owed card that Figma's Needs you does not have | Test follows Figma |
| Driver messages and voice (7 tests) | **Product bug:** the Messages button had been removed from the header, so the driver could not open the trip's thread | Button restored (agreed with @jv-ransika) |
| Driver notification voice | The test counted waveform bars that the 2026-10-04 polish replaced | Assertion removed |

### B-05 business KPI baseline

Runs in parallel with Phase 1.5 and Phase 2 and blocks neither. Where the app is not yet used, the figures come from Kandy's paper run sheets, spreadsheets and phone logs.

| KPI | Definition | Owner |
| --- | --- | --- |
| Fresh on time | Share of Fresh stops arriving before the window closes, and before 08:00 | Depot manager |
| Deferrals | Deferrals per run, and outlets deferred on two or more runs in a row | Dispatcher lead |
| Disputes | Receipt disputes per 100 deliveries | Depot manager |
| Write-offs | Damaged-goods write-off value in LKR per week | Finance |
| Calls | Phone calls to the dispatcher per trip | Dispatcher lead |
| Adoption | Share of stops recorded in the app | Depot manager |

Every later item names which of these it moves, and the business case ([08](08-BUSINESS-CASE.md)) replaces its estimates with these numbers.
