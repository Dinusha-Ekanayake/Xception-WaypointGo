# Issue #92: improvement pass for the allocation engine

## Current state

`priority-insertion-v1` places orders one at a time in priority order and never revisits a decision. On the Task 2B peak day (S1, Peliyagoda, 85 orders) it serves 70, defers 14 and finds 1 unservable. 14 of the 15 not served are chilled, and every chilled order on S1 is Fresh, so they all compete for 4 reefers and a pre-dawn Fresh budget of 270 minutes per vehicle (R-PLN-09).

## Measured before building

An exhaustive search over every reefer day the rules allow (capacity, the 270 min budget summed per vehicle, windows simulated as `TripTimeline` does, van-only access) found:

| S1 chilled | Served | m³ |
| --- | --- | --- |
| First pass | 12 of 26 | 78.7 |
| Most orders possible, the 3 skipped yesterday forced in | 19 | 131.0 |

So there is real headroom, but it cannot be reached by moving one order at a time: the better plans change several reefer trips together. The issue's relocate and swap moves would stop in a local optimum.

## Decisions

1. **A scarce-fleet re-plan, not generic local search.** Clear every reefer day, list each reefer's feasible days of one or two chilled trips through the registry, choose one day per reefer, then place everything else by the same cheapest insertion as the first pass.
2. **Compared by rank, never by a weighted score.** R-PLN-21 forbids a score, so a plan is better only when the highest ranked order that one plan serves and the other does not is served by it. The search admits orders highest rank first, each only if every order admitted before it can still be served. Consequence: on S1 the pass serves every chilled order that closes before 08:00 (the `EARLIEST_CLOSE` key), so it reaches 15 chilled, not the 19 a count-maximiser would reach by deferring earlier-closing orders.
3. **Deterministic.** The issue's "spend what is left of the wall-clock budget" would make the plan depend on machine speed and break "same inputs, same plan" (#9 decision 1, `PeakDayAllocationTest`). The search stops on a node budget fixed in the engine version (`scarce-replan-v1`); the clock is a safety stop only and is recorded when it fires.
4. **One explanation of a deferral.** The placement and binding-rule logic moves out of `PriorityInsertionEngine` into `domain/CheapestInsertion`, used by both passes. Deferrals after the second pass are explained against the final plan (R-PLN-19).
5. **No new rule parameter.** `RuleSet` refuses compiled-in defaults (POL-10); the budget is part of the engine version instead, and the engine name is stamped on every run.
6. **Visible.** The run stores an improvement summary (`planning.runs.improvement`), `PlanView` serves `engine` and `improvement`, and the Plan screen says what the pass did, or that it stopped early.

## Pull request breakdown

One pull request into `dev`: the extraction (S1 CSV byte-identical), the pass and decorator, the migration and contract, the Plan screen line, tests and registers.
