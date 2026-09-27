# Data model review

Validation of the team's `Waypoint_Database_Schema_Quick_Guide.pdf` (40 tables, 5 schemas, 76 foreign keys), the findings against enterprise practice, and the corrected target model.

Read with [MODULES.md](MODULES.md) for what each module owns and [../../SYSTEM-ARCHITECTURE.md](../../SYSTEM-ARCHITECTURE.md) for the principles applied.

## Verdict

**The design is sound and should be adopted.** The schema separation is correct, the core modelling decisions are right, and the foreign-key discipline is better than most production systems. The findings below are additions and hardening, not a redesign.

### What the team got right

| Decision | Why it matters |
| --- | --- |
| Five schemas by context (`ref`, `iam`, `ops`, `ml`, `integration`) | Different access patterns, grants and retention. This is the single best decision in the design |
| Order and allocation as **separate** tables | The order is the customer's request; the allocation is the dispatcher's decision. Keeping them apart is what lets an order be deferred and replanned without losing history. Most teams get this wrong |
| Proof of delivery and receipt confirmation as separate records | Driver evidence and store acceptance are different events by different actors. Merging them destroys the dispute trail |
| `allocation → route_leg → loading_check → delivery_record` as a 1:1 chain | The same planned stop is traceable from plan to dock to road to outlet |
| `ml` isolated from OLTP | A bad model can never become a data-integrity problem |
| `integration.outbox_events` present from the start | Reliable messaging designed in, not retrofitted |
| `iam.devices` distinct from `iam.users` | A shared dock tablet is one device used by many people, and audit must distinguish them |
| Model version recorded on every prediction | Predictions stay reproducible and replaceable |

---

## Findings

Twenty-one findings, ordered by severity. Each states the risk and the fix.

**Verification status.** Every finding below was checked against the real `waypoint_industry_schema.sql` (684 lines, 40 tables), and the corrections applied. Confirmed present: `row_version` on six tables, 57 status `CHECK` constraints, `plan_version`, `published_at`, a unique published plan per depot-day, 21 indexes, `sha256_hex` and `byte_size` on attachments, `before_data`/`after_data`/`correlation_id` on the audit log. Confirmed absent: row-level security, exclusion constraints, partitioning, outbox worker state, `superseded_by`, outlet coordinates.

Findings 1, 2, 3, 5, 11 and 20 were softened or corrected as a result; every underlying concern survived. The fixes are implemented as **Part 2** of that file and verified by executing it against PostgreSQL: a fresh run from an empty database succeeds, a re-run of Part 2 is idempotent, the published-plan trigger rejects an in-place edit, and the exclusion constraint rejects an overlapping driver assignment.

The file still lives outside this repository. Move it in before treating it as the source of truth.

### Critical: correctness or data loss

