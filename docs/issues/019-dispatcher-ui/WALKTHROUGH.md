# Issue #19: Dispatcher UI: walkthrough of Orders, Plan and Live

What was built. The plan and its nine decisions are in [PLAN.md](PLAN.md). Cases are PLN-06, PLN-07, PLN-20 to PLN-22 and ORD-19 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md). The rest of the issue is **not** built; see "Known gaps".

## Layers

Backend, under `backend/src/main/java/com/waypoint/dispatch/`, three additive reads and no schema change:

- `ordering/`: `JdbcOrderRepository.forDay`, `OrderDataQuery.ordersForDay`, `GET /api/orders/day?depot=&date=`. Every order due at a depot on a day, in any status. Scope in SQL; no depot is `403` plus audit.
- `planning/`: `PlanDataQuery.workingDraft`, `GET /api/plans/draft?depot=&date=`. The open draft, `404` while none is open.
- `planning/`: `PlanViews.PlacementView`, `PlanDataQuery.previewPlacements`, `GET /api/plans/preview/placements?order=`. The same places as `preview/assignments`, each naming the vehicle and trip number a `plan:Override` sends. `preview/assignments` is unchanged.

Frontend, under `frontend/src/roles/dispatcher/`:

- `data/plan.ts`, `orders.ts`, `live.ts`: pure. Open decisions in order, the vehicle by trip board with load against capacity, counts; the order flow and filters; a vehicle's day on the road, urgency and what needs the dispatcher.
- `data/useDay.ts`: the reads, each polled every 30 seconds. `data/useCommand.ts`: one command, with the same command id resent after an outage.
- `screens/Orders.tsx`, `Plan.tsx` with `PlanDecide.tsx`, `PlanBoard.tsx`, `PlanTrip.tsx`, `PlanPublish.tsx`, `Live.tsx`, and `Refusal.tsx` for every failed read or command.
- `shared/domain/planning.ts`: the `PlacementView` mirror.

## Flows

- **Orders.** Reads the day's orders and each depot's published plan. The flow strip counts due, planned, left the dock, delivered and confirmed by store from the order statuses; the table says which vehicle and trip an order rides on, from the published plan only. Close orders sends `order:CloseForDay`; before the cutoff the server refuses with R-ORD-01 and the screen shows it.
- **Plan, no plan yet.** Generate sends `plan:Generate` for the depot and day.
- **Plan, draft.** The screen reads the open draft by depot and day and holds no id.
  - *Decide*: each order not placed, with its binding rule and reason, and every check on expand. Selecting one reads its places; only feasible ones can be chosen, and the refused ones show which rule refuses them. Placing sends `plan:Override` with a reason and the draft's `rowVersion`.
  - *View plan*: vehicles by trip, a trip opened with its load, departure, return and stops. Take off sends `plan:Defer`; Move to another vehicle reads `preview/interchange` and sends `plan:Replan` only when it is feasible.
  - *Publish*: what the plan leaves undelivered, then Publish and Confirm publish send `plan:Publish`. A refusal lists every reason the gate gave.
  - Every command is followed by reading the day again, whatever the answer: a `409` usually means the draft moved, and the screen moves to the current version with the server's message on screen.
- **Plan, published.** Read only. Start a revision sends `plan:Revise` with a reason; the result is a draft that changes nothing on the dock or the road until it is published. Moving a trip on the published plan starts a revision the same way.
- **Live.** Reads Execution's run sheets and Loading's trips for the depots and day. Vehicles are listed most urgent first: a stop not delivered, then a late stop (recorded late by the server, or not reached with its window closed), then the rest. "Needs you" lists those stops and deliveries with no proof yet.
- **Offline.** The header says read only, every write control is disabled, and polling pauses.

## Run and verify

```
cd backend && TEST_DATABASE_URL=postgresql://... mvn verify
cd frontend
npm test            # dispatcher-data.test.ts holds the 11 new tests
npm run typecheck && npm run build
npx playwright test -c playwright.dispatcher.config.ts   # 7 browser tests at 1440x900, mocked API
```

Backend tests added: `PlanningCommandIntegrationTest.theOpenDraftIsFoundByDepotAndDayThroughEveryEdit`, the placement preview inside `anOverrideThatBreaksARuleIsRefusedWithTheRule`, and `OrderingCommandIntegrationTest.aDispatcherReadsEveryOrderDueAtTheDepotThatDay`. Each includes its denied-scope case.

By hand, against a running backend with reference data imported: sign in as a dispatcher, place orders as a store manager for tomorrow, close orders after the cutoff, then Plan, Generate, Publish. The loader's dock board then shows the trips.

## Decisions

All nine are in [PLAN.md](PLAN.md). Added while building:

- **A new placement view rather than a changed one.** `AllocationView` carries a trip id and no vehicle, so a place that opens a new trip could not be named. `PlacementView` is additive; nothing that read `preview/assignments` changes.
- **A revision is recognised by the draft naming the plan it supersedes**, not by a published plan happening to exist.
- **Regenerate is offered on a first draft only.** A revision is changed by hand; regenerating it would replan what was already announced (PLN-18).

## Known gaps

- Still open on #19: assigning an issue to someone other than yourself (no read lists a depot's staff), interchange approval (waits on #10), the sync conflict queue, late risk on the plan (backend built, #16). Forecast is built (see the third slice).
- No backend for these parts of the design, so they are left out: snapshots and compare, regenerate with locked orders, late-risk percentages, contact store manager, global search, the map.
- A stale draft is shown as the server's message, not as a side by side diff.
- The browser tests run against a mocked API and are not in CI, like the loader's and the driver's. The flows against the real backend need the fresh-install seed.
- Order ETA on the order board waits on a per-order ETA read; Live shows window and plan times.

