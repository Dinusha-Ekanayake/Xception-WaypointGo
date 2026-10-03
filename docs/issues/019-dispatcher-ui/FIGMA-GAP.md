# Dispatcher: Figma against code, UI changes to make

Ground truth is the Figma file `TX2bkkANaIMI3UbpnjlPer`, page "05 · Dispatcher · Desktop" (frames 1440x900) and the page "04 · Dispatcher · Workflow map" (node 1067:295). Code is `frontend/src/roles/dispatcher/`.

## Status after the shell and screens pass (2026-10-04)

Every frame named below was seen this time (Overview, Orders current, upcoming and details, Plan decide, view, publish ready and published, compare, swap window and swap ready, edit trip, trip checks, trip details, deferred order, account menu, time range menu, toasts, Vehicles, Forecast, Issues, Live). What changed, and what still differs:

- **Shell.** One sidebar on every screen, folded to the rail only by the dispatcher (kept in `localStorage`), never by the screen; the rail keeps the depot scope (a card) and the account (Figma "Account menu", with Sign out). The sidebar is the one depot scope: Live's pills and the Overview depot menu set it, Plan asks for one depot when both are in view. Plan shows "Draft" or "Not sent" in amber, never a "Due" time, which no module serves (S1). The bell never wraps away from the sync pill. Success is a toast (`shared/ui/Toast.tsx`), refusals stay on screen.
- **Overview.** O1 to O4 built from existing reads: tinted tiles from orders and `plans/deferrals`, a range menu, and trips and on time per day from orders and run sheets read per day (`useHistory`). O5 and O6 still wait on a fleet status read. The skipped outlets card is folded into "Must-deliver deferred".
- **Orders.** R2 to R4 built: Current, Upcoming (day cards with Close orders and Open plan) and Past (grouped by day); Planned, Loaded, Delivered, Confirmed by store; At risk and Issues reported tiles; brand and status menus; an expected arrival or done column; the order drawer with a timeline (03d). R1: the cutoff is a fixed rule (16:00 depot time the day before, `Cutoff.TIME`), mirrored as `ORDER_CUTOFF` in `shared/domain/ordering.ts`; the subtitle and each upcoming day card show it. Call store and Add make-up order are not built (no contact or make-up command).
- **Plan.** Swap window and Edit trip rebuilt as Figma's three-column window (`TripWindow.tsx`); Publish rebuilt with checks, Send to, and "What people will receive"; Compare rebuilt with Plan A / Plan B and Decision impact; trip checks and trip details are dark cards; a deferred card opens the deferred order card. The day is a field beside the plan picker. A swap and a new stop order are one `plan:Swap` (optional `orderIds`, R-PLN-33), so the swap window reorders stops once a stop gives way. "AI order" is a slot: `useStopOrderProposal` in `data/usePlanReads.ts` answers "not available" and the type `StopOrderProposal` is in `shared/domain/planning.ts`; when Intelligence serves a proposal, wire that hook and the window offers "Use AI order" and sends it as the stop order.
- **Vehicles.** Status tabs from Execution, Trips from the published plan, fuel as a planned share of the quota, refrigerated available against needed per week from the forecast. Workshop tab, In workshop and Back in service wait on the fleet status read.
- **Issues.** Open / In progress / Resolved tabs, rows and detail as Figma. Resolved waits on a read of resolved issues (the list read serves open and assigned only); "All reports" and "Exception register" (08c, 08d) are not built for the same reason; "What each side reported" shows the one report the issue carries.

## How far this was checked

| Screen | Figma frame | Checked by |
| --- | --- | --- |
| 02 Overview | 189:10617 | screenshot and code read line by line |
| 03 Orders: current | 189:10786 | screenshot and code read line by line |
| Plan 1 Decide | 189:11113 | screenshot and code read line by line |
| Plan 2 View plan | 189:18510 | screenshot and code read line by line |
| Every other state (03b Upcoming, 03c Past, 03d Order details, 1b swap, 1e too big, 1f/1g menus, 1s/1w read-only, 2a, 3a to 3e, 4 Compare, Edit trip, Trip checks overlay, Deferred order overlay) | listed in section 6 | frame names and the workflow map text only. Not seen. Confirm each against Figma before building |

