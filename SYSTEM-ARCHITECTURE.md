# Waypoint Dispatch: system architecture

Audience: the Xception build team. This is the from-scratch enterprise design for Waypoint Dispatch: the actors, the modules and services, the layers, how the data layer maps to objects, how authentication and authorization are enforced, how every known edge case is handled, and how the work is split across developers with its dependency order.

It consolidates three inputs: the Tech-Triathlon challenge booklet, the team's `our team identified things draft not finilized.docx`, and the team's `Waypoint_Database_Schema_Quick_Guide.pdf` (40 tables, 5 schemas, 76 foreign keys). Where those three disagree with what is currently built, this document states the gap rather than hiding it.

Companion documents: [docs/code-structure.md](docs/code-structure.md) is the folder layout and its enforced rules. [docs/development-docs/enterprise-architecture-plan.md](docs/development-docs/enterprise-architecture-plan.md) is the phased plan against the competition deadlines. This document is the design those two serve.

---

## 0. Read this first: two different targets

There are two systems in play and confusing them wastes the most time.

**The target architecture** is what this document describes: six actors, thirteen modules, five database schemas, an event backbone. It is what Waypoint Group would actually run.

**The competition deliverable** is due October 4. It must let a judge complete the four-role workflow, respect the operating constraints, run from `docker compose up`, and be faithful to the Day 5 design.

A 40-table rebuild from scratch will not ship in eight days alongside the Datathon. So every section below marks scope as one of:

- **NOW** - in the competition build. Judged.
- **NEXT** - designed now, built after the deadline. Must not be blocked by NOW decisions.
- **LATER** - acknowledged, deliberately out of scope.

The value of designing the whole thing now is that the NOW subset stops painting us into corners.

---

## 1. The problem, stated precisely

Waypoint Group runs 120 outlets across three brands from two depots with 60 vehicles. The three brands compete for the same fleet every day. On most days the fleet cannot serve everyone.

The business problem is not routing. It is **accountable decision-making under scarcity**:

1. Demand must be captured before a 16:00 cutoff and be trustworthy (stock actually exists).
2. Capacity must be allocated against hard physical constraints, and what cannot be served must be **deferred with a recorded, defensible reason**.
3. The decision must reach the dock, the road and the outlet without a phone call.
4. Field work happens where there is no signal and must not be lost or applied twice.
5. Every handoff must leave evidence, because disputes currently depend on memory.

Five failure modes follow directly, and the architecture exists to close them:

| Current failure | Architectural answer |
| --- | --- |
| Planning lives in one dispatcher's spreadsheet and head | Constraints as executable rules, one definition, every consumer |
| No shared view after vehicles leave | Event-sourced status projected per role |
| Deferrals leave no record, outlets get skipped twice running | Deferral is a first-class record with reason, count and carry-forward |
| Verbal instructions, no proof | Proof of delivery and receipt confirmation as separate, linked records |
| No signal in the hill country | Offline-first write path with idempotent replay |

---

## 2. Actors

The booklet names four roles. The team's document introduces a fifth, and operating the system needs a sixth. Getting this list right now matters, because authorization scoping is built on it.

| Actor | Scope | Device and context | Core responsibility |
| --- | --- | --- | --- |
| Store manager | One outlet | Desktop or phone at the counter | Place orders, confirm receipt, report issues |
| **Stock manager** | One depot | Warehouse terminal | Release or adjust orders locked for insufficient stock |
| Dispatcher | One or more depots | Large screen, stable network | Close orders, allocate, defer, override, resolve exceptions |
| Loader | One depot | Shared dock tablet | Load to stop sequence, flag shortfalls, release for departure |
| Driver | One vehicle, one day | Personal phone, intermittent signal | Execute stops, capture proof, report vehicle and road faults |
| Admin | Global | CLI or admin screen | Accounts, roles, reference data, calendar |

The stock manager is **NOW-critical to decide** even if the screen is NEXT, because "order locked pending stock approval" is an order state, and states are expensive to add later.

