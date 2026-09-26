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

Twenty findings, ordered by severity. Each states the risk and the fix.

### Critical: correctness or data loss

| # | Finding | Risk | Fix |
| --- | --- | --- | --- |
| 1 | **No optimistic concurrency columns.** No `version` on mutable aggregate roots | Two dispatchers, or a replayed offline command, silently overwrite each other. This is the failure the whole offline design exists to prevent | Add `version integer NOT NULL DEFAULT 0` to `orders`, `planning_runs`, `loading_sessions`, `delivery_records`. Every command carries `expected_version` |
| 2 | **No idempotency receipt table.** `sync_operations` tracks offline queue state, not command results | A retried delivery creates a second record. Networks retry by design | Add `integration.command_receipts (command_id, actor_id, fingerprint, response, created_at)`, primary key `(command_id, actor_id)` |
| 3 | **Statuses are free text with no transition guard** | An order goes from `delivered` back to `confirmed` and nobody notices until a dispute | Lookup tables (`ops.order_statuses`) plus an explicit `order_status_transitions` table, validated in the domain and constrained in the database |
| 4 | **Vehicle interchange is not modelled.** `ops.trips.vehicle_id` is a single column | Swapping the truck at the dock becomes an `UPDATE` that loses history and skips revalidation. A reefer load can end up on an ambient truck | New `ops.trip_vehicle_assignments` with validity period, reason and actor. `trips.vehicle_id` becomes the current assignment, derived |
| 5 | **Published plans are mutable** | A published plan edited in place destroys the record of what was decided and communicated | `planning_runs` gets `published_at` and `immutable` enforcement: after publication, changes create a new version and supersede the old one |
| 6 | **No outlet coordinates**, although the requirements document states GPS was added | Distance-ordered dispatch, "longest distance first", and any map view have no input | `ref.outlets` gets `latitude numeric(9,6)`, `longitude numeric(9,6)` |

### High: operability and scale

| # | Finding | Risk | Fix |
| --- | --- | --- | --- |
| 7 | **No partitioning strategy** for high-volume tables | `route_legs`, `delivery_records`, `audit_log`, `outbox_events`, `sync_operations` grow without bound. Vacuum, index bloat and backup windows degrade together | Range-partition by month on `occurred_at`, with a retention and detach policy per table |
| 8 | **`outbox_events` lacks worker state** | A poison event blocks the queue, or is retried forever with no visibility | Add `status`, `attempts`, `next_attempt_at`, `last_error`, `dead_lettered_at`, plus a partial index on `(status, next_attempt_at)` |
| 9 | **`ops.notifications` mixes business intent with delivery state** | You cannot answer "was the store actually told" versus "did we decide to tell them" | Split into `notifications` (intent) and `notification_deliveries` (per channel attempt, status, error) |
| 10 | **`ops.attachments` has no integrity or lifecycle fields** | Corrupt or oversized uploads, no retention, no way to detect tampering with evidence | Add `content_hash`, `size_bytes`, `content_type`, `storage_key`, `scan_status`, `retain_until` |
| 11 | **Driver-to-vehicle assignment has no temporal exclusion** | Two drivers assigned to one vehicle on one day; the application is the only guard | PostgreSQL 18 `WITHOUT OVERLAPS` on `(vehicle_id, validity PERIOD)`, or an exclusion constraint with `daterange` and a GiST index |
| 12 | **No return-cost model**, although the requirements demand it for the vehicle pool | The fuel ledger under-counts, so the weekly quota constraint is wrong | Add `return_distance_km` and `return_fuel_l` to `vehicle_trip_fuel_usage`, and a `vehicle_week_fuel` rollup view |
| 13 | **No reference-data versioning** | Re-reading a past plan against today's outlet data silently changes history | `ref.reference_versions`, with `planning_runs.reference_version_id` recording the snapshot a plan was built against |

### Medium: correctness of types and constraints