The Figma connector dropped mid-session. Re-run the unseen states when it is back.

## How to read the Status column

- **Build**: data already reaches the screen, UI only.
- **Backend**: needs a read or command that does not exist yet. Name given.
- **Decided out**: `PLAN.md` decisions 3 and 9 and `WALKTHROUGH.md` already chose to leave it out (D-D, nothing faked). Listed so the decision is a visible one. Reverse it only with the team.
- **Keep**: code has it, Figma does not. Not a defect.
- **Wording**: Figma shows it, but `GLOSSARY.md` or AGENTS.md overrides the Figma text.

## 0. Shell, all screens

| # | Figma | Code | Status | Change |
| --- | --- | --- | --- | --- |
| S1 | Sidebar badges: Orders `3`, Plan `Due 02:00`, Live `2` | None. `Sidebar.tsx` header says badges wait on a module serving the count | Backend | Orders badge = orders needing attention (already derivable from `flow().attention`, lift it into the shell). Live badge = trips at risk. Plan "Due" needs the plan due time, no source today. Show only the badges with a real count |
| S2 | Plan screens draw an 84px icon-only rail with badges and a layout toggle at the bottom. Overview and Orders draw the 260px sidebar | 260px sidebar always | Build | Add a collapsed rail variant in `Sidebar.tsx`, used when `view === "plan"` |
| S3 | User footer: `Dispatcher · DSP-00024` | `Dispatcher` only | Backend | Staff id is in the session? If so pass it to `Sidebar`. Check `app-shell` session shape |
| S4 | Plan header has no sync pill and no bell | `PageHeader` always draws both plus the MCP button | Keep | Keep. Only the Plan layout differs, low value |
| S5 | Header clock "Synced 4:12 PM", "Mon 28 Sep · 4:12 PM" | `clock()` 24-hour | Wording | Keep 24-hour (glossary: 24-hour depot time). Do not copy the Figma 12-hour text |

## 1. 02 Overview

Figma layout: greeting header, left column 708px (Orders card, Summary card with chart), right column 380px (Vehicles card, Recent notifications card). Code layout matches the two column split, right column `lg:max-w-[380px]`.