## Second slice: Issues, Overview, skipped outlets, fuel (2026-10-02)

Frontend only; every read and command already existed (#9, #13). Under `frontend/src/roles/dispatcher/`:

- `data/issues.ts`, pure: labels and tones, `actionsFor` (what the command would accept: redelivery only for FAILED_DELIVERY and STOCK_DISCREPANCY per A-24, a replacement only for a loading shortfall naming a trip and an order, close only once resolved), urgency order and counts. `data/live.ts` gains `punctuality`.
- `data/useDay.ts`: `useIssues` (`GET /api/issues?depot=`, every page), `useIssue` (the issue and `/history`, by id), `useDeferrals` (`/api/plans/deferrals`), `useFuel` (`/api/plans/fuel`).
- `screens/Issues.tsx`, `IssueDetail.tsx`, `IssueActions.tsx`: the inbox, one issue with its history, and the commands `issue:Assign` (take it), `issue:Resolve` (write off, no fault found, other), `issue:RecordReplacement`, `issue:ScheduleRedelivery`, `issue:Close`, `issue:Cancel`, each with the issue's `rowVersion` and a reason. The issue list holds only open and assigned issues, so a resolved issue stays selected and is read by id until it is closed.
- `screens/Overview.tsx`: Orders and Summary tiles from the day's orders, run sheets, dock and issues. `screens/SkippedOutlets.tsx`: today's deferrals, most skipped first (R-PLN-20). `screens/FuelWeek.tsx` in the vehicle drawer: planned litres against the weekly quota, published plans only (D-K).

Flows: every command is followed by reading the issue and the list again, whatever the answer, so a `409` shows the server's version. Offline, every action is disabled and the panel says so.

Verify: `npm test` (4 new tests in `dispatcher-data.test.ts`), `npm run typecheck && npm run build`, `npx playwright test -c playwright.dispatcher.config.ts` (4 new in `issues.spec.ts`: take and resolve then close, redelivery offered only when nothing arrived with a stale refusal, overview tiles and skipped outlets, offline read only).

Gaps: assigning to another person needs a read of the depot's staff, which identity does not serve to dispatchers; the server already checks the assignee works the depot (R-ISS-08). People are shown by the tail of their id until such a read exists. The fuel drawer has no browser test.

## Third slice: Forecast (2026-10-03)

The Forecast tab on the model service (#16). One new backend read and the screen; the Figma design (node 189:11993) is followed where the endpoints can honestly support it.

Backend, in `backend/.../intelligence/`:

- `GET /api/ml/forecast/overview?depot=&weeks=10` (`ml:Read`, weeks 1 to 12): for one depot, every brand of the newest run per ISO week, the calendar of each week (operating days, holidays, paydays, festival) and what the reference fleet can carry (`domain/FleetCapacity`, A-40). A depot outside the actor's scope is `403` plus an audit row. `status` is `NONE` before the first run; `degraded` says the run came from the weekday averages (A-38).
- `ForecastJob` checks hourly (P-29): it runs when no run exists since this Monday, or when the newest run is the fallback, a model has since been activated and six hours have passed. A fresh deploy has a forecast within the hour.

Frontend, under `frontend/src/roles/dispatcher/`:

- `data/forecast.ts`, pure: `combine` sums the depots in view (Both shows combined totals), `forBrand`, `segments` (chilled first, then each brand's ambient), `kpis`, `dayNeeds` (an average operating day against the fleet, A-41), `actions` (refrigerated fleet at 95% or more, over weekly capacity, a short week). `data/useForecast.ts` reads one overview per depot, polled every 5 minutes.
- `screens/Forecast.tsx` (container), `ForecastChart.tsx` (plain SVG stacked bars, dashed capacity, festival, short week and payday tags, the chilled against refrigerated strip, a hidden table for screen readers), `ForecastSide.tsx` (what the busiest days need, suggested actions).
- `screens/ReeferNeed.tsx` replaces the Vehicles rail's pending refrigerated card: reefers available today against next week's average day. `Upcoming.tsx` is gone.

Scale: the reference fleet carries far more than a week of demand (about 12,150 m³ a week at Peliyagoda against 800-1,700 of demand), so `focusScale` (in `data/forecast.ts`) draws capacity on the scale only within 1.6 times the busiest week, as in the design; above that the bars follow demand with round ticks and a dashed line on the top edge states the fleet and how much of it the peak week uses. The chilled strip follows the same rule and shows, under each bar, the refrigerated vehicles an average day needs out of those the depot has (`refrigeratedNeeded`).

States: no run yet is a notice, a fallback run is a warning that names it, an error is the Refusal screen.

Runs: `screens/ForecastRuns.tsx` shows the last run (with its model, or "Recent averages" for the fallback) and the next, in depot time, with a countdown. The next run is `nextRunAt` on the overview, from `domain/ForecastSchedule.nextRun`, which walks the job's hourly wake-ups through the same `due` rule the job obeys, so the screen and the job cannot disagree. When it comes the strip says "Running now" and the screen reads again every 20 seconds until the run lands.

Verify: `npm test` (9 in `dispatcher-forecast.test.ts`), `npx playwright test -c playwright.dispatcher.config.ts forecast.spec.ts` (4: the screen, a brand filter, the fallback notice, no forecast yet), `mvn test -Dtest=IntelligenceDomainTest`, and `IntelligenceIntegrationTest` on PostgreSQL (overview shape and capacity, out of scope, the job runs once a week).

Gaps: the models must be registered and activated on each deployment (`ml:RegisterModel`, `ml:ActivateModel`) before the forecast is the model's; until then it says it is the fallback. Late risk on the plan is still to build.