Non-human actors, which need identity for audit just as much:

| System actor | What it does |
| --- | --- |
| Planner | Runs the allocation engine, produces a draft plan with reasons |
| Outbox worker | Publishes committed domain events, delivers notifications |
| Sync reconciler | Applies queued offline operations exactly once |
| Predictor | Serves service-time, lateness and demand estimates behind a port |

---

## 3. Module map

Modules are bounded contexts. Each owns its tables, exposes commands, queries and events, and imports nothing from another module except its published contract. This maps cleanly onto the team's five schemas.

```
                         ┌───────────────────────────────┐
                         │  Reference data  (ref)        │  outlets, vehicles,
                         │  read-mostly master data      │  calendar, travel, products
                         └──────────────┬────────────────┘
                                        │ read
  ┌─────────────────┐   ┌───────────────▼───────────────┐   ┌──────────────────┐
  │ Identity & (iam)│   │      Ordering       (ops)     │   │  Inventory  (ops)│
  │ access          │   │  capture, cutoff, status      │◄─►│  stock, ATP,     │
  └────────┬────────┘   └───────────────┬───────────────┘   │  reservations    │
           │                            │ confirmed demand   └──────────────────┘
           │            ┌───────────────▼───────────────┐
           │ authorizes │      Planning       (ops)     │  runs, trips, allocations,
           │            │  allocate, defer, publish     │  deferrals, route legs, fuel
           │            └───────────────┬───────────────┘
           │                            │ published plan
           │            ┌───────────────▼───────────────┐
           │            │      Loading        (ops)     │  sessions, checks,
           │            │  manifest, shortfall, release │  vehicle interchange
           │            └───────────────┬───────────────┘
           │                            │ released trip
           │            ┌───────────────▼───────────────┐
           │            │     Execution       (ops)     │  delivery records,
           │            │  stops, outcomes, POD         │  attachments, proof
           │            └───────────────┬───────────────┘
           │                            │ delivery outcome
           │            ┌───────────────▼───────────────┐
           │            │      Receipt        (ops)     │  confirmations, disputes
           │            └───────────────────────────────┘
           │
  ┌────────▼───────────────────────────────────────────────────────────────────┐
  │ Cross-cutting: Issues (ops) · Notification (ops + integration.outbox)       │
  │ Sync (integration) · Audit (integration) · Intelligence (ml)               │
  └────────────────────────────────────────────────────────────────────────────┘
```

| Module | Owns | Scope | Publishes |
| --- | --- | --- | --- |
| Reference data | brands, depots, districts, outlets, vehicles, vehicle_day_status, calendar_days, district_travel, service_allowances, products | NOW (products NEXT) | `vehicle.status_changed` |
| Identity and access | users, roles, user_roles, user_depot_access, user_outlet_access, vehicle_driver_assignments, devices, sessions | NOW (fine-grained access tables NEXT) | `access.revoked` |
| Ordering | orders, order_items, order_status_history | NOW (items NEXT) | `order.confirmed`, `order.locked`, `order.amended` |
| Inventory | stock_levels, stock_reservations, stock_adjustments | **NEXT, state NOW** | `stock.insufficient`, `stock.released` |
| Planning | planning_runs, trips, order_allocations, order_deferrals, route_legs, vehicle_trip_fuel_usage | NOW | `plan.published`, `order.deferred` |
| Loading | loading_sessions, loading_checks | NOW | `loading.shortfall`, `trip.released` |
| Execution | delivery_records, proof_of_delivery, attachments | NOW | `delivery.completed`, `delivery.failed` |
| Receipt | receipt_confirmations | NOW | `receipt.confirmed`, `receipt.disputed` |
| Issues | operational_issues | NOW | `issue.raised`, `issue.resolved` |
| Notification | notifications, outbox_events | NEXT (polling NOW) | fan-out only |
| Sync | sync_operations | NOW | none |
| Audit | audit_log | NOW (as `events`) | none |
| Intelligence | model_versions, delivery_predictions, demand_forecasts | LATER (port NOW) | none |

