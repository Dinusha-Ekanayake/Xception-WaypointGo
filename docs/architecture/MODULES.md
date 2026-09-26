# Module specifications

Every module in Waypoint Dispatch: what it owns, the layers inside it, the commands and queries it exposes, the events it publishes and consumes, the ports it depends on, the invariants it guarantees and how it fails.

Read [../../SYSTEM-ARCHITECTURE.md](../../SYSTEM-ARCHITECTURE.md) first for the principles and layer rules. [../code-structure.md](../code-structure.md) maps these modules onto folders.

## How to read a module spec

Each module has the same five internal layers. The spec lists what belongs in each.

```
<module>/
├── contract/        the ONLY package other modules may import: DTOs, event payloads, query interfaces
├── domain/          aggregates, value objects, invariants. Pure. No framework, no SQL, no clock
├── application/     command handlers, query services. Opens the transaction. Enforces authorization
├── infrastructure/  repositories, port adapters, projections
└── web/             REST adapters. Thin
```

**Connection rules.** A module reaches another only by: publishing an event, calling another module's `contract` query, or calling a port for something outside the process. Never by reading another module's tables or importing its domain.

---

## 1. Reference data (`ref`)

**Purpose.** The shared master data every other module reads: outlets, vehicles, districts, depots, calendar, travel assumptions, service allowances, products. Read-mostly, changes on a different cadence from operations.

| Layer | Contents |
| --- | --- |
| contract | `ReferenceSnapshot`, `OutletView`, `VehicleView`, `CalendarQuery`, `TravelQuery` |
| domain | `Outlet`, `Vehicle`, `District`, `Depot`, `CalendarDay`, `DeliveryWindow`, `TravelProfile`, `OperatingCalendarPolicy` |
| application | `ReferenceDataQuery`, `CalendarService`, `VehicleAvailabilityHandler` |
| infrastructure | `JdbcReferenceRepository`, `CsvReferenceImporter`, `ReferenceCache` |
| web | admin read endpoints |

**Owns:** `ref.brands`, `ref.depots`, `ref.districts`, `ref.outlets`, `ref.vehicles`, `ref.vehicle_day_status`, `ref.calendar_days`, `ref.district_travel`, `ref.service_allowances`.

**Caches, does not own:** `ref.products`. The catalogue belongs to the external warehouse and arrives by scheduled bulk sync with a catalogue version. It is a projection: never edited here, and always able to report that it is stale.

**Commands:** `SetVehicleDayStatus`, `ImportReferenceData`, `OverrideCalendarDay`.
**Queries:** `snapshotFor(day)`, `outlet(id)`, `vehicle(id)`, `isOperating(date)`, `nextOperatingDay(date)`, `travelProfile(district)`.
**Publishes:** `vehicle.status_changed`, `reference.version_published`.
**Consumes:** nothing.

**Invariants.** An outlet belongs to exactly one depot and district. A vehicle belongs to exactly one home depot. Reference data is **versioned**: a published plan keeps the snapshot it was built against, so re-reading history never silently changes a past decision.

**Failure modes.** Calendar exhausted beyond the supplied range: extension policy applies (Monday to Saturday), with supplied dates and `CALENDAR_FILE` overrides taking precedence. Reference import with a changed outlet set: rejected unless explicitly versioned, because silently moving an outlet between depots invalidates published plans.

**Connections.** Everything reads it; it reads nothing. Cached in memory with a version stamp; cache invalidation is triggered by `reference.version_published`.

---

## 2. Identity and access (`iam`)

**Purpose.** Authentication, and the policy decision point for every authorization question in the system.

| Layer | Contents |
| --- | --- |
| contract | `CurrentActor`, `Role`, `Scope`, `AuthorizationDecision`, `IdentityQuery` |
| domain | `Account`, `Session`, `Role`, `ScopeGrant`, `VehicleAssignment` (temporal), `AuthorizationPolicy`, `PasswordPolicy` |
| application | `LoginHandler`, `LogoutHandler`, `SessionService`, `LoginThrottle`, `AccountAdminUseCase`, `PolicyDecisionPoint` |
| infrastructure | `JdbcAccountRepository`, `JdbcSessionRepository`, `JdbcScopeRepository`, `RlsContextSetter` |
| web | `AuthController` |

