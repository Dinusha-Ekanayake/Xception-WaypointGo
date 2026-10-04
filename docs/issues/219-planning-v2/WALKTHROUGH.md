# Issue #219: walkthrough of planning v2

Why and the decisions: [PLAN.md](PLAN.md). Rules R-PLN-38 to R-PLN-41 in [RULES-AND-POLICIES.md](../../architecture/RULES-AND-POLICIES.md); cases PLN-33 to PLN-39 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md).

## The measurement

[benchmark/planning-benchmark.ipynb](benchmark/planning-benchmark.ipynb), on [benchmark/results-all.csv](benchmark/results-all.csv): 62 days, 8 engines, 435 runs, every plan valid.

| Engine | Best by rank | Served | Vehicles | Litres |
| --- | --- | --- | --- | --- |
| E0 rules | 24 | 5,081 | 1,135 | 47,838 |
| E1 rules + exact reefer pass (before) | 30 | 5,103 | 1,137 | 47,872 |
| E2 exact MIP, CP-SAT | 39 | 4,979 | 1,120 | 46,460 |
| E4 OR-Tools routing | 13 | 4,659 | 1,077 | 38,824 |
| **E5 production** | **58** | **5,464** | **954** | **38,730** |

- On all 34 days where the MIP proved the best set by rank, production serves exactly that set; the rules alone fell one or two short on 6.
- Against the rules plan: a median 16% less fuel (up to 35%), up to 7 fewer vehicles a day. S1: the same 73 orders, 16 to 13 vehicles, 725 to 611 L.
- HiGHS through OR-Tools 9.14 crashes when given a hint and ignores its time limit: S1 only.
- Found and fixed: the reefer pass's day listing was unbounded and its covered-day pruning quadratic, so a 200-order day held a worker for over an hour; now under 10 s (PLN-39).

## Layers (`backend/src/main/java/com/waypoint/dispatch/planning/`)

- `domain/CostReplan.java`: the ALNS cost stage (destroy: random, vehicle, related district, emptiest trip; repair in rank order through the registry; seeded from the orders; iteration count plus a clock safety stop).
- `domain/StopTravel.java`, `domain/Coordinates.java`, `domain/TripTimeline.java`: GPS stop order and drive times, district fallback.
- `domain/ScarceFleetReplan.java`: listing bounded by half the node budget and the clock; at most 4096 days kept per reefer.
- `domain/AllocationEngine.java`, `domain/PlanningRun.java`: `cost` and the rules `alternative` carried with the run; `domain/PlanOrder.java` gains `location`.
- `infrastructure/CostImprovingEngine.java`, `infrastructure/PlanningEngineConfiguration.java`: the chain `ValidatingEngine(CostImprovingEngine(ImprovingEngine(PriorityInsertionEngine)))`; `ValidatingEngine` re-checks the alternative too.
- `infrastructure/JdbcGenerationJobs.java`, `application/PlanGenerationWorker.java`, `application/GenerationSignal.java`, `application/GeneratePlanHandler.java` (enqueue; prepare, allocate, persist): the queue.
- `application/ReferenceSnapshotCache.java`: outlets, allowances, travel and depot vehicles by immutable reference version.
- `application/SnapshotRecords.java` (`saveView`), `application/PlanDataQuery.java` (`viewOf`, job reads), `web/PlanController.java` (`GET /api/plans/jobs/{id}?depot=`, `GET /api/plans/jobs?depot&date`).
- Migrations: `migrations/20261004T1600_planning_cost_stage.sql` (`runs.cost_summary`, snapshot kind `rules`), `migrations/20261004T1610_planning_generation_jobs.sql`.

Frontend: `shared/domain/planning.ts` (`CostView`, `GenerationJobView`, `RULES`); `roles/dispatcher/data/useGeneration.ts` (follows the job, resumes after a reload); `data/plan.ts` `costNote`; `screens/Plan.tsx` (progress, the cost note, Compare); `screens/PlanCompare.tsx` (opens on the rules plan).

## Flows

**Generate.** `plan:Generate` refuses a closed or published day, else inserts a job (a second press gets the same job) and answers `{jobId, status}`. The worker claims it, builds the problem in a short transaction, runs the engine with none open, re-checks the demand fingerprint and writes the draft, the AUTO snapshot and, when the cost stage changed the plan, the RULES snapshot. The screen polls the job and opens the draft. `orders.closed` queues the same way.

**Engine.** Rules, then the reefer pass, then the cost stage when R-PLN-39 says so, each result re-checked by `ValidatingEngine`.

## Run and verify

```
cd backend
TEST_DATABASE_URL=postgresql://... DATABASE_URL=postgresql://... mvn verify
mvn test -Dtest=EngineBenchmark -Dbench=all            # about 45 minutes; -Dbench=s1 for one day
python ../tools/check_allocation/check_allocation.py target/task2b/submission_task2b.csv
cd ../frontend && npm test && npm run typecheck && npm run build && npx playwright test -c playwright.dispatcher.config.ts
```

Tests: `CostReplanTest`, `StopTravelTest`, `ScarceFleetReplanTest.aLargeChilledDayIsListedWithinTheBudgetAndSaysItWasCutShort`, `PeakDayAllocationTest.theCostStageServesTheSameOrdersOnFewerVehiclesAndLessFuel`, `PlanGenerationQueueIntegrationTest` (one job, concurrent presses, demand moved, lease re-claim, scope), `dispatcher-data.test.ts` (`costNote`), `e2e-dispatcher/plan-decisions.spec.ts` (Compare on the rules plan).

## Known gaps

- Outlet GPS needs real exact coordinates in `data/General Data/geo_points.csv`; the supplied data has district centres only.
- The trip disruption channel is #214's (messaging).