### Gaps found in the team's schema

Four things the current schema does not cover but the requirements document demands. Decide these before writing migrations:

1. **No inventory tables.** The docx requires that orders with insufficient stock are locked and the stock manager is notified. There is nowhere to hold stock levels, reservations or available-to-promise. Add an inventory module.
2. **No outlet coordinates.** The docx states GPS coordinates were added for every outlet. `ref.outlets` needs `latitude` and `longitude`, otherwise distance-ordered dispatch and the map view have no input.
3. **Vehicle interchange is not modelled.** `ops.trips.vehicle_id` is a single column. Swapping the truck at the dock must be an auditable event that revalidates the whole trip, not a silent `UPDATE`.
4. **Return cost has no home.** The docx requires calculating return cost for the vehicle pool. `ops.vehicle_trip_fuel_usage` covers a trip; the pool-level figure needs either a computed view or explicit columns.

---

## 4. Layers

Seven layers. A request enters at the top, business rules live in the middle, the database is at the bottom, and four concerns cut across all of them.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ 1. CLIENT            Four role apps + service worker + IndexedDB outbox      │
│    Responsibility: capture intent, show state, survive losing the network.   │
│    Never: business rules. The client proposes; the server decides.           │
├──────────────────────────────────────────────────────────────────────────────┤
│ 2. EDGE              TLS, Next.js /api proxy, cookies, rate limit, body caps │
│    Responsibility: transport safety. Rejects malformed traffic cheaply.      │
├──────────────────────────────────────────────────────────────────────────────┤
│ 3. API               Command endpoint + query endpoints, DTO validation      │
│    Responsibility: shape and identity. Who is calling, is the payload valid. │
│    Never: business decisions, SQL.                                           │
├──────────────────────────────────────────────────────────────────────────────┤
│ 4. APPLICATION       Use cases, command bus, idempotency, TRANSACTIONS       │
│    Responsibility: orchestrate one decision, atomically. The ONLY layer      │
│    that opens a transaction or enforces authorization.                       │
├──────────────────────────────────────────────────────────────────────────────┤
│ 5. DOMAIN            Aggregates, invariants, constraint registry, engines    │
│    Responsibility: the rules. Pure. No Spring, no SQL, no JSON, no clock.    │
│    Testable in milliseconds. This is where Waypoint's value lives.           │
├──────────────────────────────────────────────────────────────────────────────┤
│ 6. DATA              Repositories, projections, outbox, sync store           │
│    Responsibility: persistence and scoped reads. Scope filters run in SQL.   │
├──────────────────────────────────────────────────────────────────────────────┤
│ 7. PLATFORM          Pool, migrations, config, clock, metrics, tracing       │
└──────────────────────────────────────────────────────────────────────────────┘
   Cross-cutting, applied at a single chosen layer, never scattered:
   Authentication (3) · Authorization (4 + 6) · Audit (4) · Observability (all)