| # | Finding | Risk | Fix |
| --- | --- | --- | --- |
| 1 | **Optimistic concurrency is incomplete, not absent.** `row_version` exists on `orders`, `trips`, `loading_sessions`, `loading_checks`, `delivery_records` and `receipt_confirmations`, and `sync_operations` carries `base_row_version`. `planning_runs` has `plan_version` (the business plan revision) but **no `row_version`** (the concurrency revision), and no command path is known to require `expected_version` consistently | Two dispatchers editing the same draft overwrite each other. A column nobody checks is not concurrency control | Add `row_version bigint NOT NULL DEFAULT 1` to `planning_runs`. Make every mutation `UPDATE ... WHERE id = ? AND row_version = ?` and fail on zero rows affected. `plan_version` and `row_version` are different concepts and both are needed |
| 2 | **No general command receipt.** `sync_operations.operation_id` de-duplicates the offline queue, and `delivery_records.allocation_id UNIQUE` prevents two deliveries for one stop | The duplicate row is already blocked, so the real failure is different: a retry after a lost response returns a **duplicate-key error instead of the original success**, and the device treats a completed command as failed | Add `integration.command_receipts (command_id, actor_id, command_type, payload_hash, result_status, result_body, created_at)`, primary key `(command_id, actor_id)`. Same key and hash replays the stored response; same key and different hash is `409` |
| 3 | **Status values are constrained; status transitions are not.** `CHECK` constraints already restrict `orders`, `trips` and `loading_sessions` to known values | The database accepts `delivered → confirmed` as readily as `in_transit → delivered`, so an illegal lifecycle jump is invisible until a dispute | Enforce the transition graph in the domain (`Order.canTransitionTo`), keep the `CHECK` constraints, and document the legal graph as data in `ops.order_status_transitions`. A database trigger is optional defence in depth |
| 4 | **Vehicle interchange is not modelled.** `ops.trips.vehicle_id` is a single column | Swapping the truck at the dock becomes an `UPDATE` that loses history and skips revalidation. A reefer load can end up on an ambient truck | New `ops.trip_vehicle_assignments` with validity period, reason, actor and a `revalidated` flag. `trips.vehicle_id` becomes the current assignment, derived, never the historical source of truth |
| 5 | **Published-plan immutability is not enforced.** `plan_version`, `status`, `published_at` and a unique published plan per depot-day already exist, so the metadata is there | Nothing stops `UPDATE ops.trips ... WHERE planning_run_id = '<published>'` or a `DELETE` from `order_allocations`. The record of what was decided and communicated can be edited away | Add `superseded_by`. Block `UPDATE` and `DELETE` on a published run and its children except through the controlled revision path, in the domain and with a trigger for defence in depth |
| 6 | **No outlet coordinates.** The team's requirements draft states under Data Structure that "the operational dataset includes newly added GPS coordinates for all delivery outlets", but the schema has none | Distance-ordered dispatch, a dispatcher map and nearest-outlet logic have no input. **Severity is scope-dependent**: the booklet's allocation model needs only district-level travel times, which `district_travel` already provides | Add `latitude numeric(9,6) CHECK (latitude BETWEEN -90 AND 90)` and `longitude numeric(9,6) CHECK (longitude BETWEEN -180 AND 180)` if maps, GPS or true distance ordering are in product scope. Otherwise defer |
| 7 | **Decisions do not record the rules that produced them.** A plan stamps the reference-data version, and a prediction stamps the model version, but nothing stamps the **rule set version**. Constraint thresholds (270 and 480 minutes, the 16:00 cutoff) are treated as constants | A deferral recorded under a 270-minute Fresh budget cannot be reproduced once the budget becomes 300. The reason string survives, the reasoning does not, and "every deferral records the binding constraint" quietly stops being true | Add `ops.policy_versions` and `ops.rule_parameters` with effective dating, and `planning_runs.policy_version_id`. Supersede, never mutate |

| 7b | **`ref.district_travel` is keyed `(district_id, depot_id)`**, implying a district can be served by more than one depot | The data says otherwise: every one of the 120 outlets sits in the depot its district maps to, with zero exceptions, and the supplied validator indexes travel by district alone. The composite key invites joins that assume a choice which does not exist | Key it by `district_id`. Move `depot_id` onto `ref.districts`. Keep `outlets.depot_id` as a checked redundancy |

### High: operability and scale

| # | Finding | Risk | Fix |
| --- | --- | --- | --- |
| 8 | **No partitioning strategy** for high-volume tables | `route_legs`, `delivery_records`, `audit_log`, `outbox_events`, `sync_operations` grow without bound. Vacuum, index bloat and backup windows degrade together | Range-partition by month on `occurred_at`, with a retention and detach policy per table |
| 9 | **`outbox_events` lacks worker state** | A poison event blocks the queue, or is retried forever with no visibility | Add `status`, `attempts`, `next_attempt_at`, `last_error`, `dead_lettered_at`, plus a partial index on `(status, next_attempt_at)` |
| 10 | **`ops.notifications` mixes business intent with delivery state** | You cannot answer "was the store actually told" versus "did we decide to tell them" | Split into `notifications` (intent) and `notification_deliveries` (per channel attempt, status, error) |
| 11 | **Attachment integrity exists; lifecycle does not.** `object_key`, `media_type`, `byte_size` and `sha256_hex` are already there | Tamper detection is covered. What is missing is malware scan state and retention, and proof artifacts are personal data that cannot be kept forever | Add `scan_status` and `retain_until` only |
| 12 | **Driver-to-vehicle assignment has no temporal exclusion** | Two drivers assigned to one vehicle on one day; the application is the only guard | PostgreSQL 18 `WITHOUT OVERLAPS` on `(vehicle_id, validity PERIOD)`, or an exclusion constraint with `daterange` and a GiST index |
| 13 | **No return-cost model**, although the requirements demand it for the vehicle pool | The fuel ledger under-counts, so the weekly quota constraint is wrong | Add `return_distance_km` and `return_fuel_l` to `vehicle_trip_fuel_usage`, and a `vehicle_week_fuel` rollup view |
| 14 | **No reference-data versioning** | Re-reading a past plan against today's outlet data silently changes history | `ref.reference_versions`, with `planning_runs.reference_version_id` recording the snapshot a plan was built against |

