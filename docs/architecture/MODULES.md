# Module specifications

Every module in Waypoint Dispatch: what it owns, the layers inside it, the commands and queries it exposes, the events it publishes and consumes, the ports it depends on, the invariants it guarantees and how it fails.

Read [../../SYSTEM-ARCHITECTURE.md](../../SYSTEM-ARCHITECTURE.md) first for the principles and layer rules. Each module is a top-level package under `backend/src/main/java/com/waypoint/dispatch/`.

A spec is the contract a module is held to. How much of it is built, and what is left, is in [../development-docs/STATUS.md](../development-docs/STATUS.md); a section marked **Not built** below is a design, not a description of code.

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
| contract | `ReferenceQuery`, and the views it returns: `OutletView`, `VehicleView`, `TravelView`, `AllowanceView`, `CalendarDayView` |
| domain | `Outlet`, `Vehicle`, `District`, `Depot`, `CalendarDay`, `DeliveryWindow`, `TravelProfile`, `ServiceAllowance`, `OperatingCalendarPolicy`, `ReferenceSnapshot`, `ReferenceValidator` |
| application | `ImportReferenceDataHandler`, `ReferenceDataQuery`, `SetVehicleDayStatusHandler`, `ReferenceBootstrap`, `ReferenceScope` |
| infrastructure | `CsvReferenceImporter`, `ReferenceVersionWriter`, `ReferenceVersionReader`, `ReferenceCache` |
| web | admin read endpoints |

**Owns:** `ref.brands`, `ref.depots`, `ref.districts`, `ref.outlets`, `ref.vehicles`, `ref.vehicle_day_status`, `ref.calendar_days`, `ref.district_travel`, `ref.service_allowances`, `ref.traffic_speed`, `ref.road_conditions`.

`district_travel` is keyed by **district alone**: depot is a function of district in the supplied data, and the official validator indexes it that way.

**Caches, does not own:** `ref.products`. The catalogue belongs to the external warehouse and arrives by scheduled bulk sync with a catalogue version. It is a projection: never edited here, and always able to report that it is stale.

**Commands:** `SetVehicleDayStatus`, `ImportReferenceData`, `OverrideCalendarDay`.
**Queries:** `snapshotFor(day)`, `outlet(id)`, `vehicle(id)`, `availableVehicles(depot, date)`, `vehiclesOfDepot(depot)` (the whole fleet, so Planning can tell unservable from deferred), `isOperating(date)`, `nextOperatingDay(date)`, `travelProfile(district)`.
**Publishes:** `vehicle.status_changed`, `reference.version_published`.
**Consumes:** nothing.

**Invariants.** An outlet belongs to exactly one depot and district. A vehicle belongs to exactly one home depot. Reference data is **versioned**: a published plan keeps the snapshot it was built against, so re-reading history never silently changes a past decision.

**Failure modes.** Calendar exhausted beyond the supplied range: extension policy applies (Monday to Saturday), with supplied dates and `CALENDAR_FILE` overrides taking precedence. Reference import with a changed outlet set: rejected unless explicitly versioned, because silently moving an outlet between depots invalidates published plans.

**Connections.** Everything reads it; it reads no other module's data. Its web reads ask Identity, through `IdentityQuery`, whether the depot, outlet or vehicle is in the actor's scope (R-IAM-28); `ReferenceQuery` itself is unscoped, because its callers are modules inside their own command. Cached in memory with a version stamp; cache invalidation is triggered by `reference.version_published`.

---

## 2. Identity and access (`iam`)

**Purpose.** Authentication, and the policy decision point for every authorization question in the system.

| Layer | Contents |
| --- | --- |
| contract | `CurrentActor`, `Role`, `Scope`, `AuthorizationDecision`, `IdentityQuery`, `McpContextView` |
| domain | `McpReadPolicy`, `Account`, `Session`, `Role`, `ScopeGrant`, `VehicleAssignment` (temporal), `PasswordPolicy`, and `domain/policy/`: `PolicyDocument`, `Statement`, `Effect`, `Pattern`, `Condition`, `ConditionOperator`, `AccessRequest`, `Decision`, `PolicyEvaluator` |
| application | `LoginHandler`, `SessionRegistry`, `LoginThrottle`, `AccountAdminUseCase`, `PolicyDecisionPoint`, `McpSessionHandler`, `McpAccessHandler`, `McpContextQuery` |
| infrastructure | `JdbcPolicyRepository`, `PolicyDocumentParser`, `PolicyCache`, `Argon2PasswordHasher` |
| web | `AuthController`, `McpSessionController`, `McpCredentialFilter` |