```

Three rules that make the layering real rather than decorative:

1. **Dependencies point inward.** Layer 5 knows nothing about 4, 3 or 6. Enforced by `ModuleBoundaryTest`.
2. **One transaction per command, opened in layer 4.** The domain never sees a connection.
3. **Authorization is enforced twice**: as a decision in layer 4 (may this actor do this?) and as a filter in layer 6 (which rows may this actor see?). Never by filtering in Java after loading everything, which is both a security smell and the current performance bug.

---

## 5. Authentication layer

**Question it answers: who is this?**

| Concern | Design | Scope |
| --- | --- | --- |
| Credential store | Per-user salt, PBKDF2 or Argon2 hash. Never a shared password | NOW |
| Session | Opaque server-side token in an `HttpOnly`, `SameSite=Strict`, `Secure` cookie. Server-side session table so revocation is instant | NOW |
| Brute force | Per-email attempt table with a lockout window, shared across instances | NOW |
| Device identity | `iam.devices` row, separate from user identity. A shared dock tablet is one device with many users | NEXT |
| Offline re-auth | Session expiry must never clear the local queue. Re-auth restores the same account and the queue drains | NOW |
| First login offline | Not supported, stated explicitly. A device must be online once | NOW |
| Token rotation | Rotate session on privilege change; revoke all sessions on role change or disable | NOW |

Device identity is worth the extra table: without it, an audit trail for a shared tablet says "the loader account did it" and cannot distinguish which of four tablets or which shift.

---

## 6. Authorization layer

**Question it answers: may this actor do this, to this row, right now?**

Role alone is not enough. Every role in Waypoint is scoped to something: a store manager to an outlet, a loader to a depot, a driver to a vehicle for a day. So this is role-based access control for the verb plus attribute-based scoping for the row.

```
Decision = Role grants the command?  AND  Scope covers the target row?
                    │                              │
              iam.user_roles              iam.user_depot_access
                                          iam.user_outlet_access
                                          iam.vehicle_driver_assignments (date-bounded)
