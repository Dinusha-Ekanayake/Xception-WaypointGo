# Waypoint Dispatch: system architecture

Audience: the Xception build team. This is the target architecture for Waypoint Dispatch as an enterprise system: the principles it obeys, the actors it serves, the modules and services it is built from, the layers inside each module, how those modules connect, and how it is operated.

It consolidates the Tech-Triathlon challenge booklet, the team's requirements draft, and the team's 40-table schema guide, then applies current enterprise practice on top. Where the inputs disagree with each other or with what is built today, this document states the gap rather than hiding it.

## Document map

| Document | Contains |
| --- | --- |
| **This document** | Principles, context, style decision, layers, module map, cross-cutting concerns, API standards, runtime, delivery plan |
| [docs/architecture/MODULES.md](docs/architecture/MODULES.md) | Every module in detail: its layers, owned data, inbound contract, outbound dependencies, events, invariants and failure modes |
| [docs/architecture/DATA-MODEL-REVIEW.md](docs/architecture/DATA-MODEL-REVIEW.md) | Validation of the team's schema, 20 findings, and the corrected target schema |
| [docs/architecture/EDGE-CASES.md](docs/architecture/EDGE-CASES.md) | The edge case register: trigger, required behaviour, enforcement point, detection and test for each |
| [docs/code-structure.md](docs/code-structure.md) | The folder layout that implements this, and its enforced boundary rules |

Scope note: the warehouse and stock system is built separately by the team. This architecture treats it as an **external system behind an anti-corruption layer**, not as a module to build here.

---

## 1. Principles

Ten rules. Every decision later in this document traces back to one of them, and a design that violates one needs an explicit ADR.

1. **The domain is the asset.** Business rules live in pure code with no framework, no SQL and no clock. They are the cheapest thing in the build to test and the most expensive thing to get wrong.
2. **One definition of every rule.** A constraint expressed twice will diverge. The allocation engine, the manual override path, the publication gate and the UI all read the same constraint registry.
3. **Decisions are recorded, not implied.** A deferral, an override, a stock rejection and a failed delivery are all first-class records with an actor, a reason and a timestamp. If a human made a choice under pressure, the system can reconstruct why.
4. **The server is authoritative; the client is a buffer.** Clients propose. The server decides, validates and versions. This is what makes offline safe.
5. **Every write is idempotent and versioned.** Networks retry. Devices replay. A command applied twice must be indistinguishable from a command applied once.
6. **Consistency inside an aggregate, eventual consistency between them.** One transaction never spans two aggregates. Cross-aggregate work happens through events.
7. **Authorization is a data-layer contract, not an application afterthought.** Scope is enforced in SQL predicates and database policies, not by filtering in application code after loading everything.
8. **Boundaries are enforced by the build, not by review.** A module boundary that only exists in a document is already broken.
9. **Operability is a feature.** Traces, metrics, health, audit and replay are designed in, not added after the first incident.
10. **Build the monolith well so the services are optional.** Extraction is earned with evidence, never assumed.

---

## 2. Context

### 2.1 Actors

| Actor | Scope | Device and conditions | Responsibility |
| --- | --- | --- | --- |
| Store manager | One outlet | Desktop or phone at the counter | Place orders, confirm receipt, report issues, see expected arrival |
| Dispatcher | One or more depots | Large screen, stable network | Close orders, allocate, defer, override, publish, resolve exceptions |
| Loader | One depot | Shared dock tablet, plans change under them | Load to stop sequence, flag shortfalls, request vehicle interchange, release for departure |
| Driver | One vehicle, one day | Personal phone, intermittent coverage, operated while stopped | Execute stops, capture proof, report vehicle and road faults |
| Stock controller | One depot | Warehouse terminal | Approve, adjust or reject orders held for stock. **Served by the external warehouse system** |
| Administrator | Global | Admin console or trusted host CLI | Accounts, roles, scopes, reference data, calendar |
| Auditor | Global, read-only | Desktop | Reconstruct any decision after the fact |

### 2.2 System actors

Non-human actors need identity too, because audit entries that say "the system did it" are worthless.