### Medium: correctness of types and constraints

| # | Finding | Risk | Fix |
| --- | --- | --- | --- |
| 15 | **Key strategy unstated** | Natural keys that change become multi-quarter migrations; random UUIDv4 primary keys fragment indexes | UUIDv7 surrogate primary keys for index locality, dataset identifiers (`OUT001`, `VEH014`) kept as `UNIQUE NOT NULL` natural keys |
| 16 | **Numeric types unstated** for weight, volume, fuel | Floating point rounding in a capacity constraint means a load that "fits" by 0.0001 m³ | `numeric(10,3)` for volume and fuel, `numeric(10,2)` for weight. Never `real` or `double precision` |
| 17 | **Timestamp types unstated** | A `timestamp without time zone` in an Asia/Colombo operation is a lateness bug waiting to happen | `timestamptz` everywhere. Clock times from the dataset stay `time` with an explicit timezone policy |
| 18 | **No soft-delete or archival policy** | Either evidence is hard-deleted, or "deleted" rows silently pollute every query | Operational records are never deleted; they reach terminal states. Reference data uses `effective_from`/`effective_to` |
| 19 | **`ml` tables hold foreign keys into `ops`** | If Intelligence is ever extracted, these become cross-database joins | Keep the columns but drop the enforced constraint at extraction time; treat them as soft references from day one |
| 20 | **Indexes cover reads, not foreign keys.** 21 indexes already target the main query paths, several partial and well chosen | The gaps are FK columns: `order_allocations.order_id`, `order_status_history.order_id`, the three `operational_issues` subject columns, `order_items.product_id` and others. An unindexed FK makes every parent delete and join a sequential scan | Add the 16 missing FK indexes; keep the existing read-path indexes as they are |
| 21 | **No row-level security policies** | Scope enforcement lives only in application code, so one forgotten `WHERE` leaks another depot's data | RLS enabled on every `ops` table, keyed on the transaction-scoped actor |

---

## Corrected target model

### Tables to add

| Schema | Table | Purpose |
| --- | --- | --- |
| `ref` | `reference_versions` | Snapshot identity for master data |
| `ops` | `trip_vehicle_assignments` | Vehicle interchange with history and reason |
| `ops` | `order_statuses`, `order_status_transitions` | The state machine as data |
| `ops` | `notification_deliveries` | Per-channel delivery attempts |
| `ops` | `policy_versions` | Effective-dated rule set versions, superseded never mutated |
| `ops` | `rule_parameters` | Effective-dated thresholds: time budgets, cutoff, turnaround |
| `integration` | `command_receipts` | Idempotency |
| `integration` | `dead_letter_events` | Poison events with attempt history |

### Columns to add

| Table | Columns |
| --- | --- |
| `ops.orders` | `version`, `stock_status`, `stock_reservation_ref`, `deferral_count` |
| `ops.planning_runs` | `row_version`, `published_at`, `superseded_by`, `reference_version_id`, `policy_version_id` |
| `ops.loading_sessions` | `version` |
| `ops.delivery_records` | `version`, `client_recorded_at`, `server_recorded_at` |
| `ops.attachments` | `content_hash`, `size_bytes`, `storage_key`, `scan_status`, `retain_until` |
| `ops.vehicle_trip_fuel_usage` | `return_distance_km`, `return_fuel_l` |
| `ref.outlets` | `latitude`, `longitude` |
| `integration.outbox_events` | `status`, `attempts`, `next_attempt_at`, `last_error`, `dead_lettered_at` |

### Tables not needed here

Inventory and stock are owned by the separate warehouse system. This schema holds only `orders.stock_status` and `orders.stock_reservation_ref`, which are the anti-corruption layer's entire footprint. Do not model stock levels, reservations or adjustments here; that duplication is how two systems start disagreeing about the same number.

---

## Key patterns, with the DDL that matters