| # | Figma | Code | Status | Change |
| --- | --- | --- | --- | --- |
| O1 | Orders card, three tinted tiles with a chevron, each links: **Confirmed today 124** (green, "Confirmed for delivery today"), **Deferred today 10** (amber, "No cold space · 9, Too big for any vehicle · 1"), **Must-deliver deferred 3** (red, "Due today · needs priority") | Four plain tiles: Due, Planned, Delivered, Need attention. No tint, no link, no reason split | Build + Backend | Replace the four tiles with these three. Confirmed = orders with status CONFIRMED or later. Deferred = status DEFERRED, split by `bindingRule` (cold space vs too big) from the plan allocations. Must-deliver needs a "must deliver today" flag: confirm which field carries it (`skipCount`/`deferralCount` and the due date are the candidates). Pass `tone` (`success`, `warning`, `danger`, already in `StatTile`) and make each a button to `orders` with the matching filter |
| O2 | Summary card has two dropdowns: depot (Both depots) and range (Last 7 days) | Static, today only, no dropdowns | Backend | New read for a 7-day window of orders, on-time and issues by depot. Add the two selects to `CardHead` |
| O3 | Summary tiles: **Orders 524** (518 delivered, 6 deferred), **On time 92%**, **Issues reported 9** (5 missing, 4 damaged) | Tiles: On the road, Stops done, On time, Open issues. Today only | Backend | Change the tile set to the Figma three. "On the road" and "Stops done" move to Live, where they already exist. Issues split by type needs the issue type in `issueCounts` |
| O4 | **Trips per day, last 7 days**: bars for trips, line for on-time %, right axis 80 to 100%, legend, caption "avg 23 trips · 92% on time", latest day bar darker | None | Backend | New read: per day, depot, trips and on-time %. Draw as plain SVG like `ForecastChart.tsx` (same project pattern, with a hidden table for screen readers). Largest single gap on this screen |
| O5 | Vehicles: three tiles **7 Available vans**, **43 Available vehicles**, **10 Workshop vehicles** (amber) | Two tiles plus a Workshop tile that shows `-` and a notice "waits on a fleet status read" | Backend | A fleet status read for vehicles in workshop. Then show the number and drop the `Notice` |
| O6 | "Workshop vehicles" list: `VEH005 · Refrigerated`, `Expected back · Tue 29 Sep, 01:00` | None | Backend | Same read as O5, with expected-back time. Render below the tiles |
| O7 | Notifications rows: small grey line "Driver · VEH020 · 2 min ago", message, **Reply** and **Mark as read** | Same rows with **Open** and **Mark as read**. Reply left out on purpose (comment in `NotificationsPanel.tsx`: no messaging between roles) | Decided out | None. Open is better than a dead Reply. Revisit only if a contact feature is built |
| O8 | Not drawn: skipped outlets | `SkippedOutlets` card, shows `ruleId` and the reason | Keep + Wording | Keep (it carries R-PLN-20). The raw `ruleId` on screen breaks the glossary rule "no raw codes". Show the reason text only |
| O9 | Header: greeting, "Mon 28 Sep · 4:12 PM · both depots" | `greeting()`, `depotStamp()`, scope label | Build | Compare spacing only. Figma title is 30px, same as code |

## 2. 03 Orders: current

Figma layout: header, then a segmented tab bar and filter row on one line, then a flow strip card, then the table card.

| # | Figma | Code | Status | Change |
| --- | --- | --- | --- | --- |
| R1 | Subtitle "Both depots · cutoff 4:00 PM" | "N due · scope · day" | Backend | Cutoff time. Needs a parameter read (the order cutoff exists as R-ORD-01, expose it). Show as `cutoff 16:00` (glossary) |
| R2 | **Tab bar**: Current (Today · 124), Upcoming (Tue 1.. · Wed 4), Past (Last 7 days). Black selected pill, white card behind | One day picker (`DayPicker`) in the header. Code comment: "tabs are the day picker" | Build | Add the three tabs. Current = today. Upcoming = tomorrow onward, count from the orders read for those days. Past = last 7 days, needs a range read. Keep `DayPicker` for jumping to a date |
| R3 | Flow strip: **Planned 124 > Loaded 124 > Delivered 113 > Confirmed by store 103** | Due > Planned > Left the dock > Delivered > Confirmed by store | Build | Figma has no "Due", and it says "Loaded" where code says "Left the dock". Loaded = status LOADING and later. Decide: follow Figma (4 steps, "Loaded") |
| R4 | Tiles right of the strip: **11 On the road** (green), **2 At risk** (amber), **3 Issues reported** (red), **10 Awaiting store** (grey), each with a chevron and link | Three: On the road, Need attention, Awaiting store. Set the status filter on click | Build + Backend | Add **At risk** (from execution: expected arrival past the window, `useLive`/`vehicleDay` already compute lateness) and **Issues reported** (count from `useIssues` by order). Replace "Need attention" |
| R5 | Filters: Search ("Search"), **All brands** dropdown, **All statuses** dropdown, in the header row beside the tabs | `FilterTabs` row (All, Need attention, To plan, Planned, On the road, Done), search box, brand select | Build | Replace the tab row with an **All statuses** select; keep the same `StatusFilter` values. Move search and selects up beside the Current/Upcoming/Past tabs |
| R6 | Columns: Order, **Outlet with a brand colour dot**, Type pill (Ambient grey, Chilled blue), **Size** (`7.9 m³ · 520 kg`), Vehicle · trip, Status pill, **ETA / done** | Same without a colour dot and without the last column. Outlet reads `OUT057 · Galle · brand` as text | Build + Backend | Add the brand dot (needs a brand to colour map in `shared/ui`). ETA / done: planned arrival from the published plan stop (`stop.plannedArrival`, already read in `rides`), expected arrival and done time from execution for orders on the road or delivered |
| R7 | Status text carries detail: "On the road · at risk", "On the road · on time", "Offline · 12 min", "Delivered · 1 missing", "Delivered · 2 damaged · 1 missing", "Confirmed by store", "Delivered · awaiting store" | `STATUS[order.status].label` only | Backend | Needs per order live risk, driver offline time, and delivery counts (missing, damaged) from receipt. Build the label composer in `data/orders.ts` once those reads exist. "Awaiting store" label already matches |
| R8 | **03d Order details**: items, temperature class, store window, trip | Rows are not clickable | Build + Backend | Row click opens a side panel. Items need the order lines (`OrderLineView`). Show lines as units, product ids labelled inferred (AGENTS.md catalogue rules). Not seen in Figma, confirm layout first |
| R9 | No-results state with a **Clear search** button, filters stay visible | Plain sentence | Build | Add the button next to the sentence |
| R10 | No "Close orders" card | `Close orders` card per depot | Keep | Keep, it is the only way to send `order:CloseForDay` |
| R11 | No warning banner for stock unknown | Warning notice for `STOCK_UNKNOWN` | Keep | Keep, it is rule 9 (degrade visibly) |