```

| Role | May command | Scoped by |
| --- | --- | --- |
| store_manager | place order, amend before cutoff, confirm receipt, report issue | `user_outlet_access` |
| stock_manager | approve, adjust or reject a locked order | `user_depot_access` |
| dispatcher | close orders, generate draft, override allocation, publish, resolve exception | `user_depot_access` |
| loader | start and finish loading, record check, flag shortfall, request interchange | `user_depot_access` |
| driver | start stop, record outcome, capture proof, report fault, set vehicle status | `vehicle_driver_assignments` on the plan date |
| admin | manage accounts, roles, reference data, calendar | global, host-trusted only |

Enforcement rules, all NOW:

1. **One policy object.** `AuthorizationPolicy` in `identity/domain` answers `permits(actor, command, target)`. Nothing else makes access decisions.
2. **Deny by default.** An unlisted command or unmatched scope is a 403 with an audit entry, never a silently empty result.
3. **Reads are scoped in SQL.** The scope predicate is part of the query, not a post-filter. This is a security property and the fix for the current full-table read.
4. **Driver scope is time-bounded.** Access is to a vehicle on a date. Yesterday's driver cannot post today's delivery.
5. **Write authorization is re-checked inside the transaction.** A permission revoked a second ago must not win a race.
6. **Every denied attempt is audited** with actor, device, command and target.

---

## 7. Data layer, and how it maps to objects

### 7.1 Schema separation

Five PostgreSQL schemas, as the team designed. The separation buys real things, not tidiness:

| Schema | Why separate |
| --- | --- |
| `ref` | Read-mostly, cacheable, changes on a different cadence. Safe to replicate |
| `iam` | Security-sensitive. Different grants, different audit requirements |
| `ops` | The transactional core. Highest write rate, strictest consistency |
| `ml` | Model outputs must never be mixed into OLTP tables, so a bad model is never a data-integrity problem |
| `integration` | Sync, outbox and audit are infrastructure, not business state. Different retention |

### 7.2 Aggregates: the object-to-table mapping

An aggregate is a consistency boundary: **one aggregate, one transaction, one lock**. This is the most important design decision in the data layer, because it decides what can be changed together.

| Aggregate | Root table | Included tables | Invariant it protects |
| --- | --- | --- | --- |
| `Order` | `ops.orders` | `order_items`, `order_status_history` | Status transitions are legal; quantities never silently shrink |
| `PlanningRun` | `ops.planning_runs` | `trips`, `order_allocations`, `order_deferrals`, `route_legs`, `vehicle_trip_fuel_usage` | **Every constraint holds across the whole plan**, which is why the plan, not the trip, is the aggregate |
| `LoadingSession` | `ops.loading_sessions` | `loading_checks` | A trip is released only when every allocated order is checked |
| `DeliveryRecord` | `ops.delivery_records` | `proof_of_delivery`, `attachments` | An outcome is recorded once, with its evidence |
| `ReceiptConfirmation` | `ops.receipt_confirmations` | none | Confirmation refers to a real delivery |
| `VehicleDayStatus` | `ref.vehicle_day_status` | none | One availability state per vehicle per day |
| `StockPosition` | `ops.stock_levels` | `stock_reservations` | Reserved never exceeds on-hand |

Aggregates reference each other **by identifier only, never by object graph**. An allocation holds an `order_id`, not an `Order`. This keeps transactions small and makes later extraction possible.

### 7.3 Identity, versioning and concurrency

| Concern | Rule |
| --- | --- |
| Primary keys | UUIDs generated by the writer, so an offline device can mint an id without the server |
| Natural keys | `ref` tables keep their CSV identifiers (`OUT001`, `VEH014`) as the key; they are stable and appear in the dataset |
| Optimistic concurrency | Every mutable aggregate root has `version`. Commands carry `expected_version`; a mismatch is rejected, never merged |
| Isolation | `SERIALIZABLE` for command transactions, with bounded retry on `40001` and `40P01` |
| Idempotency | `(command_id, user_id)` receipt with a payload fingerprint. Same id and payload returns the stored response; same id and different payload is rejected |
| Time | Server clock is authoritative in `Asia/Colombo`. Client time is stored alongside, never used for decisions |

### 7.4 Reading: projections, not joins-at-request-time

Each role reads a projection shaped for its screen, built from the event log and refreshed on write:

| Projection | Consumer | Key |
| --- | --- | --- |
| `DispatcherBoard` | dispatcher | (depot, day) |
| `LoaderManifest` | loader | (depot, trip) |
| `DriverRunSheet` | driver | (vehicle, day) |
| `StoreTimeline` | store manager | (outlet, order) |

Clients poll a **cursor**: `GET /sync?since=<cursor>` returns only entitled events after that point. Server-sent events push the cursor when something changes, with polling as fallback. History is keyset-paginated. NOW.

### 7.5 Files

Proof photos and signatures are file metadata in `ops.attachments` with bytes in object storage, fetched through short-lived signed URLs. NOW ships bytes in PostgreSQL behind a `ProofStore` port, which is an accepted trade at 120 outlets and a one-adapter change later.

### 7.6 Reliable messaging

A notification is never sent inside the request transaction. The state change and an `integration.outbox_events` row commit together; a worker publishes after commit, at least once, and consumers are idempotent. This is what makes "the store was told its order was deferred" as reliable as the deferral itself.

---

## 8. Edge cases

This is the section that separates a demo from a system. Each case names the trigger, the required behaviour and where it is enforced.

### 8.1 Ordering and stock

| Case | Behaviour | Enforced in |
| --- | --- | --- |
| Order arrives after 16:00 | Accepted for the next operating run. Cutoff evaluated server-side in Asia/Colombo | Ordering domain |
| Order for a non-operating day | Rolled to the next `is_operating` date, shown to the store before confirm | Ordering + Reference |
| Chilled and ambient in one request | Split into two orders. A vehicle eligibility decision cannot straddle temperature | Ordering domain |
| Double submit or lost acknowledgment | Same `command_id` returns the original order, no duplicate | Application |
| Amend after allocation | Version conflict. Requires dispatcher revalidation, never silent | Ordering + Planning |
| **Insufficient stock** | Order state `locked`, stock manager notified, excluded from allocation. If unresolved at cutoff, auto-defer with reason `stock_unresolved` | Inventory + Ordering |
| Stock partially available | Stock manager may adjust down with a mandatory reason; the store is notified of the adjusted quantity | Inventory |
| Outlet window shorter than its service allowance | Rejected at capture with the arithmetic shown, not discovered at 04:00 | Reference + Ordering |

### 8.2 Planning and capacity

| Case | Behaviour | Enforced in |
| --- | --- | --- |
| Demand exceeds capacity | Defer by policy: prior skips, then Fresh, then chilled, then earliest closing window. Every deferral records the **binding constraint** | Planning domain |
| Order exceeds every vehicle's capacity | Marked `unservable`, surfaced for a split decision. Never silently deferred forever | Constraint registry |
| Outlet skipped on consecutive runs | Skip count escalates priority; publishing requires an explanation | Planning |
| Vehicle enters workshop after publication | Only affected trips are replanned. Untouched trips keep identity and loading state | Planning |
| Weekly fuel quota exhausted | Allocation blocked with remaining litres shown. Reservations include other published plans in the same Monday-to-Sunday week | Constraint registry |
| Two dispatchers edit one draft | Draft revision check rejects the stale edit with a diff | Application |
| Order set changed since draft | Publication blocked, regeneration required. Coverage must match the queue | Planning |
| Mall window conflicts with the Fresh window | Infeasible, named explicitly. Not silently dropped | Constraint registry |
| Chilled order to a van-only outlet above reefer-van capacity | Unservable, named. This is the documented `OVERLOAD-001` fixture | Constraint registry |
| Longest-distance-first conflicts with a delivery window | **Windows win.** Distance ordering is a tie-break, never a constraint override | Planning domain |
| Plan generated for a non-operating day | Refused | Reference |

### 8.3 Loading

| Case | Behaviour | Enforced in |
| --- | --- | --- |
| Missing or damaged goods at the dock | Departure blocked. Dispatcher records a replacement, loader rechecks every order in the trip | Loading |
| **Assigned truck unavailable at the dock** | Interchange request. The substitute is revalidated for capacity, temperature, access, depot and fuel **across the whole trip**. Recorded as an event, never an `UPDATE` | Loading + Planning |
| No compatible substitute vehicle | Trip deferred as a unit with reason, orders carried forward with identity | Planning |
| Loader shift ends mid-session | Session persists with partial checks. Another loader resumes; both are recorded | Loading |
| Loading complete, no driver assigned | Trip waits in `ready_for_departure`. Dispatcher is notified rather than the trip silently stalling | Loading + Notification |
| Loader marks loaded twice | Idempotent, no duplicate check rows | Application |

### 8.4 Execution and offline

| Case | Behaviour | Enforced in |
| --- | --- | --- |
| Offline for an entire run | All outcomes queue locally. UI acknowledges only after the **local** write is durable | Client + Sync |
| Reconnect with a queue | Operations replay in order, each idempotent. Accepted items leave the queue only on server confirmation | Sync |
| Server state changed while offline | Conflict held in "Needs review". The user inspects the current record and explicitly discards or re-enters. **Never auto-overwrite** | Sync |
| Arrive before the window opens | Vehicle waits. Service time starts at window open, not at arrival | Execution domain |
| Arrive after the window closes | Delivery still recorded, lateness flagged with a reason | Execution domain |
| Outlet closed or refuses goods | `failed` outcome plus an issue. Dispatcher chooses redelivery, return or closure. Redelivery links a new order and preserves the original evidence | Execution + Issues |
| Vehicle breaks down mid-route | Vehicle set `fault`, issue raised, remaining stops released for replanning, goods disposition recorded | Execution + Planning |
| Camera denied or photo too large | Delivery may complete with a recorded reason, flagged as lower-evidence. Work is never blocked by a device limitation | Client + Execution |
| Device lost or data cleared before sync | Unsynced work is lost. Stated plainly in the UI; the durable-save acknowledgment is the contract | Client |
| Device clock wrong | Server timestamps decide. Client time is stored for forensics only | Application |
| Session expires with a pending queue | Re-authenticate without clearing the queue. Sign-out is blocked while work is pending | Identity + Client |

### 8.5 Receipt, platform and data

| Case | Behaviour |
| --- | --- |
| Store confirms partial receipt | Recorded as `partial` with quantities, raises a dispute issue |
| Store never confirms | Auto-closes after a configured window with status `unconfirmed`, never silently "delivered" |
| Store disputes after proof exists | Dispute recorded alongside the proof. Evidence is never deleted or overwritten |
| Role changed or account disabled mid-session | All sessions revoked, account audit written, pending queue preserved for review |
| Scope violation attempt | 403 plus audit entry. Never an empty list that looks like "no data" |
| Serialization failure or deadlock | Bounded retry that re-runs validation, never a blind replay |
| Outbox worker crashes after commit | Events redelivered at least once. Consumers are idempotent |
| Supplied calendar runs out (ends June 2026) | Extension policy at startup, Monday to Saturday, with supplied dates and `CALENDAR_FILE` overrides taking precedence |
| Reference CSV changes between releases | Versioned reference load; published plans keep the snapshot they were built against |

---

## 9. Notification matrix

From the team's document, formalized. Every row is an outbox event with a durable delivery record.

| Event | From | To | Trigger |
| --- | --- | --- | --- |
| `stock.insufficient` | Inventory | Stock manager | Order exceeds available-to-promise |
| `stock.released` | Inventory | Store manager | Locked order approved or adjusted |
| `order.deferred` | Planning | Store manager | Plan published without this order, with reason |
| `plan.published` | Planning | Loader, driver | Trips available for loading |
| `loading.shortfall` | Loading | Dispatcher | Missing or damaged goods |
| `trip.released` | Loading | Driver | Vehicle ready, dock assigned |
| `delivery.completed` | Execution | Store manager | Proof captured |
| `delivery.failed` | Execution | Dispatcher, store manager | Refused, closed or undeliverable |
| `issue.raised` | Any | Dispatcher | Fault, delay, damage, access problem |
| `vehicle.status_changed` | Execution | Dispatcher | Available, on trip, workshop, fault |
| `eta.changed` | Planning | Store manager | Material change to expected arrival |

---

## 10. Work breakdown and developer assignment

### 10.1 Workstreams and dependency order

```
WS0 Platform foundation ───────────────────────────────┐  blocks everything
    schemas, migrations, Database seam, config, clock  │
                                                        │
