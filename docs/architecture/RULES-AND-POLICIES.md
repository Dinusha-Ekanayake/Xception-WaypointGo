# Rules and policies

Every operational rule Waypoint Dispatch must obey, where it comes from, whether anyone has actually defined it, and what we decide where nobody has.

This is the single reference for behaviour. [MODULES.md](MODULES.md) says which module enforces a rule; [EDGE-CASES.md](EDGE-CASES.md) says what happens when it is violated; this document says what the rule *is* and who says so.

## This document and the edge case register

They are not the same thing and the split matters:

| | [RULES-AND-POLICIES.md](RULES-AND-POLICIES.md) | [EDGE-CASES.md](EDGE-CASES.md) |
| --- | --- | --- |
| Answers | What must be true | What happens when it cannot be |
| Example | R-EXE-04, a vehicle arriving early waits until the window opens | EXE-05, arrival before the window, with detection and test |
| Changes when | The business changes its mind | We find a new way for reality to break the rule |

Every edge case traces to a rule; a rule may have several edge cases or none. A rule with no edge case is one nobody has asked "and what if not?" about yet.

## Source hierarchy

When sources disagree, the higher row wins, and the disagreement is recorded in section 8 rather than silently resolved.

Values that will change are not listed here. They live in the parameter register in [ASSUMPTIONS.md](ASSUMPTIONS.md), because a rule is "the trip must fit the Fresh budget" while 270 minutes is a parameter. Beliefs we have not proved live in the same document's assumption register.

| Rank | Source | Standing |
| --- | --- | --- |
| 1 | `check_allocation.py` | **Executable truth.** The supplied validator. Whatever it enforces is non-negotiable, because it is the scoring gate |
| 2 | Challenge booklet | **Binding.** Stated requirements, including rules the validator does not check |
| 3 | Supplied datasets | **Factual.** What the data actually contains, which sometimes contradicts a written claim |
| 4 | Team requirements draft | **Product intent.** Our own additions, valid unless they contradict 1 to 3 |
| 5 | This document | **Policy.** What we decide where the sources are silent |

## Rule identifiers and status

`R-<AREA>-<n>`. Areas: `ORD` ordering, `STK` stock, `PLN` planning, `LOD` loading, `EXE` execution, `RCP` receipt, `FLT` fleet, `CAL` calendar, `NOT` notification, `PLT` platform.

| Status | Meaning |
| --- | --- |
| **Validated** | Enforced by `check_allocation.py`. Must be implemented exactly |
| **Binding** | Stated in the booklet. Must be implemented; the validator does not check it |
| **Team** | From our requirements draft |
| **Policy** | Nobody defined it. We decide. Recommendation given |
| **Conflict** | Sources disagree. See section 8 |

---

