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

## Second part: room on the trip (decided 2026-10-04)

The first part offered a day on bookings alone, with no capacity claim. This part checks room without copying the capacity rule.

| Concern | Owner | How it connects |
| --- | --- | --- |
| Whether one more order joins a trip already going there | Planning: `TripRoom` (pure), `PlanQuery.joinsTrip` | Packs the booked loads as trips on the vehicles available that day, and asks only the registry's own load constraints (rule 5) |
| The booked loads and the store's usual order | Ordering: `bookedLoads`, `usualLoad` | Measured orders only (R-ORD-12) |
| Offering only days with room | Ordering: `RideAlong.suggest` with a room predicate, `RideAlongQuery` | Contract query into Planning |
| Saying it | Store: `RideAlongHint` | "room on the vehicle", with "estimated" in the note |

Decisions:
- **The new order's size:** it is not known before the warehouse reserves it, so it is the median of the store's latest twenty measured orders (A-45). The screen says "estimated".
- **Which rules are asked:** load rules only. Time budgets, windows and fuel need a route that a day weeks ahead does not have.
- **Degrading:** with no usual order, or Planning unable to answer, the hint falls back to bookings alone and makes no room claim (ORD-22, ORD-23).
- **Style within its weekly run: not built.** R-ORD-11 holds a scheduled order to its date, so offering Style another day would break a rule rather than add a feature.