## 3. Plan 1: Decide

Figma layout: collapsed rail, header with the plan switcher and three buttons, a three step bar with a progress underline and a primary button on the right, then the order list card (about 900px) and a detail panel (380px).

| # | Figma | Code | Status | Change |
| --- | --- | --- | --- | --- |
| D1 | Title "Plan Tue 29 Sep", subtitle "Peliyagoda + Kandy · 77 of 85 fit · saved 16:41" | "Plan day", subtitle "depot · Draft version N" | Build | Add "N of M fit" from `summarise`, and the draft saved time (`plan` has a timestamp field? check `PlanView`). Keep the version label, it is more precise |
| D2 | Both depots in one plan | One depot at a time (`PLAN.md` decision 3) | Decided out | None. Revisit only with the team. The decision is the larger conflict with Figma |
| D3 | **PLAN switcher** card: "Working draft ▾" with Auto plan and Snapshot 1.. read-only states (1f, 1s, 1w) | None | Decided out | Needs snapshot storage. None today |
| D4 | **Save snapshot** button, toast "snapshot saved" | None | Decided out | As D3 |
| D5 | **Regenerate ▾** menu (1g), "2 locked" variant (1g2), "can't place locked order" (1u), unlock (1u2), result states (1r, 1r2, 1r3) | One button with `window.confirm` | Decided out (locks) / Build (menu) | Replace `window.confirm` with a small menu: "Regenerate, keep my decisions" or "Regenerate from scratch". Lock part needs a lock flag on orders (decided out) |
| D6 | **Compare** button and screen (4) | None | Decided out | Needs two stored plans to compare. Revisions give two plan versions, so a simple read-only compare of draft against published could be built. Not seen in Figma |
| D7 | Step bar: 1 Decide "8 need a decision" with a progress underline, 2 View plan "24 trips · 2 tight", 3 Publish "Not published yet", **black primary button on the right** that moves to the next step (View plan, then Publish). Done step shows a check and "Done · 8 of 8" | Three tab buttons, Regenerate in the same bar, no next button, no check | Build | Add the primary next-step button (label by step). Add the check and "Done" copy when no open decisions. Add the progress underline |
| D8 | List title "**8 orders need your decision**", right side **"0 of 8 decided"** counter and **Keep the rest deferred** bulk button | "N orders were not placed", no counter, no bulk action | Build | Count orders the dispatcher has acted on this session (placed or explicitly kept). Bulk action keeps every remaining order deferred and records the reason once. Needs a "keep deferred" decision record, today deferral is the engine's own result. See D12 |
| D9 | Row: brand pill (Fresh), outlet and volume ("OUT067 Kurunegala · 7.2 m³ chilled"), centre text "**Last served 2 days ago**", right chip **Swap · high impact**, **Add VEH005 · fits**, or **No swap fits**, chevron, a coloured priority dot | Row: order ref, outlet and fields, "Deferred N× before", pill with `bindingRule` code | Backend + Wording | "Last served" needs the last delivered date per outlet. The chip needs a suggestion per order: **Add Vehicle · fits** comes from the placements read (`/api/plans/preview/placements`, first feasible place). **Swap** needs a swap model (decided out). Replace the code pill with the reason in words |
| D10 | Row group order: priority first | Sorted unservable last, then by deferral count | Build | Same intent. Add the priority dot (amber for waited longest) |
| D11 | Separate card **Too big for any vehicle**: order, outlet, "40.7 m³ · largest vehicle 38 m³", **Contact store manager** button (1e, 1p) | Unservable orders sit in the same list. Panel text says the store is told | Build + Decided out | Split `UNSERVABLE` into its own card with the order's size against the largest vehicle (largest vehicle is in `fleet`). The Contact button is decided out |
| D12 | Detail panel: title, outlet line, Priority "served 2d ago" chip, **Lock** switch ("must be scheduled when you regenerate"), **Candidate trips** with an **Only trips it can fit** switch, a trip card (VEH007 Trip 2 · Puttalam, "Only option" tag) with "**Defer instead: OUT074 Puttalam (ORD0092283)**", reasons, "Why? 2 more reasons", **Open swap window** | Panel: "Why it was not placed" with rule code, All checks list, radio list of places that fit, a toggle for places that do not, reason input, Place button | Build + Decided out | Keep the radio list (that is the real override). Add the **Only trips it can fit** switch in place of the "Show N places it does not fit" link. Drop the rule code from the box. Lock and swap are decided out. Panel footer in Figma: "If deferred: next delivery Wed 30 · first", "Days without delivery 3" and a black **Keep deferred** button. Add the first two (next delivery from the next operating day, days from `deferralCount`/last served) and **Keep deferred** (D8) |
| D13 | States 1c, 1m (add a vehicle), 1d, 1d2 (trip window), 1e (too big), 1i to 1l, 1n, 1p, 1v, 1x, 1y, 1h | Single panel, no trip window | Not seen | Look at each in Figma. Most are the same panel with a different order. 1d trip window is a trip opened over the list |