**Owns:** `iam.users`, `iam.roles`, `iam.user_roles`, `iam.user_depot_access`, `iam.user_outlet_access`, `iam.vehicle_driver_assignments`, `iam.devices`, `iam.sessions`, `iam.login_attempts`.

**Commands:** `Login`, `Logout`, `CreateAccount`, `UpdateAccount`, `ResetPassword`, `DisableAccount`, `GrantScope`, `RevokeScope`, `RegisterDevice`, `AssignDriverToVehicle`.
**Queries:** `permits(actor, command, target)`, `scopeOf(actor)`, `actorForSession(token)`, `driverVehicleOn(date)`.
**Publishes:** `access.granted`, `access.revoked`, `account.disabled`, `authorization.denied`.
**Consumes:** nothing.

**Policy change.** Scope changes are **data**: rows in `user_depot_access`, `user_outlet_access` and the assignment tables, effective immediately with an audit entry. A new command type or a new predicate is **code** in the PDP. Neither requires touching an enforcement point, because every enforcement point asks `permits(...)`.

**Invariants.** A session maps to exactly one account. Disabling an account revokes every session in the same transaction. A driver-to-vehicle assignment is **time-bounded and non-overlapping**: one driver per vehicle per period, enforced by a temporal exclusion constraint rather than by application code.

**Failure modes.** Revocation during an in-flight command: authorization is re-checked inside the transaction, so the command fails rather than completing with stale rights. Pooled connection reuse: the RLS actor is set with `SET LOCAL` inside the transaction, so it can never leak to the next borrower of that connection.

**Connections.** Called synchronously by the application layer of every module through `permits(...)`. Sets the database session actor that every RLS policy reads. It is the only module allowed to be a synchronous dependency of everything else.

---

## 3. Ordering (`ops`)

**Purpose.** Capture demand, enforce the cutoff, own the order lifecycle, and mediate with the external warehouse.

| Layer | Contents |
| --- | --- |
| contract | `OrderSnapshot`, `OrderStatus`, `ConfirmedDemandQuery`, `OrderEvents` |
| domain | `Order`, `OrderLine`, `OrderStatus` state machine, `Cutoff`, `TemperatureRequirement`, `OrderVersion` |
| application | `PlaceOrderHandler`, `AmendOrderHandler`, `CancelOrderHandler`, `ApplyStockDecisionHandler`, `CloseOrdersHandler`, `OrderQuery` |
| infrastructure | `JdbcOrderRepository`, `WarehouseStockAdapter` (anti-corruption layer), `OrderProjection` |
| web | routed through the command endpoint |

**Owns:** `ops.orders`, `ops.order_items`, `ops.order_status_history`.

**State machine.** This is the module's core asset and every transition is guarded:

```
draft ─► confirmed ─┬─► stock_held ──► confirmed        (warehouse released)
                    │            └──► adjusted ──► confirmed
                    │            └──► rejected ──► cancelled
                    ├─► allocated ──► loading ──► in_transit ──► delivered ──► confirmed_receipt
                    │                                        └──► failed ──► redelivery_scheduled
                    └─► deferred ──► (next run) confirmed
                                  └──► cancelled
```

**Commands:** `PlaceOrder`, `AmendOrder`, `CancelOrder`, `CloseOrdersForDay`, `ApplyStockDecision`.
**Queries:** `confirmedDemand(depot, day)`, `order(id)`, `timeline(orderId)`, `ordersForOutlet(outletId)`.
**Publishes:** `order.confirmed`, `order.amended`, `order.cancelled`, `order.stock_held`, `orders.closed`.
**Consumes:** `stock.reserved`, `stock.insufficient`, `stock.adjusted` (from the warehouse), `order.deferred` (from Planning, to move the order to the next run), `delivery.completed`, `receipt.confirmed`.

**Ports:** `StockPort` to the external warehouse.

