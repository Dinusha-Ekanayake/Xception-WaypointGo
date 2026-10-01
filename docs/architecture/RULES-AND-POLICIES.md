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

`R-<AREA>-<n>`. Areas: `ORD` ordering, `STK` stock, `PLN` planning, `LOD` loading, `EXE` execution, `RCP` receipt, `FLT` fleet, `CAL` calendar, `NOT` notification.

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

Still absent: an idempotency key on `POST /orders`, a client reference on an order, and temperature anywhere. Those keep R-STK-11, D-E's temperature gap and the change requests in MODULES open.

### What this means for the design

| ID | Rule | Status |
| --- | --- | --- |
| R-STK-08 | **Creating the order is the reservation**, in the depot's own warehouse. **Revised 2026-10-01:** it is no longer all-or-nothing. `201` takes every line; `202` locks what is available as `reserved` with an expiry and reports the shortfall; `409` means nothing was available. To keep D-F (a short line rejects placement, nothing is held), the adapter must cancel a `202` reservation at once and answer `Insufficient` with the per-line quantities the `202` reported. Owner: #7 | Documented, not yet probed |
| R-STK-09 | **Cancelling restores stock.** `PUT /orders/:id/status` to `cancelled` is the compensating action, from `reserved` or `pending` | Verified for `pending` |
| R-STK-10 | The warehouse runs **its own order lifecycle**: `reserved -> pending | cancelled | expired`, `pending -> shipped | cancelled`, `shipped -> delivered`. It is one-way; an invalid transition is `409`. A `reserved` order left alone expires and its units return | Documented; `reserved` and `expired` not yet probed |
| R-STK-11 | **`POST /orders` is not idempotent.** No idempotency key exists, so a blind retry creates a second order and decrements stock twice. The adapter must reconcile by query before retrying, never replay | Policy, critical |
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
| R-EXE-05 | A late arrival is **still delivered**. Lateness is recorded with a reason | Booklet | Binding |
| R-EXE-13 | Merged into R-EXE-04, which it duplicated | Booklet | Merged |
| R-EXE-14 | **Lateness means arrival after `window_close_time`**, not arrival after the planned time. A stop can be later than planned and not late, or on time and late | Booklet | Binding |
| R-EXE-15 | Lateness has a cost even though the goods are delivered: receiving staff may have moved to other duties, and a Fresh outlet may miss morning sales. Lateness is surfaced to the dispatcher and the store, not buried in a log | Booklet | Binding |
| R-EXE-06 | The driver reports vehicle status: available, on trip, at workshop, fault | Team draft | Team |
| R-EXE-07 | The driver reports road faults and delays | Team draft | Team |
| R-EXE-08 | The driver is notified which dock to load at, and when loading and unloading finish | Team draft | Team |
| R-EXE-09 | A report option is available at every stage | Team draft | Team |
| R-EXE-10 | **Server time is authoritative.** Device time is stored for forensics only | Policy | Policy |
| R-EXE-11 | A device limitation, such as a denied camera, never blocks completing the work. The outcome records the reason and is flagged lower-evidence | Policy | Policy |
| R-EXE-12 | **Returns are out of scope.** A failed delivery records the outcome and raises an issue; goods disposition is recorded but no return workflow exists | Team draft | Team |

## 6. Receipt

