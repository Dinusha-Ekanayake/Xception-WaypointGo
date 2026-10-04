# Development log: @jv-ransika

@jv-ransika's entries, newest first. Only @jv-ransika adds to this file; how to write an entry is in the [log's index](../development-log.md).

---

## 2026-10-04 - feat(store): delivery day picker reads as a calendar strip

`feat/store-plan-date-strip` · @jv-ransika

The order sheet's day strip wrapped "Mon 12 Oct" over two lines and repeated an "On track" pill on every tile. Each day is now a fixed tile (weekday, date number, month at the first day and each 1st) with one outlook dot, a key under the strip, the chosen day spelled out with its outlook above it, and arrows that page about a week. Accessible names, the "Delivery day" group and the busy-day warning are unchanged (`screens/order/DateStrip.tsx`).
Verified: typecheck, build, wording and boundary tests, store outlook and order specs (11 of 11). `deliveries.spec.ts` "upcoming days wait for the plan" fails outside this change: the order dialog it opens has no "Close" button.

---

## 2026-10-04 - fix: the demo clock reaches scheduled jobs and every role's screens

`fix/demo-clock-reach` · @jv-ransika

Moving the demo clock moved commands only. The scheduler handed jobs real time and fired crons on the real hour, so the 16:00 cutoff, outlook warnings, forecast and calendar check ignored it, and auto-close and escalation compared real time with rows stamped on the demo clock. Jobs now get the business clock; a job whose cron names a business hour opts in with `onBusinessClock()` and is checked every 15 s against it (`BusinessCronTick`, DEMO-07). The other way round, the warehouse catalogue's age and the warehouse and model circuits were on the demo clock, so a clock moved 2 h ahead showed "The warehouse is not answering" until the next sync; they are on real time now (DEMO-08). On screen, today, the 16:00 cutoff, windows, countdowns, notification ages and the driver's arrival stamp read `businessNow()` (`shared/wording/now.ts`), set from `/api/demo` and kept when offline.
Verified: `BusinessCronTickTest`, `WarehouseCircuitClockTest`, `mvn verify` (1028, one error in `ReferenceCreationIntegrationTest`, admin reference additions, outside this change); `npm test` (267), typecheck, build; store browser suite 57 of 57. The other suites run in CI.
Open: a business-clock job is up to 15 s late, and with several replicas it can run twice in a row (the jobs are idempotent).

---

## 2026-10-04 - fix: Compare shows an order's ref, never its internal id

`fix/compare-order-refs` · @jv-ransika

"Orders that differ" took refs from the day's order list; a saved plan can still place an order that has since moved to another day, which then showed as a raw id. Missing orders are now read by id (`useOrdersById`); one that cannot be read says "Order not found".
Verified: `npm test` (259), typecheck, build; dispatcher browser suite 68 of 68.
Open: Decide, Publish and the decision card still fall back to the id for an order off the day's list; they show the working plan, where that does not happen today.

---

## 2026-10-04 - feat: trip drawer, vehicle picker, published plan final at 16:00

`feat/plan-trip-drawer` · @jv-ransika

The trip detail on View plan opens as a side drawer like the order drawer (shared `Drawer`); nothing opens by default. "Move trip to" is a pop-up with a search, filters (All, Free, Refrigerated, Ambient, Van) and each vehicle's trips in the plan (`VehiclePicker.tsx`). Once a day is published the screen shows only that plan: saved candidates, Save snapshot, Regenerate and Compare go. A published plan takes a revision or a trip move until 16:00 depot time on its day, then it is final (R-PLN-43, `PublishedEditWindow`, mirrored as `PUBLISHED_EDIT_CLOSES`). The error pop-up closes on a click outside or Escape, like every other pop-up.
Why: user review: one detail pattern, no candidates after publish, a findable vehicle list, and the day's plan settles at the cutoff.
Verified: `PublishedEditWindowTest`, `PlanningDecisionsIntegrationTest`, `PlanningRevisionIntegrationTest`; `npm test` (235), typecheck, build; dispatcher browser suite 59 of 60 (`live-map.spec.ts` flaky as before).
Open: none new.

---

## 2026-10-04 - feat: one plan view across depots, depot switch in every page header