**Invariants.** An order is never silently reduced: an adjustment is an explicit, attributed transition the store can see. Chilled and ambient never share an order, because vehicle eligibility is decided per order. The cutoff is evaluated on the **server clock in Asia/Colombo**, never on a client timestamp. `order_items` are **descriptive**: the order's own weight, volume and temperature stay authoritative and are never recomputed from lines.

**Failure modes.** Warehouse unreachable: circuit breaker opens, the order enters `stock_unknown`, and the dispatcher sees the degraded state rather than a false confirmation. Stock unresolved at cutoff: auto-deferred with reason `stock_unresolved` and the store is notified.

**Connections.** Publishes confirmed demand that Planning consumes. Calls `StockPort` through the anti-corruption layer; the warehouse model never leaks past that adapter.

---

## 4. Planning (`ops`)

**Purpose.** Turn confirmed demand plus a fleet snapshot into a defensible allocation. This is the system's highest-value module.

| Layer | Contents |
| --- | --- |
| contract | `PublishedPlan`, `TripView`, `AllocationView`, `DeferralView`, `PlanQuery`, `PlanEvents` |
| domain | `PlanningRun`, `Trip`, `Allocation`, `Deferral`, `PriorityPolicy` (versioned decision table), `RuleSetVersion`, `ConstraintRegistry`, `Constraint`, `ConstraintResult`, `AllocationEngine` (port), `AllocationRequest`, `AllocationResult`, `TripTimeCalculator`, `FuelLedger` |
| application | `GenerateDraftHandler`, `OverrideAllocationHandler`, `PublishPlanHandler`, `ReplanTripHandler`, `AssignmentPreviewQuery`, `FuelReservationService` |
| infrastructure | `PriorityInsertionEngine`, `ValidatingEngine` (decorator), `JdbcPlanRepository`, `PlanProjection` |
| web | routed through the command endpoint |

**Owns:** `ops.planning_runs`, `ops.trips`, `ops.order_allocations`, `ops.order_deferrals`, `ops.route_legs`, `ops.vehicle_trip_fuel_usage`.

**The constraint registry.** Every rule is a named unit reporting pass, fail with a human-readable reason, and remaining slack:

| Constraint | Rule |
| --- | --- |
| `WeightCapacity` | Trip weight must not exceed `weight_cap_kg`. Reads **order-level** `order_weight_kg`, never a sum of product lines |
| `VolumeCapacity` | Trip volume must not exceed `volume_cap_m3`. Reads **order-level** `order_volume_m3` |
| `Temperature` | Chilled orders require `temp = reefer`; reefers may carry ambient |
| `VanOnlyAccess` | `parking_constraint = van_only` requires `type = van` |
| `HomeDepot` | A vehicle serves only its own depot's outlets |
| `SingleBrandDistrict` | One brand and one district per trip |
| `WholeOrder` | No splitting an order across trips or vehicles |
| `TripCount` | At most two trips per vehicle per day |
| `TimeBudget` | Fresh 270 min in 03:30-08:00; Style and Tech 480 min combined; checked separately |
| `DeliveryWindow` | Arrival within the outlet window; mall outlets within the mall window; early arrival waits |
| `FuelQuota` | Weekly litres per vehicle, including return legs and other published plans that week |

Four consumers read that one registry: the engine, the manual override path, the publication gate and the UI. That is what makes explainability structural instead of a feature someone remembers to add.

**Predicates are code; thresholds are effective-dated parameters.** The rule "trip minutes must fit the Fresh budget" is compiled; the number 270 is a `rule_parameters` row with an effective date. Changing the budget is a config change, not a release.

**`PriorityPolicy` is a versioned decision table**, not a hardcoded comparator. It is the rule most likely to change by business preference and the one dispatchers will argue about, so the ordering (prior skips, Fresh, chilled, earliest closing window) is authorable and versioned. Every `PlanningRun` stamps the `rule_set_version` that produced it, alongside the reference-data version, so a historical deferral can be replayed under the rules that were actually in force.

**Commands:** `GenerateDraft`, `OverrideAllocation`, `DeferOrder`, `PublishPlan`, `ReplanTrip`, `ReviseDraft`.
**Queries:** `publishedPlan(depot, day)`, `draft(id)`, `previewAssignments(orderId)`, `deferralsFor(day)`, `fuelRemaining(vehicle, week)`.
**Publishes:** `plan.published`, `plan.revised`, `order.deferred`, `order.unservable`, `trip.replanned`.
**Consumes:** `order.confirmed`, `orders.closed`, `vehicle.status_changed`, `loading.interchange_requested`, `delivery.failed`.

