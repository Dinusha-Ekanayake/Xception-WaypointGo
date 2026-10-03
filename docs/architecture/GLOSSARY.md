# Glossary

One word per concept on every screen, message and document a person reads. Each term comes from a field in the data; where the data distinguishes two things, both words stay and each is used only for its own meaning. Code, API fields, command kinds, event names and enum values keep their names (AGENTS.md: the contract is not renamed for wording).

Status: **proposed** in issue #126. A term marked *proposed* is the default until the team records a different choice on that issue. The shared labels and formatters are in `frontend/src/shared/wording/` (#127), the screens follow these terms (#128), and `frontend/tests/wording.test.ts` fails on any retired word, raw code or 12-hour clock that comes back.

## Quantities

| Term | Meaning | Source in the data | Replaces | Notes |
| --- | --- | --- | --- | --- |
| **unit** | The count of goods: what an order, a line or a check is measured in | `order_units` (CSV), `ordering.orders.item_count` / `OrderView.itemCount`, `orderedUnits`, `units`, `loadedUnits`, `deliveredUnits` | case, package, piece, item (as a count) | "12 units". The data has no case or package; never show one |
| **product line** | One product of an order with its units; "line" after first mention | `OrderLineView`, `ItemView.lineNo`, `DeliveryLineView`, `ReceiptLineView` | item (as a row to check), SKU | A product id is the warehouse's *inferred* candidate: label it inferred, never "SKU" (AGENTS.md, catalogue rule 3) |
| **weight**, **volume** | The order's totals, which decide whether a load fits | `weightKg`, `volumeM3` | size, load (as a measure) | "520 kg", "7.9 m³", never summed from lines |

## Places and people