## 4. Plan 2: View plan

Figma layout: step bar (Decide done, View plan current, black **Publish** button), four KPI cards, then three columns: Deferred (250px), board (about 710px), trip panel (340px).

| # | Figma | Code | Status | Change |
| --- | --- | --- | --- | --- |
| V1 | Four KPI cards: **Orders 79 planned of 85** (red "6 deferred"), **Vehicles 15 in use, 14 idle** (red "9 in workshop"), **Low-load trips 2 trips, 12.4 m³ spare** (tag "under 70% full"), **Late risk 2 high, 77 low** (tag "over 35% chance") | Three cards: Orders, Vehicles, Trips (tight) | Build + Backend | Add Low-load trips: trips under 70% on both volume and weight, spare volume sum, computed from `board()`. Replace "Trips" card: tight count moves to the board. Add "9 in workshop" once O5 exists. Late risk: backend built (#16, WALKTHROUGH says "backend built"), UI decided out. Needs `ml` read of per trip risk |
| V2 | Left column **Deferred (6 orders)**: cards "Fresh Kurunegala OUT067", "Kept deferred · first on Wed", reason chip (No cold space, Too big). Click opens an overlay (Deferred order overlays) | None. Deferred orders appear only on Decide and Publish | Build | New column from `openDecisions(plan, byId)`. Card: brand, district, outlet, deferral line, reason chip in words. "first on Wed" = next operating day. Clicking opens the order panel (overlay not seen) |
| V3 | Board toolbar: **Filter ▾** (Fresh, Chilled, mixes: 2c, 2e) and **Search vehicle, outlet...** | None | Build | Filter by brand and temperature, search by vehicle and outlet. Pure, in `data/plan.ts` |
| V4 | Table columns: VEHICLE, TRIP 1, TRIP 2. Vehicle cell: `VEH003` and `Refrigerated · 26.4 m³` | Same columns. Subtitle is `typeLabel` only | Build | Add capacity (`volumeCapM3`) to the subtitle |
| V5 | Trip cell: brand pill, district, right tag **Tight** (amber) or **Added** (green, a trip the dispatcher added a vehicle to), second line with a temperature dot and "Chilled · 3 stops" | Brand pill, district, Tight, "Chilled · N stops", no dot, no Added | Build | Add the dot (blue chilled, grey ambient). Add **Added** when a trip came from an override (`PlacementView.joins === false`, or the allocation was placed by hand) |
| V6 | Empty cells: dashed card "**Free · refrigerated**" or "**Free · ambient only**" | Dashed "Free" | Build | Label by the vehicle's temperature capability |
| V7 | Selected cell: teal outline | Teal border plus tint | Build | Compare the border weight (Figma 2px). Minor |
| V8 | Trip panel header: "**VEH010 Trip 1** Fresh Colombo", "Depart Peliyagoda 03:30 · **Dock 4** · back 06:07" | "VEH010 Trip 1 [brand] district", "Departs HH:mm · back HH:mm · temperature" | Build | Add depot name and dock (`dockCode` from loading's trip read, glossary term **dock**). Say "Depart" and show the depot |
| V9 | Volume and Weight bars (94% and 92%, amber when tight) with the warning "**! Volume 94%**" and a **More** button (Trip checks overlay, 381:6686 and following) | Bars only | Build | Add the one-line warning under the bars, and a More menu that opens the trip checks (reuse `Checks` from `PlanDecide.tsx`) |
| V10 | Timeline: `03:30 Depart Peliyagoda, Dock 4`, then each stop `03:54 OUT006 · Borella`, window `03:00-08:00`, **a load percentage per stop** (5%, 5%, 5%, 11%, 15%), then `06:07 Back at Peliyagoda, Dock 4`. Drawn as a vertical line with dots | List of stops with the window and a Take off link. No depart row, no back row, no percent, no line | Build | Add the depart and back rows, the line with dots, a per stop percent of the trip's volume (order volume over vehicle capacity, display only, never decides fit). Keep Take off, move it behind Edit |
| V11 | Footer black button **Edit this trip** (Edit trip states 445:xxxx, decided and open decisions) | Two secondary controls: Take off per stop, Move to another vehicle | Build + Backend | Edit mode: reorder stops, move an order between trips, live checks. Reorder and move-between-trips have no command (`plan:Override` places a deferred order, `plan:Replan` moves a whole trip). Not seen. Confirm in Figma, then a command is needed |
| V12 | 2a: undecided orders flagged on the board | Not flagged | Build | Mark deferred orders in the left column (V2) and block Publish, see P1 |
| V13 | 2b (VEH007 Trip 2) and the twelve trip states | One panel for all | Build | Same panel, different data |

## 5. Plan 3: Publish (from the workflow map only)

| # | Figma text | Code | Status | Change |
| --- | --- | --- | --- | --- |
| P1 | 3a "**Publish blocked**: open decisions or a missing reason, jump straight back to it" | Publish button always enabled. The server refuses and the screen shows the refusal | Build | Disable Publish while `decisions` has deferred orders without a recorded decision. The button label links to the first one. Keep the server gate as the truth |
| P2 | 3b ready, 3c published | `PlanPublish` has draft, confirm and published states | Build | Compare to the frames |
| P3 | 2d "**1 change not sent**", 3d "**Send update**", 3e update sent. Only affected drivers and stores are told | Revise needs a typed reason, then a second publish | Backend | A diff of the revision against the published plan, listing affected drivers and stores. Wording "Send update" in place of "Publish revision". Not seen in full |

## 6. Frames not examined

Orders: 03b Upcoming (189:19944), 03c Past (189:20260), 03d Details (189:20558).
Plan: 1b, 1b2, 1b3 swap windows; 1b-ok, 1b-done; 1c, 1m, 1d, 1d2; 1e, 1p; 1f, 1g, 1g2, 1r, 1r2, 1r3, 1s, 1w; 1t, 1u, 1u2, 1u3; 1i to 1l; 1n, 1v, 1x, 1y, 1h; 2a, 2b, 2c, 2d, 2e; 3a to 3e; 4 Compare and the two plan menus; the Trip More, Trip checks and Deferred order overlays; Edit trip frames.
Also unchecked: Live (5 and the 05b to 05n states), Vehicles (06), Forecast (07), Issues (08 to 08d), Overlays (toasts, calls, audience picker), and the dispatcher sign in (01 to 01d).

## 7. Suggested order

1. **UI only, no new data**: S2, O1 (tint and links on existing numbers), R2 tabs shell, R5, R6 dot, R9, D1, D5 menu, D7, D8 counter, D11 card, D12 switch, V3, V4, V5, V6, V8, V9, V10 (depart/back rows and percent), P1. These are the safest and carry most of the visible difference.
2. **Small reads**: R1 cutoff, S3 staff id, V2 deferred column (data exists), V1 low-load card, R3 flow strip.
3. **Backend first**: O2 to O6 (7 day reads, workshop list), R4 and R7 (risk, offline, delivery detail), R8 order lines panel, V11 edit trip commands, P3 send update.
4. **Decided out, re-open only with the team**: snapshots, compare, locks, swap, contact store manager, both depot plan, Reply.

Wording rules to apply while building: 24-hour time, `units`, no raw rule or status codes on screen (`bindingRule`, `ruleId`), "deferred" for orders, "dock" and "refrigerated" as in `GLOSSARY.md`. No em or en dash anywhere.

## 8. Plan: what the data already supplies

Read from `frontend/src/shared/domain/planning.ts` and `data/plan.ts`. Use it to size each Plan row above before building.

| Figma element | Source today | Verdict |
| --- | --- | --- |
| "saved 16:41" in the header | `PlanView` has `publishedAt` only. No created or updated time | Backend: add `updatedAt` to `PlanView`. Until then say nothing rather than guess |
| "77 of 85 fit" | `summarise(plan, fleet)`: served and orders | Build now |
| Fit count for both depots | `usePlans` already takes a list of depots | Reads exist. The blocker is the one-depot decision, not data |
| Depart / back times | `trip.plannedDeparture`, `plannedMinutes`, `after()` | Build now |
| "Dock 4" on a trip | `TripView` has no dock. `dockCode` exists only on loading's `ReadyTripView`, which appears after loading starts | Backend: a dock assignment at plan time, or omit the dock from draft plans. Do not show a dock that nothing assigned |
| Per-stop load % | Order `volumeM3` over the vehicle's `volumeCapM3`, display only | Build now. Needs the order for every stop (already in `orders` map) |
| "! Volume 94%" warning line | `TripLoad.volumePercent` and `tight` | Build now |
| Low-load trips (under 70%, spare m³) | `board()` loads give both percents; spare = cap minus `trip.volumeM3` | Build now. Pick the rule: Figma says "under 70% full", decide volume only, or both measures |
| Tight tag | `TIGHT_PERCENT = 90` | Exists. Figma KPI "2 tight" matches the same idea |
| Added tag | `PlacementView.joins`, but a published `PlanView` does not record which trips were added by hand | Backend or approximate: `AllocationView` has no "placed by hand" flag. Needs a field |
| Free · refrigerated / ambient only | Vehicle `temperatureCapability` in `VehicleView` | Build now |
| Deferred column and reason chip | `openDecisions()`, `allocation.reason` and `bindingRule`. Map the rule id to plain words in one table (no raw ids on screen) | Build now. The map needs a catalogue lookup: reuse `docs/architecture/RULES-AND-POLICIES.md` titles |
| "Kept deferred · first on Wed" | Next operating day: `CalendarDayView` in `referencedata.ts` | Build now. Check the calendar read is exposed to the dispatcher |
| "Last served 2 days ago" | `OrderView.deferralCount` only. No last delivered date per outlet | Backend: last served date per outlet |
| Chip "Add VEH005 · fits" per order | `/api/plans/preview/placements?order=` returns every place with `feasible`. One request per order | Build now for 8 rows (8 reads, only when the Decide tab is open). A batch read would be better at scale |
| Chip "Swap · high impact" | No swap model | Decided out |
| "Only option" tag on a candidate | `fits.length === 1` from the placements read | Build now |
| "Only trips it can fit" switch | `feasible` already splits the list | Build now |
| "If deferred · next delivery Wed 30" and "Days without delivery 3" | Next operating day plus `deferralCount` (an approximation: days is not the same as count) | Build now for next delivery. Days without delivery needs last served |
| Late risk 2 high, 77 low | Not in `PlanView` | Backend: per trip risk from `ml` (#16 built) |
| Edit trip: reorder stops, move order between trips | Commands: `plan:Override` (place a deferred order), `plan:Defer` (take an order off), `plan:Replan` (whole trip to another vehicle) | Backend: reorder and order-to-other-trip are not covered. Move between trips = Defer then Override as a workaround, not atomic |
| Publish blocked until decided | `decisions.length`, server gate R-PLN-07 | Build now (client disable) |
| Send update, only affected drivers | No diff read | Backend |

Net for Plan: about two thirds of the visible differences can be built now with no backend change. The backend items are `updatedAt`, dock at plan time, hand-placed flag, last served per outlet, late risk per trip, stop reordering, and the revision diff.

## 9. Where the Plan rows stand now

Built on 2026-10-03, all of sections 0 (S1, S2 partly), 3, 4 and 5:

- **Shell:** S1 badges for Orders and Live (Plan "Due" has no source), S2 the Plan icon rail.
- **Decide:** D1 to D12 (the counter, bulk keep, the too-big card, the suggestion chip, last served, the panel, the swap window, lock, keep deferred) and D5 to D6 (the Regenerate menu, snapshots, Compare). D2 stays one depot per plan.
- **View plan:** V1 to V12 except the dock (V8: a draft trip has no dock; the screen shows the depot and times) and the Late risk numbers on a draft (a draft is never scored, and the card says so).
- **Publish:** P1 (blocked until decided) and P3 (what a revision changes, and who is told).
- **Words:** no rule id is on screen; `ruleLabel` and a tooltip.

Not built, and why:

- Lock on a deferred order meaning "must be scheduled when you regenerate": a lock here holds an order on its trip.
- "Swap · high impact": the chip says where an order fits ("Add VEH005 · fits") or that no place fits; the swap window is opened from the panel.
- "AI order or default order" in the swap window: the predictor does not give a stop order, so only the default order is shown.
- Sections 1 (Overview) and 2 (Orders) are untouched.

