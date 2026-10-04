# Issue #224 walkthrough: the delivery promise

A store manager ordering ahead sees four weeks of days, each with how likely it is to be kept, and a suggested day when the chosen one is busy. When the plan is published, the store is told its stop and planned arrival, and the order shows them. The plan is in [PLAN.md](PLAN.md).

## What was built, layer by layer

### Ordering

- **Migration:** [`20261004T1900_ordering_order_stops.sql`](../../../migrations/20261004T1900_ordering_order_stops.sql). It holds one row per order: trip, stop, planned arrival, service day, plan version. Outlet RLS applies through the order.
- **Writing the stop:** [`PlanAllocation`](../../../backend/src/main/java/com/waypoint/dispatch/ordering/application/PlanAllocation.java) records it for each stop of `plan.published` and `plan.revised`. [`JdbcOrderRepository.recordStop`](../../../backend/src/main/java/com/waypoint/dispatch/ordering/infrastructure/JdbcOrderRepository.java) keeps the newest plan version (ORD-20).
- **Reading the stop:** [`OrderDataQuery.withStops`](../../../backend/src/main/java/com/waypoint/dispatch/ordering/application/OrderDataQuery.java) adds `plannedStop` and `plannedArrival` to `OrderView`. It does so only for planned, loading or on-the-road orders whose stop is for their own delivery day (ORD-21).
- **Contract:** `OrderQuery.bookedVolumes(depot, from, to)` returns depot totals per delivery day and brand, read as the system.

### Notification

- **Routing:** [`20261004T1920_notification_routing_v5.sql`](../../../migrations/20261004T1920_notification_routing_v5.sql) copies version 4 and adds `plan.published` to the store manager per outlet (R-NOT-15).
- **Consumer:** [`NotificationConsumers.OnPlanPublished`](../../../backend/src/main/java/com/waypoint/dispatch/notification/application/NotificationConsumers.java) adds one outlet target per stop, keyed `stop:{trip}:{n}`, with the facts `stopNumber` and `plannedArrival`.

### Identity

- **Migration:** [`20261004T1910_iam_store_reads_outlook.sql`](../../../migrations/20261004T1910_iam_store_reads_outlook.sql) adds the catalogue row `ml:ReadOutlook` and a new `WaypointStoreManager` version granting it on `wpt:ml:outlet:*`. It also bumps the policy generation.

### Intelligence

- **Policy:** [`DateOutlookPolicy`](../../../backend/src/main/java/com/waypoint/dispatch/intelligence/domain/DateOutlookPolicy.java) is pure. It returns on track, busy, at risk, too early or closed, with the load and a reason (A-44, P-35).
- **Query:** [`DateOutlookQuery`](../../../backend/src/main/java/com/waypoint/dispatch/intelligence/application/DateOutlookQuery.java):
  1. checks the outlet against the asker's scope, with an audited `403` outside it (ML-11);
  2. reads, as the system, booked volume (Ordering), the newest depot forecast, the calendar and the vehicles available each day (Reference);
  3. returns only a status per day (R-ML-07).
- **Endpoint:** `GET /api/ml/outlook?outlet=&from=&to=` in [`IntelligenceController`](../../../backend/src/main/java/com/waypoint/dispatch/intelligence/web/IntelligenceController.java), at most 31 days.

### Store screens