**Ports:** `AllocationEngine`, `TravelAndServiceEstimator`.

**Invariants.** A published plan is **immutable**; a change creates a new version. Every constraint holds across the whole plan, which is why `PlanningRun` and not `Trip` is the aggregate. Every deferral records the binding constraint, never a generic message. Deferred orders keep identity, original requested date, skip count and history when carried to the next run.

**Failure modes.** Demand exceeds capacity: defer by policy (prior skips, Fresh, chilled, earliest closing window) with reasons. Order larger than any vehicle: `unservable`, surfaced for a split decision, never deferred forever. Vehicle removed after publication: only affected trips replan. Concurrent draft edits: revision check rejects the stale one with a diff.

**Extraction.** Designed to be extracted when a depot-day allocation exceeds 30 s at p95 or the engine needs a different runtime. It already sits behind `AllocationEngine`, owns its tables, and communicates by events.

---

## 5. Loading (`ops`)

**Purpose.** The dock workflow: load to the planned stop sequence, catch shortfalls before departure, and handle a vehicle substitution safely.

| Layer | Contents |
| --- | --- |
| contract | `ManifestView`, `LoadingStatus`, `LoadingEvents` |
| domain | `LoadingSession`, `LoadingCheck`, `Shortfall`, `DepartureGate`, `VehicleInterchange` |
| application | `StartLoadingHandler`, `RecordCheckHandler`, `FlagShortfallHandler`, `RequestInterchangeHandler`, `ReleaseTripHandler`, `ManifestQuery` |
| infrastructure | `JdbcLoadingRepository`, `ManifestProjection` |
| web | routed through the command endpoint |

**Owns:** `ops.loading_sessions`, `ops.loading_checks`, `ops.trip_vehicle_assignments`.

**Commands:** `StartLoading`, `RecordCheck`, `FlagShortfall`, `RequestVehicleInterchange`, `ConfirmInterchange`, `ReleaseTrip`.
**Queries:** `manifest(tripId)`, `readyTrips(depot, day)`, `openShortfalls(depot)`.
**Publishes:** `loading.started`, `loading.shortfall`, `loading.interchange_requested`, `trip.released`.
**Consumes:** `plan.published`, `plan.revised`, `shortfall.resolved` (from Issues).

**Invariants.** A trip releases only when **every** allocated order has a passing check. A shortfall blocks departure until a dispatcher records a replacement and the loader rechecks the whole trip; the store's order is never silently reduced. Manifest order follows the planned stop sequence reversed for loading, so the first stop is unloaded first.

**Vehicle interchange.** This is the subtle one. Swapping the truck is not an `UPDATE` to `trips.vehicle_id`. It is a request that:

1. asks Planning to revalidate the **entire trip** against the substitute (capacity, temperature, access, home depot, fuel, time budget),
2. is accepted only if every constraint still passes,
3. writes a new `trip_vehicle_assignments` row so history shows both vehicles,
4. invalidates loading checks if the substitute changes stop feasibility.

If no compatible substitute exists, the trip is deferred as a unit and the orders carry forward with identity intact.

**Failure modes.** Loader shift ends mid-session: partial checks persist, another loader resumes, both are recorded. Loading complete with no driver assigned: the trip holds in `ready_for_departure` and the dispatcher is notified rather than the trip stalling silently. Duplicate mark-loaded: idempotent, no duplicate check rows.

---

## 6. Execution (`ops`)

**Purpose.** What actually happened on the road, captured on a phone that may have no signal.

| Layer | Contents |
| --- | --- |
| contract | `RunSheetView`, `DeliveryOutcome`, `ExecutionEvents` |
| domain | `Stop`, `StopOutcome`, `DeliveryRecord`, `ProofOfDelivery`, `ServiceWindow`, `LatenessPolicy`, `ProofStore` (port) |
| application | `StartStopHandler`, `RecordArrivalHandler`, `RecordDeliveryHandler`, `CaptureProofHandler`, `ReportFaultHandler`, `RunSheetQuery` |
| infrastructure | `ObjectStorageProofStore`, `JdbcDeliveryRepository`, `RunSheetProjection` |
| web | routed through the command endpoint |