`feat/dispatcher-figma-screens` · @jv-ransika

The depot scope left the sidebar. It is one switch in the page header of every screen, in the same place, from a shared context (`depotScope.tsx`). Plan is one plan view: "Both" shows both depots' trips and orders together. Each depot keeps its own plan, and each command goes to the plan that holds its order or trip (`data/planRouting.ts`, unit-tested). Plan-wide commands (generate, save, publish, revise) go to each depot's plan in turn. Live's day field stays in the header on every view.
Why: user review: no second plan window stacked below the first; one switch in one place; Live's day field moved when the view changed.
Verified: `npm test` (216), `npm run typecheck`, `npm run build`; dispatcher browser suite 56 of 57 (`live-map.spec.ts` flaky on a marker click, as before).
Open: none new.

---

## 2026-10-04 - fix: dispatcher UX review fixes (must and should fix)

`feat/dispatcher-figma-screens` · @jv-ransika

From a critic and defender review of the dispatcher screens:
- **Trip window:** stops with no window or no time are no longer counted as met, and the tiles say "Not checked yet" until the server answers.
- **Required reasons:** marked, and the footer says what Accept waits for.
- **Swap:** no dead swap dialog; "Open swap window" is disabled with the reason.
- **Day picker:** one shared field in depot words on Plan, Live and the redelivery date; Live marks a day that is not today.
- **Charts:** labelled refrigerated chart; whole-number trips chart with an empty state; "The 1 check".
- **One leftover wording:** "6 not on a trip · 5 deferred · 1 too big", the same on the board and Publish.
- **Step bar:** warns on Decide and names a blocked Publish's blocker.
- **Decide:** moves to the next open order, and notes when an order now fits. "Keep the rest deferred" is quiet until one order is decided, and lists its outlets and when stores are told.
- **Names and terms:** names in "Also sent to"; truck and van as in the glossary.
- **Order drawer:** focus trap and return, plus a link to the order's day in Plan.
- **Overview:** a "Tomorrow's plan" card.
- **Other:** badge tooltips; a confirm before an edited trip or swap is closed; Compare pickers disabled with one plan; saved time with its day; a flag when the return is before the last stop; Orders placeholder and header fixed; Issues fits its content.
- **Refactors:** `Plan.tsx` and `Sidebar.tsx` split under 300 lines.

Why: findings of the UX review, reranked by both sides.
Verified: `npm run typecheck`, `npm test` (213), `npm run build`; dispatcher browser suite 57 of 57.
Also: the Plan header has a depot switch (Both, Kandy, Peliyagoda) on the same shared scope as the sidebar (FIGMA-GAP updated); Orders has a brand legend that also filters.
Open: a pass on real data to confirm the mock-only items.

---

## 2026-10-04 - feat: plan screen edits trips by drag and drop, shows both depots, refusals pop up

`feat/dispatcher-figma-screens` · @jv-ransika

`plan:EditTrip` sets what a trip carries and in what order in one change (add from deferred or another trip, take off, reorder, remove the trip), judged whole with a preview (R-PLN-42, migration 20261004T1810). Edit trip is drag and drop; the board's trip panel can lock a stop to its vehicle. The Plan screen shows a section per depot in the sidebar's scope instead of asking; its header is the one every screen has (sync, bell); the step bar keeps one button slot on every step, with Edit plan and Watch the run in the Publish card; Compare has Back to the plan. Refusals are pop-ups that stay until closed. Trip cells no longer truncate, the hover card is gone, the trip timeline line and dots line up, passed checks read as met.
Why: review of the plan pages with a realistic day.
Verified: `PlanningRunTest` (32), `ModuleBoundaryTest`, `PlanningDecisionsIntegrationTest` (14, PostgreSQL 16, with a denied-scope case); `npm run typecheck`, `npm test`, `npm run build`; dispatcher browser suite.
Open: AI stop order is still a placeholder; workshop list and resolved issues still wait on a read.

---

## 2026-10-04 - feat: dispatcher shell and screens follow Figma 05

`feat/plan-figma` · @jv-ransika