**Scope enforcement at the database, not only in code.** The transaction sets the actor, every policy reads it:

```sql
ALTER TABLE ops.orders ENABLE ROW LEVEL SECURITY;

CREATE POLICY orders_outlet_scope ON ops.orders
  FOR SELECT USING (
    outlet_id IN (SELECT outlet_id FROM iam.user_outlet_access
                  WHERE user_id = current_setting('app.actor_id')::uuid)
    OR EXISTS (SELECT 1 FROM iam.user_depot_access d
               JOIN ref.outlets o ON o.depot_id = d.depot_id
               WHERE d.user_id = current_setting('app.actor_id')::uuid
                 AND o.outlet_id = ops.orders.outlet_id)
  );
```

Three things break this if you get them wrong:

1. The actor must be set with `SET LOCAL` **inside** the transaction. A plain `SET` on a pooled connection leaks one user's identity into the next request that borrows it.
2. The application role must **not** hold `BYPASSRLS`.
3. **The table owner bypasses RLS by default.** This is the trap that survives the first two. Separate the roles: `waypoint_migrator` owns the tables and runs migrations; `waypoint_app` owns nothing, holds no `BYPASSRLS`, and is what the pool connects as. Add `ALTER TABLE ... FORCE ROW LEVEL SECURITY` so policies apply even to the owner.

Beyond RLS, give **each module its own role** granted only its schema plus read access to `ref` and `iam`. `waypoint_app` is `NOINHERIT` and a member of each, so a transaction must `SET LOCAL ROLE waypoint_ops` before it can touch `ops`. A cross-module query then fails at the database, which is the only enforcement that survives a developer who is inside the right module and writes the wrong SQL. One caveat learned by testing: roles are cluster-wide, so a migration that only does `CREATE ROLE ... EXCEPTION WHEN duplicate_object` will silently keep a pre-existing role with the wrong attributes. Assert them with `ALTER ROLE`.

**The outbox the relay can actually work.** Status, attempts and a partial index are what stop one poison event from halting everything:

```sql
CREATE TABLE integration.outbox_events (
  event_id         uuid PRIMARY KEY,
  aggregate_type   text NOT NULL,
  aggregate_id     uuid NOT NULL,
  event_type       text NOT NULL,
  payload          jsonb NOT NULL,
  occurred_at      timestamptz NOT NULL DEFAULT now(),
  status           text NOT NULL DEFAULT 'pending'
                   CHECK (status IN ('pending','published','failed','dead')),
  attempts         integer NOT NULL DEFAULT 0,
  next_attempt_at  timestamptz NOT NULL DEFAULT now(),
  last_error       text
) PARTITION BY RANGE (occurred_at);

CREATE INDEX outbox_due_idx ON integration.outbox_events (next_attempt_at)
  WHERE status IN ('pending','failed');
```

Workers claim a batch with `SKIP LOCKED`, so several relay instances drain the same queue without fighting over the same rows:

```sql
SELECT * FROM integration.outbox_events
 WHERE status IN ('pending','failed') AND next_attempt_at <= now()
 ORDER BY occurred_at
 FOR UPDATE SKIP LOCKED
 LIMIT 100;
```

Without `SKIP LOCKED`, every worker blocks on the first locked row and the relay serialises to one effective instance.

**Temporal assignment without application guesswork.** One driver per vehicle per period, enforced by the database:

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;   -- required to mix = with && in one GiST index

