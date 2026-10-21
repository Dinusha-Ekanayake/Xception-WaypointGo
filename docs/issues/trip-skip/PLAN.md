# Skip a store, end a trip: the dispatcher's live decisions

Owner: @Oxshadha. Piece 1 of the trip-problems vision; reviewed against the code on 2026-10-21. Pieces 2 to 4 (quiet goods problems, the loader's remove-flag, the driver's problem list and the channel notice) have plans of their own. The folder takes its issue number once the issue is opened.

## What it does

While a trip is on the road the dispatcher can **skip a store** (its orders go to the next operating day, or are cancelled and come back to the depot) or **end the trip** (every stop not yet done goes to the next day). Each decision carries a reason (rule 8). The store hears it in the trip's messages and by push, the driver's route drops the stop, and the order moves.

## Current state

- `execution.delivery_records` already has the outcome `skipped`, a `row_version` and the check constraints for it (`20261002T0100_execution_tables.sql`). `DeliveryRecord.skip()` is used only by a plan revision, from `PENDING`, stamped `REPLANNED`, and publishes nothing.
- Row-level security lets only the system and the vehicle's driver write a delivery record (`delivery_update`). `app.actor_oversees_depot` exists for dispatchers.
- The driver policy grants `delivery:*`; a new `delivery:` action is the driver's unless denied.
- Ordering: `OrderStateMachine` has `LOADING -> DEFERRED` but nothing out of `IN_TRANSIT` except delivered, partial and failed. `OnOrderDeferred` moves an order to the next operating day (`Order.deferTo`), and planning reads deferred orders as demand for that day.
- Warehouse: `OnOrderCancelled` sends "cancelled" for any order with a warehouse reference.

## Who owns what

| Piece | Layer |
| --- | --- |
| Skip from pending or arrived, with what then and why | `execution/domain/DeliveryRecord.skipByDispatcher` |
| `delivery:SkipStop`, `delivery:EndTrip` | `execution/application` handlers, one transaction each, `expectedVersion` on SkipStop |
| Who may | policy (dispatcher allow, driver deny) and row-level security (`actor_oversees_depot`, the row written must be `skipped`) |
| `delivery.skipped` | `execution/contract/ExecutionEvents`, catalogued in MODULES.md |
| The order's move | `ordering/application/OrderingConsumers.OnDeliverySkipped`, edges in `OrderStateMachine` |
| No warehouse call for a returned order | `OrderCancelled.returnedToDepot`, `warehouse/application/WarehouseConsumers` |
| The store's message and push, the driver's route | `messaging` consumer (a system message to the outlet), `notification` routing version 15 |
| Buttons | dispatcher `screens/LiveTrip*`, mirrors in `shared/domain/execution.ts` |

## Decisions

1. **Names** `delivery:SkipStop {deliveryId, then: NEXT_DAY | CANCEL, reason}` and `delivery:EndTrip {tripId, reason}` (every pending or arrived stop skipped as `NEXT_DAY`). Reason 3 to 500 characters.
2. **A second domain method.** `skipByDispatcher(then, reason, actor, now)` from `PENDING` or `ARRIVED`; `skip()` stays as it is for revisions. Nothing leaves `SKIPPED`.
3. **A table, not columns.** The decision is kept in `execution.stop_skips` (then, reason, whole trip, who, when, command), append only and one per stop, as a driver's store-answer waiver is. The stop itself is only `skipped`, with `failure_reason = skipped_by_dispatcher`; a plan revision's skip keeps `replanned`.
4. **Authority in data.** Dispatcher policy gains an Allow for both actions on `wpt:delivery:*`; driver policy gains an explicit Deny (the driver holds `delivery:*`). Row-level security adds `delivery_dispatcher_skip FOR UPDATE USING (app.actor_oversees_depot(depot_code)) WITH CHECK (... AND outcome = 'skipped')`: a dispatcher can write nothing but a skip.
5. **Order moves.** New edges `IN_TRANSIT -> DEFERRED`, `IN_TRANSIT -> CANCELLED`, `LOADING -> CANCELLED`. A store still cannot cancel after loading starts (`CANCELLABLE` is unchanged). `NEXT_DAY` reuses `deferTo(nextOperatingDay)`; `CANCEL` publishes `order.cancelled` with `returnedToDepot = true`.
6. **Redelivery is not a skip.** At least once delivery: a redelivered `delivery.skipped` for yesterday must not defer the order that is on the road again today. The consumer ignores a skip whose service date is not the order's current delivery date.
7. **Fail versus skip** (rule to write): the dispatcher's skip or end trip makes the stop `SKIPPED` and the same order moves on; the driver's "not delivered" makes it `FAILED` and a redelivery is a new linked order (A-24), as today.
8. **A driver's write to a skipped stop** is refused by the domain (`requireOutcome`) and held on the phone for review, like any refused write.
9. **Routing version 15**: `delivery.skipped` to the outlet's store managers and the vehicle's driver. Version 14 is taken (issue resolved).

## Pull requests

1. Backend: domain, migration (columns, row policy, catalogue rows, policy versions), handlers, event, ordering consumer and edges, warehouse flag. Tests: domain unit (skip from arrived, refused after delivered), state machine edges, integration through the command bus for both commands with a denied other-depot dispatcher and a denied driver, a skip racing the driver's "delivered", the redelivered skip ignored.
2. Messaging and notification: the store's system message, routing version 15, the driver's run sheet showing the stop gone. Tests: routing integration, a store notified once.
3. Dispatcher UI: Skip store (Next day or Cancel, with a reason) and End trip (confirm with the reason and the number of stops) on the live trip page and the stop row; mirrors. Browser spec.

Docs in the same pull requests: RULES-AND-POLICIES (skip, end trip, returned order, fail versus skip), EDGE-CASES rows with their tests, MODULES.md event catalogue, the development log and the STATUS row.

## Not here

The report audiences, the driver's waiting and problem list (decisions 1 to 3 of the review, awaiting @jv-ransika) and the loader's remove-flag. Quiet goods problems were built after this, on 2026-10-21 (R-ISS-16).