## 1. Ordering

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-ORD-01 | Orders for the next run close at **16:00**. Orders after the cutoff wait for the following run | Booklet | Binding |
| R-ORD-02 | A Fresh outlet may hold **two orders for the same delivery day**: one dry, one chilled. They are separate orders and must never be merged or de-duplicated | Booklet | Binding |
| R-ORD-03 | Style orders weekly for a scheduled delivery day, larger before seasonal peaks | Booklet | Binding |
| R-ORD-04 | Tech orders as needed, often a single large item | Booklet | Binding |
| R-ORD-05 | An order carries a temperature requirement, unit count, weight and volume | Booklet | Binding |
| R-ORD-06 | Chilled and ambient never share one order, because vehicle eligibility is decided per order | Derived from R-ORD-02 | Policy |
| R-ORD-07 | The cutoff is evaluated on the **server clock in `Asia/Colombo`**, never a client timestamp | Policy | Policy |
| R-ORD-08 | An order for a non-operating date rolls to the next `is_operating` date, shown to the store before confirmation | Policy, from R-CAL-01 | Policy |
| R-ORD-09 | ~~Store managers cannot place orders on holidays~~ **Withdrawn 2026-09-30 (D-I):** placement is allowed and R-ORD-08 rolls the date | Team draft | Withdrawn |
| R-ORD-10 | An order whose outlet window is shorter than its brand and dock service allowance is rejected at capture, because it can never be served | Policy | Policy |
| R-ORD-11 | Scheduled orders are mandatory for their scheduled date | Team draft | Team |
| R-ORD-12 | Order weight and volume at **order level** are authoritative for every capacity decision. Product lines are descriptive | Policy, from catalogue accuracy | Policy |
| R-ORD-13 | A Tech store choosing a delivery day is told which open days within two either side already carry more stops for its brand and district (a trip goes there anyway), as a count of other stores and never which. **Advice only**: it never moves an order, and claims room on the trip only as R-ORD-14 allows. Fresh and Style are never offered another day (R-ORD-03, R-ORD-11); moving Style within its weekly run was considered and rejected, because R-ORD-11 holds a scheduled order to its date. Issue #199 | Team, from booklet trip rules | Team |
| R-ORD-14 | A shared-trip day is offered only when the store's usual order (the median of its latest twenty measured orders) would join a trip already going there, and the screen says the room is estimated. Planning answers (`PlanQuery.joinsTrip`): the booked measured orders of the brand and district are packed as trips on the vehicles available that day, and only the registry's load rules are asked (temperature, van access, weight and volume with the validator's epsilon), so capacity keeps one definition. A store with no measured order yet, or a Planning that cannot answer, gets the days on bookings alone with no room claimed. Issue #199 | Rule 5, R-ORD-12, Policy | Policy |

## 2. Stock and the external warehouse

The warehouse is a separate system. Documentation: [triathon-warehouse-simple.vercel.app/docs](https://triathon-warehouse-simple.vercel.app/docs). **Revised 2026-10-01** from the published documentation; the read endpoints were re-probed live the same day, the write endpoints were not (placing an order locks shared stock).

```
base    https://triathon-warehouse-simple.vercel.app/api/v1
auth    x-api-key: <key>   or   Authorization: Bearer <key>      (401 without)
errors  {"error":{"code","message"}}
        400 bad JSON · 401 missing or revoked key · 404 not found
        409 insufficient stock, invalid status change, reservation expired · 422 validation

Warehouses  KDY Kandy, PLG Peliyagoda. A name is accepted wherever a code is (case-insensitive)

GET   /usage               ?range=24h|7d|30d                 usage of the caller's keys
GET   /warehouses          units available and reserved, order counts by status
GET   /products            ?brand= &q= &warehouse= &sort=stock &low_stock=true &page= &limit<=100
GET   /products/:id
PATCH /products/:id        {"warehouse", "stock": n} absolute, or {"warehouse", "adjust": +n|-n}
POST  /products/:id/transfer  {"from", "to", "quantity"}     409 if the source is short
GET   /orders              ?warehouse= &status=reserved|pending|shipped|delivered|cancelled|expired
GET   /orders/:id          items:[{product_id, quantity, requested_quantity}]; reserved orders add shortfall
POST  /orders              {"warehouse", "items":[{product_id, quantity}]}
                           201 all lines taken, status pending
                           202 some lines short: available units locked, status reserved, until
                               confirm, cancel or expires_at; body lists shortfall per line and
                               what the other warehouse has
                           409 nothing available
POST  /orders/:id/confirm  reserved -> pending with the locked quantities; 409 reservation_expired
PUT   /orders/:id/status   reserved -> pending | cancelled, pending -> shipped | cancelled,
                           shipped -> delivered. Cancelling returns units to the order's warehouse

Product  product_id, brand, unit_weight_kg, unit_volume_m3, basis, verified_real_sku,
         stock:{KDY:{available,reserved}, PLG:{...}}, total_available, total_reserved, updated_at
Order    order_id, status, warehouse, source, total_weight_kg, total_volume_m3, expires_at,
         created_at, item_count (lines, not units)
```

**Verified live 2026-10-01 (issue #7):** `temp_requirement` (`chilled`, `ambient`, `mixed`) is on every product and order, `POST /orders` returns totals and temperature, `PUT /status` and `confirm` behave as documented (A-20). **Still absent:** an idempotency key on `POST /orders`, a client reference or `?ref=` filter on orders, a webhook, and `updated_since` on products (ignored). Those keep R-STK-11 content matching, polling and full catalogue resync in place.

### What this means for the design

| ID | Rule | Status |
| --- | --- | --- |
| R-STK-08 | **Creating the order is the reservation**, in the depot's own warehouse. `201` takes every line (`pending`); `202` locks what is available as `reserved` with a 15-minute expiry and reports the shortfall and the other warehouse's stock; `409` means nothing was available. **Revised 2026-10-01 (issue #7):** a `202` is kept, not cancelled. The order is saved `partially_reserved`; the store accepts it (`order:AcceptShortfall`, the warehouse `confirm` call) or cancels it, and an expiry cancels it. Amendment stays strict: a partial answer is released and refused. Owner: #7 | Verified live 2026-10-01 |
| R-STK-09 | **Cancelling restores stock.** `PUT /orders/:id/status` to `cancelled` is the compensating action, from `reserved` or `pending` | Verified for `pending` |
| R-STK-10 | The warehouse runs **its own order lifecycle**: `reserved -> pending | cancelled | expired`, `pending -> shipped | cancelled`, `shipped -> delivered`. It is one-way; an invalid transition is `409`. A `reserved` order left alone expires and its units return | Documented; `reserved` and `expired` not yet probed |
| R-STK-11 | **`POST /orders` is not idempotent.** No idempotency key and no client reference exist, so a blind retry creates a second order and decrements stock twice. The adapter records every attempt (lines, time) in `warehouse.placements` before sending, in a transaction of its own. After an unknown outcome it looks for the order **by content**: same warehouse, same products and requested quantities, created within the match window of an attempt, not already claimed. Exactly one match is adopted; none is placed again only after the window passes; several are raised, never guessed | Policy, critical |
| R-STK-12 | Stock is writable: `PATCH /products/:id` sets or adjusts it. This is how a stock manager's approval or adjustment (R-STK-02) is applied | Verified |
| R-STK-13 | Waypoint's order state machine and the warehouse's are **two state machines for one real order**. Keeping them aligned is a saga, and every transition can fail independently | Policy |

**Two lifecycles, mapped.** Waypoint owns the delivery workflow; the warehouse owns stock. The mapping is deliberately narrow:

| Waypoint state | Warehouse state | Transition trigger |
| --- | --- | --- |
| `confirmed`, stock reserved | `pending` | `POST /orders` returned `201` |
| placement rejected, per-line availability shown (D-F) | none, or `cancelled` | `POST` returned `409`, or `202` and the adapter cancelled the `reserved` order at once |
| `stock_unknown` | unknown | timeout, `5xx` or no key configured |
| `in_transit` | `shipped` | trip released |
| `delivered` | `delivered` | driver recorded the outcome |
| `cancelled` | `cancelled` | compensating call, stock restored |

Waypoint never keeps a warehouse order in `reserved`: it has an expiry, and D-F holds nothing on a short line. Current warehouse data (2026-10-01): all 97,321 orders are `delivered` and there are zero `reserved`, `pending`, `shipped`, `cancelled` or `expired`. The lifecycle is therefore **untested in their seed data**; the first order we create will be the first `pending` one, which is a reason to exercise it early rather than at integration time.

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-STK-01 | ~~An order with insufficient stock is locked and the stock manager is notified~~ **Withdrawn 2026-09-30 (D-F):** a short line rejects placement outright; the store sees per-line availability and resubmits | Team draft | Withdrawn |
| R-STK-02 | ~~When the stock manager approves or adjusts, processing continues~~ **Withdrawn 2026-09-30 (D-F):** there is no held state to release | Team draft | Withdrawn |
| R-STK-03 | ~~An adjustment requires a reason and an audit trail~~ **Withdrawn 2026-09-30 (D-F):** Waypoint never adjusts a placed order; the store amends it | Team draft | Withdrawn |
| R-STK-04 | Stock availability is queried through the warehouse API, never held as a second copy here | Policy | Policy |
| R-STK-05 | When the warehouse is unreachable, the order state is `stock_unknown`, shown as degraded. **Never assume stock exists** | Policy | Policy |
| R-STK-06 | Stock unresolved at the cutoff auto-defers with reason `stock_unresolved` | Policy | Policy |
| R-STK-07 | Waypoint stores the warehouse order reference (the reservation), the order-level totals and temperature the warehouse returned, and descriptive lines. The warehouse model never leaks past the adapter | Policy | Policy |
| R-STK-14 | A deferral keeps the warehouse reservation; only cancelling the Waypoint order cancels the warehouse order (D-H) | Team decision 2026-09-30 | Policy |

**Resolved 2026-09-30 (A-18, D-E):** the store picks products from the catalogue, so a Waypoint order carries lines at capture and the warehouse checks them at placement. The warehouse returns weight, volume and temperature, and those order-level values stay authoritative for capacity.

## 3. Planning and allocation

### 3.1 The seven validated feasibility rules

Enforced by `check_allocation.py`. These are exact.

| ID | Rule | Detail |
| --- | --- | --- |
| R-PLN-01 | **Brand and district** | All orders sharing a vehicle and trip belong to one brand and one district |
| R-PLN-02 | **Refrigeration** | `temp_requirement = chilled` requires `temp = reefer` |
| R-PLN-03 | **Vehicle access** | `parking_constraint = van_only` requires `type = van` |
| R-PLN-04 | **Home depot** | A vehicle serves only outlets of its own depot |
| R-PLN-05 | **Whole orders** | One order to one vehicle and one trip. No splitting |
| R-PLN-06 | **Capacity** | Trip volume and weight must not exceed the vehicle caps. The validator allows a tolerance of `1e-6`; we compare with the same epsilon, never with bare floating point |
| R-PLN-07 | **Trips and time** | At most **2** trips per vehicle per day, fitting the budgets in 3.2 |

The booklet values are upper bounds for a scored submission. An effective-dated operational rule set may set a lower trip or time ceiling for future service dates; it cannot raise those values above the scored baseline. The plan stamps the version it used, and publication rejects a draft whose rule set is no longer in force.

**What a trip actually contains.** R-PLN-01 to R-PLN-05 together fix the composition, and the historical data agrees exactly:

| Dimension | Rule | Observed in 25,198 training routes |
| --- | --- | --- |
| Brand | One per trip | 0 routes mix brands |
| District | One per trip | 0 routes mix districts |
| Vehicle | One per trip | 0 routes mix vehicles |
| Outlets | **Many per trip**, all in that brand and district | typical trip is several stops |
| Temperature | Not constrained by any rule | **0 routes mix temperatures**, see A-04 |

So a Fresh trip visits several Fresh outlets in one district. It does not mix Style or Tech, and it does not cross districts. Mixing chilled and ambient orders on one reefer is **permitted** by the rules but never happens in the supplied history, which is why it is recorded as assumption A-04 rather than as a rule.

Also enforced: every order must carry exactly one decision, `served` or `deferred`; a served order names a vehicle and a `trip_id` of 1 or 2; a deferred order names neither; a vehicle with status `in_workshop` cannot be allocated.

### 3.2 Trip time

```
trip_minutes = depot_to_district_freeflow_min
             + inter_stop_freeflow_min x (stops - 1)
             + sum(service_allowance_min for each stop)
```

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-PLN-08 | The return journey is **not** added. The budgets already allow for it | Booklet, validator | Validated |
| R-PLN-09 | Fresh budget **270 min**, window 03:30 to 08:00, applied to the total of that vehicle's Fresh trips | Booklet, validator | Validated |
| R-PLN-10 | Style and Tech budget **480 min** combined, trading day, checked separately from Fresh | Booklet, validator | Validated |
| R-PLN-11 | A vehicle may run one Fresh and one Style trip against their separate budgets, but still only **two trips total** | Booklet, validator | Validated |
| R-PLN-12 | Travel times come from `district_travel`, keyed by **district alone**. The validator indexes it by district, and the data has exactly one row per district | Validator, dataset | Validated |

### 3.3 Rules the validator does not check

Binding for the delivered system even though Task 2B does not score them.

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-PLN-13 | Every outlet has a **delivery window**; arrival must fall inside it. A vehicle arriving early waits until the window opens | Booklet | Binding |
| R-PLN-14 | Mall outlets accept deliveries only inside the mall's fixed access window | Booklet | Binding |
| R-PLN-15 | Fresh deliveries must arrive before stores open at 08:00, although individual outlet windows differ | Booklet | Binding |
| R-PLN-16 | Each vehicle has a **weekly fuel quota** in litres; route distance consumes it | Booklet | Binding |
| R-PLN-17 | Waypoint operates **Monday to Saturday** | Booklet | Binding |
| R-PLN-18 | Driver availability is **not** a separate constraint when allocating the existing fleet | Booklet | Binding |
| R-PLN-19 | When demand exceeds capacity the dispatcher decides what defers and **records the reason** | Booklet | Binding |
| R-PLN-20 | Outlets already skipped must be identifiable, so the same outlet is not unserved on consecutive runs | Booklet | Binding |
| R-PLN-29 | **Effective window = intersection of the outlet window and the mall window.** A `mall_dock` outlet must satisfy both. In the supplied data the two are identical for all 12 mall outlets, so the intersection is currently a no-op, but they are separate facts and a mall may change its access hours independently | Booklet, dataset | Binding |
| R-PLN-30 | An outlet whose effective window is shorter than its service allowance is **unservable**, not merely tight. Detected at reference import (R-ORD-10) and again at allocation | Policy | Policy |
| R-PLN-31 | **One temperature class per trip.** A reefer carries chilled or ambient on a trip, never both (refrigeration on or off); it may run a chilled trip and an ambient trip on the same day | Team decision 2026-09-30 (D-J, A-04) | Policy |

### 3.4 Planning policy we own

| ID | Rule | Recommendation |
| --- | --- | --- |
| R-PLN-21 | **Deferral priority.** The booklet requires a recorded reason but prescribes no order | **Revised 2026-10-01 (issue #9, decision 1).** A lexicographic, versioned decision table in `planning.policy_versions`, not a weighted score, so every deferral can be explained by rank. Highest first: prior skip (P-12), Fresh, chilled, strict access (mall dock or a window under P-17), brand cadence (P-18), earliest closing window, longest outbound distance (a tie-break only, R-PLN-25), largest volume, longest unserved, then order reference and id. A depot-scoped version wins over the global one (POL-08) |
| R-PLN-22 | **Oversized order.** An order exceeding every vehicle's capacity | Mark `unservable`, surface for a manual split decision. Never defer silently forever. R-PLN-05 forbids automatic splitting |
| R-PLN-23 | **Fuel week boundary** | Monday to Sunday, matching `iso_week` |
| R-PLN-24 | **Does fuel include the return leg?** The booklet excludes the return from *time* but says route distance consumes fuel | Include the return distance. Fuel is physical; the time budget exclusion is a planning simplification, not a statement about diesel. **Confirmed 2026-09-30 (D-K, A-03, Q7 closed)** |
| R-PLN-25 | **Longest distance first** conflicts with delivery windows | **Windows win.** Distance ordering is a tie-break inside a feasible sequence, never a constraint override |
| R-PLN-26 | **Frozen goods.** The schema allows `frozen`; the supplied data contains only `ambient` and `chilled` | Treat `frozen` as reefer-requiring, identically to `chilled` |
| R-PLN-27 | **Balanced routes without unnecessary looping** (team) | Interpreted as: minimise stop count variance across trips in a district, and never revisit a district within one trip. Not a hard constraint |
| R-PLN-28 | **Published plan immutability** | A published plan is never edited. A change creates a new version that supersedes it |
| R-PLN-32 | **Improving a plan never trades priority** (issue #92). The engine's second pass plans the reefers again as a whole | A changed plan is kept only when it is better **by rank**: the highest ranked order that only one of the two plans serves decides (R-PLN-21). So no order is deferred to serve a lower ranked one, and the pass can never make a plan worse. It stops on a fixed node budget that is part of the engine version, so the same inputs give the same plan on any machine; the clock is a safety stop and the plan says when it fired. Deferrals are explained against the final plan (R-PLN-19) |
| R-PLN-33 | **A swap is whole or not at all.** A dispatcher trades a served order for a deferred one on its trip | `plan:Swap` defers the served order (R-PLN-19, with the dispatcher's name) and places the other in its trip in one new draft version. The vehicle's whole day is checked against the registry; a failure refuses the swap with every failing rule and changes nothing. An optional `orderIds` fixes the trip's stop order in the same command (R-PLN-13 judges it with the swap), so a swap and a new stop order are one change, never two versions; `GET /api/plans/preview/swap` takes the same order as `orders` |
| R-PLN-34 | **A dispatcher's stop order is judged like the engine's.** The timeline sequences stops by window; a person may fix the order instead (`plan:ReorderStops`) | The timeline times the stops in the order given and the registry judges the result (R-PLN-09, R-PLN-10, R-PLN-13, R-PLN-29). An order that breaks a window or a budget is refused with the rule. A fixed order is kept by later edits of the draft; it is not a way around a rule |
| R-PLN-35 | **A hand decision on an order says who and when** (rule 8). Placed, swapped, taken off, kept deferred, held | Each is a mark on the order's allocation: its source, the dispatcher and the time. The engine's own answers carry none. A mark falls away when it stops describing the order (a deferral once the order is placed; a lock once it is off its trip) |
| R-PLN-36 | **A regenerate can keep what a dispatcher decided.** Orders placed, swapped in or locked, and orders kept deferred | With `keepDecisions` the engine puts the placed and locked orders back on their vehicle and trip first, judged by the whole registry, and does not offer a place to the orders kept deferred; it then places the rest. A decision that no longer passes refuses the whole regenerate and names the order and the rule, and the draft stays as it was. The second pass over the reefers does not run when decisions are kept, because it could move them, and the plan does not claim it did. Without `keepDecisions` the engine decides everything again |
| R-PLN-37 | **A saved plan is never edited.** The engine's plan of a generate, a plan a dispatcher saved, and the draft a regenerate replaced | Written once, whole, with the checks and who decided each order; the module role can insert and read, never update or delete. Restoring one is a new draft that puts its placements back (an order the engine placed is marked as restored, a dispatcher's decision keeps their name) and lets the engine place what arrived since. Comparing reads two plans and changes neither |
| R-PLN-38 | **Cost never trades priority** (planning v2). After the rules plan, a cost stage looks for the same orders on fewer vehicles and less fuel | Adaptive large neighbourhood search from the rules plan (`CostReplan`). A plan replaces another only when it serves a better set by rank (R-PLN-21), then uses fewer vehicles, then fewer litres; so no order the rules served is deferred for cost. Every placement is checked by the registry and the result by `ValidatingEngine` (PLN-12). Seeded from the orders and stopped on an iteration count that is part of the engine version, so the same day gives the same plan; the clock is a safety stop and the plan says when it fired. When it changes the plan, the rules plan is saved beside the draft as a `rules` snapshot to compare and choose |
| R-PLN-39 | **When the cost stage runs.** A simple day needs no search | It runs when the rules plan deferred an order, or its trips are on average less full than `engine.cost.min.utilisation` (0.85 by default). It does not run when the dispatcher kept decisions (it would move orders around them) or when `engine.cost.enabled` is 0. It has its own time budget, `engine.cost.budget.ms` (5000 by default), counted from when the rules plan is ready, so a day whose reefer search used the whole engine budget is still optimised. All are optional rule-set parameters; the run says which reason applied |
| R-PLN-40 | **Outlet GPS orders stops, when it is exact.** Reference data gives each outlet an exact point or only its district's | With an exact point for every stop of a trip, the shortest path by road (great-circle distance x 1.3, nearest neighbour then 2-opt) is tried beside the two window orders, and the drive between stops is timed from the points at the district's inter-stop speed. With any stop at district level, stops are ordered by window and timed by the district's inter-stop minutes, as before. The booklet's trip-time formula for the 270 and 480 minute budgets and the fuel formula (R-PLN-24) are unchanged, so Task 2B validation is unaffected |
| R-PLN-41 | **Generating a plan is a queued job.** The engine runs for seconds; a command must not | `plan:Generate` refuses a closed or published day at once, otherwise records a job and answers with it. One job per depot and day is active at a time, so a second Generate gets the same job. A worker claims jobs with `FOR UPDATE SKIP LOCKED` and a lease; it reads the day in one short transaction, runs the engine with none open, and writes the draft in another, after checking the demand fingerprint: if the orders changed meanwhile it runs again (three attempts), then fails with the reason. A worker that dies lets its lease lapse and another claims the job. The `orders.closed` consumer queues the same way |
| R-PLN-42 | **A trip is edited whole.** A dispatcher sets what one trip carries and in what order | `plan:EditTrip` names the trip and its orders in order: orders left out are deferred with the dispatcher's name (R-PLN-19), orders named from the deferred list or another trip join it, and an empty list removes the trip. The vehicle's whole day is judged; any failing rule refuses the edit with every rule and changes nothing. `GET /api/plans/preview/trip` judges the same list first, so the screen enables Accept only when every rule passes |

## 4. Loading

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-LOD-01 | The loader sees the stop sequence so goods load in an order that supports unloading | Booklet | Binding |
| R-LOD-02 | The loader flags **short, damaged, doesn't fit, or missing** items **before** the vehicle leaves. Short is one item with some of its units missing (Figma 03, decision 2026-10-01) | Booklet and confirmed product decision | Binding |
| R-LOD-03 | Loading lists must not go stale when the plan changes | Booklet | Binding |
| R-LOD-04 | The loader sees only loading-ready vehicles, starts loading, marks finish, and reports destroyed items | Team draft | Team |
| R-LOD-05 | **Truck interchange:** if the assigned truck becomes unavailable at the dock, another may take the trip | Team draft | Team |
| R-LOD-06 | An interchange revalidates the **whole trip** against the substitute: capacity, temperature, access, depot, time budget, fuel. It is recorded as history, never an update of the vehicle column: Planning publishes a new plan version (`plan.revised`) | Policy | Policy |
| R-LOD-07 | A trip is released only when every allocated item has a recorded check on the current plan version. A flagged item is a recorded exception and does not by itself block release | Confirmed product decision | Binding |
| R-LOD-08 | ~~Mall deliveries are prioritised in the loading order~~ **Withdrawn 2026-09-30 (D-L):** loading follows the reversed stop sequence only | Team draft | Withdrawn |
| R-LOD-09 | If no compatible substitute exists, the trip defers as a unit and its orders carry forward with identity | Policy | Policy |
| R-LOD-10 | Release requires the loader to confirm doors sealed, orders secured and driver present. No reefer reading or seal number is required | Confirmed product decision | Binding |
| R-LOD-11 | One loader holds a trip at a time; only that holder writes. Hand back retains the names and times on earlier checks. A hold with no accepted command from its holder for 30 minutes lapses and another loader may take the trip (decision 2026-10-01) | Confirmed product decision | Binding |

## 5. Execution

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-EXE-01 | Drivers record delivery outcomes and proof of delivery so disputes do not depend on memory | Booklet | Binding |
| R-EXE-02 | Work must be recordable **offline** and reconcile when connectivity returns | Booklet | Binding |
| R-EXE-03 | Interactions are designed for use when safely stopped | Booklet | Binding |
| R-EXE-04 | **Early arrival waits.** Service starts at `max(arrival_time, window_open_time)`, never at arrival. Waiting time is recorded separately from service time | Booklet | Binding |
| R-EXE-05 | A late arrival is **still delivered**. Lateness is recorded with a reason. **Except at a mall outlet:** after its effective window closes the mall does not accept goods (R-PLN-14, R-PLN-29), so the stop is a failed delivery with reason `mall_window_closed`, and the dispatcher decides on a redelivery (C-7, EXE-20) | Booklet | Binding |
| R-EXE-13 | Merged into R-EXE-04, which it duplicated | Booklet | Merged |
| R-EXE-14 | **Lateness means arrival after `window_close_time`**, not arrival after the planned time. A stop can be later than planned and not late, or on time and late | Booklet | Binding |
| R-EXE-15 | Lateness has a cost even though the goods are delivered: receiving staff may have moved to other duties, and a Fresh outlet may miss morning sales. Lateness is surfaced to the dispatcher and the store, not buried in a log | Booklet | Binding |
| R-EXE-06 | The driver reports vehicle status: available, on trip, at workshop, fault | Team draft | Team |
| R-EXE-07 | The driver reports road faults and delays | Team draft | Team |
| R-EXE-08 | The driver is notified which dock to load at, and when loading and unloading finish | Team draft | Team |
| R-EXE-09 | A report option is available at every stage | Team draft | Team |
| R-EXE-10 | **Server time is authoritative.** Device time is stored for forensics only. A record whose two clocks differ by more than five minutes is marked `timing_uncertain` (A-31) | Policy | Policy |
| R-EXE-11 | A device limitation, such as a denied camera, never blocks completing the work. The outcome records the reason and is flagged lower-evidence | Policy | Policy |
| R-EXE-12 | **Returns are out of scope.** A failed delivery records the outcome and raises an issue; goods disposition is recorded but no return workflow exists | Team draft | Team |
| R-EXE-16 | **Only the owner reviews a held offline write.** Discarding or redoing a conflict or refusal is done by the account that made it, on its own device; no role reads or settles another account's operations (decision D-O, 2026-10-02). Enforced by row-level security on `sync.operations` | Team decision | Team |
| R-EXE-17 | **A held write is redone, never resent or merged.** A redo is the same command on the version the device now sees, under a new id, recorded before the `sync:Resolve` that names it; the held operation becomes `RESOLVED` with `replaced_by`. Discarding needs a reason. Only a conflict can be redone, because a refusal broke a rule and would be refused again | Policy | Policy |
| R-EXE-18 | Position batches contain 1-100 chronological fixes inside latitude 5.8-9.9 and longitude 79.5-82.0, at most six coordinate decimals. Accept old offline observations, reject more than five minutes ahead of receipt time. Optional accuracy and speed are nonnegative; heading is 0-359.9 degrees. Exact repeated observations are deduplicated; stationary fixes at later times remain heartbeats. Errors and audit snapshots never disclose coordinates or trails | Policy, #161; enforced by `PositionPolicy` in `delivery:RecordPositions` | Policy |
| R-EXE-19 | Accuracy above 200 metres is low quality and cannot advance last-seen time. A trip in progress is offline after ten minutes without a good fix; finished trips are not labelled offline. Time is a supplied parameter | Policy, #161; enforced by `PositionPolicy` in `PositionsQuery` | Policy |
| R-EXE-20 | Positions are written only by the driver assigned to the vehicle on each point's own service date (an offline trail from yesterday still lands; another day never does). A dispatcher reads the vehicles of their depots. A store manager reads a trip's positions only while that trip has a pending or arrived stop at one of their outlets, and loses them once those stops are done. Enforced by row-level security on `execution.vehicle_positions`, not in a controller | Policy, #161 | Security |
| R-EXE-21 | Full trails are kept for the service day plus 30 days (P-31), then thinned to one point per stop event (the fix nearest each arrival and each completion). Sync's stored copy of a position command loses its points after the same window. The execution role holds no UPDATE or DELETE on positions; thinning runs through a definer function only it may call | Policy, #161, approved by product 2026-10-03 | Retention |

## 6. Receipt

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-RCP-01 | The store confirms what arrived and reports issues | Booklet | Binding |
| R-RCP-02 | The store is given an expected arrival time so staff can be scheduled | Booklet | Binding |
| R-RCP-03 | The store receives clear notice when an order is deferred | Booklet | Binding |
| R-RCP-04 | Driver proof and store acceptance are separate records; neither overwrites the other | Policy | Policy |
| R-RCP-05 | An unconfirmed receipt auto-closes after a configured window as `unconfirmed`, never silently `delivered` | Policy | Policy |
| R-RCP-06 | The store sees the probability that an order can be supplied on its scheduled day. Answered by Intelligence with its basis (planned, deferred, deferral rate), deterministic for now: `GET /api/ml/orders/{id}/supply-probability` (issue #16) | Team draft | Team |
| R-RCP-07 | **Loaded but not received.** When a passing loading check, a completed delivery and a short receipt disagree, the system raises a shortage investigation linked to all three records. It is **never auto-resolved in favour of either party**, and no record is amended to make them agree. A short receipt the loader's own flags explain in full (every short unit flagged at the dock for that product) and to which the store added nothing is not a contradiction: it is already the loader's issue, and no second one is raised (decision 2026-10-02, Figma store manager "06-5") | Policy | Policy |
| R-RCP-08 | Each link in the custody chain is attributed: who checked it at the dock, who delivered it, who received it. That chain is the evidence, and it is what replaces memory in a dispute | Booklet, policy | Policy |
| R-RCP-09 | **Handover PIN.** When the store answers a receipt it is given a one-time four-digit PIN, shown once. The driver of the vehicle on that date types it on their own phone, which is evidence the handover happened at the store. **It is never a gate:** the delivery, the trip and the receipt do not wait for it, and a handover nobody confirms is shown as not confirmed, not as a failure. Five wrong entries lock it, it expires after 15 minutes, and the store can issue a new one, which starts the count again. Only a salted hash is held. A confirmed handover is final | Decision 2026-10-02, Figma store manager "06b" | Binding |

### 6a. Operational issues (issue #13)

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-ISS-01 | **One lifecycle.** OPEN to ASSIGNED (reassign allowed) to RESOLVED to CLOSED; OPEN or ASSIGNED may be CANCELLED with a reason. Resolved, closed and cancelled are history: a recurring problem is a new issue | Policy, settles conflict B17 | Policy |
| R-ISS-02 | Every resolution and cancellation records an action from a fixed vocabulary (replacement, redelivery, write-off, no fault found, other), a reason, a person and a time. There is no return action (R-EXE-12, A-10) | Rule 8 | Policy |
| R-ISS-03 | An issue names a depot, a description and at least one subject: order, trip, delivery, receipt, shortfall or vehicle. A vehicle alone is valid, because fault and disruption reports carry no trip. A named outlet must belong to the depot | Policy | Policy |
| R-ISS-04 | A replacement answers only a loading shortfall about that trip and order, and `shortfall.resolved` names the shortfall when the issue was raised from one. A redelivery answers only an issue where **nothing reached the outlet**: a failed delivery or a stock discrepancy. A redelivery is the whole order (A-24), so for a disputed, damaged or late delivery it would ship the goods twice; those are resolved as write-off, no fault found or other, and the store reorders what is missing | Policy | Policy |
| R-ISS-05 | A redelivery is requested at most once per issue and never for a past date. Ordering creates one order linked to the original (B17, A-24) | Policy | Policy |
| R-ISS-06 | **Escalation timer.** An issue still OPEN and unassigned past its severity's deadline (P-20 to P-23) is stamped escalated once, with a history row, a metric and `issue.escalated` for Notification to route to the depot's dispatchers | Policy | Policy |
| R-ISS-07 | **Who may raise what is policy data.** `issue:Raise` is evaluated on `wpt:issue:type:<TYPE>`, and the role policies name each role's types; scope is the issue's depot, the outlet it names, or for a driver the depot of the vehicle they drive today (R-IAM-13, `driverVehicleOn`). Store: damaged goods, late delivery, other. Loader: loading shortfall, damaged goods, other. Driver: failed delivery, vehicle fault, road disruption, late delivery, damaged goods, other. Dispatcher: all | Policy, issue #13 decision 2 | Policy |
| R-ISS-08 | An issue is assigned only to someone scoped to its depot, so it never lands with a person who cannot see it | Policy | Policy |

## 7. Fleet, calendar and notification

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-FLT-01 | Fleet: 60 vehicles, 12 refrigerated trucks, 40 dry-box trucks, 8 vans of which 4 refrigerated, giving 16 reefer-capable | Booklet, dataset | Binding |
| R-FLT-02 | Each vehicle operates from its assigned depot | Booklet | Binding |
| R-FLT-03 | A vehicle in `in_workshop` cannot be allocated | Validator | Validated |
| R-FLT-04 | Vehicle status changes affect the next planning run, never retroactively | Policy | Policy |
| R-CAL-01 | Operating dates come from `calendar.csv`; non-operating days and holidays are excluded from the schedule | Booklet, team | Binding |
| R-CAL-02 | Paydays, festivals, weekends and monsoon affect demand or travel time | Booklet | Binding |
| R-CAL-03 | Beyond the supplied calendar range, generate Monday to Saturday as operating, mark generated, and alert before exhaustion | Policy | Policy |
| R-REF-01 | A store manager may change their own outlet's delivery window, dock type and contacts (`reference:UpdateOutletDetails`). The change is laid over the current reference version from `ref.outlet_details`, so the next plan, the run sheet and the loading manifest read it and an import cannot discard it; a published plan does not change. A blank window or dock returns to the published one. A mall bay belongs to the building: a store can neither choose one nor leave one, and a mall outlet's window must still overlap the mall's (R-PLN-29). Scope is the outlet or its depot (R-IAM-28). Like calendar overrides, the details are not versioned | Policy, decision 2026-10-03 | Policy |
| R-REF-02 | Geographic reference imports require one sourced point for every depot and district, unique known kind/code keys, decimal latitude within -90 to 90 and longitude within -180 to 180, and at most six fractional digits. Depot precision is exact or approximate, district precision is centroid, supplied outlet precision is exact. An outlet without supplied coordinates receives its district centroid with district precision, never jitter. The geo file participates in the content hash and atomic reference publication; historical versions remain unchanged | Policy, issue #161 | Policy |
| R-REF-03 | An administrator creates a depot, outlet or vehicle through a command with a unique natural id. The complete reference snapshot is validated and published as a new immutable version. The created row is retained as a managed source and overlaid on later CSV imports. A new depot starts without districts, outlets or fleet; it needs a supplied approximate or exact point and explicit IAM depot access before it appears in scoped reads | Policy, issue #22 | Policy |
| R-CAL-04 | A person may override one day's operating status. The override carries an actor, a reason and a timestamp, lives in `ref.calendar_overrides` so a reference import cannot discard it, and is never marked generated: a decided day is not an assumed one | Policy | Policy |
| R-NOT-01 | Warehouse to store manager: insufficient quantity | Team draft | Team |
| R-NOT-02 | Loader to dispatcher: damage or other problem | Team draft | Team |
| R-NOT-03 | Driver to dispatcher: report messages | Team draft | Team |
| R-NOT-04 | Dispatcher to store manager: automatic message when deferred, with a note | Team draft | Team |
| R-NOT-05 | A notification is never sent inside the request transaction. Intent commits with the state change; delivery is a separate tracked attempt | Policy | Policy |
| R-NOT-06 | Read state is set-once: a notification goes from unread to read and never back, and keeps the time it was first read. Marking read therefore takes no `expectedVersion`; `MarkAllRead` covers only what existed when the person looked (`upTo`) | Policy, issue #14 | Policy |
| R-NOT-07 | The person who caused an event is not notified of it | Policy, issue #14 | Policy |
| R-NOT-08 | A driver is pushed only trip-level events (plan published, plan revised, trip released), all of which happen before departure. Anything else routed to a driver reaches the inbox only | Policy, issue #14 | Policy |
| R-NOT-09 | Who hears about which event is data: a versioned routing table in `notification.routing_rules`, one version current, changed by a new version and never edited in place. The module may read it, not write it | Policy, issue #14 | Policy |
| R-NOT-10 | When a trip is released, the depot's other loaders are told it left; the loader who released it is the actor and is not (R-NOT-07). Routing version 2 | Team decision, issue #118 | Team |
| R-NOT-11 | When a trip is released, each outlet on it is told its stop number and expected arrival, so the store can schedule staff to receive (booklet p6, R-RCP-02). Routing version 2 | Booklet, issue #118 | Binding |
| R-NOT-12 | A revised plan tells only what it changed. The drivers of the trips a driver would see differently (another vehicle, number, departure or stop time), the outlets reached on another trip or at another time, and the depot's loaders when any trip differs | `plan.revised` carries `changedTripIds` and `affectedOutletIds`, worked out by `PlanDiff` against the plan it replaced. An outlet whose order the revision drops is told by the deferral, not twice. An event written before these were recorded tells every trip, as it always did. Routing version 3 |
| R-NOT-13 | A dispatcher's message about an order the plan could not serve goes to the store managers of its outlet, in the dispatcher's own words | `plan.store_contacted` routes to the outlet; the message and who sent it are on the event (rule 8). Routing version 3 |
| R-NOT-14 | A message on a trip's thread is told to the depot's dispatcher whoever it is for, so every message appears under the dispatcher's bell, and to the loaders, the driver or the outlets only when it is for them. The author is never told (R-NOT-07). A report made from an issue is not told again (R-MSG-05). Routing version 4 | Product decision 2026-10-04 | Policy |
| R-NOT-15 | A published plan tells each outlet on it its own stop and planned arrival for the day, once per stop: "Delivery planned for {day}", "Your order is stop {n}, planned arrival {time}". The order shows the same stop and time while it is planned, loading or on the road, and an older plan version never overwrites a newer one's (issue #224). Routing version 5 | Product decision 2026-10-04 | Policy |
| R-NOT-16 | When a day a store already booked worsens to busy or at risk before its plan, the outlet's store manager is told: "{day} is busy" or "is at risk", with the reason, once per order, day and status (R-ML-08). The day itself reads as the glossary writes it, and from routing version 6 so does R-NOT-15's title | Product decision 2026-10-04 | Policy |

---

## 7a. Authorization policy

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-IAM-01 | An action is `<module>:<Verb>`; a resource is `wpt:<module>:<type>:<id>`. Both accept `*` wildcards | Policy | Policy |
| R-IAM-02 | Evaluation order is fixed: default Deny, any matching Deny wins, then Allow, else Deny | Policy | Policy |
| R-IAM-03 | An action a policy names must exist in `iam.action_catalogue`. A typo like `order:Plase` is rejected when the policy is written, because it would otherwise deny silently forever and look exactly like a permissions bug nobody can find | Policy | Policy |
| R-IAM-04 | A policy version is immutable. Changing a policy creates a new version and moves the default, so a decision taken under an older policy stays explainable | Policy | Policy |
| R-IAM-05 | Exactly one default version per policy, enforced by a partial unique index | Policy | Policy |
| R-IAM-06 | A request with no specific resource is normalised to `*`, so an unscoped request matches only an unscoped grant and never satisfies a scoped one | Policy | Policy |
| R-IAM-07 | A missing context key fails its condition rather than passing it. Treating absence as satisfied would grant access whenever a caller simply forgot to supply the value | Policy | Policy |
| R-IAM-08 | A denial names the statement that caused it. A denial with no explanation is indistinguishable from missing data | Policy | Policy |
| R-IAM-09 | Effective access is `policy AND scope`. Policies decide actions; the scope tables decide rows, and row-level security enforces them | Policy | Policy |
| R-IAM-10 | Any policy change clears the whole policy cache. A cache clever about invalidation is a cache that eventually serves a revoked permission | Policy | Policy |
| R-IAM-11 | Sessions are opaque and server side. Revocation must be immediate, which a self-contained token cannot do | Policy | Policy |
| R-IAM-12 | A wrong password and an unknown account return the same message and spend the same hashing work | Policy | Policy |
| R-IAM-13 | A vehicle has at most one driver on any date, enforced by an exclusion constraint on `(vehicle_id, validity)`. A check followed by an insert is a race; a constraint is not. Ranges are half open, so one assignment ending the day another begins is not an overlap | Policy | Policy |
| R-IAM-14 | A driver assignment is ended by shortening its range, never by deleting the row, so who held a vehicle on a past date stays answerable | Policy | Policy |
| R-IAM-15 | A password reset and a disable both revoke every session in the same transaction. A reset that leaves old sessions alive protects nobody, because the reason to reset is usually that someone else has the account | Policy | Policy |
| R-IAM-16 | An account cannot disable itself. An administrator holding the only admin policy would otherwise lock everyone out permanently | Policy | Policy |
| R-IAM-17 | A scope may only name a depot or outlet that exists. A scope naming nothing is a permanent silent denial, which is the same class of mistake as an uncatalogued action | Policy | Policy |
| R-IAM-18 | The last active dispatcher of a depot cannot be disabled, moved to another role or taken off that depot. A depot with nobody who can publish its plan is found out the morning nothing is dispatched | Policy | Policy |
| R-IAM-19 | A change that takes access away (role, scope revoked, device retired) revokes the affected sessions in the same transaction, as a disable and a password reset already do (R-IAM-15) | Policy | Policy |
| R-IAM-20 | Sign-in is throttled on three counters inside one window (P-13): an identity from one address, one address across identities, one identity across addresses. A failure is recorded and audited in a committed transaction; a success marks the pair's failures cleared and deletes nothing | Policy | Policy |
| R-IAM-21 | Every change that can alter a decision moves `iam.policy_generation` in the same transaction. A decision is served from cache only at the generation it was loaded at, and a command re-evaluates inside its own transaction against the generation its snapshot sees | Policy | Policy |
| R-IAM-22 | A device identity is granted by an administrator, never claimed by a client. A sign-in naming an unregistered or retired device is refused (422) after the credentials are checked | Policy | Policy |
| R-IAM-23 | A command kind with no handler, and a kind whose action is not marked `implemented`, are refused as 403 with an audit row. Policy, account, scope, assignment and device administration are all commands, each guarded by the version of what it changes | Policy | Policy |
| R-IAM-24 | The session token is stored only as its SHA-256. The cookie is `SameSite=Strict`, `HttpOnly`, kept for the session's absolute lifetime, and behind HTTPS is `Secure` and named with the `__Host-` prefix. A state-changing request that names an `Origin` must name the host it was sent to | Policy | Policy |
| R-IAM-25 | On a shared loader device, loading writes are made by the operator who entered their PIN, named in the command's `actingUserId`. A device with no operator is locked and every loading write is refused | Confirmed product decision | Binding |
| R-IAM-26 | An operator PIN is four digits. Five wrong tries pause PIN entry for that person for five minutes | Confirmed product decision | Binding |
| R-IAM-27 | A loader may switch with their PIN while offline against the crew list the device downloaded: a PBKDF2 verifier per member, never the PIN, valid for 12 hours and wiped at sign-out, with the same five-try pause kept on the device. On reconnect the switches are replayed into the operator history before any queued work, only for crew of that device, in order, after the history the server has, and audited as offline. A four-digit PIN is recoverable from its verifier; that risk is accepted because the list reaches only a supervisor-signed-in loader device (decision 2026-10-01) | Confirmed product decision | Binding |
| R-IAM-28 | A reference read keyed by a depot, an outlet or a vehicle is `policy AND scope` like any other read, checked by `ReferenceScope` because reference data is one shared snapshot with no row for row-level security to hide. A depot's lists are for accounts scoped to that depot; one outlet for its own scope, its depot's, or a driver whose vehicle today works from that depot; one vehicle for its depot's scope or its driver today (R-IAM-13). The version and the calendar are the same for every depot and are not scoped. A refusal is `403` with an audit row. The store policy's `reference:Read` covers outlets and calendar days only | Policy, issue #5 decision 9 | Policy |
| R-IAM-29 | Every schema, table and function is owned by `waypoint_migrator`, which is not a superuser and holds no `BYPASSRLS`. `migrate` applies every file as that role once a database has been handed over, whoever it logged in as, and with `row_security` off so a statement that forced row-level security would filter is refused instead. A migration can therefore not grant `SUPERUSER` or `BYPASSRLS`, install an untrusted extension, or read past a forced policy without lifting the force in the same file | Policy, issue #5 decision 8 | Policy |
| R-IAM-30 | MCP is opt-in: a dedicated opaque server-side session plus `mcp:Connect` AND the normal business read policy AND SQL scope, and since #177 AND the connection's client scope (R-IAM-34). Its immutable credential purpose admits only curated GETs, the two write steps of R-IAM-35 and ending its own connection, never `/api/commands`; cookie placement never upgrades it to a browser session. Context and policy are refreshed on each request, the loader actor is the personal account, and access decisions are audited. The adapter selects safe fields, withholds personal ones (R-IAM-36), labels inferred product identifiers and fails visibly on dependency or size limits | Policy, issue #87 [plan](../issues/087-readonly-mcp/PLAN.md), issue #177 [plan](../issues/177-mcp/PLAN.md) | Policy |
| R-IAM-31 | Remote MCP uses personal consent, registered exact redirects, one-time two-minute codes and PKCE S256. Codes and opaque sessions bind the client and configured resource; a missing resource defaults to the configured one and anything else must match, at exchange and each remote read. The scopes granted are those of R-IAM-34, shown on the consent page and stored on the code and the session. Replaying a code revokes its session. No refresh tokens or browser-session upgrade; blank public URL disables remote OAuth; a blocked client gets no code or token (R-IAM-37). Authentication bookkeeping is owned by Identity outside the business command bus | Policy, issue #87 [plan](../issues/087-readonly-mcp/PLAN.md) | Policy |
| R-IAM-32 | A person changes their own display name and phone number with `iam:UpdateOwnProfile`, granted on `wpt:iam:user:self`. The command always acts on the actor's own account whatever the payload names, takes the account's `expectedVersion`, keeps the phone as its digits with an optional `+` (7 to 15) and never puts the new values in the audit reason. The email (the sign-in name) and the password stay with the administrator | Policy, decision 2026-10-03 | Policy |
| R-IAM-33 | MCP requests are rate limited per credential and per OAuth client (P-30), counted in shared rows of `iam.mcp_rate_windows` so a second replica is no way around the limit. Over the limit is `429` with `Retry-After` to the next one-minute window; the first refusal in a window is audited, the rest counted. Ending one's own connection is never limited. The limit is checked before any policy work | MCP specification 2025-11-25, Tools: servers must rate limit tool invocations; issue #139 | Policy |
| R-IAM-34 | An MCP connection holds client scopes that only narrow: effective access is client scope AND user policy AND row scope. `waypoint.read` is every read; `orders.read`, `plans.read`, `loading.read`, `deliveries.read`, `receipts.read`, `issues.read`, `audit.read` and `policies.read` each one area; `issues.write` the confirmed issue writes. Asking for nothing grants `waypoint.read issues.write` (decision 2026-10-03); unknown words are ignored and a request naming no known scope is refused. A connection made before scopes keeps `waypoint.read`. A read outside the scope is `403` with the rule and an audit row; a remote write without its scope is answered `insufficient_scope` with the wider scope so the client can ask the person | MCP specification 2025-11-25, Authorization: scope challenge; issue #177 | Policy |
| R-IAM-35 | MCP writes are only `raise_issue` and `assign_issue`, never ones that cancel, publish or replace work (`plan:Generate` cancels the open draft, so it is excluded). Each needs its write scope and the person's `mcp:Write` grant, on by default for the four field roles and turned off by attaching `WaypointMcpNoWrites`. A write is two steps: prepare stores the exact command with a fixed command id and returns a preview and a confirmation; confirm spends it once, from the same connection, within two minutes, and submits the stored command through the command bus under the person's own policy, scope and `expectedVersion`. The client cannot change the command at confirm. Prepare and confirm each count against P-32. The payload is never in an audit reason | Issue #177; MCP specification, Tools: clients should confirm sensitive operations | Policy |
| R-IAM-36 | Personal fields (who raised or is assigned an issue, its description and resolution note, a receipt's note and confirmer) reach an MCP client only with `mcp:ReadPersonal`, attached to no one by default. Without it they are removed at any depth and the result says `withheld: ["personal"]`. Every other field is operational or internal (versions and audit plumbing) and is returned | Issue #177 | Policy |
| R-IAM-37 | An administrator with `mcp:BlockClient` blocks a registered MCP client by command, with the client's version and a reason; every connection it holds is revoked in the same transaction and it gets no new code or token until `mcp:UnblockClient`. MCP for one person or role is turned off with no deployment by attaching `WaypointMcpBlocked` (Deny `mcp:*`) | Issue #177 | Policy |
| R-IAM-38 | An administrator with `mcp:ManageClients` sees, per role and per person, which MCP switches (`WaypointMcpBlocked`, `WaypointMcpNoWrites`, `WaypointMcpPersonalReader`) are attached directly, and turns them on and off with the existing `iam:AttachPolicy` and `iam:DetachPolicy` commands under the policy's version; the change applies on the person's next request. With `mcp:RevokeUserConnections` (resource `wpt:mcp:user:<id>`) they end every MCP connection one person holds, by command with a reason, leaving that person's browser sessions alone. Ending connections that are already gone succeeds with a count of 0. The views are outside the MCP route boundary, so an assistant can never list or end anyone's connections | Issue #177 | Policy |

## 7b. Platform: events, jobs and audit (issue #6)

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-PLT-01 | An event is delivered at least once and applied by each consumer once. A consumer records `(consumer, event)` in the same transaction as its own change, so a redelivery re-runs only the subscribers that had not finished | Policy | Policy |
| R-PLT-02 | Events of one aggregate are delivered in the order written: an event waits while an earlier one of its aggregate is pending, failed or processing. A dead-lettered event does not block its successors, and a replayed one arrives after them | Policy | Policy |
| R-PLT-03 | A failed delivery is retried with exponential backoff (2 s doubling to a 5 min cap, 20% jitter) and dead-lettered after 8 attempts (`RELAY_MAX_ATTEMPTS`), with its last error. Replay is an administrator command (`platform:ReplayEvent`) that resets the attempts. Dead events are never purged | Policy, issue #6 | Policy |
| R-PLT-04 | A scheduled job runs on one instance at a time under an advisory-lock lease, records each run, and counts a refused lease as a duplicate run | Policy | Policy |
| R-PLT-05 | Audit partitions are created ahead of need (current month plus three), and an alert fires when fewer than two future months exist. Partitions older than the retention period are detached, never dropped | Policy, P-26 | Policy |
| R-PLT-06 | Every command's answer is a receipt, including a deterministic rejection (validation, constraint, conflict, version conflict, not found): a retry gets the same rejection and the handler does not run again. A denial, a rate limit, a timeout and an unavailable dependency are never stored, because a retry exists to get past them | Policy | Policy |
| R-PLT-07 | An audit row records the command, the target, a redacted outcome and, where the handler supplies it, a redacted state before. Snapshots are redacted by field name before they are written, bounded in size, and never carry a payload with personal data | Policy | Policy |

---

## 7d. Messaging (issue #136)

ADR-004: a conversation is a thread anchored to one subject; the first subject is a trip.

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-MSG-01 | **Membership and visibility.** A trip's thread belongs to the depot's dispatcher (overseen, not merely granted), the depot's loaders, the vehicle's driver on the service date, and the store managers of its outlets. The dispatcher reads every message. Anyone else reads messages for everyone, messages addressed to them, and their own. Enforced by row-level security; a thread outside scope is 403 plus an audit row | Product decision 2026-10-04 | Policy |
| R-MSG-02 | **Who writes to whom.** The dispatcher writes to the driver, the loaders, one outlet on the trip, or everyone. The driver writes to the dispatcher or to an outlet on the trip. A loader and a store manager write to the dispatcher only | Product decision 2026-10-04 | Policy |
| R-MSG-03 | **A report is for the dispatcher alone.** A loader's, a driver's or a store's report, typed or spoken, is addressed to the dispatcher and seen by nobody else but its author. The dispatcher reads reports and does not make them | Product decision 2026-10-04 | Policy |
| R-MSG-04 | **Posting window.** A trip's thread opens when its plan is published and takes posts until the end of the day after its service date; then it is read only | Our policy | Policy |
| R-MSG-05 | **Reports from the field arrive once and are not told twice.** Every raised issue about a trip is posted on its thread as a report, once per event, naming the role that reported it. It publishes nothing, because `issue.raised` already notified the dispatcher | Product decision 2026-10-04 | Policy |
| R-MSG-06 | **Voice notes.** A message or report may be a voice note: WebM, Ogg, MP4/AAC or MP3, at most 2 MB and 120 seconds, uploaded under the phone's id before the message and heard only by who may see that message, and by its author | Product decision 2026-10-04 | Policy |

## 7e. Demo runtime (issue #231)

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-DEMO-01 | Demo starts OFF. Only an administrator can enable it or change settings, with a reason and the current row version. Disabling clears the business-clock offset | Product decision 2026-10-04 | Policy |
| R-DEMO-02 | Business time may move by at most seven days. Authentication, sessions, command receipts, audits and scheduler leases use real time; a database outage falls back to real time | Product decision 2026-10-04 | Policy |
| R-DEMO-03 | A day reset records its run and delegates preparation to Reference, Identity and Ordering commands. It accepts only an empty operating date within seven days and never deletes operational rows or changes account credentials | Product decision 2026-10-04 | Policy |
| R-DEMO-04 | A simulated vehicle drives only a released trip of an assigned driver, and each point is a real `delivery:RecordPositions` command sent as that driver, so Execution's own checks apply. The simulator never records an arrival, a delivery, proof or receipt: people do. Straight legs between known locations are shown as such, never as road navigation | Product decision 2026-10-04 | Policy |

## 8. Conflicts found

Seven places where the sources disagree. C-1, C-2, C-3, C-5, C-6 and C-7 are settled; C-4 remains open.

| # | Conflict | Detail | Recommendation |
| --- | --- | --- | --- |
| **C-1** | **Reefers carrying ambient goods** | Team draft: "Refrigerated vehicles exclusively transport chilled items." Booklet: "Refrigerated vehicles may also carry ambient goods." **Settled by the data**: of 9,734 reefer routes in training, 4 carried only ambient orders, and no ambient vehicle ever carried chilled | **Resolved: follow the booklet.** Reefers may carry ambient. Recorded as A-01. In practice it is rare, 0.04% of reefer routes, so the planner treats reefers as chilled-first and uses them for ambient only when it improves the plan. **Refined 2026-09-30 (D-J):** one temperature class per trip, R-PLN-31 |
| **C-2** | **"Each order mapped to the nearest available department"** | Team draft implies a depot choice. The data makes depot a **function of district**: all 120 outlets have `outlet.depot` equal to their district's depot, with zero exceptions, and R-PLN-04 forbids serving another depot's outlets | **Closed 2026-10-01 as inert** (issue #9): depot is a function of district, so there is no depot to choose; `HomeDepot` (R-PLN-04) covers it |
| **C-3** | **GPS coordinates** | Team draft: "the operational dataset includes newly added GPS coordinates for all delivery outlets." The shipped `outlets.csv` has nine columns and none is latitude or longitude | Sourced depot locality points and district centroids added by #161. Exact outlet coordinates remain absent; district fallback is approximate. Distance ordering still uses `district_travel`, not point geometry |
| **C-4** | **"Reefers are assumed to run at full capacity"** | Team draft. Meaning unclear: it could mean reefers are always loaded to capacity, that their capacity is not derated when chilled, or that refrigeration does not reduce usable volume | Undecided. See question Q2 |
| **C-5** | **Longest distance first** | Team draft requires longest distances dispatched earliest. Delivery windows and the Fresh 03:30 to 08:00 window may require the opposite | Windows win; distance is a tie-break. Recorded as R-PLN-25 |
| **C-6** | **No ordering on holidays** | Team draft. The booklet does not restrict *placing* an order, only *delivering* on a non-operating day. Blocking placement stops a store preparing Monday's order on a Sunday | **Resolved 2026-09-30 (D-I):** allow placement, roll the delivery date to the next operating day, and show the store the date it will arrive. R-ORD-09 withdrawn |
| **C-7** | **A late arrival at a mall** | R-EXE-05 (booklet): a late arrival is still delivered. R-PLN-14 (booklet): a mall accepts deliveries only inside its fixed access window. After the window closes the goods physically cannot be unloaded | **Resolved 2026-10-02 (issue #13):** R-EXE-05 holds for ordinary outlets. At a mall outlet, arrival after the effective window is a failed delivery (`mall_window_closed`), raised as an issue; the dispatcher decides on a redelivery, which carries a skip so the next plan serves it first (EXE-20, ORD-15) |

## 7c. Intelligence: predictions and models (issue #16)

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-ML-01 | A model is never called inside an operational transaction. A published plan only queues a scoring; the job calls the model service with no transaction open and stores the answer in a transaction of its own | ADR-001, Policy | Policy |
| R-ML-02 | Every stored prediction and forecast names the model that produced it (`name@version`) or `deterministic`, and is written once, so a result can be reproduced and a bad model traced | Policy, issue #16 | Policy |
| R-ML-03 | One model per kind is active. Activating a model returns the kind's previous one to registered; a retired model is never activated again; retiring needs a reason | Policy, issue #16 | Policy |
| R-ML-04 | A model answers only when serving is configured, a model of that kind is active, and the service reports exactly that model. Anything else is the deterministic answer, marked degraded with the reason, and the plan says it was scored without the predictor | Rule 9, Policy | Policy |
| R-ML-05 | Predictions are advice. Allocation keeps the booklet's service allowances and travel times, which the validator checks (R-PLN-08); learned times never change a plan | Booklet, Policy | Policy |
| R-ML-06 | The training export keeps waiting for the window apart from service time, so an early arrival never teaches a long service (EXE-18) | R-EXE-04, Policy | Policy |
| R-ML-07 | The date outlook is advice for a store choosing a delivery day, never a promise or a block: any day can still be ordered, and the plan made the afternoon before decides. It is built from the depot's totals (booked volume, the forecast share, the vehicles available that day) and returns a status per day, never another outlet's orders. The outlet is checked against the asker's scope first; outside it is `403` plus an audit row (issue #224) | Rule 7, Policy | Policy |
| R-ML-08 | A booked, unplanned order's day is watched from tomorrow to two weeks ahead, hourly from 06:00 to 15:00, with the same outlook the store saw when booking. A warning is given only when the day is busy or at risk and worse than any warning already given for that order and day, recorded and published in one transaction, so a day that eases and worsens again, a rerun or a second replica never tells the store twice. A deferral to another day is watched afresh; a planned order is no longer watched (issue #224) | Rule 8, Policy | Policy |

---

## 9. Where each rule is enforced

One rule, one enforcement point, so a change has one home.

| Rule group | Enforced in | Tested by |
| --- | --- | --- |
| R-ORD-01, 07, 08, 10 | Ordering domain, cutoff and window policy | Domain unit tests, clock injected |
| R-ORD-02, 06, 12 | Ordering domain, order construction | Domain unit tests |
| R-STK-* | Warehouse adapter and Ordering application | Integration with a stubbed port |
| R-PLN-01 to 12 | Planning constraint registry | Property tests plus `check_allocation.py` on the real submission |
| R-PLN-13 to 20 | Planning constraint registry | Property tests |
| R-PLN-21 to 28 | Priority policy and publication gate, versioned | Domain unit tests |
| R-PLN-32 | Improvement pass keeps rank | `ScarceFleetReplanTest`, `PeakDayAllocationTest.theSecondPassServesMoreAndEveryOrderItDropsIsOutrankedByOneItAdds` |
| R-PLN-33 to 35 | Swap, stop order, hand decisions | `PlanningRunTest` (swap, keep, lock, reorder), `PlanningDecisionsIntegrationTest` |
| R-PLN-36 to 37 | Kept decisions, saved plans | `PinnedDecisionsTest`, `PlanDiffTest`, `PlanningSnapshotsIntegrationTest` |
| R-PLN-38 to 39 | Cost stage keeps the served set; when it runs | `CostReplanTest`, `PeakDayAllocationTest.theCostStageServesTheSameOrdersOnFewerVehiclesAndLessFuel`, `PlanningCommandIntegrationTest` |
| R-PLN-40 | GPS stop order, district fallback | `StopTravelTest` |
| R-PLN-41 | Queued generation | `PlanGenerationQueueIntegrationTest`, `PlanningRevisionIntegrationTest.closingTheDayGeneratesADraftAsTheSystem` |
| R-PLN-42 | Trip edited whole | `PlanningRunTest`, `PlanningDecisionsIntegrationTest`, `plan-decisions.spec.ts` |
| R-LOD-* | Loading domain and departure gate | Integration tests |
| R-EXE-* | Execution domain and offline queue | Browser tests |
| R-RCP-* | Receipt domain, `ReceiptAutoCloseJob`, `ReceiptAnswerHandler` | Domain unit tests, integration tests with the job run at chosen instants |
| R-ISS-* | Issues domain, `IssueCommandHandler`, role policies (R-ISS-07), `IssueEscalationJob` | Domain unit tests, integration tests through the command bus |
| R-FLT-*, R-CAL-* | Reference data module | Domain unit tests |
| R-ML-*, R-RCP-06 | `ModelGate`, `PlanScoringJob`, `ForecastJob`, `ModelHandlers`, `SupplyPolicy`, `ml.*` constraints | Domain unit tests (`IntelligenceDomainTest`), integration tests against a stub model service (`IntelligenceIntegrationTest`), the model service's own tests (`ml-server/tests`) |
| R-NOT-* | `NotificationPolicy`, `Notifier`, `PushDeliveryJob`, `NotificationHandlers`, the routing table | Domain unit tests (`NotificationPolicyTest`, `DeliveryTest`), integration tests with events delivered and the push job run at chosen instants |

Rules with status **Validated** get a second gate: our allocation output is run through the supplied `check_allocation.py` in CI, so a regression against the scoring rules fails the build rather than the submission.

---

## 10. Questions for the team

| # | Question | Blocks |
| --- | --- | --- |
| ~~Q1~~ | ~~C-1: do reefers carry ambient goods?~~ **Answered:** yes, one temperature class per trip (C-1, R-PLN-31) | closed |
| **Q2** | C-4: what does "reefers run at full capacity" mean? | Whether reefer usable volume is derated |
| ~~Q3~~ | ~~How is availability queried?~~ **Answered:** `stock` per product, enforced by `POST /orders` with `409 insufficient_stock` | closed |
| ~~Q4~~ | ~~An API key, so response schemas can be specified~~ **Answered:** contract recorded in section 2 | closed |
| ~~Q5~~ | ~~C-2: what does "nearest available department" mean, given depot is fixed by district?~~ **Answered:** no depot choice exists (C-2 closed) | closed |
| ~~Q6~~ | ~~C-6: block order placement on holidays, or roll the delivery date?~~ **Answered:** roll the date (D-I) | closed |
| ~~Q7~~ | ~~R-PLN-24: does the weekly fuel quota include the return leg?~~ **Answered:** yes (D-K) | closed |
| ~~Q8~~ | ~~Do Waypoint orders carry product lines at capture?~~ **Answered:** yes, descriptive lines checked by the warehouse at placement (D-E) | closed |