**Owns:** `ops.delivery_records`, `ops.proof_of_delivery`, `ops.attachments`.

**Commands:** `StartStop`, `RecordArrival`, `RecordDelivery`, `RecordFailedDelivery`, `CaptureProof`, `ReportVehicleStatus`, `ReportRoadFault`.
**Queries:** `runSheet(vehicle, day)`, `deliveryRecord(allocationId)`, `proof(deliveryRecordId)`.
**Publishes:** `delivery.started`, `delivery.completed`, `delivery.failed`, `vehicle.fault_reported`, `road.disruption_reported`.
**Consumes:** `trip.released`.

**Ports:** `ProofStore`.

**Invariants.** One outcome per allocated stop, recorded once. Proof is attached to the outcome, never replaces it. **Server time decides**; the device clock is stored alongside for forensics but never used for a decision. Early arrival waits: service time starts at window open, not at arrival. A late arrival is still delivered and flagged with a reason.

**Failure modes.** Offline for a whole run: every outcome queues locally, and the UI acknowledges only after the local write is durable. Camera denied or photo too large: delivery may complete with a recorded reason and is flagged lower-evidence, because a device limitation must not block the work. Vehicle breakdown: vehicle set to `fault`, issue raised, remaining stops released for replanning, goods disposition recorded.

**Connections.** The most offline-sensitive module. Every command it accepts is designed to be replayable and version-checked, because it will be replayed.

---

## 7. Receipt (`ops`)

**Purpose.** The outlet's independent acceptance, deliberately separate from the driver's proof.

| Layer | Contents |
| --- | --- |
| contract | `ReceiptStatus`, `ReceiptEvents` |
| domain | `ReceiptConfirmation`, `AcceptanceOutcome`, `AutoCloseePolicy` |
| application | `ConfirmReceiptHandler`, `ReportDiscrepancyHandler`, `AutoCloseJob`, `ReceiptQuery` |
| infrastructure | `JdbcReceiptRepository`, `StoreTimelineProjection` |

**Owns:** `ops.receipt_confirmations`.

**Commands:** `ConfirmReceipt`, `ConfirmPartialReceipt`, `DisputeReceipt`.
**Queries:** `receiptFor(orderId)`, `pendingConfirmations(outletId)`.
**Publishes:** `receipt.confirmed`, `receipt.partial`, `receipt.disputed`, `receipt.auto_closed`.
**Consumes:** `delivery.completed`, `delivery.failed`.

**Invariants.** Confirmation refers to a real delivery record. Driver proof and store acceptance are separate events and neither overwrites the other, which is the entire point: disputes become evidence-based rather than memory-based.

**Failure modes.** Store never confirms: auto-closes after a configured window with status `unconfirmed`, never silently "delivered". Dispute after proof exists: recorded alongside the proof; evidence is never deleted.

---

## 8. Issues (`ops`)

**Purpose.** One lifecycle for every operational problem, wherever it is raised.

**Owns:** `ops.operational_issues`.

**Commands:** `RaiseIssue`, `AssignIssue`, `ResolveIssue`, `RecordReplacement`, `ScheduleRedelivery`, `CloseIssue`.
**Queries:** `openIssues(depot)`, `issuesFor(order|trip|allocation)`.
**Publishes:** `issue.raised`, `issue.resolved`, `shortfall.resolved`, `redelivery.scheduled`.
**Consumes:** `loading.shortfall`, `delivery.failed`, `vehicle.fault_reported`, `receipt.disputed`.

**Invariants.** An issue always links to at least one of order, trip or allocation. Resolution requires a recorded action and a reason. A redelivery links a new order while preserving the original proof and history.

---

## 9. Notification (`ops` + `integration`)

**Purpose.** Turn domain events into messages people actually receive, with delivery tracked per channel.