- [`DateStrip.tsx`](../../../frontend/src/roles/store/screens/order/DateStrip.tsx) replaces the three-day picker with 28 days, each with its chip. A busy or at-risk day shows a warning with "Deliver {day}". With the outlook down every day can still be chosen, and the strip says so (ML-12).
- [`outlook.ts`](../../../frontend/src/roles/store/data/outlook.ts) holds the chip, the warning and the suggestion. The suggestion prefers a day a trip already serves the district (from #211's [`RideAlongHint`](../../../frontend/src/roles/store/screens/order/RideAlongHint.tsx)), then the nearest day, the earlier on a tie.
- [`format.ts`](../../../frontend/src/roles/store/data/format.ts) `planNote` gives "Planned for Fri 9 Oct · stop 3 · planned arrival 06:10". It is shown under the order in Orders and as a notice in the order sheet.

## Flows end to end

1. **Booking:**
   1. The store opens Place order. The strip reads `GET /api/ml/outlook` for the first open day and the 27 after it.
   2. Choosing a busy day warns and offers an on-track one.
   3. The order is placed for whatever day is chosen.
2. **Publishing:** `plan.published` goes to the outbox, then to two consumers:
   - **Ordering:** the order moves to ALLOCATED and its stop is recorded.
   - **Notification:** each outlet's store manager gets "Delivery planned for {day}" in the inbox, with a push if subscribed.
   - The store's order list and sheet then show the stop and planned arrival.
3. **Revision:** `plan.revised` rewrites the stop with the newer version. The existing R-NOT-12 notice tells the outlets it reaches.
4. **Deferral:** unchanged (`order.deferred`). The stop no longer shows, because it belongs to the old day.

## How to run and verify locally

- **Backend:** `mvn test -Dtest='DateOutlookPolicyTest,IntelligenceIntegrationTest,OrderingConsumersIntegrationTest,NotificationConsumersIntegrationTest'` with `TEST_DATABASE_URL` set.
- **Frontend Node tests:** `node --test --experimental-strip-types tests/store-outlook.test.ts tests/store-plan-note.test.ts`.
- **Store browser suite:** `npx playwright test -c playwright.store.config.ts`, specifically `outlook.spec.ts` and `orders.spec.ts`.
- **By hand:**
  1. Run `scripts/dev.sh` and sign in as a store manager.
  2. Open Place order and scroll the strip.
  3. As the dispatcher, generate and publish the plan for an order's day.
  4. The store's bell and order show the stop.

## Decisions and where they are recorded

| Decision | Recorded in |
| --- | --- |
| R-NOT-15 and R-ML-07 | [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md) |
| A-44, P-34 and P-35 | [ASSUMPTIONS](../../architecture/ASSUMPTIONS.md) |
| ORD-20, ORD-21, NOT-14 and ML-09 to ML-12 | [EDGE-CASES](../../architecture/EDGE-CASES.md) |
| The outlook words | [GLOSSARY](../../architecture/GLOSSARY.md) |
| `bookedVolumes` | [MODULES](../../architecture/MODULES.md) |

## Slice 3: the early warning

- **Ordering:** `OrderQuery.openOrders` lists orders booked and not yet planned (stock unknown, partially reserved, confirmed, deferred). It returns ids, outlet, brand and day only.
- **Intelligence:**
  - [`OutlookWatchJob`](../../../backend/src/main/java/com/waypoint/dispatch/intelligence/application/OutlookWatchJob.java) runs hourly 06:00-15:00. For each depot and brand it computes the outlook once with `DateOutlookQuery.assess`, the same computation as the strip, then checks each order.
  - [`OutlookChangePolicy`](../../../backend/src/main/java/com/waypoint/dispatch/intelligence/domain/OutlookChangePolicy.java) warns only when the day is busy or at risk and worse than any warning already given (R-ML-08).
  - The warning is recorded in `ml.order_outlooks` ([migration](../../../migrations/20261004T2310_ml_order_outlooks.sql)) and published as [`order.outlook_changed`](../../../backend/src/main/java/com/waypoint/dispatch/intelligence/contract/OutlookEvents.java) in one transaction.
- **Notification:** [routing version 6](../../../migrations/20261004T2320_notification_routing_v6.sql) and `OnOrderOutlookChanged` tell the outlet's store manager "Fri 9 Oct is at risk", with the reason (R-NOT-16).
- **Store:** the order sheet of an unplanned later order shows the same warning, from the existing `outlook` read.

**Flow:**
1. A vehicle goes into the workshop for Friday.
2. At the next hour, the job finds Friday at risk for the depot.
3. Each order booked for Friday that was never warned is recorded and its event goes to the outbox.
4. The relay delivers the event to Notification, and the store's bell and push show it.
5. The next hour finds the warning recorded and sends nothing.

**Verify:** `mvn test -Dtest='OutlookChangePolicyTest,IntelligenceIntegrationTest,NotificationConsumersIntegrationTest,EventCatalogueTest'` and `e2e-store/outlook.spec.ts`. Recorded as R-ML-08, R-NOT-16, P-36, NOT-15, ML-13 and ML-14.

## Known gaps

- **Supply probability on the outlook basis:** not built by decision. A probability per status would be invented; the outlook chip says the same thing honestly.
- **The #199 remainder:** room on the truck from reserved orders, and Style within its weekly run. Owned by #199.
