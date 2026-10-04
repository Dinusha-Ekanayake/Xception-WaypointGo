# #199 Shared trip hint: walkthrough

Plan and reasoning: [PLAN.md](PLAN.md). Rule: R-ORD-13 in [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md). Edge cases: ORD-16 to ORD-18 in [EDGE-CASES](../../architecture/EDGE-CASES.md).

## What was built
- **Domain:** `backend/.../ordering/domain/RideAlong.java` is pure. It takes the brand, the chosen day, the open days and the stops booked per day, and returns at most two days, busiest first, then the nearest.
- **Infrastructure:** `JdbcOrderRepository.bookedStops` counts distinct other outlets with a live order (`stock_unknown`, `confirmed`, `allocated`, `deferred`) per delivery date. It is served by `ix_orders_demand`.
- **Application:** `ordering/application/RideAlongQuery.java` runs these steps in order:
  1. `OrderDataQuery.requireOutletScope` returns `403` plus an audit row when the outlet is out of scope.
  2. The chosen day is resolved with `DeliveryDateResolver`.
  3. The open days are the ones the same rule leaves unrolled.
  4. The count is read as `Actor.SYSTEM_ID`.
- **Web:** `GET /api/orders/ride-along?outlet=&requestedDate=` returns `RideAlongView {requestedDate, deliveryDate, offered, days[{date, stopsBooked}]}`. Its frontend mirror is `RideAlongView` in `frontend/src/shared/domain/ordering.ts`.
- **Store:** `RideAlongHint.tsx` sits under the day picker on Place order. "Deliver <day>" sets the day, and the order is then placed as usual. When the read fails, the screen says suggestions are unavailable.

## Flow
1. A Tech manager picks a day.
2. The store calls `gateway.rideAlong`. The request goes through the proxy to `OrderController.rideAlong` and then `RideAlongQuery`.
3. The card lists, for example, "Thu 08 Oct · 2 other stores booked".
4. Tapping the button changes the day, and the hint for that day comes back empty.
5. Submitting sends `order:Place` with that `requestedDate`.

## Verify locally
- `mvn test -Dtest='RideAlongTest,ModuleBoundaryTest'` from `backend/`.
- `OrderingCommandIntegrationTest.aTechStoreIsToldWhichNearbyDayATripAlreadyServesItsDistrict` needs a test database (see AGENTS.md, Testing).
- `npx playwright test -c playwright.store.config.ts ride-along` from `frontend/`.
- By hand: sign in as a manager of a Tech outlet (for example OUT023, Colombo) after another Colombo Tech store has ordered for a nearby day.

## Known gaps
- No capacity check: a busy day may still be full, so the dispatcher decides at planning. The card says so.
- Planning is not told that an order came in through a hint.