One sidebar on every screen, folded to the rail by the dispatcher and kept, with the depot scope in the rail too, and the account and Sign out inside Settings behind the picture (as every role has it); the sidebar is the only depot scope, Live included. Overview, Orders (current, upcoming, past, order drawer), Plan (three-column swap and edit windows, publish, compare, trip and deferred cards), Vehicles and Issues rebuilt to the Figma frames from existing reads; `plan:Swap` takes an optional stop order (`orderIds`, R-PLN-33) so the swap window swaps and reorders in one command, and "AI order" is a placeholder (`useStopOrderProposal`); success is a toast (`shared/ui/Toast.tsx`, `Popover.tsx`). What still waits on a read is listed in [FIGMA-GAP](../../issues/019-dispatcher-ui/FIGMA-GAP.md).
Why: the screens and the left bar did not match the design and the bar changed shape between screens.
Verified: `PlanningRunTest`, `ModuleBoundaryTest`, `PlanningDecisionsIntegrationTest` (11, PostgreSQL 16); `npm run typecheck`, `npm test` (193), `npm run build`; `playwright.dispatcher.config.ts` 44 of 45 after rebasing on `dev` (live map passes alone, flaky in the full run), with a new `shell.spec.ts`.
Open: AI stop-order proposal (placeholder only), workshop list, resolved issues and a stop order proposal have no read; Live and Forecast specs are stale against their screens.

---

## 2026-10-04 - feat: the Plan screen follows the design, with decisions recorded and saved plans

`feat/plan-figma` · @jv-ransika

Each hand decision is recorded on the order (who, when, held or not); a regenerate can keep them; swap, keep deferred, lock, a fixed stop order, a message to a store, saved plans and compare are commands and reads; a revision tells only the trips and outlets it changes. The Plan screen is rebuilt around them: counter and bulk keep, swap window, too-big card, deferred column, filter, low-load and late risk, timeline, Edit trip, publish gate, plan switcher, compare, and an icon rail. No rule id is shown; `ruleLabel` says it in words. Migrations 20261004T0900, 0910, 1000, 1100; R-PLN-33 to 37, R-NOT-12 and 13, PLN-25 to 32, NOT-11 to 13.
Why: Figma is the ground truth and the Plan screen lacked most of it; decision 9 of #19 had left these out only because nothing served them (reversed, see FIGMA-GAP).
Verified: `mvn test` on PlanningRunTest (28), PinnedDecisionsTest, PlanDiffTest, PeakDayAllocationTest, ModuleBoundaryTest, EventCatalogueTest, and against PostgreSQL 16 Planning{Decisions,Snapshots,Revision,Command,Schema}IntegrationTest, CommandPathIntegrationTest, NotificationConsumersIntegrationTest, LoadingIntegrationTest, ExecutionIntegrationTest; frontend `npm run typecheck`, `npm test` (146), `npm run build`; browser `plan.spec.ts` and `plan-decisions.spec.ts` (13). Five other dispatcher specs (live, forecast, live map) fail the same way on a clean origin/dev.
Open: Overview and Orders still differ from the design (FIGMA-GAP sections 1 and 2); the swap window, Compare, Edit trip and publish frames were built from the workflow map, not seen; the full `mvn verify` was not run; after editing these migrations before merge, recreate the test database (checksums).

---

## 2026-10-03 - ci: backend tests run as three parallel shards

`feat/observability-metrics-dashboard` · @jv-ransika

`checks.yml` runs the backend job as a matrix of three shards, each on its own runner with its own PostgreSQL service, selected by package with surefire `-Dtest`: execution, loading, identity; notification, issues, receipt; everything else (which holds `PeakDayAllocationTest`, so the Task 2B validator runs there, and catches any new package). A `Backend tests` job stays green only when every shard passed. No test code changed: shards share nothing, so the shared-database setup in each class is untouched.
Why: the backend job was the long pole of every run (5.5 min, `mvn verify` 4m51s) while every other job finished in about a minute.
Verified: locally each shard selects its classes and together they cover every test class exactly once.
Open: shards are balanced on 2026-10-03 timings; rebalance when one grows. Each class still migrates and imports reference data before every test.

---

## 2026-10-03 - feat: Prometheus metrics and the "Waypoint operations" Grafana dashboard

`dev` · @jv-ransika

