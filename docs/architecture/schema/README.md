# Target database schema

The schema Waypoint Dispatch is moving to: five schemas, 47 tables, row-level security and a database role per module.

**Status: design, not yet adopted.** The running application still uses the nine-table schema in the repository's root `migrations/`. Nothing here is applied automatically: the backend migrator reads `*.sql` directly inside `migrations/` and does not recurse, so these files are inert until someone moves them.

## Layout

```
migrations/
├── 001_baseline.sql                        the team's original 40-table schema, unchanged
├── 002_reference_versioning.sql
├── 003_policy_and_rule_versioning.sql       effective-dated rules and thresholds
├── 004_plan_provenance_and_immutability.sql row_version, superseded_by, immutability trigger
├── 005_command_receipts.sql                 idempotency
├── 006_order_status_transitions.sql         the legal state graph as data
├── 007_trip_vehicle_assignments.sql         vehicle interchange history
├── 008_driver_assignment_exclusion.sql      one driver per vehicle per period
├── 009_warehouse_stock_fields.sql           the anti-corruption footprint
├── 010_product_catalogue_projection.sql     ref.products is a cache, not master data
├── 011_attachment_lifecycle.sql             scan state and retention
├── 012_return_fuel_and_week_rollup.sql
├── 013_outbox_worker_state.sql              retry state and dead-lettering
├── 014_notification_deliveries.sql          intent vs delivery
├── 015_foreign_key_indexes.sql
├── 016_row_level_security.sql               policies, fail-closed actor
├── 017_partitioning_recipe.sql              documentation only, no DDL
└── 018_module_database_roles.sql            a role per module
```

`001_baseline.sql` is the team's work and is not edited. Every correction after it is a new numbered file, because an applied migration is immutable.

## Verified

Applied in order against PostgreSQL 16 from an empty database: all 18 succeed. Behaviour checks that passed:

| Check | Result |
| --- | --- |
| Published plan edited in place | rejected by trigger |
| Overlapping driver assignment on one vehicle | rejected by exclusion constraint |
| `waypoint_app` reading `ops` without assuming a module role | permission denied |
| `waypoint_ops` reading `ops` and the `ref`/`iam` kernel | allowed |
| `waypoint_ops` writing `iam.users` | permission denied |
| `waypoint_ops` deleting from `ops.orders` | permission denied |
| `waypoint_ops` inserting into the outbox | allowed |

## How the application connects

One process, one pool, so module identity is set per transaction:

```sql
BEGIN;
SET LOCAL ROLE waypoint_ops;              -- module identity, decides which tables
SET LOCAL app.actor_id = '<user uuid>';   -- row identity, drives RLS policies
-- ... one command ...
COMMIT;
```

`SET LOCAL`, never plain `SET`: a pooled connection would otherwise carry one request's identity into the next. `waypoint_app` is `NOINHERIT`, so forgetting `SET LOCAL ROLE` is a permission error rather than a silent full-access query.

## Adopting this

Expand and contract, as described in [../DATA-MODEL-REVIEW.md](../DATA-MODEL-REVIEW.md#migration-from-the-current-schema). When the team commits to it, move these files into the root `migrations/` directory, renumbered to continue after `002_account_management.sql`, and they become live. Until then they are a design under review.

A readable single-file view is any concatenation of these in order; there is deliberately no second combined copy in the repository, because two copies drift.
