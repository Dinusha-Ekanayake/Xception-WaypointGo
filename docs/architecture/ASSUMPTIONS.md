# Assumptions and parameters

Two registers that the rules depend on but that nobody has proved.

**Assumptions** are beliefs we are treating as true. Each one could be wrong, and the cost of being wrong is written down. **Parameters** are values that are true today and will change: booklet constants, thresholds, timeouts. Neither belongs in [RULES-AND-POLICIES.md](RULES-AND-POLICIES.md), which states what must be true, or in [EDGE-CASES.md](EDGE-CASES.md), which states what happens when it is not.

The practice is an assumption register: description, category, basis, impact if false, how to verify, status and owner. **When an assumption is invalidated it becomes a risk, not a footnote**, and the rules that depend on it are revisited in the same change.

## How to use this

1. Before implementing a rule, check whether it rests on an assumption here.
2. When you learn something that confirms or breaks one, update `Status` and the date in the same commit as the code.
3. A `Broken` assumption blocks release of anything in its blast radius until the dependent rules are re-decided.

---

## 1. Assumption register

| ID | Assumption | Basis | If it is wrong | How to verify | Status |
| --- | --- | --- | --- | --- | --- |
| **A-01** | **Refrigerated vehicles may carry ambient goods.** They are not restricted to chilled loads | Booklet states it explicitly. **Confirmed in data**: of 9,734 reefer routes in the training set, 4 carried only ambient orders | If reefers were chilled-only, 16 of 60 vehicles would be idle on low-chilled days and effective fleet capacity would fall by about 27% | Already verified against `deliveries_train.csv` | **Confirmed** |
| **A-02** | **A reefer's whole body is refrigerated.** There are no multi-temperature compartments, and usable weight and volume are not reduced when carrying chilled goods | `vehicles.csv` gives one `weight_cap_kg` and one `volume_cap_m3` per vehicle, with no chilled variant. The validator applies the same caps regardless of temperature | If reefer capacity were derated for chilled loads, every chilled trip would be over-planned and would fail at the dock | Ask the team. This is what "reefers run at full capacity" in the draft appears to mean | **Assumed**, Q2 |
| **A-03** | **The weekly fuel quota covers the outbound trip and the return to depot.** Trip *time* excludes the return; fuel does not | Booklet: "Route distance consumes that allowance." The time exclusion is a stated planning simplification, not a claim about diesel | If fuel excluded the return, roughly twice as many trips would fit the quota, and our plans would be needlessly conservative | **Confirmed by the team 2026-09-30 (D-K)** | **Confirmed** |
| **A-04** | **A trip carries one temperature class.** Chilled and ambient orders are not mixed on the same trip | **Observed in data**: 0 of 25,198 historical routes mix temperatures. Likely emergent, because Fresh outlets place dry and chilled as separate orders | If mixing were permitted, some trips could be consolidated and fewer vehicles used. Treating it as a hard constraint is safe but may cost efficiency | **Confirmed by the team 2026-09-30 (D-J):** a hard constraint, R-PLN-31. A reefer runs with refrigeration on or off per trip | **Confirmed** |
| **A-05** | **Only Fresh has chilled demand.** Style and Tech are always ambient | **Confirmed in data**: all 34,742 chilled orders in training are Fresh. The Datathon brief states it for Task 2A | Reefer planning for Style or Tech would be dead logic | Verified | **Confirmed** |
| **A-06** | **Depot is determined by district.** An outlet is served by its district's depot, with no choice | **Confirmed in data**: all 120 outlets have `outlet.depot` equal to their district's depot, zero exceptions. `district_travel` has one row per district | Any "nearest available depot" logic would be dead code. See conflict C-2 | Verified | **Confirmed** |
| **A-07** | **The warehouse contract is `/api/v1` with `x-api-key`.** `GET /products`, `GET /orders`, `GET /orders/{id}`, `POST /orders` | **Verified with a live key.** Response schemas, pagination and error codes all observed. **Revised 2026-10-01:** the API added two warehouses, a `warehouse` field on `POST /orders`, partial reservations (`202`, status `reserved`, with expiry) and `POST /orders/:id/confirm`; reads re-verified live | The `StockPort` contract has no warehouse or depot parameter, so the #7 adapter cannot yet place an order correctly | Add the depot to `StockPort.placeOrder` and `amendOrder` (#7, with Ordering passing `order.depot`). Contract recorded in RULES-AND-POLICIES section 2 | **Confirmed** |
| **A-08** | ~~No stock availability endpoint exists.~~ **Corrected 2026-09-27.** Stock is real and enforced: it is a `stock` field on each product, and `POST /orders` rejects an over-quantity line with `409 insufficient_stock`, naming requested and available | Verified live. There is no `/stock` resource, which is why probing by resource name missed it | The assumption was wrong in our favour: R-STK-01 to R-STK-06 are implementable today, not inert | Verified | **Broken, superseded by A-18** |
| **A-18** | **Availability is per product, so a stock check needs product lines.** An order with only weight and volume cannot be checked | `POST /orders` takes `items:[{product_id, quantity}]` and validates each line against that product's stock | Waypoint orders are order-level. Without product lines there is no stock check, so either orders carry lines or the hold flow cannot run | **Decided 2026-09-30 (D-E):** orders carry descriptive lines, checked by the warehouse at placement; the warehouse returns authoritative totals and temperature | **Resolved** |
| **A-09** | ~~Order placement is blocked on non-operating days~~ **Withdrawn 2026-09-30 (D-I):** placement is allowed and the delivery date rolls to the next operating day | Team decision | A store that cannot prepare Monday's order on a Sunday would phone it in. See conflict C-6 | Team decision, recorded | **Withdrawn** |
| **A-10** | **There is no returns workflow.** A failed delivery records an outcome and raises an issue; goods disposition is noted but not tracked as a return | Team draft: "We don't handle returns" | Undelivered goods have no tracked destination, so stock reconciliation with the warehouse will not balance | Accepted scope limit | **Assumed** |
| **A-11** | **Outlet coordinates do not exist.** Distance ordering uses `district_travel`, not point geometry | `outlets.csv` has nine columns, none of them latitude or longitude. See conflict C-3 | Any map, nearest-outlet or true distance feature is unbuildable until coordinates are supplied | Verified | **Confirmed** |
| **A-12** | **The product catalogue is approximate.** It reproduces order weight and volume within 1%, so order-level totals remain authoritative for capacity | Catalogue provenance: reconstructed from order totals, `verified_real_sku = false` on every row | Summing product lines for capacity would introduce up to 55 kg of invisible error on a 5,510 kg truck | Verified from the catalogue readme | **Confirmed** |
| **A-13** | **Mall window and outlet window coincide today.** The effective window is their intersection | **Verified in data**: all 12 mall outlets have identical values for both | If they diverge and the code reads only one, deliveries are attempted outside mall access hours | Verified; the intersection rule protects against future divergence | **Confirmed** |
| **A-14** | **One operating timezone**, `Asia/Colombo`, with no daylight saving | Booklet states all times are Asia/Colombo | Multi-timezone operation would make every stored `time` ambiguous | Verified | **Confirmed** |
| **A-15** | **Driver availability is not a planning constraint** for the existing fleet | Booklet states it explicitly | If drivers were scarce, vehicle count would overstate real capacity | Verified | **Confirmed** |
| **A-16** | **Service allowance is a planning allowance, not an observed duration** | Booklet states it explicitly for `service_allowance.csv` | Using it as a predicted service time would bias every estimate. It is a budget, and actuals differ | Verified | **Confirmed** |
| **A-17** | **The custody chain is sufficient evidence.** A loading check, a proof of delivery and a receipt confirmation together settle what happened | Booklet: proof exists so disputes do not depend on memory | If disputes need more, for example weighbridge tickets, the evidence model is incomplete | Ask the team after the first real dispute | **Assumed** |

