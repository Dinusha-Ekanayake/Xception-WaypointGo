# #199 Shared trip hint for a store choosing a day: plan

## Current state
Each day is planned on its own. `GeneratePlanHandler` plans one service date from that day's confirmed demand and never moves orders between days. A plan exists only for a day already past its 16:00 cutoff (R-ORD-01), and a published plan is immutable (R-PLN-28). So joining another day's plan, as first proposed, is not possible. A day that is still open has no plan yet.

## Approach chosen
The hint is built from **demand already booked**, not from plans. A trip carries one brand to one district (R-PLN-01; booklet Task 2B rules 1 and 4). Other stores of the same brand and district booked for a nearby open day therefore mean a trip will go there. The store is shown that day and can choose it.

- Ordering owns everything. There is no Planning read and no engine change, so this cannot conflict with how plans are calculated while that work is still moving.
- It is advice only. The manager picks the day, and the normal `order:Place` command sends it.
- It makes no capacity claim, because the order's weight and volume are unknown until the warehouse reserves it (R-ORD-12).
- Only Tech stores see it. Fresh is daily and perishable, and Style is held to its scheduled day (R-ORD-03, R-ORD-11).

## Layers
| Layer | Owns |
| --- | --- |
| `ordering/domain/RideAlong` | Which days to offer: Tech only, within two days, open, busier than the chosen day, at most two |
| `ordering/infrastructure/JdbcOrderRepository.bookedStops` | Other outlets booked per day for one depot, brand and district: a count only |
| `ordering/application/RideAlongQuery` | Scope check as the actor, open days through `DeliveryDateResolver`, then the count as the system |
| `ordering/web/OrderController` | `GET /api/orders/ride-along?outlet=&requestedDate=` |
| `frontend/src/roles/store/screens/order/RideAlongHint.tsx` | The card on Place order, plus a line saying so when the read fails |

## Decisions
- **The count is read as the system.** A store manager's scope cannot see other outlets' orders. The scope check runs first, as the actor, and the result is one number per day that names nobody (recorded as R-ORD-13).
- **Reach is two days either side, at most two suggestions.** It is a hint, not a planner.

## PR breakdown
One PR: backend, store UI, tests and docs.
