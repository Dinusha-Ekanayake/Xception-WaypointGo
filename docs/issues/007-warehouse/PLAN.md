# Issue #7: Warehouse integration module (`warehouse` schema): plan

Written before code, per AGENTS.md "Issue Documents". What was built is in [WALKTHROUGH.md](WALKTHROUGH.md).

## Where `dev` stood

- Only `warehouse/contract` and `UnconfiguredStockPort` existed. No HTTP client, no circuit breaker, no warehouse tables, no scheduler, no outbox relay (issue #6).
- `StockPort.placeOrder(orderRef, lines)` carried no depot, so it could not place in the right warehouse (A-07).
- The live warehouse API was re-probed on 2026-10-01 (reads, then one throwaway order per path, A-20). Present: `temp_requirement`, totals on orders, `201`/`202`/`409`, `confirm`, `PUT /status`. Absent: `Idempotency-Key`, a client reference or `?ref=` filter, a webhook, `updated_since`.

## Which layer owns each dependency

| Concern | Owner | Why there |
| --- | --- | --- |
| Placement outcome mapping, circuit breaker, orphan matching, retry decisions, lifecycle, HMAC | `warehouse/domain` | Pure; time is a parameter; tested with no database |
| Warehouse HTTP, JDBC repositories | `warehouse/infrastructure` | Only place that knows the external JSON or SQL |
| `StockPort` adapter, jobs, consumers, reconciler, inbox, commands | `warehouse/application` | Opens transactions, decides |
| Catalogue and webhook endpoints | `warehouse/web` | Routing only |
| Partial reservation as an order state, `AcceptShortfall` | `ordering` | Ordering owns the order status vocabulary |
| Separate-transaction system write | `platform/db/Database` | Only `platform` touches `JdbcTemplate` |
| Job timetable and lease | `platform/scheduling/ScheduledJobRunner` | Minimal until #6 lands |

## Decisions

1. **Keep a `202` instead of cancelling it** (revises D-F and R-STK-08; chosen 2026-10-01). The store sees the shortfall and the other warehouse's stock and accepts or cancels. Amendment stays strict.
2. **Match a lost placement by content**, because the warehouse has neither an idempotency key nor a client reference (R-STK-11). Exactly one match is adopted; several are raised.
3. **Record the attempt before sending, in its own transaction.** The warehouse call cannot roll back with Ordering's transaction, so its record must not either.
4. **Hand-written circuit breaker**, no new dependency. Three consecutive failures open it for 30 s, then one trial call.
5. **Reconciler raises, never corrects**, except an orphan with no Waypoint order (STK-08). Open decision 4 resolved as recommended.
6. **Webhook is HMAC-SHA256 over `timestamp.rawBody`**, off when no secret is set. Polling is the fallback and stays as a backstop. Open decision 2 resolved as recommended.
7. **Stock is per depot** (Kandy to KDY, Peliyagoda to PLG, never split; A-25). Open decision 1.
8. **Catalogue sync is a full read every 15 minutes**; stale after 2 hours, shown on screen. Whether ordering blocks on staleness is left to #18.

## Work breakdown

1. Contract: `StockPort` with `PlacementRequest`, `PartiallyReserved`, `confirmReservation`; frontend mirrors.
2. Domain and its unit tests.
3. Migrations: `warehouse.*`, and Ordering's `partially_reserved`.
4. Infrastructure and application: client, port, jobs, consumers, reconciler, inbox, commands.
5. Ordering: `PARTIALLY_RESERVED`, `AcceptShortfall`, expiry consumer.
6. Integration tests against a stub warehouse; docs; log entry.