| **A-19** | **`POST /orders` has no idempotency key**, so the adapter can never safely replay it | Official API documentation and probing. No key parameter exists | A retry after a timeout creates a second order and decrements stock twice, silently. This is the highest-risk integration behaviour in the system | Requested from the warehouse team 2026-09-30 (D-M); query-before-create stays until it ships | **Confirmed**, mitigated by R-STK-11 |
| **A-20** | **The warehouse order lifecycle is untested.** All 97,321 seeded orders are `delivered`; there are zero `reserved`, `pending`, `shipped`, `cancelled` or `expired` (re-checked 2026-10-01) | Verified live | Transitions may behave differently from the documentation the first time they run in anger | Exercise `pending -> shipped -> delivered`, `pending -> cancelled` and `reserved -> cancelled` (the adapter's 202 path) against throwaway orders early, not at integration time | **Confirmed** |

| **A-21** | **In-process policy evaluation is fast enough to sit in front of every command.** Statements are cached per actor and evaluation is pattern matching over a small list | Policies are few and small: six role policies with one or two statements each | If evaluation became a bottleneck, the cache would need to be keyed more finely or decisions memoised per request | Measure p95 authorization time once commands carry real traffic. Budget: under 2 ms | **Assumed** |
| **A-22** | **"The next run" closes at 16:00 on the calendar day before its service date**, not on the previous operating day | R-ORD-01 names a time, not a day; runs leave early in the morning | An order placed on Sunday for Tuesday would close on Saturday instead of Monday. `Cutoff.closesAt` is the one place to change | Confirm with operations which day a Monday run closes | **Assumed** (issue #8) |
| **A-23** | **Style's weekly and Tech's as-needed cadence is guidance, not validation** (R-ORD-03, R-ORD-04) | No schedule data exists to validate against | Orders off cadence would be accepted; the UI can only suggest | Obtain Style's delivery schedule, then add it as reference data | **Assumed** (issue #8) |
| **A-24** | **A redelivery is the whole original order and carries its reservation**, so it is confirmed without calling the warehouse | Issues emits `redelivery.requested` with no lines; the goods were already picked | A partial redelivery would ship too much and a consumed reservation would overstate stock. Partial redelivery is a known gap | Settle with the Issues owner whether `redelivery.requested` should carry lines | **Assumed** (issue #8) |
| **A-25** | **An order is placed in its own depot's warehouse** (`Kandy` to KDY, `Peliyagoda` to PLG), never split across the two | The API scopes stock and orders per warehouse since 2026-10-01, and each depot's trucks load from one site | A short line that the other warehouse could fill is rejected; filling it needs a transfer (`POST /products/:id/transfer`), which is a stock decision, not an automatic one | Confirm with operations whether a cross-warehouse transfer before cutoff is expected | **Assumed** (2026-10-01) |
| **A-26** | **A vehicle's next trip leaves when its previous trip's last service ends, with no return leg** | The booklet's worked example: 101 + 112 = 213 of 270 Fresh minutes adds no return drive; the supplied validator checks the formula alone | Second-trip arrivals are optimistic by the return drive, so a planned arrival may slip later than the plan says | Compare planned and actual second-trip departures once Execution records them (#16 lateness) | **Assumed** (issue #9) |
| **A-27** | **Reefers are not derated** for chilled loads (A-02 stands) | Q2 answered "no derating" for now | Chilled trips planned to full volume may not fit physically | Confirm usable reefer volume with the fleet team | **Assumed** (issue #9) |
| **A-28** | **An outlet Planning has never served counts as served 0 days ago** in the priority tie-break | There is no service history before Waypoint's first published plan | A long-waiting outlet with no history in the system ranks below one with history on the last tie-break key only | Seed days-since-served from the dataset's history if the tie-break starts deciding real deferrals | **Assumed** (issue #9) |
| **A-29** | **Temporary, until #5: a driver raises an issue through depot scope.** The team's model scopes a driver by vehicle and date (`iam.vehicle_driver_assignments`, `IdentityQuery.driverVehicleOn`, `app.actor_drives` in #12), but #5 has not implemented `driverVehicleOn` yet, so `IssueScope` accepts depot or outlet scope only | Issues are placed by depot; the only scope functions today are `actor_has_depot` and `actor_has_outlet` | A driver with no depot grant can read what they raised but cannot raise a new issue (403) | When #5 implements `driverVehicleOn`, `IssueScope` also accepts a driver assigned that day to a vehicle of the issue's depot, and drivers no longer need a depot grant. Tracked in #13 | **Assumed**, replace when #5 lands |
| **A-30** | **A store's whole-order confirmation means every line arrived in full**, and a partial confirmation lists only the short lines | The store screen sends no lines for a confirmation; listing every line would be noise | A store that confirms without checking hides a shortage until a later dispute (still accepted, RCP-08) | Watch the late-dispute rate | **Assumed** (issue #13) |

### Assumptions that are currently blocking

`A-02` is the only assumption still both unconfirmed and behaviour-changing; it maps to question Q2. Updated 2026-09-30: A-03, A-04 and A-18 were settled by the team, A-09 was withdrawn, and A-08 was already superseded by A-18.

---

## 2. Parameter register

Values that are correct today and will change. **None of them is a literal in code.** They live in effective-dated `rule_parameters` rows, so a change is a config row with an effective date, not a release. Changing one never rewrites a past decision, because every plan stamps the rule set version it was built under.

| ID | Parameter | Value today | Source | Blast radius if changed |
| --- | --- | --- | --- | --- |
| **P-01** | Fresh trip budget | 270 min | Booklet, validator | Fresh feasibility for the entire fleet. Scored by the validator |
| **P-02** | Style and Tech combined budget | 480 min | Booklet, validator | Daytime feasibility. Scored |
| **P-03** | Maximum trips per vehicle per day | 2 | Booklet, validator | Total daily capacity. Scored |
| **P-04** | Order cutoff | 16:00 Asia/Colombo | Booklet | Which orders reach tomorrow's run |
| **P-05** | Fresh store-open deadline | 08:00 | Booklet | The pre-dawn window, and what counts as late for Fresh |
| **P-06** | Fresh operating window | 03:30 to 08:00 | Booklet, validator | Departure scheduling |
| **P-07** | Operating days | Monday to Saturday | Booklet | Calendar generation beyond the supplied range |
| **P-08** | Capacity comparison tolerance | 1e-6 | Validator source | Whether a borderline load fits. Must match the validator exactly |
| **P-09** | Fuel quota week boundary | Monday to Sunday, ISO week | Our policy | Which trips share a quota |
| **P-10** | Receipt auto-close window | 24 hours (`receipt.parameters` `auto_close.hours`) | Our policy, issue #13 | When an unconfirmed receipt stops waiting. Stamped on each receipt when it opens, so a change never moves a deadline already given |
| **P-11** | Stock hold timeout | to decide | Our policy | When an unresolved stock hold auto-defers |
| **P-12** | Repeated-deferral escalation threshold | 1 skip (`escalation.skips`) | Our policy, issue #9 | When a skipped outlet is forced up the priority order |
| **P-13** | Login lockout threshold and window | to decide | Our policy | Brute-force resistance against usability |
| **P-14** | Proof artifact retention | to decide | Our policy, legal | How long evidence survives |
| **P-15** | Earliest departure of a daytime trip with no Fresh trip before it | 08:00 (`daytime.departure.minute.of.day`) | Our policy, issue #9 | When Style and Tech trips can start, and so how much of the 480 minutes is usable |
| **P-17** | Strict window threshold for priority | 120 min (`strict.window.min`) | Our policy, issue #9 | Which outlets are placed early as hard to fit |
| **P-18** | Brand cadence, days until the brand's next run | Fresh 1, Style 7, Tech 1 (`cadence.days.<brand>`) | Our policy, issue #9 | How costly a deferral is: a weekly order deferred waits a week |
| **P-19** | Allocation engine time budget per depot-day | 10 s (`engine.budget.ms`) | Our policy, issue #9 | When a run returns a partial plan (PLN-11) |
| **P-20** | Escalation deadline, CRITICAL issue unassigned | 15 min (`issues.parameters`) | Our policy, issue #13 | When a critical issue nobody owns is escalated |
| **P-21** | Escalation deadline, HIGH | 60 min | Our policy, issue #13 | As above |
| **P-22** | Escalation deadline, MEDIUM | 240 min | Our policy, issue #13 | As above |
| **P-23** | Escalation deadline, LOW | 1440 min | Our policy, issue #13 | As above |

### How a parameter changes

1. A new `rule_parameters` row with an `effective_from` date. The previous row is superseded, never updated.
2. Compare before promoting: a what-if run under the candidate set, never publishable, compared side by side with the plan in force. Hidden shadow evaluation is withdrawn (issue #9, decision 6); the what-if run is a follow-up issue.
3. Canary on one depot, then enforce.
4. Every plan already stamps its rule set version, so historical decisions continue to replay under the values that were in force.

The three parameters marked *Scored* are different in one respect: **P-01, P-02, P-03 and P-08 are checked by the supplied validator.** Changing them would pass our tests and fail the official scoring, so they are locked for the competition and only become genuinely adjustable afterwards. Our CI runs `check_allocation.py` over generated output precisely to catch that.

---

## 3. Sources

[Assumption logs and RAID registers](https://brainsensei.com/glossary/assumption-log/) · [Project assumptions guide](https://www.rocketlane.com/blogs/project-assumptions) · [Managing assumptions in agile projects](https://medium.com/doctolib/managing-assumptions-in-agile-software-development-projects-fd17bac16531) · [A survey on software architectural assumptions](https://www.sciencedirect.com/science/article/abs/pii/S0164121215002824) · [Architectural assumption documentation framework, industrial case study](https://www.sciencedirect.com/science/article/abs/pii/S0164121217301966) · [The danger of assumptions in software architecture](https://nazdelam.medium.com/the-danger-of-assumptions-in-software-architecture-38af98d0b126)
