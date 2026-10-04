# Issue #7: Warehouse integration module: walkthrough

What was built. Rules are in [RULES-AND-POLICIES.md](../../architecture/RULES-AND-POLICIES.md) (R-STK-04 to R-STK-14), cases in [EDGE-CASES.md](../../architecture/EDGE-CASES.md), the contract in [MODULES.md](../../architecture/MODULES.md) "External warehouse integration contract". The plan is [PLAN.md](PLAN.md).

## Layers

Under `backend/src/main/java/com/waypoint/dispatch/warehouse/`:

- `contract/StockPort.java`: `placeOrder(PlacementRequest)`, `amendOrder`, `confirmReservation`. Results: `Reserved`, `PartiallyReserved`, `Insufficient`, `Rejected`, `Unavailable`; confirm gives `Confirmed`, `Expired`, `Unavailable`.
- `domain/`: `CircuitBreaker`, `PlacementDecision` (reply to result), `OrphanMatcher` and `RetryPolicy` (R-STK-11), `Placement`, `WarehouseLifecycle`, `WebhookSignature`, `CatalogueEntry`, `WarehouseCode`.
- `infrastructure/`: `WarehouseHttpClient` (the only code that speaks the warehouse JSON), `Jdbc*Repository`, `WarehouseKeyPresent`.
- `application/`: `WarehouseStockPort` and `PlacementSender`, the jobs, `WarehouseConsumers`, `WarehouseReconciler`, `InboundEvents`, `WarehouseCommandHandlers`, `WarehouseCatalogueQuery`.
- `web/`: `WarehouseController` (`/api/warehouse/catalogue`), `WarehouseWebhookController`.

Elsewhere: `platform/db/Database.asSystemSeparately`, `platform/scheduling/ScheduledJobRunner`, `ordering` (`PARTIALLY_RESERVED`, `AcceptShortfallHandler`, `OnWarehouseOrderStatusChanged` for `expired`). Migrations `20261001T1500_warehouse_integration.sql` and `20261001T1501_ordering_partial_reservation.sql`.

## Flows

- **Placement.** Ordering calls `StockPort.placeOrder`. The port records the attempt (separate transaction), calls `POST /orders` (2 s), records the answer. `201` reserved; `202` kept as `PARTIALLY_RESERVED`; `409` insufficient with per-line quantities; timeout or `5xx` is `Unavailable`, so the order saves `STOCK_UNKNOWN`. An open circuit answers `Unavailable` without a call.
- **Retry.** `warehouse.stock-unknown-retry` (every minute). Queued: send. Unknown: list the warehouse's recent orders, match by content, then adopt, wait, place, or raise as ambiguous. The result reaches Ordering as `warehouse.order_status_changed`. A late partial answer is released and reported as `insufficient`.
- **Accept shortfall.** `order:AcceptShortfall` calls the warehouse confirm; the locked quantities become the order's lines and totals. Expired: the order is cancelled, `reservation_expired`.
- **Status calls.** `order.cancelled`, `trip.released`, `delivery.completed` queue a row in `warehouse.status_requests`; `warehouse.status-requests` makes the call, retries with backoff, treats an already-reached status as done, and raises any other `409`.
- **Polling and reconcile.** `warehouse.status-poll` (2 minutes) reads held orders; `warehouse.reconcile` (15 minutes) and `warehouse:Reconcile` compare with Waypoint's orders and raise.
- **Catalogue.** `warehouse.catalogue-sync` (15 minutes) reads every page, hashes it into a version, writes only on change, publishes `catalogue.synced`; a failure keeps the last copy.
- **Webhook.** `POST /api/integrations/warehouse/events` lands the request first. Unverified is stored quarantined and answered `401`. A repeat of the same event id is a no-op. `warehouse.inbound-events` processes verified rows.

## Run and verify

```
export WAREHOUSE_API_KEY=...        # never committed
export WAREHOUSE_WEBHOOK_SECRET=... # optional
mvn spring-boot:run                 # from backend/
TEST_DATABASE_URL=... mvn verify    # from backend/
```

Without a key every placement saves `STOCK_UNKNOWN` and the jobs do nothing. Set `SCHEDULING_ENABLED=false` to stop the jobs.

## Decisions taken

Recorded in PLAN.md and in RULES-AND-POLICIES (R-STK-08, R-STK-11), MODULES, ASSUMPTIONS (A-07, A-20).

## Known gaps

- No warehouse idempotency key, client reference, webhook or `updated_since`; matching, polling and full resync stand in. The change requests to the warehouse team are still open.
- Whether the store UI blocks ordering on a stale catalogue, and the picker itself: #18. Quarantine and staleness views: #22.
- `ScheduledJobRunner` is minimal and overlaps #6; the outbox relay that delivers events at runtime is #6.
- The chaos drills for STK-04 and CAT-01 are manual.
