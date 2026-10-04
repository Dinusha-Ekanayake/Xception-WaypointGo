# Issue #8 walkthrough: the Ordering module

What was built for [issue #8](https://github.com/kavindamihiran/Xception-WaypointGo/issues/8), how each flow runs, and what is left. The approach and its reasoning are in [PLAN.md](PLAN.md); rules are in [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md) (`R-ORD-*`, `R-STK-*`), cases in [EDGE-CASES](../../architecture/EDGE-CASES.md) (`ORD-*`, `STK-*`), and the log entry in the [development log](../../development-docs/development-log.md).

Branch `feat/ordering-module`.

## What exists, layer by layer

All paths are under `backend/src/main/java/com/waypoint/dispatch/` unless they start with `migrations/` or `frontend/`.

| Layer | Files | What it holds |
| --- | --- | --- |
| contract | `ordering/contract/` | Already merged with the module contracts. This issue added `OrderEvents.OrderAutoDeferred` (`order.auto_deferred`) |
| domain | `ordering/domain/` | `Order` and its invariants, `OrderStateMachine` (edges, and `onEvent` to judge a consumed event), `Cutoff`, `DeliveryDate`, `WindowFeasibility`, `TemperatureMix`, `OrderRef`, `OrderLine`, `Reservation`, `LineDiscrepancy`. No Spring, no SQL, time is a parameter |
| application | `ordering/application/` | The four command handlers, `OrderDataQuery` (implements `OrderQuery`), `DeliveryDateResolver`, `OrderTransitions` and the consumers, `CutoffJob`, `OrderMessages` (translation only) |
| infrastructure | `ordering/infrastructure/JdbcOrderRepository.java` | All SQL. Every update is `WHERE order_id = ? AND row_version = ?` |
| web | `ordering/web/OrderController.java` | Reads only, under `/api/orders` |
| schema | `migrations/20261001T0200_ordering_orders.sql`, `20261001T0300_ordering_actions_implemented.sql` | Tables, row-level security, grants; the five `order:*` actions marked implemented |

Platform and neighbouring pieces this issue needed, done with their owners:

| Piece | File | Why Ordering needed it |
| --- | --- | --- |
| Outbox writer | `platform/messaging/OutboxEventPublisher.java`, `migrations/20261001T0100_platform_outbox_writer_and_system_actor.sql` | An event commits with the change or not at all |
| Consumer inbox | `platform/messaging/ConsumerInbox.java` | `(consumer, event_id)` so redelivery is a no-op |
| System actor | `shared/domain/Actor.SYSTEM`, `Database.asSystem`, SQL `app.actor_is_system()` | Consumers and the cutoff job act for no person, and must still pass row-level security |
| Contract reads in their own transaction | `Database.readAs`, `Database.ambientActor` | A contract query called from another module's command must not switch that command's role; it reads as the owning module for the same actor |
| Scope denials audited after rollback | `platform/messaging/CommandBus.java` | Scope is only known inside the transaction, and a denial there would roll its own audit row back |
| Warehouse not configured | `warehouse/infrastructure/UnconfiguredStockPort.java` | With no API key every placement is `STOCK_UNKNOWN`, visibly, rather than failing to start |

`referencedata/application/ReferenceDataQuery.java` now reads the database through `readAs` too, for the same reason as Ordering's contract reads.

## The schema

`ordering.orders` holds order-level weight, volume and temperature as the warehouse returned them (R-ORD-12). They are null together with `warehouse_order_ref` until the warehouse reserves, and a `CHECK` refuses a planned status without a reservation. `ordering.order_lines` is keyed by `(order_id, revision, product_id)`: an amendment adds a revision and never overwrites one. `ordering.order_status_history` records every move with its actor, its reason and, for a consumed event, the event id (rule 8). `ordering.day_closures` records the dispatcher closing a depot-day.

Row-level security is `ENABLE` and `FORCE` on all four tables. An order is visible to the system actor, to its outlet's store manager and to its depot's dispatcher. Every other actor, including no actor, sees nothing. Anyone may read whether a day is closed, because a store manager placing an order has to know its date will roll; only depot scope may close one.

## Flows

### Placing an order (`order:Place`)

`POST /api/commands` → `CommandBus` checks policy, then the receipt → `PlaceOrderHandler` runs in one serializable transaction as `waypoint_ordering`:

1. Resolve the outlet from Reference Data. Unknown outlet is `422`.
2. Scope: `app.actor_has_outlet OR app.actor_has_depot`, else `FORBIDDEN`. The bus audits it after the rollback (ORD-09).
3. Window against service allowance, with the arithmetic in the message (R-ORD-10, ORD-08).
4. One temperature per order, when a catalogue is wired (R-ORD-06, ORD-03). Mixed is rejected; the client submits two orders.
5. `DeliveryDateResolver`: cutoff, then closed day, then non-operating day, each recorded as a reason (R-ORD-01, 07, 08).
6. `OrderRef.derive(actor, commandId)`, then `StockPort.placeOrder`. `Reserved` confirms; `Insufficient` is `422` listing each short line (STK-01) and saves nothing; `Unavailable`, or a reply without usable totals (STK-07), saves `STOCK_UNKNOWN` and returns `degraded` with the reason (rule 9).
7. Insert the order, its lines and the first history row, then publish `order.placed`, all in the same transaction.

A serializable retry calls the warehouse again with the same reference. The adapter's contract (R-STK-11) is to look that reference up before creating, so a retry finds the first reservation.

### Amending and cancelling (`order:Amend`, `order:Cancel`)

Both need `expectedVersion`. A stale version fails before the warehouse is called, and the `row_version` guard on the write settles any race: of two parallel amends at the same version, exactly one wins.

An amend is accepted only for `STOCK_UNKNOWN` or `CONFIRMED` orders whose day is still open. An allocated order is a conflict (ORD-05), and a loading one is refused (ORD-06). A confirmed order re-reserves through `amendOrder`. If the warehouse is down it is refused with `503` and nothing changes (ORD-14). A stock-unknown order retries `placeOrder` and is confirmed if that succeeds.

A cancel is refused once loading starts (ORD-10). It does not call the warehouse: `order.cancelled` carries the reservation reference and Warehouse releases it from the event (D-H, R-STK-09).

### Closing a day (`order:CloseForDay`)

Only for a dispatcher with the depot in scope, and only once the 16:00 cutoff has passed. It writes `day_closures` and publishes `orders.closed` with the order ids due that day. Closing again answers `alreadyClosed` and publishes nothing. Placement for that day then rolls with reason `closed`.

### Reads

| Endpoint | Returns | Out of scope |
| --- | --- | --- |
| `GET /api/orders/{id}` | `OrderView` | `404`, indistinguishable from absent |
| `GET /api/orders/{id}/timeline` | status changes, oldest first | `404` |
| `GET /api/orders?outlet=&cursor=&limit=` | a keyset page on `(placed_at, order_id)`, newest first | `403` plus audit, never an empty page |
| `GET /api/orders/demand?depot=&date=` | what Planning will allocate: confirmed or deferred, and reserved | `403` plus audit |
| `GET /api/orders/delivery-date?outlet=&requestedDate=` | the date the order would be served and why it moved | policy only |

`OrderQuery`, the contract, gives other modules the same reads. They run as `waypoint_ordering` for the actor of the calling unit of work, in a separate read-only transaction.

### Consumed events

Each is an `EventSubscriber` in `ordering/application/` (`OrderingConsumers`, `OnRedeliveryRequested`, `OnWarehouseOrderStatusChanged`). They run in the relay's transaction as the system actor. `OrderTransitions` judges every event with `OrderStateMachine.onEvent`. A legal move is written with its event id. A late or repeated event is a no-op. An impossible one is counted as `waypoint.order.event_illegal` and skipped, never thrown (ORD-13).

| Event | Effect |
| --- | --- |
| `plan.published`, `plan.revised` | `ALLOCATED` to the trip; a revision moves an allocated order to its new trip |
| `order.deferred` | `DEFERRED` to the next operating day after the run, keeping the reservation (D-H) |
| `order.unservable` | `UNSERVABLE` |
| `loading.started` | the trip's orders become `LOADING` |
| `trip.released` | `IN_TRANSIT` |
| `delivery.completed`, `delivery.failed` | `DELIVERED` or `PARTIALLY_DELIVERED`, or `FAILED` |
| `receipt.confirmed`, `receipt.auto_closed` | `RECEIVED`, or `UNCONFIRMED` |
| `redelivery.requested` | a new `CONFIRMED` order linked to the original, carrying its reservation; one per issue |
| `warehouse.order_status_changed` | a late reservation confirms a stock-unknown order; `insufficient` cancels it |

### The cutoff job (`ordering.cutoff`, 16:00 Asia/Colombo)

`CutoffJob` defers every order due on or before the day that just closed that the warehouse has still not reserved, with reason `stock_unresolved`, and publishes `order.auto_deferred` for Notification (R-STK-06, STK-03). The order stays unreserved and so is still not demand (R-STK-05). Each order is its own transaction, and "on or before" lets a missed run catch up.

## Running and verifying it locally

```bash
docker compose up -d db
cd backend
TEST_DATABASE_URL=postgresql://<user>:<password>@127.0.0.1:<port>/<dedicated_test_db> mvn test
```

The Ordering tests:

| Test | Proves |
| --- | --- |
| `ordering/domain/*Test` | the rules, with no database and an injected clock |
| `OrderingSchemaIntegrationTest` | row-level security per role, the version guard, line revisions, keyset paging, contract reads inside another module's transaction |
| `OrderingCommandIntegrationTest` | every command over HTTP through the bus, with a scripted warehouse and a movable clock: replay, stale version, parallel amends, scope denial audited, cutoff roll, closed-day roll, degraded placement |
| `OrderingConsumersIntegrationTest` | each consumer through a stand-in relay, events delivered twice and out of order, and the cutoff job one second either side of 16:00 |

To try it by hand, run the backend with no `app.warehouse.api-key`. Every placement is then `STOCK_UNKNOWN`, with the reason in `degraded`.

## Decisions and where they are recorded

- Mixed chilled and ambient is rejected, not split: [EDGE-CASES](../../architecture/EDGE-CASES.md) ORD-03.
- The cutoff is 16:00 on the calendar day before the service date: [ASSUMPTIONS](../../architecture/ASSUMPTIONS.md) A-22.
- Style and Tech cadence is guidance only: A-23.
- A redelivery is the whole order and carries the original reservation: A-24.
- A new event, `order.auto_deferred`, and a new consumer, `loading.started`: [MODULES](../../architecture/MODULES.md), Ordering and the event catalogue. The `EventCatalogueTest` and `frontend/src/shared/domain/events.ts` are updated to match.
- An order outside scope reads as `404`; a list outside scope is `403` plus audit: this document, above.

## Known gaps and who owns them

| Gap | Owner |
| --- | --- |
| No outbox relay or scheduler runs the consumers and `CutoffJob` yet. The tests stand in for the relay's contract | Platform, issue #6 |
| No real `StockPort` adapter: the reference lookup before retrying (R-STK-11), the circuit breaker and reconciliation (STK-05, 08, 11) | Warehouse, issue #7 |
| No `CatalogueQuery` implementation, so the temperature pre-check is skipped; the warehouse's single temperature per reservation still holds R-ORD-06 | Warehouse, issue #7 |
| A redelivery is the whole order, never partial (A-24) | Issues, with Ordering |
| A warehouse cancellation made outside Waypoint is only counted (`waypoint.order.cancelled_outside_waypoint`), not yet raised as an issue (STK-11) | Warehouse reconciler |
| No store or dispatcher screens for ordering yet | Frontend, after Figma |
