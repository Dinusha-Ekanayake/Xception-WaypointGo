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

- Still open on #19: assigning an issue to someone other than yourself (no read lists a depot's staff), interchange approval (waits on #10), the sync conflict queue, Forecast (waits on #16).
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
