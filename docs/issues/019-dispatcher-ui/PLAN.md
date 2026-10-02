# Issue #19: Dispatcher UI: plan for Orders, Plan and Live

Written before code, per AGENTS.md "Issue Documents". What was built is in [WALKTHROUGH.md](WALKTHROUGH.md).

This plan covers the three screens the delivery flow cannot run without: **Orders, Plan and Live**. The rest of the issue stays open and is listed under "Not in this slice".

## Where `dev` stood

- The dispatcher shell, Overview and Vehicles are built. Orders, Plan, Live, Forecast and Issues are a "coming" placeholder (`screens/Upcoming.tsx`).
- Planning (#9) has every command (`plan:Generate`, `Override`, `Defer`, `Publish`, `Revise`, `Replan`) and reads by plan id, the published plan for a depot and day, deferrals, and the two previews. Nobody can reach them from a screen: on a running instance a plan can only be made by posting commands by hand.
- **No read finds the open draft for a depot and day.** `plan:Generate` answers with the plan id, so a reload, or a second dispatcher, loses the draft.
- **No read lists a depot's orders for a day.** Ordering serves an outlet's orders (the store) and the confirmed demand (what Planning will see), not what became of every order due that day.
- Execution (#12) serves run sheets by depot and day; Loading (#10) serves the dock board. Both are enough for Live.
- The design is Figma page "05 · Dispatcher · Desktop": Orders (current), Plan (decide, view plan, publish, compare), Live (map and timeline).

## Which layer owns each dependency

| Concern | Owner | Why there |
| --- | --- | --- |
| The open draft for a depot and day | Planning, `PlanDataQuery.workingDraft`, `GET /api/plans/draft` | Planning owns its runs; the screen must not guess an id |
| A depot's orders for a day | Ordering, `OrderDataQuery.ordersForDay`, `GET /api/orders/day` | Ordering owns orders; scope is applied in SQL by its row-level security |
| What a plan, an order list and a run sheet add up to on screen (counts, load against capacity, which decisions are open) | `roles/dispatcher/data/*.ts`, pure | Tested without a browser |
| Sending a command and reporting a refusal with its violations | `roles/dispatcher/data/useCommand.ts` | One place for the idempotent retry the Vehicles screen already does |
| The screens | `roles/dispatcher/screens/` | Container and view split at about 300 lines |

## Decisions

1. **Override is select and place, not drag and drop** (open decision 1). The dispatcher picks a deferred order, sees every trip it could go on with each rule's verdict from `GET /api/plans/preview/assignments`, and places it with a reason. It works by keyboard and screen reader, and it shows why a place is refused, which a drop target cannot.
2. **The binding rule and its reason are always shown; the full list of checks is on expand** (open decision 2). A deferral never reads as a generic message (R-PLN-19).
3. **A plan is one depot's day, so the Plan screen works on one depot.** With several depots in scope it asks which one; the design's "both depots" plan would be two plans drawn as one, with two publish gates behind one button.
4. **The plan screen never holds a draft id of its own.** It reads the published plan and the open draft for the depot and day, and every command names the draft's `rowVersion`. A `409` because another dispatcher moved the draft reloads it and shows the server's message, which names what changed (PLN-06).
5. **Publishing shows every refusal at once**, as the server returns them, and the button is a deliberate second step.
6. **Live is a list, not a map.** Outlets have no coordinates (A-11), so a map would be invented positions. Each vehicle on the road shows its stops as a progress strip with lateness against the window, from Execution's run sheets; vehicles still at the dock come from Loading's board. "Needs you" is failed and late stops, first.
7. **Live and Orders poll every 30 seconds** while the tab is visible and online, and say "live updates paused" otherwise. No SSE: nothing on the server pushes yet.
8. **The dispatcher stays online only.** Offline, every write control is disabled and the banner says read only.
9. **Where the design shows something no backend provides, it is left out, not faked** (D-D): snapshots and compare, regenerate with locked orders, late-risk percentages, "contact store manager", the map, global search and the notification bell. A stale-draft conflict shows the server's diff in words rather than a diff screen.

## Not in this slice (still open on #19)

Overview's live tiles, skipped outlets with their counts, weekly fuel per vehicle, the Issues inbox, interchange approval, the sync conflict queue, Forecast, and the Playwright flows that need a seeded backend.

## Work breakdown

One pull request into `dev`:

1. Backend: the two reads, each with an integration test and a denied-scope test.
2. `roles/dispatcher/data`: plan, orders and live derivations with unit tests; the command hook.
3. Screens: Orders, Plan (decide, view, publish, revise), Live.
4. Playwright against a mocked API: generate, see a deferral's rule, place it, publish; a refused publish lists its reasons; a stale draft reloads; offline is read only.
5. Walkthrough, register rows, log.
