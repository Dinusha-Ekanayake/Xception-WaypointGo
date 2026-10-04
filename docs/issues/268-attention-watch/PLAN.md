# Attention watch: only what is critical reaches the dispatcher

Issue [#268](https://github.com/Dinusha-Ekanayake/Xception-WaypointGo/issues/268). Reviewed against `dev` at `6b6704a` on 2026-10-04.

## Goal

One dispatcher cannot watch every trip all day. The system watches every live trip and raises only what needs a decision, most urgent first. If a critical item is not acknowledged in time, the dispatcher is reminded. The dispatcher still decides; the system only watches.

## Current state

- The dispatcher's Live screen works out "Needs you" in the browser (`frontend/src/roles/dispatcher/data/live.ts` `attention`, `liveDesk.ts` `NeedCard`). It runs only while that screen is open, has fixed thresholds, sends no reminder and cannot say when it last looked.
- `intelligence` already has the shape this needs: `OutlookWatchJob` (a `ScheduledJob` that reads other modules through their contracts, records in `ml`, publishes through the outbox), `OutlookChangePolicy` (a pure rule), `CircuitBreaker`.
- Execution publishes a vehicle's run sheet through `ExecutionQuery.runSheet(vehicleId, serviceDate)`: every stop with its outcome, planned and expected arrival, window, and whether proof was captured.
- Planning publishes the day's plan through `PlanQuery.publishedPlan(depotCode, serviceDate)`, which names the vehicles on the road.

## Layer ownership

| Piece | Layer | Notes |
| --- | --- | --- |
| `AttentionPolicy` | `intelligence/domain` | Pure. One stop's facts, the thresholds and the time in; at most one finding out. No clock read, no SQL |
| `AttentionThresholds` | `intelligence/domain` | The numbers the rule reads. Defaults in code; a depot's row in `ml.attention_thresholds` overrides them |
| `ReminderPolicy` | `intelligence/domain` | Pure. Whether an unacknowledged item is due a reminder; capped |
| `AttentionWatchJob` | `intelligence/application` | Every minute. Published plan for the vehicles, run sheets for the stops, both through contracts. One transaction per depot |
| `AttentionQuery` | `intelligence/application` + `contract` | The open items for a depot and day, most urgent first, with when the watch last looked |
| `JdbcIntelligenceRepository` | `intelligence/infrastructure` | New methods beside the existing ones |
| `GET /api/ml/attention` | `intelligence/web` | Authorised as the other intelligence reads; scope filtered in SQL by row-level security |
| Migration `ml_attention` | `migrations/` | `ml.attention_items`, `ml.attention_thresholds`, `ml.attention_heartbeats` |

No other module's tables are read and no other module's code changes in increment 1.

## Scenarios (one definition, in `AttentionPolicy`)

| Kind | When | Severity |
| --- | --- | --- |
| `FAILED_STOP` | The stop's outcome is failed | Critical |
| `WINDOW_AT_RISK` | Not finished, and the expected (else planned) arrival is after the window closes | Critical when the window closes within the warning minutes, else high |
| `RUNNING_LATE` | Not finished, and expected arrival is later than planned by the late threshold, but still inside the window | Medium |
| `PROOF_OWED` | Delivered in whole or part, no proof, and the grace minutes have passed since completion | Medium |

A stop yields at most one finding, the most severe. A skipped stop yields none.

## Decisions

1. **Rules, not a model.** The ranking is the rule above plus the expected arrival the execution module already computes from the observed delay. No external model: a second decider would break "one definition of every rule", and trip data would leave the system.
2. **Thresholds are data.** Per depot, in `ml.attention_thresholds`, with defaults in code when a depot has no row. Changing a number needs no deployment.
3. **An item is a record, never deleted.** It is raised once per stop and kind, refreshed while the condition holds, and reaches a terminal state: cleared (the condition went away) or acknowledged (actor, time, reason).
4. **The watch says when it last looked.** `ml.attention_heartbeats` holds the last run per depot, and the read returns it. A stalled job must never look like a calm day (rule 9), so the screen shows "last checked HH:mm" and says so when it is stale.
5. **Reminders are capped.** At most `max_reminders` per item, `remind_after_minutes` apart, so a long incident does not become noise.

## Increments

1. **This branch:** the rule and its tests, the tables, the job, the read and its endpoint.
2. **Next:** `ml:AcknowledgeAttention` (command, handler, action catalogue row, authorisation test), `attention.raised` and `attention.reminded` through the outbox with their MODULES.md catalogue rows, the notification subscriber, and the Live screen reading from the endpoint instead of computing its own list.
3. **Then #269:** playbooks an administrator can edit, matched to the item's kind.

## Tests

| Level | Covers |
| --- | --- |
| Domain unit | Each scenario, the order of severity, the thresholds, the reminder cap; the time is a parameter |
| Integration | An item is raised once, refreshed, then cleared; a second run raises nothing twice; the heartbeat moves |
| Authorization | A reader without the depot in scope sees no item |

Integration and authorization tests need a database. They are written with the code and run in CI; a local run without Docker skips them and proves nothing about the SQL.

## Risks

- **Alert fatigue.** Mitigated by one finding per stop, the reminder cap, and clearing an item when its condition ends.
- **Load.** One run-sheet read per vehicle per minute per depot. About 40 vehicles is 40 reads a minute; measured through `platform/observability/Metrics` (`waypoint.ml.attention_run`, job age).
- **Edge case to add to EDGE-CASES.md with increment 2:** the watch stalls while trips are live.