**Read-only MCP (#87).** Root `mcp/` is a separate stdio and stateless Streamable HTTP adapter over the existing authorized REST reads. Identity also owns public OAuth client registration, consent, one-time PKCE codes, resource binding and revocation (R-IAM-31). The frontend hosts the consent page and protocol proxy routes; the adapter has no database access. Remote access requires a canonical `MCP_PUBLIC_URL`, identical in backend and adapter. Identity owns dedicated opaque session purpose, the `mcp:Connect` policy action, connection context and revocation. `MCP_ENABLED` defaults off. The connection grant adds no business action or scope; every concrete read still uses its owning module's policy and SQL filtering. It never adopts a shared loader browser's PIN-switched identity. Authentication uses the existing throttle, and reads record their authorization in audit. See [R-IAM-30](RULES-AND-POLICIES.md) and the [walkthrough](../issues/087-readonly-mcp/WALKTHROUGH.md) for the curated surface and remaining work.

**Owns:** `iam.users`, `iam.roles`, `iam.user_roles`, `iam.user_depot_access`, `iam.user_outlet_access`, `iam.vehicle_driver_assignments`, `iam.devices`, `iam.sessions`, `iam.login_attempts`, `iam.policies`, `iam.policy_versions`, `iam.policy_attachments`, `iam.action_catalogue`.

**Commands:** `Login`, `Logout`, `CreateAccount`, `UpdateAccount`, `ResetPassword`, `DisableAccount`, `GrantScope`, `RevokeScope`, `RegisterDevice`, `AssignDriverToVehicle`, `CreatePolicy`, `CreatePolicyVersion`, `SetDefaultPolicyVersion`, `AttachPolicy`, `DetachPolicy`.
**Queries:** `permits(actor, command, target)`, `scopeOf(actor)`, `actorForSession(token)`, `driverVehicleOn(date)`.
**Publishes:** `access.granted`, `access.revoked`, `account.disabled`, `authorization.denied`.
**Consumes:** nothing.

**Policy change.** Scope changes are **data**: rows in `user_depot_access`, `user_outlet_access` and the assignment tables, effective immediately with an audit entry. A new command type or a new predicate is **code** in the PDP. Neither requires touching an enforcement point, because every enforcement point asks `permits(...)`.

**Invariants.** A session maps to exactly one account. Disabling an account revokes every session in the same transaction. A driver-to-vehicle assignment is **time-bounded and non-overlapping**: one driver per vehicle per period, enforced by a temporal exclusion constraint rather than by application code.

**Failure modes.** Revocation during an in-flight command: authorization is re-checked inside the transaction, so the command fails rather than completing with stale rights. Pooled connection reuse: the RLS actor is set with `SET LOCAL` inside the transaction, so it can never leak to the next borrower of that connection.

**Connections.** Called synchronously by the application layer of every module through `permits(...)`. Sets the database session actor that every RLS policy reads. It is the only module allowed to be a synchronous dependency of everything else.

---

## 3. Ordering (`ordering`)

**Purpose.** Capture demand, enforce the cutoff, own the order lifecycle, and mediate with the external warehouse.

| Layer | Contents |
| --- | --- |
| contract | `OrderViews`, `OrderStatus`, `OrderQuery` (incl. `confirmedDemand`), `OrderCommands`, `OrderEvents` |
| domain | `Order`, `OrderLine`, `OrderStatus` state machine, `Cutoff`, `TemperatureRequirement`, `OrderVersion` |
| application | `PlaceOrderHandler`, `AmendOrderHandler`, `CancelOrderHandler`, `CloseOrdersHandler`, `OrderDataQuery` |
| infrastructure | `JdbcOrderRepository`, `OrderProjection`. The warehouse adapter lives in the Warehouse module, behind `StockPort` |
| web | `PlanController`: reads under `/api/plans` (published plan, plan by id, deferrals, fuel, the two previews). Commands go through the command endpoint |

**Owns:** `ordering.orders`, `ordering.order_lines`, `ordering.order_status_history`.

**State machine.** This is the module's core asset and every transition is guarded:

```
place ─┬─► confirmed ─┬─► allocated ──► loading ──► in_transit ──┬─► delivered ───────────┬─► received
       │   (reserved) │                                          ├─► partially_delivered ─┤
       │              │                                          └─► failed               └─► unconfirmed
       │              ├─► deferred ──► (next run) allocated │ deferred │ unservable
       │              └─► unservable
       ├─► stock_unknown ──► confirmed           (warehouse reachable again)
       └─► rejected at placement, nothing saved  (short stock, decision D-F)

any state before loading ──► cancelled           (releases the warehouse reservation)
```

Revised 2026-09-30. `OrderStatus` in `ordering/contract` is the one vocabulary; other modules keep their own state and never write it. There is no `stock_held`, `adjusted` or `rejected` state: short stock rejects placement with per-line availability and the store resubmits (D-F). A failed delivery is not redelivered in place: Issues emits `redelivery.requested` and Ordering creates a new order linked to the original.

**Commands:** `order:Place`, `order:Amend`, `order:Cancel`, `order:CloseForDay`.
**Queries:** `confirmedDemand(depot, day)`, `order(id)`, `timeline(orderId)`, `ordersForOutlet(outletId, cursor)`.
**Publishes:** `order.placed`, `order.amended`, `order.cancelled`, `order.auto_deferred`, `orders.closed`.
**Consumes:** `plan.published`, `plan.revised`, `order.deferred`, `order.unservable`, `loading.started`, `trip.released`, `delivery.completed`, `delivery.failed`, `receipt.confirmed`, `receipt.auto_closed`, `redelivery.requested`, `warehouse.order_status_changed`. `loading.started` is what makes an order `loading`, after which amendment is refused (ORD-06).
**Job:** `ordering.cutoff` at 16:00 Asia/Colombo.

**Ports:** `StockPort` (Warehouse contract), called synchronously at placement.

**Invariants.** An order is never silently reduced: short stock rejects placement and the store sees the available quantity. Chilled and ambient never share an order, because vehicle eligibility is decided per order. The cutoff is evaluated on the **server clock in Asia/Colombo**, never on a client timestamp. `order_lines` are **descriptive**: the order's own weight, volume and temperature stay authoritative and are never recomputed from lines.

**Failure modes.** Warehouse unreachable: circuit breaker opens, the order enters `stock_unknown`, and the dispatcher sees the degraded state rather than a false confirmation. Stock unresolved at cutoff: auto-deferred with reason `stock_unresolved`, still without a reservation and so still not demand, and `order.auto_deferred` tells Notification to inform the store. A consumed event that the state machine cannot apply is counted (`waypoint.order.event_illegal`) and skipped, never thrown, so one bad event cannot block a consumer.

**Connections.** Publishes confirmed demand that Planning consumes. Calls `StockPort` through the anti-corruption layer; the warehouse model never leaks past that adapter.

---

## 4. Planning (`planning`)

**Purpose.** Turn confirmed demand plus a fleet snapshot into a defensible allocation. This is the system's highest-value module.

| Layer | Contents |
| --- | --- |
| contract | `PlanViews` (`PlanView`, `TripView`, `AllocationView`, `ConstraintResultView`, `DeferralView`, `FuelView`, `InterchangePreview`), `PlanQuery`, `PlanCommands`, `PlanEvents` |
| domain | `PlanningRun` (the aggregate: draft, override, defer, revision, trip move and replan, options), `VehicleDay`, `Trip`, `PlanOrder`, `FleetVehicle`, `DistrictTravel`, `PlanContext`, `RuleSet`, `PriorityPolicy` (versioned decision table), `ConstraintRegistry`, `Constraint`, `Constraints`, `ConstraintResult`, `PlanVerification`, `PublicationGate`, `DemandFingerprint`, `TripTimeline`, `FuelLedger`, `TemperatureClass`, `AllocationEngine` (port, with `Problem` and `AllocationResult`) |
| application | `GeneratePlanHandler`, `OverrideAllocationHandler`, `DeferOrderHandler`, `PublishPlanHandler`, `RevisePlanHandler`, `ReplanTripHandler`, `PlanDataQuery` (implements `PlanQuery`, previews included), `PlanningProblems`, `PlanningDrafts`, `PlanningRevisions`, `PlanPublication` (the gate), `PlanRecords` (translation only), `PlanningConsumers` |
| infrastructure | `PriorityInsertionEngine`, `ValidatingEngine` (decorator), `PlanningEngineConfiguration`, `JdbcPlanRepository`, `PeakDayScenario` (the Task 2B fixture and CSV export) |
| web | routed through the command endpoint |

**Owns:** `planning.runs`, `planning.trips`, `planning.allocations`, `planning.deferrals`, `planning.route_legs` (planned times only; actual times belong to Execution), `planning.fuel_usage`, `planning.rule_sets`, `planning.rule_parameters`, `planning.policy_versions`. A trip id is unique within a plan and stable across versions while the trip carries the same orders, whichever vehicle carries it.

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
| `SingleTemperaturePerTrip` | Refrigeration on or off for the whole trip (R-PLN-31) |
| `VehicleAvailable` | No vehicle in the workshop or unavailable that day (R-FLT-03) |

Capacity comparisons use the supplied validator's tolerance of `1e-6`, never bare floating point. Every rule here carries its identifier from [RULES-AND-POLICIES.md](RULES-AND-POLICIES.md), and the rules marked Validated are re-checked in CI by running our allocation output through the supplied `check_allocation.py`, so a regression fails the build rather than the submission.

Four consumers read that one registry: the engine, the manual override path, the publication gate and the UI. That is what makes explainability structural instead of a feature someone remembers to add.

**Predicates are code; thresholds are effective-dated parameters.** The rule "trip minutes must fit the Fresh budget" is compiled; the number 270 is a `rule_parameters` row with an effective date. Changing the budget is a config change, not a release.

**`PriorityPolicy` is a versioned decision table**, not a hardcoded comparator. It is the rule most likely to change by business preference and the one dispatchers will argue about, so the ordering is authorable and versioned: prior skips, Fresh, chilled, strict access, brand cadence, earliest closing window, then distance, volume and time unserved as tie-breaks (R-PLN-21). Every `PlanningRun` stamps the `rule_set_version` that produced it, alongside the reference-data version, so a historical deferral can be replayed under the rules that were actually in force.

**Commands:** `plan:Generate`, `plan:Override`, `plan:Defer`, `plan:Publish`, `plan:Revise`, `plan:Replan`. A published plan and every child row are immutable; revise and replan create a new version that supersedes it.
**Queries:** `publishedPlan(depot, day)`, `draft(id)`, `previewAssignments(orderId)`, `previewInterchange(tripId, vehicleId)`, `deferralsFor(depot, day)`, `fuelRemaining(vehicle, week)`.
**Publishes:** `plan.published`, `plan.revised` (both carry trips and stops, so consumers never read Planning's tables), `order.deferred`, `order.unservable`.
**Consumes:** `order.placed`, `order.amended`, `order.cancelled`, `orders.closed`, `vehicle.status_changed`, `reference.version_published`, `calendar.overridden`, `loading.interchange_requested`. It does not consume `delivery.failed`: a redelivery arrives as a new order. Consumers never publish, except an interchange that moves exactly the requested trip and passes the whole gate: `orders.closed` generates a draft as the system; order changes, a new reference version and a calendar override mark drafts stale; a vehicle lost after publication drafts a revision for the dispatcher. Detail in [the issue #9 walkthrough](../issues/009-planning/WALKTHROUGH.md).

**Ports:** `AllocationEngine`, `TravelAndServiceEstimator`.

**Invariants.** A published plan is **immutable**; a change creates a new version. Every constraint holds across the whole plan, which is why `PlanningRun` and not `Trip` is the aggregate. Every deferral records the binding constraint, never a generic message. Deferred orders keep identity, original requested date, skip count and history when carried to the next run. One open draft per depot and day. A revision carries the published plan's orders, drops cancelled ones, defers late arrivals under `PLN-07`, and announces only the deferrals it made.

**Failure modes.** Demand exceeds capacity: defer by policy (prior skips, Fresh, chilled, earliest closing window) with reasons. Order larger than any vehicle: `unservable`, surfaced for a split decision, never deferred forever. Vehicle removed after publication: only affected trips replan. Concurrent draft edits: revision check rejects the stale one with a diff.

**Extraction.** Designed to be extracted when a depot-day allocation exceeds 30 s at p95 or the engine needs a different runtime. It already sits behind `AllocationEngine`, owns its tables, and communicates by events.

---

## 5. Loading (`loading`)

**Purpose.** The dock workflow: load to the planned stop sequence, catch shortfalls before departure, and handle a vehicle substitution safely.

| Layer | Contents |
| --- | --- |
| contract | `LoadingViews`, `LoadingQuery`, `LoadingCommands`, `LoadingEvents` |
| domain | `LoadingSession`, `ItemLine`, `ReleaseChecklist` |
| application | `StartLoadingHandler`, `RecordCheckHandler`, `FlagShortfallHandler`, `HandBackHandler`, `ReleaseTripHandler`, `ManifestBuilder`, `LoadingDataQuery`, guarded development-only `LoadingFixture` |
| infrastructure | `JdbcLoadingRepository`, `JdbcManifestWriter`, `JdbcLoadingReads` |
| web | `LoadingController` for reads; writes through the shared command endpoint |

**Owns:** `loading.trips`, `loading.stops`, `loading.items`, `loading.sessions`, append-only `loading.item_checks` and `loading.shortfalls`, built from `plan.published` and `plan.revised`.

**Commands:** `loading:Start`, `loading:Check`, `loading:Shortfall`, `loading:HandBack`, `loading:Release`. Interchange and dispatcher handover are deferred.
**Queries:** `manifest(tripId)`, `readyTrips(depot, day)`, `openShortfalls(depot)`.
**Publishes:** `loading.started`, `loading.shortfall`, `trip.released`. `loading.interchange_requested` is a planned event; the interchange command is deferred.
**Consumes:** `plan.published`, `plan.revised`, `shortfall.resolved` (from Issues).

**Invariants.** A trip releases only when **every** allocated item has a recorded check on the current plan version, including any Short, Damaged, Doesn't fit or Missing exception. Short is one item with some units missing; the units that arrived stay loaded. The loader also confirms doors sealed, orders secured and driver present; there is no temperature-reading or seal-number gate. One loader holds a trip at a time, and hand back preserves earlier checks. Manifest order is the planned stop sequence reversed, so the first stop is unloaded first. There is no separate mall-first loading rule (decision D-L, R-LOD-08 withdrawn).

**Vehicle interchange (planned, not implemented in issue #10).** Swapping the truck must not be an `UPDATE` to a trip's vehicle. The intended workflow is a request that:

1. previews the swap through Planning's `previewInterchange` contract query, which revalidates the **entire trip** against the substitute (capacity, temperature, access, home depot, fuel, time budget),
2. emits `loading.interchange_requested`; Planning accepts it only if every constraint still passes, with `plan:Replan`,
3. arrives back as `plan.revised`, a new plan version, so history shows both vehicles and the published plan is never edited in place,
4. rebuilds the manifest from the new version, so checks made against the old one no longer count toward release.

If no compatible substitute exists, the trip is deferred as a unit and the orders carry forward with identity intact.

**Failure modes.** Loader shift ends mid-session: hand back preserves partial checks and their authors, and another loader may take the trip. Driver-assignment gating and dispatcher handover remain deferred. Duplicate mark-loaded: idempotent, no duplicate check rows.

---

## 6. Execution (`execution`)

**Purpose.** What actually happened on the road, captured on a phone that may have no signal.

| Layer | Contents |
| --- | --- |
| contract | `ExecutionViews`, `ExecutionQuery`, `ExecutionCommands`, `ExecutionEvents` |
| domain | `DeliveryRecord`, `DeliveryLines`, `ProofOfDelivery`, `ProofLink`, `ServiceWindow`, `LatenessPolicy`, `EtaPolicy`, `FailureReason`, `ProofStore` (port) |
| application | `StartStopHandler`, `RecordArrivalHandler`, `RecordDeliveryHandler`, `CaptureProofHandler`, `ReportVehicleStatusHandler`, `ReportFaultHandler`, `ExecutionConsumers`, `RunSheetBuilder`, `ExecutionDataQuery` |
| infrastructure | `DatabaseProofStore` (default), `LocalProofStore`, `JdbcDeliveryRepository`, `JdbcExecutionReads` |
| web | `ExecutionController`; state changes go through the shared command endpoint |

**Owns:** `execution.delivery_records` (with actual times), `execution.proofs`, `execution.attachments`, and its run sheets built from `trip.released`.

**Commands:** `StartStop`, `RecordArrival`, `RecordDelivery`, `RecordFailedDelivery`, `CaptureProof`, `ReportVehicleStatus`, `ReportRoadFault`.
**Queries:** `runSheet(vehicle, day)`, `deliveryRecord(allocationId)`, `proof(deliveryRecordId)`.
**Publishes:** `delivery.started`, `delivery.completed`, `delivery.failed`, `vehicle.fault_reported`, `road.disruption_reported`.
**Consumes:** `trip.released`.

**Ports:** `ProofStore`.

**Invariants.** One outcome per allocated stop, recorded once. Proof is attached to the outcome, never replaces it. **Server time decides**; the device clock is stored alongside for forensics but never used for a decision. Early arrival waits: service time starts at window open, not at arrival. A late arrival is still delivered and flagged with a reason.

**Late at a mall.** After a mall's effective window closes the goods cannot be unloaded, so the stop is recorded `failed` with reason `mall_window_closed`, not delivered late (C-7, EXE-20).

**Failure modes.** Offline for a whole run: every outcome queues locally, and the UI acknowledges only after the local write is durable. Camera denied or photo too large: delivery may complete with a recorded reason and is flagged lower-evidence, because a device limitation must not block the work. Vehicle breakdown: vehicle set to `fault`, issue raised, remaining stops released for replanning, goods disposition recorded.

**Connections.** The most offline-sensitive module. Every command it accepts is designed to be replayable and version-checked, because it will be replayed.

---

## 7. Receipt (`receipt`)

**Purpose.** The outlet's independent acceptance, deliberately separate from the driver's proof.

| Layer | Contents |
| --- | --- |
| contract | `ReceiptStatus`, `ReceiptEvents` |
| domain | `Receipt`, `ReceiptLine`, `ReceiptStateMachine`, `AutoClosePolicy`, `ReceiptParameters` |
| application | `ReceiptAnswerHandler` with `ConfirmReceiptHandler`, `ConfirmPartialReceiptHandler`, `DisputeReceiptHandler`; `ReceiptConsumers.OnDeliveryCompleted`; `ReceiptAutoCloseJob`; `ReceiptDataQuery` |
| infrastructure | `JdbcReceiptRepository` |

**Owns:** `receipt.confirmations`, `receipt.confirmation_lines`, `receipt.confirmation_history`, `receipt.parameters`.

**Commands:** `ConfirmReceipt`, `ConfirmPartialReceipt`, `DisputeReceipt`.
**Queries:** `receiptFor(orderId)`, `pendingConfirmations(outletId)`, `custodyChain(orderId)`, the loading check, the proof and the receipt side by side (R-RCP-08). Web: `/api/receipts/pending?outlet=`, `/{orderId}`, `/{orderId}/custody`.
**Publishes:** `receipt.confirmed` (with `partial`), `receipt.disputed`, `receipt.auto_closed`. A shortage reported after auto-close is announced as `receipt.disputed`, because the order is already UNCONFIRMED.
**Consumes:** `delivery.completed`, which opens the receipt. Not `delivery.failed`: nothing arrived, so there is nothing to accept, and Issues raises the failure.

**Invariants.** Confirmation refers to a real delivery record. Driver proof and store acceptance are separate events and neither overwrites the other, which is the entire point: disputes become evidence-based rather than memory-based.

**Failure modes.** Store never confirms: auto-closes after a configured window with status `unconfirmed`, never silently "delivered". Dispute after proof exists: recorded alongside the proof; evidence is never deleted.

---

## 8. Issues (`issues`)

**Purpose.** One lifecycle for every operational problem, wherever it is raised.

| Layer | Contents |
| --- | --- |
| contract | `IssueViews` (`IssueView`, `IssueHistoryView`, `SubjectRef`), `IssueQuery`, `IssueCommands`, `IssueEvents` |
| domain | `Issue`, `IssueLifecycle`, `ResolutionAction`, `SeverityPolicy` |
| application | `RaiseIssueHandler`, `IssueCommandHandler` with six decisions in `IssueHandlers`, `IssueScope`, `IssuesConsumers`, `IssueEscalationJob`, `IssueDataQuery` |
| infrastructure | `JdbcIssueRepository` |
| web | `IssueController`, reads only |

**Owns:** `issues.issues`, `issues.issue_subjects`, `issues.issue_history`, `issues.parameters`.

**Commands:** `RaiseIssue`, `AssignIssue`, `ResolveIssue`, `RecordReplacement`, `ScheduleRedelivery`, `CloseIssue`, `CancelIssue`. Raise rights by type are policy data (R-ISS-07).
**Queries:** `openIssues(depot)` (most severe first, keyset), `issuesFor(subject)`. Web: `/api/issues?depot=`, `/by-subject?type=&id=`, `/{id}`, `/{id}/history`.
**Publishes:** `issue.raised`, `issue.resolved`, `issue.escalated`, `shortfall.resolved` (naming the shortfall when the issue came from one), `redelivery.requested`.
**Consumes:** `loading.shortfall`, `delivery.failed`, `vehicle.fault_reported`, `road.disruption_reported`, `receipt.disputed`, `receipt.confirmed` (partial only), `warehouse.discrepancy_found`. One issue per source event.

**Redelivery.** Only when nothing reached the outlet (failed delivery, stock discrepancy), at most once per issue, and the new order carries a skip so the next plan serves it first (R-ISS-04, R-ISS-05, ORD-15).

**Invariants.** An issue links at least one subject: order, trip, delivery, receipt, shortfall or vehicle (a vehicle alone is valid). A shortage investigation is never resolved by the system (R-RCP-07). Resolution requires a recorded action and a reason. A redelivery links a new order while preserving the original proof and history.

---

## 9. Notification (`notification`)

**Built** (issue #14), backend only. Each role UI places its own inbox, push opt-in and service worker handlers; there is no shared notification component (decided on the issue). What was built is in [the walkthrough](../issues/014-notification/WALKTHROUGH.md).

**Purpose.** Turn domain events into messages people actually receive, with delivery tracked per channel.

| Layer | Contents |
| --- | --- |
| domain | `NotificationPolicy` (the routing table applied to one event), `RoutingRule`, `RoutingTable`, `RoutedEvent`, `Template`, `Delivery` (channels, statuses, retry policy) |
| application | `NotificationConsumers` (one subscriber per routed event), `Notifier`, `PushDeliveryJob`, `NotificationHandlers`, `NotificationDataQuery`, `InboxSignals`, the port `PushGateway`. The outbox relay is platform, not Notification |
| infrastructure | `JdbcNotificationRepository`, `WebPushGateway` and `WebPushCrypto` (RFC 8030, 8291, 8292 on the JDK) |
| web | `NotificationController`: `/api/notifications`, `/unread-count`, `/stream` (server-sent events), `/push-config` |

**Owns:** `notification.notifications`, `notification.deliveries`, `notification.push_subscriptions`, and the read-only `notification.routing_versions` and `notification.routing_rules`. It consumes events like any other module; `integration.outbox_events` and the relay belong to the platform. Recipients come from `IdentityQuery.recipientsFor`; it never reads `iam` tables.

**Channels (D-N).** The in-app inbox, which is delivered when the row is written, and web push, sent by `PushDeliveryJob` after commit with retry and a dead letter (P-27). With no VAPID keys push is off, visibly (NOT-05).

**The matrix** is data: version 1 of `notification.routing_rules` (R-NOT-09). Every row produces a durable notification and delivery records.

| Event | To | Push | Why it matters |
| --- | --- | --- | --- |
| `warehouse.order_status_changed` (`insufficient`, `expired`) | Store manager | yes | A retried placement found stock short, or a partial reservation expired (R-NOT-01) |
| `order.unservable` | Store manager, dispatcher | yes | No vehicle can take it; needs a decision |
| `order.deferred` | Store manager | yes | With the binding reason (R-RCP-03, R-NOT-04) |
| `order.auto_deferred` | Store manager | yes | The warehouse never confirmed stock before the cutoff (STK-03) |
| `plan.published`, `plan.revised` | Loader; driver of each trip's vehicle on the service date | yes | Work is available, or changed |
| `trip.released` | Driver; dispatcher when the vehicle has no driver (LOD-05) | yes | Vehicle ready |
| `loading.shortfall` | Dispatcher | yes | Departure is blocked now (R-NOT-02) |
| `delivery.started`, `delivery.completed` | Store manager | no | Arriving; proof is available to review |
| `delivery.failed` | Dispatcher, store manager | yes | Requires a decision |
| `eta.changed` | Store manager, dispatcher | yes | Staffing at the outlet, lateness at the depot (R-RCP-02, R-EXE-15) |
| `issue.raised` | Dispatcher; store manager when an outlet is named | yes | Fault, delay, damage, access problem (R-NOT-03) |
| `issue.escalated` | Dispatcher | yes | Waited unassigned past its severity's deadline (R-ISS-06) |
| `vehicle.fault_reported`, `road.disruption_reported` | Dispatcher | yes | The dispatcher decides the vehicle's status |
| `receipt.disputed` | Dispatcher | yes | The store disagrees with what arrived |
| `vehicle.status_changed` | Dispatcher | no | Fleet availability changed. Reference does not publish it yet |

A driver is pushed only trip-level events (R-NOT-08), and whoever caused an event is not told about it (R-NOT-07).

**Commands:** `notification:MarkRead` (ids), `notification:MarkAllRead` (`upTo`), `notification:Subscribe`, `notification:Unsubscribe`. Read state is set-once, so marking takes no `expectedVersion` (R-NOT-06).

**Invariants.** A notification is never sent inside the request transaction (R-NOT-05): the consumer writes the intent, and the push job sends with no transaction open. The state change and the outbox row commit together; the relay publishes after commit, at least once, and each person's notification is unique per event and target, so a redelivery writes nothing (NOT-03). A push that keeps failing is dead with its last error; it never blocks the queue and never vanishes (NOT-04). Notifications are never deleted (A-34).

---

## 10. Sync (`sync`)

**Purpose.** Apply operations that were created while a device was offline, exactly once, in order, with conflicts surfaced rather than resolved silently.

**Owns:** `sync.operations`.

**Commands:** `SubmitOperation`, `AcknowledgeOperation`, `DiscardOperation`. Batch submit and acknowledge are built. Discard, and a resolve that reapplies a held write, are not: they wait on decision D-O, who may review another person's conflict (issue #28).
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

**Recorded per decision (issue #6).** The command id, the target (`wpt:<module>:<type>:<id>` split into type and id), a redacted outcome, a redacted state before where the handler supplies it (`AuditContext.before`), the correlation id of the request, and the policy generation the decision was taken under. Reads go through `GET /api/audit` (filters: actor, target, action, decision, correlation id, command id, time range; keyset paginated; `audit:Read`, held by auditors and administrators) and `GET /api/audit/decisions/{commandId}`, which returns the audit rows, the receipt and the policy versions that governed the decision, saying whether they are exact (generation unchanged since) or reconstructed.

**Event backbone and scheduler (issue #6).** `OutboxRelay` delivers `integration.outbox_events` at least once, per aggregate in order, to every `EventSubscriber` of the type, each in a transaction under its own module role with the consumer inbox making the delivery idempotent. Dead letters are read at `GET /api/platform/dead-letters` and replayed with `platform.replay-event` (`platform:ReplayEvent`, administrators). `ScheduledJobRunner` leases and records every `ScheduledJob`; platform jobs are `AuditPartitionJob` and `PlatformRetentionJob`, and Identity and Reference register `SessionRetentionJob` and `CalendarExhaustionJob` through the same port. See R-PLT-01 to 07.

**Invariants.** Written in the same transaction as the change it describes, so an audit gap is impossible. Append-only, no updates or deletes. **Denied authorization attempts are audited too**, because failed attempts are the interesting ones. Partitioned monthly, retained for years.

---

## 12. Intelligence (`ml`)

**Built** (issue #16), backend only; the screens are the role UIs' (Forecast and late risk #19, supply probability #18, model registry #22). What was built is in [the walkthrough](../issues/016-intelligence/WALKTHROUGH.md).

**Purpose.** Predictions that support planning, kept strictly out of the transactional core.

| Layer | Contents |
| --- | --- |
| contract | `TravelAndServiceEstimator` (port), `PredictionQuery`, `ModelViews` (registry, kinds `delivery_risk` and `demand_forecast`), `PredictionViews` |
| domain | `DeterministicEstimator`, `ModelGate`, `SupplyPolicy`, `PlannedRoutes`, `CircuitBreaker` |
| application | `PlanScoringJob`, `ForecastJob`, `ModelHandlers`, `IntelligenceConsumers`, `IntelligenceDataQuery`, `ReferencePayload` |
| infrastructure | `ModelServingAdapter` (HTTP to the model service, behind a circuit breaker), `JdbcIntelligenceRepository` |
| web | `/api/ml/models`, `/plans/{id}/predictions`, `/forecast`, `/orders/{id}/supply-probability`, `/training/deliveries` |

**The model service** (`ml-server/`, ADR-001's Python trigger) serves the trained Datathon models: `delivery_risk` (service minutes and P(late) per stop, scored over whole planned routes, with a no-road-conditions fallback) and `demand_forecast` (weekly total and chilled m³). It only predicts, and refuses to start if a model file differs from its manifest.

**Owns:** `ml.model_versions`, `ml.plan_scorings`, `ml.delivery_predictions`, `ml.demand_forecasts`.

**Invariants.**
- A model is never called inside an operational transaction (R-ML-01).
- Every stored prediction names its model or `deterministic` and is written once (R-ML-02).
- One model per kind is active (R-ML-03).
- A model answers only when it is the active one and the service reports exactly it; otherwise the deterministic answer, marked degraded with the reason, and the plan says it was scored without the predictor (R-ML-04).
- Predictions never change allocation (R-ML-05).

**Connections.**
- Consumes `plan.published` and `plan.revised`.
- Reads Planning (`PlanQuery.plan`), Ordering (`order`, `dailyVolumes`), Execution (`actuals`) and Reference (outlets, vehicles, travel, calendar, traffic speed, road conditions) through their contracts.
- Planning reads `PredictionQuery.planScoring` for `plannedWithoutPredictor`, lazily, so it still works without this module.

---

## External warehouse integration contract

The boundary was defined (port, anti-corruption layer, circuit breaker, degraded mode, who owns what). The **contract** was not: no operations, no reservation lifecycle, no inbound security. This closes that.

### Outbound: Waypoint calls the warehouse

Revised 2026-09-30 against the verified API (RULES-AND-POLICIES §2) and decisions D-E to D-H and D-M. Creating a warehouse order **is** the reservation; there is no separate reserve call. **Revised 2026-10-01 (issue #7):** a partial placement (`202`) is a `reserved` warehouse order with a 15-minute expiry. Waypoint keeps it as `partially_reserved` and shows the store the shortfall and the other warehouse's stock; the store accepts it (`order:AcceptShortfall`, the warehouse `confirm` call) or cancels, and an expiry cancels it. Only a `201` (`pending`, no expiry) or a confirmed `202` is a reservation Waypoint plans against. Orders are placed in the depot's warehouse, `Kandy` or `Peliyagoda` (A-25).

| Operation | Caller | When | Timeout | On failure |
| --- | --- | --- | --- | --- |
| `StockPort.placeOrder(orderRef, lines)` | Ordering, synchronously | Order placed | 2 s | Circuit opens, the order saves as `stock_unknown` and the dispatcher sees the degraded banner. **Never assume reserved** |
| `StockPort.amendOrder(ref, lines)` | Ordering, synchronously | Order amended | 2 s | As above |
| Retry of a `stock_unknown` placement | Warehouse module | Warehouse reachable again | 2 s | Queries by `orderRef` first, because `POST /orders` is not idempotent (R-STK-11) |
| Cancel (`PUT status cancelled`) | Warehouse module, on `order.cancelled` | Order cancelled | 5 s | Retried through the outbox until acknowledged. Deferral does **not** cancel (D-H) |
| Ship and deliver (`PUT status`) | Warehouse module, on `trip.released` and `delivery.completed` | Trip leaves, stop completes | 5 s | Retried through the outbox; an invalid transition is recorded and alerted |
| Catalogue sync (`GET /products`) | Warehouse module, scheduled | Bulk sync | 60 s | Keep the last good copy, mark it stale with its age |

Nothing available is `409 insufficient_stock`: Waypoint returns per-line availability to the store and saves nothing. A short line with some stock is the partial reservation above (D-F revised). Every call carries a correlation id, and an `Idempotency-Key` once the warehouse accepts one.

### Reservation lifecycle

The warehouse's own order lifecycle is the reservation's lifecycle. Waypoint stores only the warehouse order reference and reads the state:

```
placeOrder ──► pending ──┬──► shipped ──► delivered      (trip released, stop completed)
     │                   └──► cancelled                  (Waypoint order cancelled only)
     └──► 409: nothing reserved, order rejected at placement
```

A deferred order keeps its `pending` warehouse order, so the store's stock stays held across runs (D-H).

### Change requests to the warehouse team

The team owns the warehouse service and may change it (D-M). Waypoint asks for: temperature per product; weight, volume and temperature totals in the `POST /orders` response; an `Idempotency-Key` on `POST /orders`; per-line available quantity on `409`; an HMAC-signed webhook for order and stock changes; and `updated_since` on `GET /products`.

### Inbound: the warehouse notifies Waypoint

Inbound is where the security lives.

1. **Transport**: an HMAC signature over the raw body plus a timestamp, with a replay window of a few minutes.
2. **Land it first**: the request writes to `warehouse.inbound_events` and returns. Nothing is processed inside the HTTP request, so processing survives the connection that delivered it.
3. **Exact replay detection**: unique on `(source_system, source_event_id)`. The sender retries by design.
4. **Unverified never processes**: a `CHECK` constraint allows an unverified row only in `received`, `quarantined` or `dead`. Storing it for forensics is fine; acting on it is not.
5. **Unknown event types are quarantined**, not ignored. Silence is how an integration drifts for months.

Until the webhook exists, the Warehouse module polls order status. Either way it publishes `warehouse.order_status_changed` for Ordering.

### What Waypoint never accepts from the warehouse

A requirement worth recording because the data cannot meet it: the team's requirements draft specified a **minimum order count per product**. The catalogue carries `product_id`, `brand`, `unit_weight_kg`, `unit_volume_m3`, `basis` and `verified_real_sku`, and nothing else. There is no minimum order quantity to enforce, so that rule cannot be implemented until the warehouse publishes it. Do not silently drop it; either the warehouse adds the field or the requirement is withdrawn.

Authority over weight, volume or temperature for capacity decisions. Those come from the order, for the reasons in [DATA-MODEL-REVIEW.md](DATA-MODEL-REVIEW.md#external-product-catalogue). The warehouse can tell Waypoint what is in stock; it cannot tell it what fits on a truck.

### Open decisions

1. Is stock tracked per depot or per outlet? It decides whether the warehouse needs a location on the order.
2. HMAC (recommended) or mutual TLS for the webhook.

---

## Module connection summary

Modules connect three ways: a contract query (synchronous, read only), an event through the outbox (asynchronous, at least once), or a port. The contracts are code in each module's `contract` package, mirrored for the frontend in `frontend/src/shared/domain/`; `EventCatalogueTest` holds the event records to the table below.

| Synchronous connection | From | To |
| --- | --- | --- |
| `IdentityQuery` (`permits`, `scopeOf`, `driverVehicleOn`, `recipientsFor`) | every module | Identity |
| `ReferenceQuery`, cached | every module | Reference |
| `StockPort.placeOrder`, `amendOrder`, with circuit breaker | Ordering | Warehouse |
| `OrderQuery.confirmedDemand` | Planning | Ordering |
| `PlanQuery.previewInterchange` | Loading | Planning |
| `PredictionQuery` (plan scoring, degradable) | Planning | Intelligence |
| `CatalogueQuery` | store UI, admin | Warehouse |
| audit row, written in the same transaction | all | platform |

**Event catalogue.** Envelope `{eventId (UUIDv7), type, version, occurredAt, producer, correlationId, actorId, payload}`. Each consumer records `(consumer, eventId)` so a redelivery is a no-op.

| Event | Producer | Consumers |
| --- | --- | --- |
| `order.placed`, `order.amended`, `order.cancelled` | Ordering | Planning, Warehouse (cancel), Notification |
| `order.auto_deferred` | Ordering | Notification |
| `orders.closed` | Ordering | Planning |
| `plan.published`, `plan.revised` | Planning | Ordering, Loading, Execution, Notification, Intelligence |
| `order.deferred`, `order.unservable` | Planning | Ordering, Notification |
| `loading.started` | Loading | Ordering, Notification |
| `loading.shortfall` | Loading | Issues, Notification |
| `loading.interchange_requested` | Loading | Planning |
| `trip.released` | Loading | Execution, Ordering, Warehouse (shipped), Notification |
| `delivery.started`, `delivery.completed`, `delivery.failed`, `eta.changed` | Execution | Ordering, Receipt, Warehouse (delivered), Issues, Notification |
| `vehicle.fault_reported`, `road.disruption_reported` | Execution | Issues, Notification |
| `receipt.confirmed`, `receipt.disputed`, `receipt.auto_closed` | Receipt | Ordering, Issues, Notification |
| `issue.raised`, `issue.resolved`, `issue.escalated` | Issues | Notification |
| `shortfall.resolved` | Issues | Loading |
| `redelivery.requested` | Issues | Ordering |
| `warehouse.order_status_changed` | Warehouse | Ordering, Notification |
| `warehouse.discrepancy_found` | Warehouse | Issues |
| `catalogue.synced` | Warehouse | Ordering |
| `reference.version_published`, `vehicle.status_changed`, `calendar.overridden` | Reference | Planning, Notification |