The observability profile gains Prometheus (15 s scrape of `/prometheus`, 15 days) and a Prometheus datasource. On the VPS it runs in preview and scrapes both backends, which join `waypoint-edge` as `production-backend` / `preview-backend`; `node-exporter` adds the server's CPU, memory and disk. HTTP requests get SLO latency buckets (`application.properties`) for p95. Dashboard provisioned from `observability/grafana/provisioning/dashboards/` (uid `waypoint-logs`): service health, commands and jobs, outbox and circuits, JVM and pool, server, then logs (error counts, top error types, trace lookup, frontend, ML and database errors, search). One Environment switch filters logs and metrics. Details in [deployment.md](../../deployment.md#logs).
Why: request of 2026-10-03; logs were only reachable through Explore and metrics were not stored.
Verified: compose renders for local, prod and VPS overlays; every LogQL query against the VPS Loki; every PromQL query against a throwaway Prometheus scraping preview on the VPS (removed after).
Open: production metrics appear only after production is redeployed with the overlay; p95 panels fill after the backend is redeployed with the buckets; no host network panel (node-exporter runs in its own network namespace).

---

## 2026-10-03 - feat: a store manager edits their own profile and their store's window, dock and contacts

`fix/store-figma-visual` · @jv-ransika

Identity: `iam:UpdateOwnProfile` on `wpt:iam:user:self` changes the actor's own name and phone (`iam.users.phone`, migration `T1400`), read at `GET /api/profile` (R-IAM-32). Reference: `reference:UpdateOutletDetails` keeps a store's window, dock type and contacts in `ref.outlet_details` (migration `T1401`), laid over the current version when a snapshot loads, so the next plan reads it; a mall bay stays a mall bay and its window must overlap the mall's (R-REF-01, R-PLN-29); scope through Identity's contract (R-IAM-28). Phone numbers are normalised once, in `shared/domain/PhoneNumber`. The store's account menu opens both editors (a bottom sheet on a phone). Home drops its stacked warning cards and shows the loader's shortage as Figma's grey card; Orders opens on what is still open, with Received, Cancelled and All filters; Delivery confirmed is Figma's check list; dock codes read as words ("Rear dock", not "rear_dock dock").
Why: request of 2026-10-03; decisions: store window and dock change directly, profile is name and phone.
Verified: `ProfileFieldsTest` (4), `OutletDetailsTest` (6), `StoreSelfServiceIntegrationTest` (10), `AdministrationIntegrationTest`, `CommandPathIntegrationTest`, `IdentityHardeningIntegrationTest`, `ModuleBoundaryTest`, `EventCatalogueTest` on PostgreSQL 16; frontend `npm run typecheck`, `npm test` (85), `npm run build`, store browser suite (25 passed).
Open: the store's details are not versioned, like calendar overrides, so a past plan re-read shows today's window; a change made on one instance reaches another's snapshot only at its next publish, as calendar overrides do (REF-02).

---

## 2026-10-03 - feat: the store's ordering picker, deliveries, deferred page and account menu

`feat/store-manager-figma` · @jv-ransika

Place order lists the outlet's usual items, and a search adds the rest of the class's catalogue ("+ Add", the new row tinted, a note for each add and for a saved draft); a count is teal only above the usual, and the usual items still list while the catalogue cannot be read (03c-03e). Deliveries is a row per vehicle's visit (expected time, stop on the trip, the loader's shortage, refrigerated only when it carries chilled, R-PLN-02), orders not on a vehicle yet, upcoming days, and a past week read only when its tab opens with the store's count per run (05a); a make-up delivery opens a drawer with its lines and its steps from the original issue to the count (05b). A deferred order has its own page: the day it was due, the day it comes, the plan's reason from the timeline and R-PLN-21's "goes first", with "Got it" kept per device (09). The sidebar's person opens the account menu with Sign out through the shell (account overlay). Track shows each order's own m³.
Why: Figma store manager sections 2, 3, 5 and 6; decisions of 2026-10-02 (no call buttons, no Staff ID or sign-in time, no guessed weight or volume before the warehouse answers).
Verified: `npm run typecheck`, `npm test` (79), `npm run build`, store browser suite (22 passed); screens compared with the Figma frames.
Open: the store cannot read a vehicle for an order before its trip leaves the depot, so such orders show against the outlet's window; brand and depot show as codes, as the outlet read carries no names.