| ID | Rule | Source | Status |
| --- | --- | --- | --- |
| R-RCP-01 | The store confirms what arrived and reports issues | Booklet | Binding |
| R-RCP-02 | The store is given an expected arrival time so staff can be scheduled | Booklet | Binding |
| R-RCP-03 | The store receives clear notice when an order is deferred | Booklet | Binding |
| R-RCP-04 | Driver proof and store acceptance are separate records; neither overwrites the other | Policy | Policy |
| R-RCP-05 | An unconfirmed receipt auto-closes after a configured window as `unconfirmed`, never silently `delivered` | Policy | Policy |
| R-RCP-06 | The store sees the probability that an order can be supplied on its scheduled day | Team draft | Team, needs a model |
| R-RCP-07 | **Loaded but not received.** When a passing loading check, a completed delivery and a short receipt disagree, the system raises a shortage investigation linked to all three records. It is **never auto-resolved in favour of either party**, and no record is amended to make them agree | Policy | Policy |
| R-RCP-08 | Each link in the custody chain is attributed: who checked it at the dock, who delivered it, who received it. That chain is the evidence, and it is what replaces memory in a dispute | Booklet, policy | Policy |

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
| R-CAL-04 | A person may override one day's operating status. The override carries an actor, a reason and a timestamp, lives in `ref.calendar_overrides` so a reference import cannot discard it, and is never marked generated: a decided day is not an assumed one | Policy | Policy |
| R-NOT-01 | Warehouse to store manager: insufficient quantity | Team draft | Team |
| R-NOT-02 | Loader to dispatcher: damage or other problem | Team draft | Team |
| R-NOT-03 | Driver to dispatcher: report messages | Team draft | Team |
| R-NOT-04 | Dispatcher to store manager: automatic message when deferred, with a note | Team draft | Team |
| R-NOT-05 | A notification is never sent inside the request transaction. Intent commits with the state change; delivery is a separate tracked attempt | Policy | Policy |

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
| R-IAM-18 | On a shared loader device, loading writes are made by the operator who entered their PIN, named in the command's `actingUserId`. A device with no operator is locked and every loading write is refused | Confirmed product decision | Binding |
| R-IAM-19 | An operator PIN is four digits. Five wrong tries pause PIN entry for that person for five minutes | Confirmed product decision | Binding |
| R-IAM-20 | A loader may switch with their PIN while offline against the crew list the device downloaded: a PBKDF2 verifier per member, never the PIN, valid for 12 hours and wiped at sign-out, with the same five-try pause kept on the device. On reconnect the switches are replayed into the operator history before any queued work, only for crew of that device, in order, after the history the server has, and audited as offline. A four-digit PIN is recoverable from its verifier; that risk is accepted because the list reaches only a supervisor-signed-in loader device (decision 2026-10-01) | Confirmed product decision | Binding |

## 8. Conflicts found

Six places where the sources disagree. C-1, C-2, C-3, C-5 and C-6 are settled; C-4 remains open.

| # | Conflict | Detail | Recommendation |
| --- | --- | --- | --- |
| **C-1** | **Reefers carrying ambient goods** | Team draft: "Refrigerated vehicles exclusively transport chilled items." Booklet: "Refrigerated vehicles may also carry ambient goods." **Settled by the data**: of 9,734 reefer routes in training, 4 carried only ambient orders, and no ambient vehicle ever carried chilled | **Resolved: follow the booklet.** Reefers may carry ambient. Recorded as A-01. In practice it is rare, 0.04% of reefer routes, so the planner treats reefers as chilled-first and uses them for ambient only when it improves the plan. **Refined 2026-09-30 (D-J):** one temperature class per trip, R-PLN-31 |
| **C-2** | **"Each order mapped to the nearest available department"** | Team draft implies a depot choice. The data makes depot a **function of district**: all 120 outlets have `outlet.depot` equal to their district's depot, with zero exceptions, and R-PLN-04 forbids serving another depot's outlets | **Closed 2026-10-01 as inert** (issue #9): depot is a function of district, so there is no depot to choose; `HomeDepot` (R-PLN-04) covers it |
| **C-3** | **GPS coordinates** | Team draft: "the operational dataset includes newly added GPS coordinates for all delivery outlets." The shipped `outlets.csv` has nine columns and none is latitude or longitude | Treat coordinates as absent. Distance ordering must use `district_travel`, not point geometry |
| **C-4** | **"Reefers are assumed to run at full capacity"** | Team draft. Meaning unclear: it could mean reefers are always loaded to capacity, that their capacity is not derated when chilled, or that refrigeration does not reduce usable volume | Undecided. See question Q2 |
| **C-5** | **Longest distance first** | Team draft requires longest distances dispatched earliest. Delivery windows and the Fresh 03:30 to 08:00 window may require the opposite | Windows win; distance is a tie-break. Recorded as R-PLN-25 |
| **C-6** | **No ordering on holidays** | Team draft. The booklet does not restrict *placing* an order, only *delivering* on a non-operating day. Blocking placement stops a store preparing Monday's order on a Sunday | **Resolved 2026-09-30 (D-I):** allow placement, roll the delivery date to the next operating day, and show the store the date it will arrive. R-ORD-09 withdrawn |

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
| R-LOD-* | Loading domain and departure gate | Integration tests |
| R-EXE-* | Execution domain and offline queue | Browser tests |
| R-RCP-* | Receipt domain and scheduler | Integration tests |
| R-FLT-*, R-CAL-* | Reference data module | Domain unit tests |
| R-NOT-* | Notification outbox | Integration tests |

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