CREATE TABLE iam.vehicle_driver_assignments (
  assignment_id   uuid PRIMARY KEY,
  vehicle_id      text NOT NULL REFERENCES ref.vehicles(vehicle_id),
  driver_user_id  uuid NOT NULL REFERENCES iam.users(user_id),
  validity        daterange NOT NULL,
  EXCLUDE USING gist (vehicle_id WITH =, validity WITH &&)
);
```

`btree_gist` is not optional: without it, GiST cannot index the equality operator on `vehicle_id` and the constraint will not build. This is the **baseline** form, because the deployment target is PostgreSQL 16. PostgreSQL 18's `WITHOUT OVERLAPS` expresses the same thing more directly, but do not make it the baseline until the target moves.

A second exclusion on `(driver_user_id WITH =, validity WITH &&)` would also stop one driver holding two vehicles at once. Treat that as a **policy decision, not an obvious correctness fix**: it is right if a driver is tied to one vehicle for a whole day, and wrong if a driver legitimately runs one vehicle in the Fresh window and another in the trading day. Decide which Waypoint means before adding it.

**Vehicle interchange as history, not an overwrite:**

```sql
CREATE TABLE ops.trip_vehicle_assignments (
  assignment_id  uuid PRIMARY KEY,
  trip_id        uuid NOT NULL REFERENCES ops.trips(trip_id),
  vehicle_id     text NOT NULL REFERENCES ref.vehicles(vehicle_id),
  assigned_at    timestamptz NOT NULL DEFAULT now(),
  superseded_at  timestamptz,
  reason         text NOT NULL,
  assigned_by    uuid NOT NULL REFERENCES iam.users(user_id),
  revalidated    boolean NOT NULL
);
```

`revalidated` is not decoration. An interchange row may only be written after Planning has re-checked the whole trip against the substitute vehicle.

**Effective-dated rules, so history stays reproducible.** Two rules make this work: supersede rather than update, and keep effective time separate from modification time.

```sql
CREATE TABLE ops.policy_versions (
  policy_version_id uuid PRIMARY KEY,
  policy_kind       text NOT NULL,           -- 'deferral_priority', 'authorization', ...
  definition        jsonb NOT NULL,          -- the decision table itself
  effective_from    timestamptz NOT NULL,    -- when it governs
  effective_to      timestamptz,             -- null while current
  created_at        timestamptz NOT NULL DEFAULT now(),   -- when the row was written
  created_by        uuid NOT NULL REFERENCES iam.users(user_id),
  EXCLUDE USING gist (
    policy_kind WITH =,
    tstzrange(effective_from, effective_to) WITH &&
  )
);