---

## 2026-10-03 - feat: one report per delivery problem, and the store's Issues tab

`feat/store-manager-figma` · @jv-ransika

Receive no longer raises its own issue: the per-item problems become the receipt's note, photos go through the offline upload queue tagged with the order and receipt, and the investigation Issues opens is the one issue (R-RCP-07). The loader's shortage shows on its item, lowers the count and is listed but not reported again (06-5); a vehicle's other orders waiting to be counted are a choice on the screen (06-4). The Issues tab shows the loader's and the store's reports as cards and reports a problem found after unpacking with its order, item, quantity and photos; missing and wrong items are raised as other, which the store's policy allows, with the words saying which (08, 08b, 08c).
Why: Figma store manager sections 4 and 5; one bad delivery had become two or three issues.
Verified: `npm run typecheck`, `npm test`, `npm run build`, store browser suite; CI green on the draft pull request for the backend part.
Open: nothing.

---

## 2026-10-02 - feat: photos of a delivery problem, and no second issue for a shortage the loader flagged

`feat/store-manager-figma` · @jv-ransika

Issues takes photos from the store (`issue:AttachPhoto`, `PUT /api/issues/attachments/{id}`), modelled on proof uploads: SHA-256 addressed, type sniffed (`ImageKind` moved to `shared/util`), 3 MB cap, kept in the database, cleared past P-14 by `IssueAttachmentRetentionJob`. `RaiseIssue` names photos; a receipt's photos join its investigation; links are made in either order (ISS-11 to ISS-14). Loading gives a store read-only access to its own order's loading line (`LoadingQuery.orderLine`, migration `T0300`), which the custody read now uses, and Issues no longer opens a shortage investigation when the loader's own flags explain every short unit and the store added nothing (R-RCP-07, RCP-16, RCP-17).
Why: Figma store manager "06-5", "06d" and "08b3". The old rule opened an investigation for every partial receipt, so the loader's shortage was reported twice.
Verified: `HandoverTest`, `ShortageInvestigationIntegrationTest` (4), `IssueAttachmentsIntegrationTest` (6), `IssuesConsumersIntegrationTest`, `LoadingIntegrationTest` (22), receipt tests, `ModuleBoundaryTest`, `EventCatalogueTest` locally; the full `mvn verify` runs in CI on the draft pull request.
Open: the store screens that use these (Receive rework, Issues report dialog) are the next step.

---

## 2026-10-02 - feat: the handover PIN, from the store's count to the driver's phone

`feat/store-manager-figma` · @jv-ransika

When the store answers a receipt it gets a one-time four-digit PIN, returned once in the answer; only a salted hash is kept (`receipt.handovers`, migration `T0200`). `receipt:VerifyHandover` is the driver's (vehicle on its date), five wrong entries lock it, it expires after 15 minutes, the store reissues with `receipt:ReissueHandoverPin`, and `receipt.handover_confirmed` is published. The PIN is evidence, never a gate (R-RCP-09, RCP-10 to RCP-15). The store screens show it (06b), poll until confirmed (07) and offer a new PIN on an answered receipt.
Why: Figma store manager "06b" and "07"; decision 2026-10-02 to keep one PIN per order.
Verified: `HandoverTest` (10), `ReceiptHandoverIntegrationTest` (12) on PostgreSQL 16; store browser suite (9 passed). Parallel entries can answer 500 from the platform's unretried serialization failure (SQLSTATE 40001); no guess is counted.
Open: no driver screen enters the PIN yet (the driver role is not in this work).

---

## 2026-10-02 - feat: show the store its driver, stop position and expected arrival

`feat/store-manager-figma` · @jv-ransika