| Layer | Contents |
| --- | --- |
| domain | `Notification`, `Recipient`, `Channel`, `DeliveryAttempt`, `NotificationPolicy` |
| application | `NotifierWorker`, `OutboxRelay`, `DeadLetterHandler` |
| infrastructure | `JdbcOutboxRepository`, channel adapters |

**Owns:** `ops.notifications`, `ops.notification_deliveries`, `integration.outbox_events`.

**The matrix.** Every row is an outbox event with a durable delivery record:

| Event | To | Why it matters |
| --- | --- | --- |
| `stock.insufficient` | Stock controller | Order blocked before it reaches planning |
| `stock.adjusted` | Store manager | Quantity changed; the store must know before delivery |
| `order.deferred` | Store manager | With the binding reason and the next planned date |
| `plan.published` | Loader, driver | Work is available |
| `loading.shortfall` | Dispatcher | Departure is blocked now |
| `trip.released` | Driver | Vehicle ready, dock assigned |
| `delivery.completed` | Store manager | Proof is available to review |
| `delivery.failed` | Dispatcher, store manager | Requires a decision |
| `issue.raised` | Dispatcher | Fault, delay, damage, access problem |
| `vehicle.status_changed` | Dispatcher | Fleet availability changed |
| `eta.changed` | Store manager | Staffing decision at the outlet |

**Invariants.** A notification is never sent inside the request transaction. The state change and the outbox row commit together; the relay publishes after commit, at least once, and consumers are idempotent by `event_id`. A poison event goes to dead-letter with its attempt history; it never blocks the queue and never vanishes.

---

## 10. Sync (`integration`)

**Purpose.** Apply operations that were created while a device was offline, exactly once, in order, with conflicts surfaced rather than resolved silently.

**Owns:** `integration.sync_operations`.

**Commands:** `SubmitOperation`, `AcknowledgeOperation`, `DiscardOperation`.
**Queries:** `pendingFor(device)`, `conflictsFor(actor)`.

**Protocol.**

1. Device writes intent locally and acknowledges only after that write is durable.
2. Worker drains with exponential backoff **plus jitter**, so a regional reconnect does not arrive in lockstep.
3. Server applies with the idempotency receipt and the expected version.
4. Version mismatch becomes a conflict held for human review against the current record, never an auto-merge.
5. Accepted operations leave the local queue only on server confirmation.

**Invariants.** Operations apply in submission order per aggregate. An operation is applied at most once regardless of replay count. Session expiry never clears the queue, and sign-out is blocked while work is pending.

---

## 11. Audit (`integration`)

**Purpose.** Reconstruct any decision: who, from which device, with what before and after, and why.

**Owns:** `integration.audit_log`.

**Invariants.** Written in the same transaction as the change it describes, so an audit gap is impossible. Append-only, no updates or deletes. **Denied authorization attempts are audited too**, because failed attempts are the interesting ones. Partitioned monthly, retained for years.

---

## 12. Intelligence (`ml`)

**Purpose.** Predictions that support planning, kept strictly out of the transactional core.

| Layer | Contents |
| --- | --- |
| contract | `ServiceTimeEstimate`, `LatenessEstimate`, `DemandForecast` |
| domain | `TravelAndServiceEstimator` (port), `DeterministicEstimator` (default implementation) |
| infrastructure | `ModelServingAdapter`, `JdbcPredictionRepository` |

**Owns:** `ml.model_versions`, `ml.delivery_predictions`, `ml.demand_forecasts`.

**Invariants.** Every stored prediction records the model version that produced it, so results are reproducible and a bad model is traceable and replaceable. Predictions **never** participate in a transaction with operational state. The system plans without the predictor when it is unavailable and says so on screen.

**Connections.** Planning consumes estimates through the port and degrades to the deterministic implementation when model serving is unavailable. Nothing else depends on this module.

---

## External warehouse integration contract

The boundary was defined (port, anti-corruption layer, circuit breaker, degraded mode, who owns what). The **contract** was not: no operations, no reservation lifecycle, no inbound security. This closes that.

### Outbound: Waypoint calls the warehouse

