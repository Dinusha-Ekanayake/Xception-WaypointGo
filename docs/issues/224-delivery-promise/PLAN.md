# Issue #224: the delivery promise for a day a store orders ahead

## Current state

A store manager can order for a later day, but:

- **The date picker offers only three days,** so "next Friday" often cannot be chosen.
- **Nothing says how likely a day is to be kept.** Intelligence's supply probability rests on the depot-wide deferral rate, and a store cannot read `/api/ml`.
- **Publishing a plan moves the order to ALLOCATED ("Planned"),** but the store is not told, and the order does not show its stop or arrival.
- **A deferral is already told to the store** (`order.deferred`, routing).

The promise cannot be made at booking: other orders and workshop vehicles for that day are not known yet. So the store is told three things:

1. **When booking:** how likely the day is.
2. **When the plan is made:** the stop and planned arrival, or the deferral, which already exists.
3. **In between:** an early warning. Deferred to a later issue.

## Which layer owns each dependency

No new module. Each piece lives with the module that owns the fact, connected through a contract query, an outbox event or routing data.

| Concern | Owner | How it connects |
| --- | --- | --- |
| The order's stop and planned arrival on the published plan | Ordering (`PlanAllocation`, `ordering.order_stops`) | Consumes `plan.published` and `plan.revised`, as before; `OrderView` gains `plannedStop` and `plannedArrival` (additive) |
| Telling the store its stop | Notification | Routing version 5 adds `plan.published` to `store_manager` per outlet; `OnPlanPublished` adds one outlet target per stop |
| Volume booked per delivery day | Ordering contract (`OrderQuery.bookedVolumes`) | Read by Intelligence as the system, totals only |
| How likely a day is | Intelligence (`DateOutlookPolicy` pure, `DateOutlookQuery`, `GET /api/ml/outlook`) | Reads Ordering, Reference (`availableVehicles`, calendar) and its own forecasts |
| Permission | Identity | Action `ml:ReadOutlook`, granted to `WaypointStoreManager` on `wpt:ml:outlet:*`, with a new default policy version |
| Showing it | Store UI (`DateStrip`, `outlook.ts`, `planNote`) | Gateway `outlook`; the ride-along days from #211 feed the suggestion |

## Open decisions and the one chosen

- **Where the stop is stored: chosen, a table `ordering.order_stops`, keyed by order.**
  - Not columns on `ordering.orders`, which every status transition rewrites with a version guard.
  - A stop is rewritten only by the same or a later plan version for the day (ORD-20).
- **The notice's words: chosen, "Delivery planned for {day}" and "Your order is stop {n}, planned arrival {time}".**
  - Not "Confirmed": the store's label for CONFIRMED already means reserved at the warehouse.
  - "Planned arrival" is the glossary's word before departure.
- **Expected load: chosen, booked volume or the forecast's share of the week, whichever is larger (A-44).**
  - It is set against the vehicles available that day in two trips; chilled is set against reefers.
  - Thresholds are 80% and 100% (P-35).
- **The 28-day horizon: chosen, a limit of the store's date strip (P-34), not a server refusal.**
  - A refusal would add a rule the booklet does not ask for.
  - It would also break every integration test that books orders far ahead.
- **Supply probability: kept on its existing bases.** The outlook is a separate read, and inventing probabilities per status was rejected.
- **The early-warning job (an order's day turning busy after booking): deferred.** It needs an event and hysteresis. It is recorded as a gap.

## Pull requests

One pull request into `dev`:

- the stop and its notice (slice 1);
- the outlook and the date strip (slice 2).