WS1 Identity & access ◄─────────────────────────────────┤  blocks every write
WS2 Reference data    ◄─────────────────────────────────┘  blocks planning
        │                    │
        ▼                    ▼
WS3 Ordering + Inventory ──► WS4 Planning engine ──► WS5 Loading ──► WS6 Execution ──► WS7 Receipt
        │                    │                       │              │
        └────────────────────┴───────────────────────┴──────────────┘
                                     │
                     WS8 Notification + outbox  (consumes all events)
                     WS9 Read models + role UIs (consumes all projections)
                     WS10 Intelligence ports    (independent, port first)
```

### 10.2 Assignment

Five developers is the natural split. With fewer, merge adjacent rows and keep the order.

| Stream | Owner | Deliverable | Depends on | Parallel from |
| --- | --- | --- | --- | --- |
| WS0 Platform | Dev A | Schemas, migrations, `platform/db`, config, clock, boundary tests | none | day 0 |
| WS1 Identity | Dev A | Authn, `AuthorizationPolicy`, scope tables, device registry | WS0 | day 1 |
| WS2 Reference | Dev B | Loaders, caches, calendar policy, vehicle day status | WS0 | day 1 |
| WS3 Ordering + Inventory | Dev B | Capture, cutoff, status machine, stock lock and release | WS0, WS2 | day 2 |
| WS4 Planning | Dev C | Constraint registry, allocation engine, deferral, publication | WS2, WS3 contract | day 2 |
| WS5 Loading | Dev D | Sessions, checks, shortfall, vehicle interchange | WS4 contract | day 3 |
| WS6 Execution + Offline | Dev D | Stops, outcomes, proof, sync reconciliation | WS5 contract | day 3 |
| WS7 Receipt + Issues | Dev E | Confirmation, dispute, issue lifecycle | WS6 contract | day 4 |
| WS8 Notification | Dev E | Outbox, worker, notification matrix | WS0, events | day 4 |
| WS9 Read models + UI | Dev C, E | Projections, cursor sync, four role shells | WS1, projections | day 2 |
| WS10 Intelligence | Dev B | `TravelAndServiceEstimator` port + deterministic implementation | WS4 contract | day 5 |

### 10.3 How parallel work stays unblocked

The dependency arrows above are **contract dependencies, not code dependencies**. The unlock is to land contracts first:

1. **Day 0 and 1, contracts only.** Every module's `contract` package (DTOs, event payloads, query interfaces) is written and merged before implementations start. A downstream stream codes against the interface and a stub.
2. **Migrations are additive and owned by one stream.** Only WS0 writes migration files, on request, so two developers never author conflicting schema versions.
3. **Events are the integration point.** If WS5 needs something from WS4, it consumes an event or calls a contract query. It never reads `ops.trips` directly. `ModuleBoundaryTest` fails the build if it tries.
4. **One branch per workstream**, rebased daily onto `main`. Long-lived branches across a 40-table change are how teams lose a weekend.
5. **Definition of done per stream:** domain unit tests without a database, one integration test through the command bus, authorization test for a denied scope, boundary test passing, an entry in `docs/development-docs/development-log.md`.

### 10.4 Repository maintenance

| Practice | Rule |
| --- | --- |
| Branching | `main` is integration. One branch per workstream, PR review, no direct pushes |
| Ownership | One owner per module folder. Cross-module changes need both owners |
| Boundaries | `ModuleBoundaryTest` (backend) and `tests/boundaries.test.ts` (frontend) run in CI. A violation fails the build |
| Migrations | Forward-only, checksummed, applied by an explicit command. Never on build or request |
| Contracts | A change to a `contract` package is a breaking change and is announced in the log |
| Log | Append to the development log when a unit of work lands. It is how parallel sessions stay informed |
| Documentation | Architecture diagram and data model regenerated when the schema changes, since the Hackathon requires both in `docs/` |

---

## 11. What to build for October 4

Against the target above, the competition subset is:

**Build now:** WS0, WS1 (roles and scoping, simplified access tables), WS2, WS3 ordering with the `locked` state present even if the stock screen is stubbed, WS4 complete, WS5 including interchange, WS6 complete with offline, WS7 complete, WS9 with cursor sync, WS10 port only.

**Design now, build later:** full inventory module and stock manager screen, outbox-driven notifications (poll instead), device registry, object storage for proof, ML serving, products and order items.

**Deliberately out:** live GPS tracking, in-app turn-by-turn navigation, SMS or email gateways, automatic order splitting, multi-tenancy.

The single most valuable thing the target design buys the competition build is that **the `locked` order state, the deferral record, the allocation-as-a-decision separation and the event log all exist from day one**. Each is cheap now and expensive to retrofit.

---

## 12. Open decisions

These need a team answer, not an architect's guess:

1. **Supabase Auth or self-hosted identity?** The schema guide raises it. Supabase removes password handling but adds a dependency and complicates the offline story. Recommendation: self-hosted for the competition, since it already works.
2. **Inventory depth.** Full stock ledger, or a single available-to-promise number per outlet and product? Recommendation: the simple version now, with the `locked` state reserved.
3. **Duplicate implementation.** `frontend/lib/` still holds a second copy of the rules in Node. Delete it and port its tests to the domain layer, or isolate and document it.
4. **Products.** The docx requires item type, volume, minimum order count and weight. Order-level totals work today. Introducing line items changes every capacity calculation, so it is a NEXT decision that must not be half-done.
5. **Migration path.** The current nine-table JSONB schema and the target 40-table schema are not compatible. Either migrate before October 4 (high risk, high reward for the architecture mark) or ship the current schema and present this document as the design. Recommendation: ship the current schema, refactor the module boundaries as already started, and present this as the target with the gap stated honestly. Judges reward a defensible plan over a half-finished rewrite.