| # | Finding | Risk | Fix |
| --- | --- | --- | --- |
| 14 | **Key strategy unstated** | Natural keys that change become multi-quarter migrations; random UUIDv4 primary keys fragment indexes | UUIDv7 surrogate primary keys for index locality, dataset identifiers (`OUT001`, `VEH014`) kept as `UNIQUE NOT NULL` natural keys |
| 15 | **Numeric types unstated** for weight, volume, fuel | Floating point rounding in a capacity constraint means a load that "fits" by 0.0001 m³ | `numeric(10,3)` for volume and fuel, `numeric(10,2)` for weight. Never `real` or `double precision` |
| 16 | **Timestamp types unstated** | A `timestamp without time zone` in an Asia/Colombo operation is a lateness bug waiting to happen | `timestamptz` everywhere. Clock times from the dataset stay `time` with an explicit timezone policy |
| 17 | **No soft-delete or archival policy** | Either evidence is hard-deleted, or "deleted" rows silently pollute every query | Operational records are never deleted; they reach terminal states. Reference data uses `effective_from`/`effective_to` |
| 18 | **`ml` tables hold foreign keys into `ops`** | If Intelligence is ever extracted, these become cross-database joins | Keep the columns but drop the enforced constraint at extraction time; treat them as soft references from day one |
| 19 | **No index plan** stated | Foreign keys without indexes make every delete and join a sequential scan | Index every FK, plus composite indexes for the actual access paths listed below |
| 20 | **No row-level security policies** | Scope enforcement lives only in application code, so one forgotten `WHERE` leaks another depot's data | RLS enabled on every `ops` table, keyed on the transaction-scoped actor |

---

## Corrected target model

### Tables to add

| Schema | Table | Purpose |
| --- | --- | --- |
| `ref` | `reference_versions` | Snapshot identity for master data |
| `ops` | `trip_vehicle_assignments` | Vehicle interchange with history and reason |
| `ops` | `order_statuses`, `order_status_transitions` | The state machine as data |
| `ops` | `notification_deliveries` | Per-channel delivery attempts |
| `integration` | `command_receipts` | Idempotency |
| `integration` | `dead_letter_events` | Poison events with attempt history |

### Columns to add

| Table | Columns |
| --- | --- |
| `ops.orders` | `version`, `stock_status`, `stock_reservation_ref`, `deferral_count` |
| `ops.planning_runs` | `version`, `published_at`, `superseded_by`, `reference_version_id` |
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

Two things break this if you get them wrong: the application role must **not** hold `BYPASSRLS`, and the actor must be set with `SET LOCAL` **inside** the transaction. A plain `SET` on a pooled connection leaks one user's identity into the next request that borrows it.

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

**Temporal assignment without application guesswork.** One driver per vehicle per period, enforced by the database:

```sql
CREATE TABLE iam.vehicle_driver_assignments (
  assignment_id   uuid PRIMARY KEY,
  vehicle_id      text NOT NULL REFERENCES ref.vehicles(vehicle_id),
  driver_user_id  uuid NOT NULL REFERENCES iam.users(user_id),
  validity        daterange NOT NULL,
  EXCLUDE USING gist (vehicle_id WITH =, validity WITH &&)
);
```

PostgreSQL 18's `WITHOUT OVERLAPS` on primary and foreign keys expresses the same thing more directly if the deployment target allows it.

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

1. **Order line items.** The requirements document specifies item type, volume, minimum order count and weight per product. Adopting `order_items` moves every capacity calculation from order level to line level. Do it in one change or not at all; a half-migrated capacity model is worse than either end state.
2. **PostgreSQL version.** `WITHOUT OVERLAPS` needs 18. If the deployment target is 16, use the `daterange` exclusion constraint shown above, which works everywhere.
3. **Warehouse contract.** The stock system needs a published contract: reservation identifier, states and the timeout after which Waypoint stops waiting. That timeout is a business decision, not a technical one.
4. **Retention periods.** Signatures, recipient names and device identifiers are personal data. Legal retention drives the partition detach schedule.
5. **Read replicas.** Projections can serve from a replica once read volume justifies it, at the cost of replication lag visible in the dispatcher view. Decide the acceptable lag before, not after.
