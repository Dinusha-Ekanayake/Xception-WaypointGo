# Issue #9 walkthrough: the Planning module

What was built for [issue #9](https://github.com/kavindamihiran/Xception-WaypointGo/issues/9), how each flow runs, and what is left.

- **Reasoning:** the approach and the step-by-step handoff are in [PLAN.md](PLAN.md).
- **Rules:** in [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md), under `R-PLN-*` and `R-LOD-06` to `R-LOD-09`.
- **Edge cases:** in [EDGE-CASES](../../architecture/EDGE-CASES.md), under `PLN-*`, `POL-*`, `LOD-02`, `LOD-03` and `FLT-01`.
- **Assumptions and parameters:** in [ASSUMPTIONS](../../architecture/ASSUMPTIONS.md), A-26 to A-28 and P-12 to P-19.
- **Log entries:** in the [development log](../../development-docs/development-log.md).

Branch `feat/planning-module`, one commit per step.

## What exists, layer by layer

All paths are under `backend/src/main/java/com/waypoint/dispatch/` unless they start with `migrations/` or `tools/`.

| Layer | Files | What it holds |
| --- | --- | --- |
| contract | `planning/contract/` | Already merged with the module contracts: `PlanViews`, `PlanQuery`, `PlanCommands`, `PlanEvents`. Unchanged by this issue |
| domain | `planning/domain/` | Thirteen constraints in one `ConstraintRegistry`, `TripTimeline` (booklet formula and the stop clock), `FuelLedger`, `PriorityPolicy`, `RuleSet`, `PlanVerification`, `PublicationGate`, `DemandFingerprint`, and the `PlanningRun` aggregate. No Spring, no SQL, no clock |
| application | `planning/application/` | Six command handlers, `PlanDataQuery`, `PlanningProblems` (builds the engine input from contracts), `PlanningDrafts`, `PlanningRevisions`, `PlanPublication` (the gate), `PlanRecords` (translation only) and `PlanningConsumers` |
| infrastructure | `planning/infrastructure/` | `PriorityInsertionEngine`, `ValidatingEngine`, `PlanningEngineConfiguration`, `JdbcPlanRepository`, `PeakDayScenario` |
| web | `planning/web/PlanController.java` | Reads only, under `/api/plans` |
| schema | `migrations/20261001T0400_planning_plans.sql`, `T0500_planning_actions_implemented.sql`, `T0600_planning_trip_identity.sql` | Tables, row-level security, immutability triggers, seeds, catalogue flags |
| CI | `.github/workflows/ci.yml`, `tools/check_allocation/` | The official validator, vendored unmodified, judges the engine on the peak day |

Outside the module, `ReferenceQuery.vehiclesOfDepot` was added; the change is additive. Without it, an order only a vehicle in the workshop could carry would look unservable rather than deferred. `FoundationIntegrationTest` now retires reference versions instead of deleting them, because a plan holds a foreign key to the version it stamped.

## The schema

**Configuration.**
- `planning.rule_sets` and `planning.rule_parameters` hold effective-dated thresholds. The keys are the `RuleSet` constants. Parameters cannot be updated at all.
- `planning.policy_versions` holds the effective-dated priority tables, global or for one depot. A GiST exclusion constraint refuses overlapping versions (POL-04).
- The booklet values and the default priority order are seeded. `PlanningSchemaIntegrationTest` holds the seed equal to the domain's defaults.

**Plans.**
- `planning.runs` is one row per version, stamped with its reference, rule set and policy versions.
- Its children are `trips`, `allocations` (one per order, with the `checks` jsonb the UI reads), `deferrals` (actor, reason, skip count), `route_legs` (planned times) and `fuel_usage`.
- Every child row carries `depot_code`, so row-level security is the single predicate `app.actor_is_system() OR app.actor_has_depot(depot_code)`, with `FORCE`.

**Immutability is the database's job, not the code's.**
- A published run may only change to `superseded`.
- `superseded` and `cancelled` are terminal.
- A child row can be written only while its run is a `draft`.

**Uniqueness.**
- A partial unique index allows one `published` run per depot and day.
- Another allows one open `draft` per depot and day.
- Trips are keyed by `(plan_id, trip_id)`, so a trip keeps its id across versions while it carries the same orders.

## Flows

Every command goes `POST /api/commands` → `CommandBus` (policy, then the idempotency receipt) → the handler, in one serializable transaction as `waypoint_planning`.

### Generating a draft (`plan:Generate`)

1. Scope: the system actor, or a dispatcher with the depot. Otherwise `FORBIDDEN`, audited by the bus.
2. Refuse when:
   - the day does not operate (PLN-13);
   - the day already has a published plan, which must be revised instead (R-PLN-28);
   - no rule set or policy is in force (POL-10).
3. `PlanningProblems` builds the problem:
   - demand from `OrderQuery.confirmedDemand`, using order-level weight, volume and temperature;
   - outlets, allowances, travel and the whole fleet from `ReferenceQuery` at the current version;
   - weekly fuel from published plans;
   - days since each outlet was last served.
4. `ValidatingEngine(PriorityInsertionEngine)` allocates:
   - It screens out unservable orders first (R-PLN-22).
   - It then places orders in priority order at the cheapest feasible place.
   - A deferral names its binding rule.
   - Running out of time defers the rest and marks the run `partial`.
   - The whole result is re-verified before it leaves the engine (PLN-12).
5. Any open draft for the day is cancelled, and the new one is written at the next plan version with its demand fingerprint.

### Overriding and deferring (`plan:Override`, `plan:Defer`)

Both need the draft's `expectedVersion` and a reason.

1. The draft is rebuilt against its stamped versions. If the demand has changed since, the command is refused (PLN-07).
2. **Override** places the order on a vehicle's trip. It is refused unless the vehicle's whole day passes the registry, and every failing rule is named.
3. **Defer** takes a served order off its trip under `R-PLN-19`, with the dispatcher's id.
4. Either way, the result is the **next version** of the draft, and the edited version is cancelled in the same transaction.
5. A second dispatcher still holding the old version gets `409 VERSION_CONFLICT`. The error names the current draft and lists each order whose place changed (PLN-06).

### Publishing (`plan:Publish`)

`PlanPublication` first refuses, with every reason at once, when:
- the draft is stale, or the demand changed (PLN-07);
- the reference version changed (PLN-14);
- the rule set or policy changed (POL-02).

Then it re-verifies the whole plan.

A draft that revises the day's plan supersedes it in the same transaction. Any other second plan for the day is refused.

The plan travels with its trips and stops, as `plan.published` or, for a revision, `plan.revised` with its reason. Only the deferrals and unservable orders that this version made are announced. A deferral carried from the previous version is copied, not recounted, so Ordering never rolls an order twice.

### Revising and replanning (`plan:Revise`, `plan:Replan`)

**Revise** builds a draft from the published plan under the versions in force now. Its orders are:
- the published plan's orders, read by id, because once published they are no longer "confirmed" demand;
- plus any new confirmed demand.

Within that:
- A cancelled order leaves its trip.
- A late arrival is deferred under `PLN-07` until a dispatcher places it (PLN-18).
- Trips that did not change keep their ids.

**Replan** moves one trip whole:
- The requested vehicle is tried first, even if unavailable, so `R-FLT-03` gets named. Then idle vehicles of the same kind, then the rest of the available fleet.
- The moved trip keeps its id (R-LOD-06).
- If no vehicle can take the trip, every order on it defers as a unit, under the rule that stopped the closest candidate. If there is no other vehicle at all, the rule is `R-LOD-09` (LOD-03).
- On a draft, the result is the draft's next version. On the published plan, it is a revision draft.

### Reads and previews

`PlanDataQuery` reads through `Database.readAs`, so row-level security decides in SQL.
- A run outside the actor's scope is `404`.
- A depot-day outside scope is `403` plus an audit row.
- `deferralsFor` reads the published plan, or the newest draft while nothing is published.
- `fuelRemaining` counts published runs only (D-K).

`previewAssignments` and `previewInterchange` run the override and the trip move read-only. They are exposed as `GET /api/plans/preview/assignments?order=` and `GET /api/plans/preview/interchange?trip=&vehicle=`.

### Consumed events

| Event | Planning does |
| --- | --- |
| `orders.closed` | Generates a draft as the system. A refusal is a metric and a log line, never a retry loop |
| `order.placed`, `order.amended` | Marks the depot-day draft stale |
| `order.cancelled` | Marks stale every open draft that decided the order; the event carries no date |
| `reference.version_published` | Marks stale every open draft built on another version |
| `calendar.overridden` | Marks that day's drafts stale. If the day stopped operating and already has a published plan, raises `waypoint.plan.published_on_closed_day` |
| `vehicle.status_changed` (not available) | For each published plan using the vehicle that day, drafts a revision that replans only its trips, and raises `waypoint.plan.vehicle_lost`. The dispatcher publishes it |
| `loading.interchange_requested` | Replans that trip as the system, the requested vehicle first. Publishes the revision itself only when exactly that trip moved and the gate passes; otherwise the draft waits for the dispatcher. `waypoint.plan.interchange_outcome` records which |

## Running and verifying it locally

```bash
docker compose up -d db          # or any PostgreSQL 16 with a privileged user
cd backend
TEST_DATABASE_URL=postgresql://<user>:<password>@127.0.0.1:<port>/<dedicated_test_db> mvn test
python ../tools/check_allocation/check_allocation.py target/task2b/submission_task2b.csv
```

Migrations are checksummed. After editing an unmerged migration, drop and recreate the test database.

| Test | Proves |
| --- | --- |
| `planning/domain/*Test` | The constraints with their slack and the `1e-6` boundary, the booklet's 101 and 213 minute examples, priority order, the publication gate and fingerprint, and every `PlanningRun` decision. No database |
| `PeakDayAllocationTest`, `UnservableScreenTest` | The Task 2B peak day: 70 served, 14 deferred, 1 unservable, reproducible, partial on timeout, an infeasible result rejected. Also writes the CSV the official validator passes |
| `PlanningSchemaIntegrationTest` | Immutability, one published plan per day, scope, the version guard, the seed, canary policies, fuel |
| `PlanningCommandIntegrationTest` | Generate, override, defer and publish over HTTP: the stale edit with its diff, the refused override, changed demand, a closed day, another depot's 403 plus audit |
| `PlanningRevisionIntegrationTest` | Revise and replan, stable trip ids, `plan.revised`, the interchange and vehicle-loss consumers, stale drafts, `orders.closed`, both previews |

To try it by hand:
1. Close a depot-day in Ordering, or send `plan:Generate`.
2. Read `GET /api/plans/{planId}`.
3. Send `plan:Publish` with `expectedVersion` from that read.

## Decisions and where they are recorded

- **Priority** is a lexicographic, versioned table, not a score: R-PLN-21, and decision 1 in [PLAN.md](PLAN.md).
- **Trip timing** has no return leg, and fuel includes it: A-26, R-PLN-24, D-K.
- **New parameters:** P-12, P-15, P-17, P-18 and P-19 in ASSUMPTIONS. The parameter-change steps there now use what-if runs, not hidden shadow evaluation; POL-06 and POL-07 are superseded (decision 6).
- **Every edit is a new draft version:** step 5 result in [PLAN.md](PLAN.md). PLN-06 is tested against it.
- **Trip identity and what a revision decides:** step 6 result in [PLAN.md](PLAN.md); PLN-18 and PLN-19 in EDGE-CASES.
- **Never-served outlets count as 0 days:** A-28.
- **C-2 is closed as inert,** and Q5 is answered: RULES-AND-POLICIES.
- **`ReferenceQuery.vehiclesOfDepot`:** MODULES (Reference data) and FOUNDATION-PLAN.

## Known gaps and who owns them

| Gap | Owner |
| --- | --- |
| No relay delivers events to the consumers yet; the tests call `on(envelope)` the way the relay will | Platform, issue #6 |
| What-if runs and policy comparison (POL-06, POL-07 superseded) | Follow-up issue, decision 6 |
| No policy or rule-set authoring command, so POL-05 (no past `effective_from`) has nothing to guard yet | Follow-up issue |
| No replay command (POL-03); every run is stamped, so replay is possible | Follow-up issue |
| Publication does not yet require an explanation for an outlet skipped twice (PLN-03) | Planning follow-up, with the dispatcher screens |
| Learned travel and service times: plans are built with `plannedWithoutPredictor = true` | Intelligence, issue #16 |
| An optimiser behind `AllocationEngine` | Follow-up, with #16 |
| An order Ordering already rolled to tomorrow, overridden back into today's revision, is not guarded against | Planning with Ordering |
| A vehicle removed from reference data after publication makes a revision fail loudly instead of planning around it | Planning follow-up |
| `FuelView` reports usage from plans; actual usage above quota (FLT-05) is Execution's to record | Execution |
| No dispatcher screens | Frontend, issue #19 |