`DeliveryRecordView` (Execution) gains `stopSequence`, `tripStopCount`, `plannedArrival`, `expectedArrival`, `releasedAt`, `startedAt` and `driver` (name and badge, no email or phone), all additive. The driver comes from the new `IdentityQuery.driverOn(vehicleId, date)` and `PersonQuery`, read after Execution's own read so each runs under its own role; when no driver is assigned or Identity fails the delivery is still returned, without a name, and `waypoint_execution_driver_name_unavailable_total{reason}` counts it (EXE-28). Migration `20261003T0150` adds `delivery_records.trip_stop_count` (a store sees only its own stops, so the trip's size cannot be counted at read time), set at release and backfilled for existing records. Home and Track show the driver, "stop 3 of 7" and the ETA (the plan's time, moved by the observed delay).
Why: Figma "02 Home" and "05 Delivery tracking" show the driver and a predicted arrival; the data existed but was not on the store's read.
Verified: `mvn test` for `ExecutionIntegrationTest` (25 passed, two new), `ModuleBoundaryTest`, `EventCatalogueTest` against a recreated `waypoint_test` database; frontend `npm run typecheck`, `npm run build`, store suite (6 passed).
Open: the ETA is the plan's time until a delay is observed; the estimator is #16.

---

## 2026-10-02 - feat: match the store manager screens to Figma "1 · Main flow"

`feat/store-manager-figma` · @jv-ransika

Home shows the vehicle, the arrival time and the loading shortfall (read from `GET /api/execution/deliveries` and `GET /api/issues/by-subject`); Place order has the Item / Usual / Order table, the summary card and Save draft (kept on the device, restored on open, cleared on submit); Order sent is a centred dialog with the window, the change deadline and Edit order; new Track screen (vehicle switcher, status, timeline, orders on the vehicle) and Issues tab; Receive reports Missing, Damaged, Wrong item or Other per line, confirms as partial and raises `DAMAGED_GOODS` or `OTHER` issues, then shows the confirmed dialog. En dashes in the store files became hyphens.
Why: the store screens matched Figma's look but not its content. Everything that has a backend today is now on screen; the rest (driver name and ETA, notifications, calls, voice message, handover PIN, live map) is built in later phases and says "not available yet" until then.
Verified: `npm run typecheck`, `npm test` (51 passed), `npm run build`; new `playwright.store.config.ts` with `tests/e2e-store/` (6 passed).
Open: Phases 2 to 5 of the plan (Execution ETA and driver, Notification #14, handover PIN, calls and voice message); the live map is skipped for now. Issues are read per order, so a manager with many recent orders makes many small reads: a store-scoped issue list would be cheaper.

---

## 2026-10-02 - fix: sync the store manager screens with the backend contracts

`fix/store-manager-sync` · @jv-ransika

The store read a catalogue path that does not exist (live "No such endpoint"); it now pages `/api/warehouse/catalogue`. `order:Place` and `order:Amend` answer with order-level totals (null while stock is unchecked), an STK-01 refusal carries per-line `availability` (kept in the stored rejection, so a retry gets it too), the order-sent screen offers Accept on a partial reservation (STK-13), the delivery-day preview uses `GET /api/orders/delivery-date`, and a `DISCARDED` sync result leaves the device queue instead of blocking it.
Why: the store screens were built against assumed contracts before ordering, warehouse and sync landed; a full read of both sides found these mismatches.
Verified: frontend `npm run typecheck`, `npm test` (51 passed), `npm run build`; backend `mvn verify` on local PostgreSQL 16: 628 run, 627 passed, no skips; the one failure is `OutboxRelayIntegrationTest.twoRelaysNeverClaimTheSameEvent` (49 of 60 delivered), outside this change.
Open: no store browser suite (`tests/e2e-store/`); the store flow was not run by hand against the live backend.

---

## 2026-10-02 - fix(deploy): preview log store, Grafana address and root URL

`fix/grafana-root-url` · @jv-ransika

The preview deploy starts Loki, Alloy and Grafana when `GRAFANA_ADMIN_PASSWORD` is in the preview `.env`; Grafana joins the edge network as `preview-grafana` and nginx serves it at `grafana-preview.<site>`. Grafana takes its URL from the request host, because `SITE_ADDRESS` in the preview `.env` is the preview name, not the site.
Why: PR #51 added the log store but the VPS deploy never started it, and the public address needs an nginx vhost that production nginx (built from `main`) does not have yet.
Verified: containers healthy on the VPS, Grafana health 200 from inside the server; public address not yet verified (needs the production nginx change).
Open: production nginx on `main` needs the vhost and a certificate with the new name; Grafana still uses the default `admin` login.

---

## 2026-10-02 - feat(platform): scheduler jobs and audit completion (issue #6, second slice)

`feat/event-backbone` · @jv-ransika

- Scheduler: `ScheduledJobRunner` records each run and counts duplicates. New jobs: audit partitions ahead and detach after 24 months, platform retention, session retention, calendar exhaustion warning (R-PLT-04, 05). Partition DDL is a `SECURITY DEFINER` function, not a grant.
- Audit: command id, target, redacted before/after and policy generation on every row; rejected commands are stored as receipts and replayed (R-PLT-06, 07); `GET /api/audit` and `/api/audit/decisions/{commandId}`.
- Migrations `20261002T1100`, `T1200`. The relay itself landed first in #65. Plan and walkthrough in `docs/issues/006-event-backbone/`.

Why: nothing delivered events, and every command fails after 2027-07-01 when the last audit partition ends.
Verified: relay slice, `OutboxRelayIntegrationTest` (12), `OutboxIntegrationTest`, `ModuleBoundaryTest`, `EventCatalogueTest`, run against a dedicated database. Scheduler and audit slices: targeted tests written, full `mvn verify` left to CI on the PR.
Open: older audit call sites still read the correlation id from the logging context; `before` state is captured only by the vehicle status handler so far; archive target for detached partitions needs P-14; audit and dead-letter screens are #23 and #22.

---

## 2026-10-01 - feat: warehouse integration module (issue #7)

`feat/warehouse` · @jv-ransika

The warehouse adapter behind `StockPort`: HTTP client with a circuit breaker, placement records, retry that finds a lost placement by content, status calls from events, polling and reconcile, catalogue sync, an HMAC webhook inbox. A `202` is now kept as `partially_reserved` and the store accepts it (`order:AcceptShortfall`). See [issue 007](../issues/007-warehouse/WALKTHROUGH.md).
Why: the warehouse has no idempotency key or client reference, so safety comes from recording every attempt and matching by content; partial reservations are kept (D-F revised).
Verified: domain, architecture and boundary tests pass; Ordering integration 15 of 15; warehouse integration against a stub 15 of 16 (all run in Docker Maven with Postgres 16); lifecycle probed live (A-20). CI on the PR ran the full suite: everything passed except `anOrderWithNoWaypointOrderIsReleasedAndRaised`, a test-helper horizon bug since fixed.
Open: the warehouse change requests; the catalogue picker (#18); the relay and scheduler (#6).

---

## 2026-10-01 - fix: make the platform trustworthy (issue #4)

`feat/platform-hardening` · @jv-ransika

Error contract: every problem body carries `code`, `correlationId` and `violations: [{rule, field?, message}]`; client mistakes are 400/409/413/422/429, never 500; every 500 logs one line with its stack. Correlation id accepted only UUID-shaped and tied to the trace. Emails removed from audit rows, problem details and the accounts cursor. `Metrics.gauge` fixed, command timers with p95, and the detection signals for PLT-01, PLT-07, SEC-03/06/09/10/12/13/16, ORD-05/PLN-06/EXE-14 (names in EDGE-CASES). Tracing export off unless configured; JDBC spans. Shared keyset `Cursor`/`Page` on accounts, policies, assignments and reference lists. Body limit in backend, Next proxy and nginx; CSP in Next and nginx. `AppProperties` types sessions, throttle, body size, tracing and the problem type base. `account-create` is idempotent and `account-grant-depot` logs no email; health checks use real endpoints; nginx limits `POST /api/session`. CI `checks.yml` now runs `mvn verify` and parses both compose files. Integration tests fall back to Testcontainers. Prototype docs deleted; README, development, deployment, verification rewritten.
Why: a 500 left no trace, personal data reached logs and audit, `docker compose up` could not start, and nine modules were about to build on all of it.
Verified: after the rebuild on `dev`, `TEST_DATABASE_URL=... mvn verify` on PostgreSQL 16: 210 tests, 0 failures, 0 skipped; `npm run typecheck` and `npm test` pass.
Rebuilt on `dev` after ordering and sync: `Database` keeps `readAs` and the system actor beside the new counters; compose keeps `scripts/compose-init.sh`; the client `Problem` keeps `extensions` beside `code`, `correlationId` and structured violations. Ordering and sync adapted: `CutoffJob` gauge reads a live value, sync's 429 goes through `DomainException.rateLimited`, `OperationOutcome` covers the new error codes, and the fleet and loader outlet reads follow `nextCursor` via `requestAll`.
Open: integration tests against PostgreSQL, the backend image build, `nginx -t`, a fresh `docker compose up` and CI are unverified (Docker Hub unreachable during this session). The login lockout rolls back with its own transaction and never triggers, and the pool still connects as the owner role: both are issue #5. Lockout now answers 429 with `Retry-After`; `GET /api/accounts` and the other lists now return `{items, nextCursor}`.

---

## 2026-10-01 - feat: add an opt-in log store (Loki, Alloy, Grafana)

`feat/platform-hardening` · @jv-ransika

Compose profile `observability` in `compose.yaml` and `compose.prod.yaml` runs Loki (14-day retention), Alloy and Grafana on 127.0.0.1. Alloy collects containers labelled `com.waypoint.logs=true` and, for a natively run backend, `var/log/*.log` written when `LOG_FILE` is set. Backend services now set `LOG_FORMAT=ecs`. Config in `observability/`, usage in development.md "Searching logs" and deployment.md "Logs". No module code changed.
Why: logs only reached a console, so nothing could be searched and a correlation id could not be followed across requests or services.
Verified: both compose files validate with and without the profile. Backend jar run with `LOG_FORMAT=ecs` and `LOG_FILE`: one request's line reached Loki from the container and from the file, found by `correlationId`, with `level` as the only new label. Logs survive a Loki restart; backend liveness stays 200 with Loki stopped.
Open: the backend Docker image does not build (`mvn dependency:go-offline` fails in `backend/Dockerfile`), and `init` still runs the missing `seed`, so full-stack `docker compose up` is unproven (issue #4). Metrics and traces have no store yet.

---

## 2026-09-30 - feat: contracts, schemas and roles for every remaining module

`feat/module-contracts` · @jv-ransika

Wrote the `contract` package for ordering, planning, loading, execution, receipt, issues, notification, sync, warehouse and intelligence: views, query interfaces, command payloads and 31 event records. Added `DomainEvent`, `EventEnvelope`, `Page` and `UuidV7` to the kernel, and the `EventPublisher`, `EventSubscriber` and `ScheduledJob` ports to platform. Migration `20260930T1200` creates one schema and one `waypoint_<module>` role per module. `20260930T1201` catalogues every new action and moves the six role policies to version 2. Added TypeScript mirrors in `frontend/src/shared/domain/`.
Why: five people build nine modules in parallel. That only works if every connection between modules exists as merged code first. The team also settled the cross-module decisions on this date: schema per module, timestamped migrations, synchronous stock placement, deferral keeping the reservation, holiday roll-forward, one temperature per trip, and fuel including the return leg.
Verified: `mvn test` in a JDK 17 container, 103 tests green: 74 unit and architecture tests, including the new boundary, event catalogue, UuidV7 and publisher tests, plus 29 integration tests against a throwaway PostgreSQL 16 with `TEST_DATABASE_URL`, which apply both new migrations and run RLS as `waypoint_ordering`. Frontend `npm run typecheck` and `npm test` pass.
Open: no handlers, no tables and no relay yet. Each module writes its own. The shared `waypoint_ops` role is retired but kept, because roles are cluster-wide.

**Decisions are in the documents, not only here.** They are in ADR-002, the Ordering state machine, the warehouse contract and the event catalogue in MODULES. RULES-AND-POLICIES withdraws R-STK-01..03, R-ORD-09 and R-LOD-08, and adds R-PLN-31 and R-STK-14. ASSUMPTIONS closes A-03, A-04, A-18 and A-09. EDGE-CASES renumbers the duplicate SEC-09..13 set to SEC-15..19.

**`ModuleBoundaryTest` now discovers modules instead of listing them.** Any top-level package other than `shared` and `platform` is a module. A module may import another only through its `contract`. The old hard-coded rule only forbade `referencedata` reaching into `identity`, not the reverse, and would not have covered a single new module.