| Actor | Responsibility |
| --- | --- |
| Planner | Runs the allocation engine, produces a draft plan with per-order reasons |
| Outbox relay | Publishes committed domain events, at least once |
| Notifier | Turns events into notifications with per-channel delivery tracking |
| Sync reconciler | Applies queued offline operations exactly once |
| Predictor | Serves service-time, lateness and demand estimates behind a port |
| Scheduler | Cutoff enforcement, calendar rollover, escalation timers, retention jobs |

### 2.3 External systems

| System | Direction | Contract | Failure policy |
| --- | --- | --- | --- |
| **Warehouse and stock** | Outbound query, inbound event | `StockPort`: check availability, reserve, release. Consumes `order.confirmed`, emits `stock.reserved`, `stock.insufficient`, `stock.adjusted` | Circuit breaker. On open, orders enter `stock_unknown` and the dispatcher sees the degraded state explicitly. Never silently assume stock exists |
| **Product catalogue** (same warehouse API) | Outbound bulk sync | `CataloguePort`: versioned catalogue and order-to-product lines, synced on a schedule into a local projection | Fall back to order-level weight and volume, which are authoritative for capacity regardless. Stale catalogue is shown as stale, never as current |
| Identity provider (optional) | Outbound | OIDC. `iam.users.user_id` maps to the external subject | Fall back to local sessions already issued; no new logins |
| Object storage | Outbound | `ProofStore`: put, signed get | Retry with backoff; capture blocks only if durable write fails, and the driver is told |
| Notification channels | Outbound | Per-channel adapter | At-least-once with delivery records and dead-letter |

The warehouse boundary is an **anti-corruption layer**: their model never leaks into this one. Waypoint holds a local `stock_status` on the order, a reservation reference, and a cached copy of the catalogue. Nothing else of theirs.