| Term | Meaning | Source | Replaces | Notes |
| --- | --- | --- | --- | --- |
| **outlet** | A customer site that receives deliveries | `outletId`, `ref.outlets` | shop, customer, store (on other roles' screens) | *Proposed:* the store manager's own screens may say "your store" for their own outlet; every other screen says outlet |
| **store manager** | The role that orders for and receives at an outlet | role `store_manager` | outlet manager, shop owner | |
| **depot** | Where vehicles load and start | `depotCode` | warehouse (as a place), hub | "Warehouse" is the external stock system only |
| **district** | The area a trip serves | `districtName` | zone, area, region | |
| **dock** | Where a vehicle loads at the depot | `dockCode` | bay (at the depot), gate | Not the outlet's dock type |

## Vehicles and the day's plan

| Term | Meaning | Source | Replaces | Notes |
| --- | --- | --- | --- | --- |
| **vehicle** | Any truck or van | `vehicleId`, `ref.vehicles` | lorry, fleet unit | Use truck or van only when the type matters |
| **truck**, **van** | The vehicle type | `vehicleType` (`truck`, `van`) | | Van-only outlets need a van (R-PLN-03) |
| **refrigerated** | Can carry chilled goods | `temperatureCapability = reefer`, `refrigerated` | reefer, cold truck, chiller | "Reefer" stays in code and docs, never on screen |
| **plan** | The day's allocation of orders to trips for one depot | `PlanView` | schedule, roster | **draft** until published, **published** after, **revision** when changed after publishing |
| **trip** | One vehicle's outing: one brand, one district, one temperature; at most two a day | `TripView`, `tripId`, `tripNumber` | route, run, load (as an outing) | "VEH043 · Trip 1" |
| **stop** | A place in a trip's order: one outlet visited | `StopView`, `RunSheetStopView.sequence` | drop, visit, run (as a visit) | "Stop 3 of 8" |
| **run sheet** | A driver's stops for one vehicle and day | `RunSheetView` | run (alone), route sheet, manifest (for drivers) | "Manifest" is the loader's list for one trip |
| **manifest** | The loader's list of orders and lines for one trip, in loading order | `ManifestView` | load list, pick list | |
| **delivery** | One order handed over at a stop, and the record of what happened | `DeliveryRecordView`, `deliveryId`, `delivery.*` events | drop, run (as a visit), shipment | Stop is the place in the sequence; delivery is the order and its outcome there |
| **release** | The loader lets a loaded trip leave the depot | `trip.released` | dispatch (as a verb), send out | "Dispatch" names the role, not this action |

## Time

| Term | Meaning | Source | Replaces | Notes |
| --- | --- | --- | --- | --- |
| **time of day** | 24-hour clock in depot time, Asia/Colombo | every `IsoTime` and `IsoInstant` | AM/PM, 12-hour clock, browser time zone | "06:40". A traveller abroad still sees Colombo time |
| **date** | Day, date and month | `IsoDate` | numeric dates, "10/05" | "Thu 1 Oct"; add the year only outside the current year |
| **date and time** | Both, comma separated | | | "Thu 1 Oct, 06:40" |
| **relative time** | Minutes or hours from now | | | "in 25 min", "25 min ago", "3 h ago" |
| **delivery window** | When an outlet accepts goods; "window opens" and "window closes" | `windowOpen`, `windowClose`, the effective window for malls (R-PLN-29) | time slot, slot | |
| **planned arrival** | When the plan expects the vehicle at a stop | `plannedArrival` | scheduled time | Fixed once published |
| **expected arrival** | The planned arrival moved by the delay observed so far | `expectedArrival`, `eta.changed` | ETA, estimated time | "Expected 06:55". The event keeps its name `eta.changed` |
| **late** | Arrived after the window closed, or not arrived and the window has closed | `lateMinutes` (R-EXE-14) | overdue, delayed (as a status) | "12 min late"; "running late" only for a stop not reached yet |

## What happens to an order

| Term | Meaning | Source | Replaces | Notes |
| --- | --- | --- | --- | --- |
| **deferred** | Left out of a plan, kept for a later day, with the rule that decided it | `AllocationDecision.DEFERRED`, `OrderStatus.DEFERRED`, `order.deferred` | postponed, skipped, moved, rescheduled | "Deferred 2 times" for `deferralCount`; skipped is not used for orders |
| **cannot be served** | No vehicle of the depot could ever carry it | `UNSERVABLE` | unservable, rejected, impossible | |
| **moved to** | The delivery date rolled past a non-operating day | `dateRolled`, `deliveryDate` | postponed, shifted | "Moved to Mon 5 Oct: Sunday is not an operating day" |
| **redelivery** | A new order for the same goods after nothing arrived | `redeliveryOf`, `redelivery.requested` (A-24) | resend, re-delivery | |
| **not delivered** | The delivery failed, with a reason | `DeliveryOutcome.FAILED`, `delivery.failed` | failed, undelivered | Screen label; the outcome keeps its name |
| **replanned** | The stop was taken off this run sheet by a plan change | `DeliveryOutcome.SKIPPED` | skipped | |
| **proof** | The photo or signature recorded at delivery | `proofCaptured` | POD, evidence (on screen) | "Proof owed" when missing |

## Loading checks

| Term | Meaning | Source | Notes |
| --- | --- | --- | --- |
| **loaded** | Every unit of the line is on the vehicle | `CheckStatus.LOADED` | |
| **short** | Some units are missing | `SHORT` | "Short 2 units" |
| **missing** | No units of the line | `MISSING` | |
| **damaged** | Units on hand are damaged | `DAMAGED` | |
| **does not fit** | The line cannot go on this vehicle | `DOES_NOT_FIT` | |
| **shortfall** | A trip released or held with lines short | `loading.shortfall` | An issue type, "Loading shortfall" |

## Receipt and issues

| Term | Meaning | Source | Notes |
| --- | --- | --- | --- |
| **confirm** | The store manager accepts what arrived | `ReceiptStatus.CONFIRMED` | |
| **dispute** | The store manager says what arrived differs | `DISPUTED`, `receipt.disputed` | |
| **closed automatically** | No answer within the window, closed by the system | `AUTO_CLOSED` | Never "confirmed" |
| **issue** | A recorded problem with a type, severity and status | `IssueView` | Not "ticket", "case" or "incident" |
| **raise**, **assign**, **resolve**, **close**, **cancel** | What people do to an issue | `issue:*` commands | "Cancel" only for an issue raised in error |
| **severity** | Low, Medium, High, Critical | `IssueSeverity` | |

## Codes shown to people

A code from the data is never shown raw. The label is:

| Field | Code | Label |
| --- | --- | --- |
| dock type | `rear_dock`, `street`, `mall_bay` | Rear dock, Street, Mall bay |
| parking | `van_only`, `mall_dock`, `normal` | Van only, Mall dock, (nothing shown) |
| temperature | `chilled`, `ambient`, `frozen` | Chilled, Ambient, Chilled (frozen is treated as chilled, R-PLN-26) |
| vehicle type | `truck`, `van` | Truck, Van |
| brand | `Fresh`, `Style`, `Tech` | as in the data |

## What was replaced

Counted in visible text under `frontend/src` on 2026-10-03 (class names excluded), before #128 replaced them:

| Concept | Words in use | By role |
| --- | --- | --- |
| order quantity | units 33, cases 13, packages 9, items as a count | cases and packages only on store manager screens |
| a vehicle's outing | trip 83, route 14, run 28 | route on admin and loader; run on store manager, driver, dispatcher |
| a place on the trip | stop 90, drop and visit | |
| the customer site | outlet 37, store 41 | store appears on dispatcher, loader and driver screens too |
| refrigerated vehicle | refrigerated 11, reefer 1 | reefer on the dispatcher |
| time of day | 24-hour, and AM/PM in 6 places | store manager 4, dispatcher 1, loader 1 |
| raw codes | `rear_dock` and others in 8 places | store manager 6, driver 2 |
