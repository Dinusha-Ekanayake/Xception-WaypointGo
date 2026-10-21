# Trip problems, the driver's side

Owner: @Oxshadha. Piece 4 of the trip-problems vision, after Skip store (docs/issues/trip-skip/PLAN.md), quiet goods problems (R-ISS-16) and the loader's Found (R-ISS-17). Decisions confirmed by @jv-ransika on 2026-10-21. The folder takes its issue number once the issue is opened.

## Decisions (owner)

1. **Breakdown or road blocked: a plain notice.** The system posts to the trip's chat room, for example "VEH037 is delayed: breakdown", never the driver's own words. Stores still waiting for the truck get a push; stores already delivered see it in the room without a push.
2. **Store closed or cannot unload: only the dispatcher decides.** The driver's report goes to the dispatcher and to that store. The driver cannot mark the stop "Not delivered"; it ends only by the dispatcher's Skip store (Next day or Cancel). No timer, no fallback, no escalation. Offline, the report waits in the phone's queue and the dispatcher hears by phone; the dispatcher may skip the stop before the report arrives, and a report arriving after the skip is recorded without a conflict. The driver may go on to the next stop meanwhile.
3. **Goods damaged:** some units usable is a partial delivery plus a quiet DAMAGED_GOODS issue; nothing usable is a failed delivery, as today.
4. **"Running late" leaves the driver's problem list.** Arrival times and positions show lateness.

## Current state

- `delivery:ReportFault` (vehicle or road) records the report and publishes `vehicle.fault_reported` or `road.disruption_reported`; Issues raises an issue and Messaging shows it in the trip's chat as a report for the dispatcher. Nothing reaches the stores.
- The driver's "Cannot unload here", "No one at the outlet" and "Goods damaged" open the not-delivered form, which records a failed delivery (`outlet_closed`, `access_blocked`, `goods_damaged`).
- The server keeps no order between stops; the phone sends the driver to the first stop not finished, so a stop waiting for the dispatcher would hold the route.

## Who owns what

| Change | Layer |
| --- | --- |
| `delivery:ReportStopProblem` (STORE_CLOSED, CANNOT_UNLOAD): kept on the stop, `delivery.problem_reported`; after a skip, recorded with no conflict | Execution application, domain |
| The driver's FAILED refused for `outlet_closed` and `access_blocked`, and for a stop the driver reported | Execution domain |
| The run sheet says a stop is waiting for the dispatcher | Execution contract, frontend mirror |
| The report in the trip's chat (report types `store_closed`, `cannot_unload`, situations of the playbooks) and a system message to the store | Messaging consumers, Intelligence playbooks |
| The delay notice in the room, a push to stores still waiting | Messaging and Notification consumers |
| Goods damaged as partial plus a quiet issue | Driver UI, Issues (existing raise) |
| Problem list, next stop past a waiting one | Driver UI (a flow change, approved by the owner under role-ui-scope.md) |

## Pull requests

All three built on 2026-10-21: R-EXE-32 and R-NOT-21; R-MSG-17 and R-NOT-22; R-EXE-33.

1. Store closed and cannot unload: the command, the event, the chat report and the store's message, the driver's list and route. Tests: domain, integration with one denied case, a report after a skip, browser.
2. The delay notice for breakdown and road blocked, with the push to stores still waiting.
3. Goods damaged as partial plus a quiet issue; "Running late" removed.