One rule governs that catalogue and it outranks convenience. The catalogue is a reconstruction from order totals, accurate to 1% on weight and volume, so **capacity constraints read order-level `order_weight_kg` and `order_volume_m3`, never a sum of product lines**. On a 5,510 kg truck, 1% is 55 kg of invisible overload. Temperature likewise comes from the order, never inferred from products. Product lines are descriptive. The full rule set is in [DATA-MODEL-REVIEW.md](docs/architecture/DATA-MODEL-REVIEW.md#external-product-catalogue).

---

## 3. Architecture style

### ADR-001: Modular monolith with earned extraction

**Decision.** One deployable Spring Boot application composed of strictly bounded modules, one PostgreSQL cluster with a schema per context, an event backbone via transactional outbox. Services are extracted only when a written trigger fires.

**Why not microservices now.** The modules in this domain share one transactional core: an allocation references an order, a loading check references an allocation, a delivery record references the same allocation. Splitting those across services replaces a serializable transaction with a distributed saga, and buys nothing at 120 outlets, 60 vehicles and roughly 200 orders a day. The failure modes of a distributed system are worse than the problem being solved.

**Why not a plain monolith.** Without enforced boundaries a service layer absorbs everything. The repository has already seen exactly that happen once.

**Extraction triggers.** A module becomes a service when one of these is true and is measured, not asserted:

| Module | Trigger |
| --- | --- |
| Planning | Allocation for a depot-day exceeds 30 seconds at p95, or the optimizer needs a different runtime or a GPU |
| Notification | Sustained outbound volume makes channel latency affect request latency, or a channel needs independent scaling |
| Intelligence | Model serving needs a Python runtime, independent release cadence, or GPU scheduling |
| Sync ingest | Reconnect storms after a regional outage saturate the request tier |

Each of those modules is already designed as if remote: it is reached through a port, it owns its data, and it communicates by events. Extraction is a deployment change plus an adapter, not a rewrite.

### ADR-002: Schema per bounded context in one database

Five PostgreSQL schemas (`ref`, `iam`, `ops`, `ml`, `integration`), one **database** in one cluster. These are namespaces, not separate databases: one connection reaches all five, foreign keys are enforced across them, and one transaction can span them, which is what lets state, event and outbox row commit together.

Cross-schema foreign keys are permitted only into `ref` and `iam`, the shared kernel. Each module has its own database role with grants limited to its own schema plus read access to the kernel, so a boundary violation fails at the database even if it passes review.

The deliberate trade: keeping those kernel foreign keys and the single transaction is a departure from strict database-per-service isolation, and it buys real integrity. The price is paid at extraction, when those keys become soft references. The triggers in ADR-001 are when that price is worth paying.

### ADR-003: CQRS-lite, not event sourcing

Writes go through aggregates with full validation. An append-only event log records what happened. Reads are served by projections shaped per screen and rebuilt from that log. The aggregate state table remains the source of truth, not a fold over events.

Full event sourcing is rejected: it would give replay and audit that the event log already provides, at the cost of schema evolution pain and a much steeper on-ramp for the team.

---

## 4. Layers

Seven layers. A request enters at the top; rules live in the middle; four concerns cut across.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ 1  CLIENT        role apps · service worker · IndexedDB outbox               │
│    does: capture intent, render projections, survive losing the network      │
│    never: decide anything. Proposes commands, shows what the server returned │
├──────────────────────────────────────────────────────────────────────────────┤
│ 2  EDGE          TLS · reverse proxy · rate limit · body caps · CORS/CSRF    │
│    does: cheap rejection of malformed and abusive traffic before it costs    │
├──────────────────────────────────────────────────────────────────────────────┤
│ 3  API           command + query endpoints · DTO validation · problem+json   │
│    does: authenticate, validate shape, map errors to RFC 9457                │
│    never: business decisions, SQL, transactions                              │
├──────────────────────────────────────────────────────────────────────────────┤
│ 4  APPLICATION   use cases · command bus · idempotency · AUTHZ · TRANSACTION │
│    does: orchestrate exactly one decision atomically; emit events + outbox   │
│    the only layer that opens a transaction or makes an access decision       │
├──────────────────────────────────────────────────────────────────────────────┤
│ 5  DOMAIN        aggregates · invariants · constraint registry · engines     │
│    does: the rules. Pure functions and value objects.                        │
│    never: Spring, SQL, Jackson, System.currentTimeMillis                     │
├──────────────────────────────────────────────────────────────────────────────┤
│ 6  DATA          repositories · projections · outbox · RLS policies          │
│    does: persistence and scoped reads. Scope predicates run IN SQL.          │
├──────────────────────────────────────────────────────────────────────────────┤
│ 7  PLATFORM      pool · migrations · config · clock · telemetry · resilience │
└──────────────────────────────────────────────────────────────────────────────┘
  cross-cutting, each applied at exactly one layer, never scattered:
  authentication (3) · authorization (4 decide, 6 filter) · audit (4) · telemetry (7)
```

**The rules that make this real**

1. Dependencies point inward. Layer 5 knows nothing of 4, 3 or 6. Enforced by `ModuleBoundaryTest`.
2. One command, one transaction, opened in layer 4 only.
3. The domain receives time as a parameter. A rule that reads the system clock cannot be tested.
4. Authorization is decided in 4 and filtered in 6. Never filtered in application code after a broad read.
5. Errors cross layer 3 as RFC 9457 `application/problem+json`, never as stack traces or bare strings.

---

## 5. Module map

Twelve modules. Full specifications, including the layers inside each and its service connections, are in [MODULES.md](docs/architecture/MODULES.md).

```
                  ┌──────────────────────────────────────────┐
                  │  ref · Reference data                     │
                  │  outlets vehicles calendar travel products│
                  └───────────────┬──────────────────────────┘
                                  │ read-only, cached
   ┌───────────────┐   ┌──────────▼───────────┐   ╔═══════════════════════╗
   │ iam · Identity│   │ ops · Ordering        │──►║ EXTERNAL              ║
   │ and access    │   │ capture cutoff status │◄──║ Warehouse and stock   ║
   └───────┬───────┘   └──────────┬───────────┘   ╚═══════════════════════╝
           │                      │ order.confirmed         via StockPort +
           │ authorizes           ▼                         anti-corruption layer
           │           ┌──────────────────────┐
           │           │ ops · Planning        │  runs trips allocations
           │           │ allocate defer publish│  deferrals legs fuel
           │           └──────────┬───────────┘
           │                      │ plan.published
           │           ┌──────────▼───────────┐
           │           │ ops · Loading         │  sessions checks interchange
           │           └──────────┬───────────┘
           │                      │ trip.released
           │           ┌──────────▼───────────┐
           │           │ ops · Execution       │  stops outcomes proof
           │           └──────────┬───────────┘
           │                      │ delivery.completed / failed
           │           ┌──────────▼───────────┐
           │           │ ops · Receipt         │  confirmation dispute
           │           └──────────────────────┘
           │
  ┌────────▼────────────────────────────────────────────────────────────────┐
  │ Issues · Notification · Sync · Audit · Intelligence · Query (projections)│
  └─────────────────────────────────────────────────────────────────────────┘
```

| Module | Purpose | Owns | Extractable |
| --- | --- | --- | --- |
| Reference data | Master data and calendar policy | `ref.*` | no, shared by all |
| Identity and access | Who you are, what you may do, where | `iam.*` | yes, standard boundary |
| Ordering | Demand capture, cutoff, order lifecycle | `ops.orders`, `order_items`, `order_status_history` | no |
| Planning | Allocation, deferral, publication | `ops.planning_runs`, `trips`, `order_allocations`, `order_deferrals`, `route_legs`, `vehicle_trip_fuel_usage` | yes, on CPU trigger |
| Loading | Dock workflow, shortfalls, interchange | `ops.loading_sessions`, `loading_checks`, `trip_vehicle_assignments` | no |
| Execution | Stop outcomes and proof | `ops.delivery_records`, `proof_of_delivery`, `attachments` | no |
| Receipt | Store confirmation and dispute | `ops.receipt_confirmations` | no |
| Issues | Operational problem lifecycle | `ops.operational_issues` | no |
| Notification | Event to message fan-out and delivery tracking | `ops.notifications`, `notification_deliveries` | yes, on volume trigger |
| Sync | Offline operation reconciliation | `integration.sync_operations` | yes, on reconnect-storm trigger |
| Audit | Immutable record of who did what from where | `integration.audit_log` | no |
| Intelligence | Predictions and forecasts | `ml.*` | yes, on runtime trigger |

**How modules connect.** Three mechanisms only, in this order of preference:

1. **Domain event** through the outbox. Default for anything that is a consequence rather than a precondition. Asynchronous, at least once, consumer idempotent.
2. **Contract query** into another module's published read interface. Synchronous, read-only, no transaction sharing.
3. **Port** for anything outside the process: warehouse, object storage, predictor, channels. Always behind a circuit breaker with a defined fallback.

Never: reading another module's tables, sharing an entity class, or joining across context schemas.

---

## 6. Cross-cutting design

### 6.1 Authentication

| Concern | Design |
| --- | --- |
| Credentials | Argon2id, per-user salt, never a shared password. OIDC optional, mapped to `iam.users` |
| Session | Opaque server-side token, `HttpOnly` `Secure` `SameSite=Strict` cookie, server-side table so revocation is immediate |
| Brute force | Per-identity attempt ledger with lockout, shared across instances |
| Device identity | `iam.devices`, separate from user identity. A shared dock tablet is one device used by many people, and audit must say which |
| Offline | Session expiry never clears the local queue. Re-authentication restores the account and drains it. First login requires connectivity, stated in the UI |
| Rotation | Session rotates on privilege change; all sessions revoke on role change or disable |

### 6.2 Authorization

Every role here is scoped to something, so role alone is insufficient. The model is **RBAC for the verb, ABAC for the row**, structured as a policy decision point (PDP) separate from the enforcement points (PEP).

```
PEP (application layer)  ──ask──►  PDP  ──uses──►  role grants        (iam.user_roles)
       │                            │              depot scope        (iam.user_depot_access)
       │ permits?                   │              outlet scope       (iam.user_outlet_access)
       │                            │              vehicle assignment (temporal, date-bounded)
       ▼                            ▼              resource state     (is the plan published?)
   command executes            allow / deny + reason
PEP (data layer) ──► RLS policy on every ops table, using the transaction-scoped actor
```

Rules:

1. **Deny by default.** An unlisted command or unmatched scope is `403` plus an audit entry, never an empty list that looks like no data.
2. **Decide once, in the PDP.** `AuthorizationPolicy.permits(actor, command, target)` is the only place an access decision is made.
3. **Defence in depth with RLS.** PostgreSQL row-level security policies enforce scope at the database, using `SET LOCAL app.actor_id` inside the transaction. Set it with `SET LOCAL`, never `SET`, or a pooled connection leaks one user's identity into another's request. The application role must not hold `BYPASSRLS`.
4. **Driver scope is temporal.** Access is to a vehicle on a date, not to a vehicle forever. Yesterday's driver cannot post today's delivery.
5. **Re-check inside the transaction.** A permission revoked a second ago must not lose a race.
6. **A database role per module.** Schema separation only bites if something enforces it. Code review and the boundary tests catch a cross-module import; neither catches a cross-module SQL query written inside the right module. Grants do. One process and one pool, so module identity is set per transaction alongside the actor:

```sql
BEGIN;
SET LOCAL ROLE waypoint_ops;              -- which tables
SET LOCAL app.actor_id = '<user uuid>';   -- which rows
COMMIT;
```

`waypoint_app` is `NOINHERIT` and a member of each module role, so it holds no privilege until it assumes one. Forgetting the `SET LOCAL ROLE` is a permission error, not a silent full-access query. Roles are cluster-wide, so migrations must assert role attributes with `ALTER ROLE`, not assume that `CREATE ROLE` ran.

7. **Externalizable.** The PDP is an interface. If policy complexity grows, it can move to OPA, Cedar or a Zanzibar-style store behind the AuthZEN interface without touching call sites. It is not worth a separate deployment today.

### 6.3 Events and the outbox

```
command ──► aggregate ──► [ state change + event + outbox row ] one transaction, committed
                                           │
                            relay polls ───┴──► broker / in-process bus ──► consumers
                                                     at least once, ordered per aggregate
```

- Business state and the intent to publish commit **together**. No event is lost because the process died after commit.
- Consumers are idempotent and keyed by `event_id`. At-least-once plus idempotency beats exactly-once, which does not exist.
- The relay carries `attempts`, `next_attempt_at` and a **dead-letter** state. A poison event never blocks the queue and never disappears quietly.
- Ordering is guaranteed per aggregate, not globally. Any consumer that needs global order is mis-designed.

### 6.4 Offline and sync

The pattern is **local store as buffer, server authoritative**. Not CRDTs: proof of delivery is not collaborative text, and last-writer-wins would silently destroy evidence.

1. The device writes intent to IndexedDB inside a local transaction and only then acknowledges. "Saved" means durable on this device.
2. A background worker drains the queue with exponential backoff **and jitter**. Without jitter, a regional outage ends with every device retrying in lockstep; measured results put p99 at roughly 2600 ms and ~17% errors without jitter versus 1400 ms and ~6% with it.
3. Every operation carries a client-generated id and an expected version.
4. A conflict is never auto-merged. It is held for human review against the current server record.
5. Operations are applied in submission order per aggregate.

### 6.5 Audit

Two separate concerns, often wrongly merged:

| Layer | What it records | Retention |
| --- | --- | --- |
| Business audit (`integration.audit_log`) | Actor, device, command, target, before and after, reason | Years. Partitioned monthly |
| Database audit (pgaudit) | Statement-level activity for privileged roles | Weeks. Security forensics |

Every state-changing command writes a business audit row in the same transaction. Denied attempts are audited too, because failed access attempts are the interesting ones.

### 6.6 Resilience

| Pattern | Applied to | Setting |
| --- | --- | --- |
| Timeout | Every outbound call and every query | Budgeted per request; no unbounded waits |
| Retry with jitter | Transient database and port failures | Exponential, plus or minus 50% jitter, capped attempts, only for idempotent operations |
| Circuit breaker | Warehouse, object storage, channels, predictor | Open after a failure-rate threshold, half-open probe, and a **defined degraded behaviour per port** |
| Bulkhead | Connection pools and worker pools | Planning, sync ingest and request serving get separate pools, so a reconnect storm cannot starve the dispatcher |
| Backpressure | Sync ingest | Queue depth limits with explicit client signalling |
| Graceful degradation | Predictor, notification | The system plans without predictions; it simply says so |

Degradation must be **visible**. A dispatcher who cannot tell that stock checking is down will trust a plan they should not.

### 6.7 Observability

- **Traces:** OpenTelemetry, propagated from the client through the command to the database and outbox consumers. A driver's failed sync is one trace.
- **Metrics:** RED for the API, plus domain metrics that matter to the business: orders deferred per run, constraint violations by type, sync queue age p95, time from delivery to receipt confirmation.
- **Logs:** structured, correlation id on every line, no payload bodies containing personal data.
- **SLOs:** defined per user-visible flow with error budgets, not one global uptime number.

| Flow | SLI | Target |
| --- | --- | --- |
| Place order | p95 latency | < 500 ms |
| Generate draft plan | p95 duration | < 10 s per depot-day |
| Record delivery, online | p95 latency | < 800 ms |
| Sync queued operation after reconnect | p95 time to confirm | < 30 s |
| Command correctness | duplicate side effects | zero, alerted |

### 6.8 Policy and rule change

Rules change. Time budgets get renegotiated, deferral priority gets argued about, a new access rule appears. The architecture has to absorb that without a code change at every enforcement point and without rewriting history.

**One definition, many enforcement points.** Enforcement is everywhere; the decision is in one place. Authorization goes through the PDP, constraints through the registry, deferral order through the priority policy. A rule change edits the decision point, and every enforcement point inherits it.

**Where a rule lives** depends on how often it changes and who changes it:

| Tier | Holds | Change cost | Waypoint examples |
| --- | --- | --- | --- |
| Domain code | Invariants that never vary. Physics | Deploy | A reefer is required for chilled goods. Whole orders only |
| Effective-dated parameters | Numbers and thresholds | A config row, no deploy | Fresh 270 min, Style and Tech 480 min, 16:00 cutoff, turnaround minutes |
| Versioned decision table | Structured, authorable, arguable rules | New version, no deploy | Deferral priority order, notification routing |
| External policy engine | Fine-grained authorization shared across services | Bundle push | Not today. The PDP interface keeps it available later |

**Nothing is mutated in place.** A rule change creates a new version with an effective date and supersedes the previous one. Effective time is stored separately from modification time and never inferred from it.

**Every decision records the versions that produced it.** A plan already stamps the reference-data version and predictions already stamp the model version; it stamps the **rule set version** for the same reason. Without it, a deferral recorded in March under a 270-minute budget cannot be reproduced in June once the budget is 300, and principle 3 quietly stops being true: the reason string survives but the reasoning does not.

**Rollout is staged.** Shadow (evaluate the new version, log what it would have decided, change nothing), then canary on one depot, then enforce. The switch is a flag, not a deploy. A rule change that silently alters tomorrow's allocation for 120 outlets is an outage with a different name.

**Constraints stay in code deliberately.** They run thousands of times inside an allocation loop, so a rules engine would cost real latency and replace a stack trace with a rule trace. Their *parameters* are config; their *predicates* are code.

---

## 7. API contract standards

These are the conventions every endpoint follows. They are cheap to adopt early and expensive to retrofit.

| Concern | Standard |
| --- | --- |
| Mutations | `POST /api/commands`, one envelope, `command_id` + `expected_version` + typed payload |
| Idempotency | `Idempotency-Key` header, receipt stored with a payload fingerprint. Same key and payload returns the original response; same key and different payload is `409`. A convention popularised by Stripe, not an RFC |
| Errors | RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, `instance`, plus a `violations` extension carrying the failed constraints. **The error body is part of the contract**, because clients branch on it |
| Optimistic concurrency | `ETag` on reads, `If-Match` on writes, mapped to the aggregate version |
| Pagination | Cursor based on a keyset, never `OFFSET`. Offset pagination degrades exactly when the table grows |
| Delta sync | `GET /api/sync?since=<cursor>` returning only entitled events since the cursor |
| Push | Server-sent events for cursor advancement, with polling fallback for hostile networks |
| Versioning | Media type versioning, `Accept: application/vnd.waypoint.v1+json`, with `/v1` URI fallback. Additive changes never break; removals require a new version and a deprecation window |
| Contract testing | Consumer-driven contract tests between the client and API, and between modules across their published contracts. Breaking a contract fails CI, not production |
| Health | `/health/live` and `/health/ready` separated, so a slow dependency does not get the process killed |

---

## 8. Data architecture

Summary only. The validation of the team's schema, the twenty findings and the corrected target model are in [DATA-MODEL-REVIEW.md](docs/architecture/DATA-MODEL-REVIEW.md).

**Aggregates**, the consistency boundaries that decide what may change together:

| Aggregate | Root | Why this boundary |
| --- | --- | --- |
| `Order` | `ops.orders` | Status transitions and quantities must not change under a plan without a version bump |
| `PlanningRun` | `ops.planning_runs` | **Constraints hold across the whole plan**, so the plan is the aggregate, not the trip |
| `LoadingSession` | `ops.loading_sessions` | A trip releases only when every allocated order is checked |
| `DeliveryRecord` | `ops.delivery_records` | One outcome, recorded once, with its evidence attached |
| `ReceiptConfirmation` | `ops.receipt_confirmations` | Store acceptance is a separate event from driver proof |
| `VehicleDay` | `ref.vehicle_day_status` | One availability state per vehicle per day |

Aggregates reference each other **by identifier only**. An allocation holds an `order_id`, never an `Order` object. This keeps transactions small and extraction possible.

**Key conventions**: UUIDv7 surrogate primary keys for time-ordered index locality, natural dataset keys (`OUT001`, `VEH014`) kept as unique constraints, `timestamptz` everywhere, `numeric` with explicit precision for weight, volume and fuel, and expand-contract migrations so a deploy never requires downtime.

---

## 9. Runtime and delivery

### 9.1 Topology

```
          ┌──────────┐
 clients ─┤   CDN    ├─► Next.js (stateless, N replicas) ─┐
          └──────────┘                                     │ same-origin /api proxy
                                                           ▼
                                        ┌─────────────────────────────────┐
                                        │ Spring Boot app tier, N replicas│
                                        │ request pool | worker pool      │ bulkheads
                                        └───────┬─────────────────┬───────┘
                                                │                 │
                              ┌─────────────────▼───┐   ┌─────────▼────────┐
                              │ PostgreSQL primary  │   │ Object storage   │
                              │  + read replicas    │   │  proof artifacts │
                              └─────────────────────┘   └──────────────────┘
                                                │
                                   ┌────────────▼─────────────┐
                                   │ Outbox relay + scheduler │ single-writer leases
                                   └──────────────────────────┘
```

The app tier is stateless: sessions in PostgreSQL, no sticky routing, any replica serves any request. The relay and scheduler use advisory-lock leases so exactly one instance runs each job.

### 9.2 Environments and pipeline

`local` (Docker database, native app) → `ci` (ephemeral database per run) → `staging` (production-shaped, anonymised data) → `production`.

CI gates, all blocking: compile, domain unit tests, module boundary tests, integration tests on an ephemeral database, contract tests, migration dry-run against a production-shaped schema, security scan, image build.

Deployment is rolling with health gates; migrations run as a separate explicit step, never on boot or on request. Expand-contract means the previous version keeps working during the rollout.

### 9.3 Data lifecycle and privacy

Signatures, recipient names, device identifiers and driver activity are personal data. Retention is defined per table, high-volume tables (`route_legs`, `delivery_records`, `audit_log`, `outbox_events`, `sync_operations`) are range-partitioned by date, and archival moves closed periods out of the hot path. Proof artifacts have an explicit retention period and are served only through short-lived signed URLs.

---

## 10. Delivery plan

### 10.1 Workstreams and dependency order

```
WS0 Platform foundation ────────────────────────────────┐ blocks everything
    schemas, migrations, db seam, config, clock,        │
    telemetry, error contract, boundary tests            │
WS1 Identity and access ◄───────────────────────────────┤ blocks every write
WS2 Reference data      ◄───────────────────────────────┘ blocks planning
        │                      │
        ▼                      ▼
WS3 Ordering ─────────────► WS4 Planning ──► WS5 Loading ──► WS6 Execution ──► WS7 Receipt
   (+ warehouse ACL)           │                │               │                │
        └──────────────────────┴────────────────┴───────────────┴────────────────┘
                                        │
                   WS8 Notification and outbox · WS9 Query, projections and UIs
                   WS10 Intelligence ports · WS11 Platform hardening
```

### 10.2 Assignment

| Stream | Deliverable | Depends on | Starts |
| --- | --- | --- | --- |
| WS0 Platform | Schemas, migrations, database seam, config, clock, telemetry, problem+json, boundary tests | none | week 1 |
| WS1 Identity | Authentication, PDP, scope tables, RLS policies, device registry | WS0 | week 1 |
| WS2 Reference | Loaders, caches, calendar policy, vehicle day status | WS0 | week 1 |
| WS3 Ordering | Capture, cutoff, state machine, warehouse anti-corruption layer | WS0, WS2 | week 2 |
| WS4 Planning | Constraint registry, allocation engine, deferral, publication, fuel | WS2, WS3 contract | week 2 |
| WS5 Loading | Sessions, checks, shortfall, vehicle interchange | WS4 contract | week 3 |
| WS6 Execution | Stops, outcomes, proof, sync reconciliation | WS5 contract | week 3 |
| WS7 Receipt and Issues | Confirmation, dispute, issue lifecycle | WS6 contract | week 4 |
| WS8 Notification | Outbox relay, notifier, channels, dead-letter | WS0, events | week 3 |
| WS9 Query and UI | Projections, cursor sync, role applications | WS1, projections | week 2 |
| WS10 Intelligence | Estimator ports, deterministic implementations, model registry | WS4 contract | week 5 |
| WS11 Hardening | Load testing, chaos drills, SLO instrumentation, runbooks | all | week 6 |

### 10.3 How parallel work stays unblocked

The arrows are **contract dependencies, not code dependencies**:

1. **Contracts land first.** Every module's `contract` package, its event payloads and its problem types are merged before implementations begin. Downstream streams build against the interface and a stub.
2. **One stream owns migrations.** Only WS0 authors migration files, on request, so two developers never write conflicting versions.
3. **Integrate by event or contract query.** Never by reading another module's tables. The boundary test fails the build if someone tries.
4. **Short-lived branches**, rebased daily. Long-lived branches across a schema change lose weekends.
5. **Definition of done:** domain tests without a database, one integration test through the command bus, an authorization test for a denied scope, a contract test, boundary tests passing, telemetry emitted, and a development log entry.

---

## 11. Open decisions

| # | Decision | Recommendation |
| --- | --- | --- |
| 1 | Local identity or OIDC provider | Local now; the `iam.users` mapping keeps OIDC a later adapter |
| 2 | Policy engine in-process or externalized | In-process PDP behind an interface. Externalize only if policy authoring moves outside engineering |
| 3 | Event bus: outbox polling or a broker | Outbox with in-process dispatch now; the relay is broker-ready when a second consumer appears |
| 4 | Products and order line items | Adopt them; capacity maths must move to line level in one change, never half |
| 5 | Migrate the current nine-table JSONB schema to the target model | Yes, with expand-contract, now that the timeline allows it. See the migration sequence in DATA-MODEL-REVIEW.md |
| 6 | Duplicate rule implementation in `frontend/lib/` | Delete it and port its tests to the domain layer |
| 7 | Outlet coordinates | **Deferred.** Additive later: a nullable column plus a check constraint. Nothing in the allocation model depends on it |
| 8 | Driver-side temporal exclusion (one driver, one vehicle at a time) | **Deferred**, and it is a policy question, not a correctness fix. Adding the constraint later requires clean data first, because it is validated against existing rows |

---

## Sources

Practice references behind the decisions above: [Modular Monolith, domain-centric design](https://www.kamilgrzybek.com/blog/posts/modular-monolith-domain-centric-design) · [Event sourcing pattern](https://microservices.io/patterns/data/event-sourcing.html) · [Timefold Solver](https://solver.timefold.ai/) · [RFC 9457 problem details in practice](https://zuplo.com/learning-center/best-practices-for-api-error-handling) · [REST API design reference 2026](https://www.digitalapplied.com/blog/rest-api-design-2026-engineering-reference-best-practices) · [Idempotency keys in practice](https://www.alekseialeinikov.com/en/blog/topics/architecture/idempotency-in-practice-api-retries-2026) · [PostgreSQL row-level security in practice](https://queryplane.com/blog/postgres-row-level-security-in-practice/) · [PostgreSQL security best practices 2026](https://www.postgresql.fastware.com/blog/postgresql-security-best-practices-for-enterprise-databases-in-2026) · [Database schema design 2026](https://www.digitalapplied.com/blog/database-schema-design-2026-engineering-reference) · [Fine-grained authorization concepts](https://openfga.dev/docs/authorization-concepts) · [OPA vs Cedar vs Zanzibar](https://www.osohq.com/learn/opa-vs-cedar-vs-zanzibar) · [Resilience patterns with jitter measurements](https://1xapi.com/blog/resilient-api-circuit-breaker-bulkhead-retry-nodejs-2026) · [Offline-first sync patterns](https://developersvoice.com/blog/mobile/offline-first-sync-patterns/)