CREATE TABLE ops.rule_parameters (
  parameter_id    uuid PRIMARY KEY,
  parameter_key   text NOT NULL,             -- 'fresh_time_budget_min'
  value           numeric NOT NULL,
  effective_from  timestamptz NOT NULL,
  effective_to    timestamptz,
  EXCLUDE USING gist (
    parameter_key WITH =,
    tstzrange(effective_from, effective_to) WITH &&
  )
);
```

The exclusion constraints mean two versions of the same policy can never be in force at the same instant, which is the failure that makes replay ambiguous. `effective_from` is when the rule governs; `created_at` is when somebody wrote it down. Never infer one from the other, and never derive either from when a message arrived.

`planning_runs.policy_version_id` then completes the set: a published plan records which reference data, which rules and which model produced it, so any historical decision can be replayed against exactly what was in force.

### Index plan for the real access paths

| Table | Index |
| --- | --- |
| `ops.orders` | `(depot_id, requested_date, status)`, `(outlet_id, requested_date)`, `(status) WHERE status IN ('confirmed','stock_held')` |
| `ops.order_allocations` | `(planning_run_id)`, `(order_id)`, `(trip_id, sequence)` |
| `ops.trips` | `(planning_run_id)`, `(vehicle_id, trip_date)` |
| `ops.route_legs` | `(trip_id, seq)`, `(allocation_id)` |
| `ops.delivery_records` | `(allocation_id)`, `(driver_user_id, recorded_at)` |
| `integration.audit_log` | `(actor_user_id, occurred_at DESC)`, `(target_type, target_id, occurred_at DESC)` |
| `integration.sync_operations` | `(device_id, status)`, `(status, created_at) WHERE status = 'pending'` |

Index every foreign key. An unindexed FK turns every parent delete and every join into a sequential scan, and it is the most common cause of a schema that tests fine and dies in production.

---

## External product catalogue

The product catalogue and order-to-product mapping are produced outside this repository and will be served by the external warehouse API. Waypoint consumes them; it does not own them.

**What the source actually is.** 280 candidate products and 194,366 order-product rows covering 97,321 historical orders. The catalogue is a *mathematical reconstruction* from order totals: it reproduces each order's unit count exactly and its weight and volume within 1%. Every row carries `verified_real_sku = False`. It contains no product names, prices, stock balances, dimensions or temperature requirements.

That provenance drives five rules. They are not negotiable, because breaking them turns an approximation into a physical failure.

| # | Rule | Why |
| --- | --- | --- |
| 1 | **Order-level `order_weight_kg` and `order_volume_m3` remain authoritative for every capacity decision.** Product lines are descriptive only and are never summed to feed `WeightCapacity` or `VolumeCapacity` | The catalogue carries up to 1% error per order. On a 5,510 kg truck that is plus or minus 55 kg of invisible uncertainty. A plan can then be published that physically overloads a vehicle while the constraint reports a pass |
| 2 | **Temperature is never derived from products.** The reefer constraint keeps reading order-level `temp_requirement` | The catalogue explicitly does not establish refrigeration suitability. Inferring it would put chilled goods on an ambient truck |
| 3 | **Never present a candidate as a real SKU** to a store manager or on a run sheet without labelling it inferred | `verified_real_sku` is `False` for all 280. Showing them as ordered items fabricates a record |
| 4 | **Do not model "at most two product types per order"** anywhere in schema, UI or validation | That ceiling is an artifact of the reconstruction method, not a property of real orders |
| 5 | **Products are a cached projection, not master data.** `ref.products` holds a warehouse-sourced copy with a catalogue version and a refresh timestamp | The warehouse owns the catalogue. A local copy that drifts silently is worse than a cache that can say it is stale |

**Coverage gap.** The mapping keys on the historical `delivery_id` values. Orders created in Waypoint have their own identifiers and **no product lines at all**. So either store managers select products at capture time, which makes the catalogue load-bearing in the ordering flow, or product lines exist only for historical analysis. That is a product decision, and it should be made before anyone builds an order-line UI.

**Stock is the real gap.** The catalogue has no stock balances. `StockPort.checkAvailability` therefore has nothing to answer with today, which means the `stock_held` order state, the stock controller role and edge cases STK-01 to STK-06 have no data source behind them. Either the warehouse API adds stock state, or that entire flow stays designed but inert. Do not ship a stock screen that always says yes.

**Integration shape.** Bulk sync on a schedule into the local projection, not a per-request call: 194k mapping rows is not a request-path fetch. The port carries a catalogue version so a plan can record which version it was built against, and the circuit breaker's fallback is simply order-level totals, which is exactly what rule 1 already requires. That fallback is why the system keeps working when the warehouse is down.

## Migration from the current schema

The shipped database is nine tables with orders as a single JSONB blob and plans as one JSONB row per day. It is not compatible with the target. Use **expand and contract**, so no step requires downtime or a big-bang cutover:

| Phase | Action | Reversible |
| --- | --- | --- |
| 1 Expand | Create `ref`, `iam`, `ml`, `integration` schemas and the new `ops` tables alongside the existing ones. Nothing reads them yet | yes |
| 2 Backfill | Copy reference CSVs into `ref`. Project existing JSONB orders and plans into the normalized tables. Verify with row and checksum comparison | yes |
| 3 Dual write | Writes go to both models inside one transaction. Reads still come from the old model. Run until counts and checksums agree for a full week | yes |
| 4 Read switch | Projections and queries move to the new model, one read path at a time, behind a feature flag | yes, flag off |
| 5 Stop dual write | Old tables become read-only | yes, with a restore |
| 6 Contract | Drop the old tables after a retention window | no |

Two rules for the whole sequence: migrations are **forward-only and checksummed**, and they run as an explicit deployment step, never on application boot or on a request. A migration that adds a `NOT NULL` column without a default on a large table takes a full table lock; add the column nullable, backfill in batches, then add the constraint.

---

## Open questions for the team

1. **Order line items.** Adopt `order_items` for description, but **do not move capacity calculations to line level**. See the product catalogue section above: the catalogue reproduces order weight and volume only within 1%, and capacity is a hard physical limit. Order-level weight and volume stay authoritative. This reverses the earlier recommendation in this document.
2. **PostgreSQL version.** `WITHOUT OVERLAPS` needs 18. If the deployment target is 16, use the `daterange` exclusion constraint shown above, which works everywhere.
3. **Warehouse contract.** The stock system needs a published contract: reservation identifier, states and the timeout after which Waypoint stops waiting. That timeout is a business decision, not a technical one.
4. **Retention periods.** Signatures, recipient names and device identifiers are personal data. Legal retention drives the partition detach schedule.
5. **Read replicas.** Projections can serve from a replica once read volume justifies it, at the cost of replication lag visible in the dispatcher view. Decide the acceptable lag before, not after.