| Operation | When | Timeout | On failure |
| --- | --- | --- | --- |
| `checkAvailability(orderRef, lines)` | Order confirmed | 2 s | Circuit opens, order enters `stock_unknown`, dispatcher sees the degraded banner |
| `reserve(orderRef, lines, ttl)` | Order confirmed and available | 2 s | As above. **Never assume reserved** |
| `release(reservationRef, reason)` | Order cancelled, deferred or delivered | 5 s | Retried through the outbox until acknowledged. A leaked reservation is the warehouse's stock held hostage |
| `fetchCatalogue(sinceVersion)` | Scheduled bulk sync | 60 s | Keep the last good version, mark it stale with its age |

Every call carries a correlation id and an idempotency key. `reserve` is idempotent on `orderRef`: calling it twice returns the same reservation, never a second one.

### Reservation lifecycle

Both sides must agree on this state machine, and Waypoint stores only the reference and the state:

```
none ──► requested ──► reserved ──┬──► consumed   (delivered)
                │                 ├──► released   (cancelled or deferred)
                └──► insufficient └──► expired    (TTL passed)
```

**The TTL is a business decision, not a technical one.** Proposal: until the dispatch date's cutoff plus two hours. Whatever is chosen, expiry must be **observable by Waypoint**, by event or poll. Without that, orders sit in `stock_held` forever waiting for a release that never comes, and nobody notices until a store calls.

### Inbound: the warehouse notifies Waypoint

This was the real gap, because inbound is where the security lives.

1. **Transport**: mutual TLS, or an HMAC signature over the raw body plus a timestamp, with a replay window of a few minutes.
2. **Land it first**: the request writes to `integration.inbound_events` and returns. Nothing is processed inside the HTTP request, so processing survives the connection that delivered it.
3. **Exact replay detection**: unique on `(source_system, source_event_id)`. The sender retries by design.
4. **Unverified never processes**: a `CHECK` constraint allows an unverified row only in `received`, `quarantined` or `dead`. Storing it for forensics is fine; acting on it is not.
5. **Unknown event types are quarantined**, not ignored. Silence is how an integration drifts for months.

Accepted inbound events: `stock.reserved`, `stock.insufficient`, `stock.adjusted`, `stock.released`, `stock.expired`, `catalogue.version_published`.

### What Waypoint never accepts from the warehouse

A requirement worth recording because the data cannot meet it: the team's requirements draft specified a **minimum order count per product**. The catalogue carries `product_id`, `brand`, `unit_weight_kg`, `unit_volume_m3`, `basis` and `verified_real_sku`, and nothing else. There is no minimum order quantity to enforce, so that rule cannot be implemented until the warehouse publishes it. Do not silently drop it; either the warehouse adds the field or the requirement is withdrawn.

Authority over weight, volume or temperature for capacity decisions. Those come from the order, for the reasons in [DATA-MODEL-REVIEW.md](DATA-MODEL-REVIEW.md#external-product-catalogue). The warehouse can tell Waypoint what is in stock; it cannot tell it what fits on a truck.

### Open decisions

1. Reservation TTL.
2. Is stock tracked per depot or per outlet? It changes whether availability is checked at capture or at planning.
3. Is availability checked at order confirm, or deferred to the planning run? Confirm gives the store earlier warning; planning avoids reserving stock for orders that end up deferred.

---

## Module connection summary

| From | To | Mechanism | Synchronous |
| --- | --- | --- | --- |
| every module | Identity | contract query `permits(...)` | yes |
| every module | Reference | contract query, cached | yes |
| Ordering | Warehouse | `StockPort` with circuit breaker | yes, degradable |
| Ordering | Planning | `order.confirmed`, `orders.closed` | no |
| Planning | Loading | `plan.published` | no |
| Loading | Planning | `loading.interchange_requested`, revalidation query | mixed |
| Loading | Execution | `trip.released` | no |
| Execution | Receipt | `delivery.completed`, `delivery.failed` | no |
| Execution, Loading, Receipt | Issues | `*.failed`, `*.shortfall`, `*.disputed` | no |
| all | Notification | outbox events | no |
| all | Audit | written in the same transaction | yes |
| Planning | Intelligence | `TravelAndServiceEstimator` port | yes, degradable |
