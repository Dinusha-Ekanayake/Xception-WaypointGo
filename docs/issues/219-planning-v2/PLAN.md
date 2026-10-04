# Issue #219: planning v2

## Current state (before)

- Engine: `ValidatingEngine(ImprovingEngine(PriorityInsertionEngine))`: a greedy pass by the priority table (R-PLN-21), then an exact re-plan of the refrigerated vehicles (#92). S1: 73 of 85 served on 16 vehicles, 725 L.
- `plan:Generate` ran the engine inside the SERIALIZABLE command transaction: no lock, no queue, a serialization retry re-ran the engine.
- No cache; Planning never read outlet coordinates.

## Who owns what

| Dependency | Layer |
| --- | --- |
| Cost stage `CostReplan`, `StopTravel`, `Coordinates` | `planning/domain` (pure; seeded, clock a parameter) |
| `CostImprovingEngine`, `JdbcGenerationJobs` | `planning/infrastructure` |
| `PlanGenerationWorker`, `GenerationSignal`, `ReferenceSnapshotCache`, the split `GeneratePlanHandler` | `planning/application` |
| Outlet points | `referencedata` contract (`OutletView.location`), read only when `exact` |
| The benchmark and OR-Tools | test scope only (`planning/bench`) |

## Decisions

1. **Choose the engine by measurement, not argument.** Rules, the exact reefer pass, an exact MIP (CP-SAT, SCIP, HiGHS through OR-Tools), ALNS and OR-Tools routing, on S1, the 8 historic depot-days (fleet as dispatched, 25% and 40% out) and seeded synthetic days up to 300 orders, all judged by the production registry (stricter than `check_allocation.py`, which ignores windows and fuel). Chosen: rules, then the exact reefer pass, then an ALNS cost stage. The MIP stays as the proof.
2. **Cost never trades priority** (R-PLN-38): a plan is better by rank first, then vehicles, then litres.
3. **Simple days stay cheap** (R-PLN-39): the cost stage runs only when the rules deferred something or trips are under 85% full, with its own time budget.
4. **The dispatcher reviews one recommended plan,** the optimised one, with the rules plan saved beside it and one click away in Compare; not two plans to choose between every day.
5. **GPS only when exact** (R-PLN-40): the supplied data has district centres only, so nothing is invented; booklet budgets untouched.
6. **Generation is a queued job** (R-PLN-41): one active per day, engine outside any transaction, SKIP LOCKED with a lease, rerun when demand moves.
7. **No production native dependency:** OR-Tools only in tests.

## PR breakdown

One PR: the domain stage and GPS, the queue and its migration, the dispatcher wiring, the benchmark and its notebook, docs.
