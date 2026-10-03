# Issue #92: walkthrough of the scarce-fleet re-plan

What was built. The reasoning and the measurement behind it are in [PLAN.md](PLAN.md). The rule is R-PLN-32 in [RULES-AND-POLICIES.md](../../architecture/RULES-AND-POLICIES.md); the cases are PLN-23 and PLN-24 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md).

## Result on the Task 2B peak day (S1)

| | First pass | With the re-plan |
| --- | --- | --- |
| Served, deferred, unservable | 70, 14, 1 | **73, 11, 1** |
| Chilled served | 12 (78.7 m³) | **15 (109.2 m³)** |
| Chilled orders closing before 08:00 left undelivered | 3 | **0** |
| Orders skipped yesterday that are served | 10 of 10 | 10 of 10 |

The search finishes in about 117,000 nodes (under a second), well inside its budget. The two orders the first pass served and the re-plan defers (S1-035, S1-067) both close at 08:00 and are outranked by orders it now serves. `check_allocation.py` passes on the new CSV.

## Layers

Under `backend/src/main/java/com/waypoint/dispatch/planning/`:

- `domain/CheapestInsertion.java`: placement cost, the closest failing candidate and the binding rule, moved out of `PriorityInsertionEngine` unchanged. The S1 CSV was byte-identical after the move.
- `domain/ScarceFleetReplan.java`: the pass. Ruin the reefer days, list each reefer's feasible days of one or two chilled trips (every one checked with `ConstraintRegistry.evaluate`, days covered by another dropped), search by rank, recreate the rest with `CheapestInsertion`, keep only if better by rank. Pure; time is a `LongSupplier`.
- `domain/AllocationEngine.java`: `AllocationResult` gains `Optional<ScarceFleetReplan.Summary> improvement`, with the old four-argument constructor kept.
- `domain/PlanningRun.java`: carries `improvement` through every version of a draft.
- `infrastructure/ImprovingEngine.java`: the decorator. A partial first pass is returned as it is. The name is `priority-insertion-v1+scarce-replan-v1`.
- `infrastructure/PlanningEngineConfiguration.java`: `ValidatingEngine(ImprovingEngine(PriorityInsertionEngine))`. `ValidatingEngine` stays outermost (PLN-12).
- `infrastructure/JdbcPlanRepository.java`, `application/PlanRecords.java`: the summary is stored as jsonb on the run.
- `contract/PlanViews.java`: `PlanView` gains `engine` and `improvement` (`ImprovementView`), additive.
- `migrations/20261003T1200_planning_run_improvement.sql`: nullable `planning.runs.improvement`.

Frontend: `shared/domain/planning.ts` mirrors the two fields; `roles/dispatcher/data/plan.ts` `improvementNote` words it; `screens/Plan.tsx` shows it above the plan.

## Flow

`plan:Generate`, then `GeneratePlanHandler`, then `ValidatingEngine`, then `ImprovingEngine`:

1. `PriorityInsertionEngine` makes the first plan.
2. `ScarceFleetReplan` re-plans the reefers and decides each order against the final plan.
3. `ValidatingEngine` re-checks whatever came back.

`PlanningRun.draft` stores the engine name and the summary, and `GET /api/plans/draft` serves both. The Plan screen says "Reefers planned again: N more orders served", or that the search stopped early.

## Run and verify

```
cd backend
mvn test -Dtest='ScarceFleetReplanTest,PeakDayAllocationTest'
python ../tools/check_allocation/check_allocation.py target/task2b/submission_task2b.csv
TEST_DATABASE_URL=postgresql://... mvn verify
cd ../frontend && npm test && npm run typecheck && npm run build
npx playwright test -c playwright.dispatcher.config.ts
```

Tests added:
- `ScarceFleetReplanTest`, 7 tests: repair, rank kept, no change, determinism, zero budget, pool limit said, rank comparison.
- `PeakDayAllocationTest`: the S1 gain with the rank guarantee checked order by order, and a partial first pass is not improved.
- In `PlanningCommandIntegrationTest`, the draft serves the engine and the summary.
- One test in `dispatcher-data.test.ts`, and one assertion in `e2e-dispatcher/plan.spec.ts`.

## Known gaps

- **Vans are not a gap.** Van-only access comes from the outlet's `parking_constraint` and is enforced by R-PLN-03 in every pass, the override and the publication gate, and the first pass already keeps vans for the outlets that need them. The reefer van (VEH036 on S1) is re-planned with the reefers. Re-planning the ambient vans as a group would gain nothing on S1: every ambient van-only order is served, and the one van-only order deferred (S1-003) is chilled, closes at 08:00 and is outranked. Build it only if data shows ambient van-only orders deferred while vans could be rearranged.
- **The search ranks at most 62 chilled orders at once** (one bit each). The supplied data never comes close: at most 38 chilled orders on any depot-day in training, 26 on S1. If a day ever has more, the highest ranked 62 are searched and the rest are placed one at a time, and the summary (`chilledCandidates`, `chilledSearched`) and the Plan screen say so (rule 9). Test: `ScarceFleetReplanTest.aPoolLargerThanTheSearchRanksIsSaidNotHidden`.
- A count-maximising plan would serve 19 chilled on S1 but defer earlier-closing orders. That is a policy question (R-PLN-21), not an engine one.
- Fuel history is empty on S1, so the fuel quota never binds in the measurement.
