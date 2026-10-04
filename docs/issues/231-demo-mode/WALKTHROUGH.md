# Demo mode: walkthrough

Issue #231. Plan: [PLAN.md](PLAN.md). Rules R-DEMO-01 to 04 in [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md), cases DEMO-01 to 06 in [EDGE-CASES](../../architecture/EDGE-CASES.md), module contract in [MODULES](../../architecture/MODULES.md) section 14.

## What was built

Demo mode lets one administrator run the whole booklet workflow live, at any time of day, from one screen, without touching the server. It is **off by default**. When it is off, nothing of it renders and every module behaves exactly as before; every existing test passes unedited.

### Backend (`backend/src/main/java/com/waypoint/dispatch/demo/`)

| Layer | Files | What it does |
| --- | --- | --- |
| contract | `DemoView`, `DemoRuntime` | The runtime projection every role reads (`GET /api/demo`) |
| domain | `DemoSettings`, `RouteWalker` | Settings bounds; the pure path of a simulated vehicle (depot, straight legs, a pause at each stop) |
| application | `DemoCommandHandlers` | `demo:Enable`, `Disable`, `SetClock`, `UpdateSettings`; Disable also stops every simulation |
| application | `ResetDayHandler`, `ResetDayJob` | `demo:ResetDay` prepares the peak day on an empty operating day through Reference, Identity and Ordering commands |
| application | `SimulationHandlers`, `SimulationJob` | `demo:StartSimulation`, `demo:ControlSimulations`; the job moves each running vehicle one point every 2 seconds |
| platform | `platform/time/AdjustedClock` | The business clock with the demo offset; security, sessions, receipts and scheduler leases stay on real time |
| web | `DemoController` | `GET /api/demo`, `/api/demo/scenario-runs`, `/api/demo/simulations` |

Migrations: `20261005T0100_demo_runtime.sql` (settings, runs, role, catalogue, policies) and `20261005T1200_demo_simulations.sql` (simulations, two catalogue rows).

### Frontend

- `src/shared/domain/demo.ts`: the contract mirror.
- `src/shared/demo/useDemo.ts`: the shared runtime reader. It returns `null` when demo mode is off or unreadable, so callers fall back to normal behaviour.
- `src/app-shell/DemoBanner.tsx`: the "Demo mode · demo clock HH:MM" banner on every role.
- `src/roles/admin/demo/`: the **Demo control room** tab in the admin console.
  - `DemoControlRoom`: on/off switch, reason, clock, demo day, settings, log.
  - `SimulationsPanel`: start, pause, resume and stop vehicles.
  - `ScenarioDeck` and `scenarios.ts`: nine guided scenarios.
  - `PresenterGuide`: demo accounts with copy, and the 12-step path checklist.
- Driver (`roles/driver/data/position.ts`, `index.tsx`): in demo mode, "Simulate drive to the next stop" sends positions from the phone, stamped with the demo clock, and the flush follows the demo setting.
- Store `LiveMapCard` and dispatcher `usePositions`: refresh every 4 seconds in demo mode instead of 15.

## Flows

**Turn on, move time.**
1. The admin presses "Turn demo mode on" with a reason. This is `demo:Enable` through the CommandBus: admin check, version check, then an audit row and a run row.
2. "After cutoff 16:05" sends `demo:SetClock` with the instant of 16:05 depot time on the demo day.
3. `AdjustedClock` adds the offset to every business decision: cutoff, close, cutoff job, ETAs and lateness.

**Prepare the day.**
1. `demo:ResetDay` records a run in the `preparing` state.
2. `ResetDayJob` sends `reference:PrepareDemoDay`, `iam:PrepareDemoDay` and `order:PrepareDemoDay`, each with a stable command id. The result is 85 confirmed orders at Peliyagoda.

**Drive.**
1. `demo:StartSimulation` reads each vehicle's released run sheet (`ExecutionQuery`), its driver (`IdentityQuery.driverOn`) and the depot and outlet locations (`ReferenceQuery`), then stores the waypoints.
2. Every 2 seconds `SimulationJob` sends `delivery:RecordPositions` as that driver, with command id `hash(simulation, tick)`. Execution validates and stores the point.
3. The dispatcher live map, the store Track map and the driver map all read the stored points.
4. If a point is refused, the simulation stops as `failed` with the reason shown on screen (DEMO-04).
5. The simulator never records arrivals, deliveries, proof or receipt (R-DEMO-04). People do.

**Turn off.** `demo:Disable` zeroes the offset and stops every simulation in the same transaction (DEMO-05). The banner disappears on the next refresh.

## Demo runbook

Windows: store manager and driver at phone width (393 px), dispatcher at 1440 px, loader on a phone, admin on a laptop. Accounts are `store_manager@`, `dispatcher@`, `loader@`, `driver@` and `admin@waypoint.local`, all with the deployment's demo password; the control room lists them with copy buttons.

1. Admin, Demo control room: **Turn demo mode on**, then **Prepare demo day**. Wait for the log to show the preparation completed.
2. **Clock 15:30.** The store places a chilled order and sees it confirmed with its weight and volume.
3. **Clock 16:05.** The dispatcher closes orders, generates the plan, explains the deferrals and publishes.
4. **Clock 05:00.** The loader checks the manifest, flags one line short and releases the trip.
5. **Start every released trip.** The dispatcher's live map shows the fleet moving. The store's Track screen shows its vehicle and arrival time.
6. The driver arrives and records the delivery with a photo. The store confirms receipt and reports the short item.
7. Scenario deck: cancel and refusal, late mall arrival, out of stock, driver offline, access outside scope, as time allows.
8. **Turn demo mode off.** Real time is back.

Backups:
- If preparation fails, its step and reason are in the log. Retry with a new demo day.
- If the warehouse is down, the store shows "stock unknown" (rule 9). Continue with the prepared confirmed orders.
- If a vehicle fails, its reason is on its row. Start that vehicle again after fixing the driver assignment.

## How to verify locally

```
scripts/dev.sh setup && scripts/dev.sh          # admin@waypoint.local, demo password
mvn -Dtest='DemoIntegrationTest,RouteWalkerTest,DemoClockTest,DemoDomainTest' test   # from backend/, with TEST_DATABASE_URL
npx playwright test -c playwright.config.ts demo-control-room                      # from frontend/, after npm run build
```

## Decisions

These are recorded in PLAN.md and R-DEMO-01 to 04.
- The demo date is a fresh empty operating day, not a database restore.
- The simulator acts as the assigned driver, never as the system actor.
- Straight legs between known locations, never invented road geometry.
- Scenarios are guided through the real screens; there are no automated fault injections.

## Known gaps

- **No automated warehouse-outage fault.** The "out of stock" and "warehouse down" cases depend on the live warehouse. The run-scoped fault decorator in PLAN.md was not built, because it would sit in the order placement path.
- **No paused business clock.** Pause and resume apply to vehicles only.
- **No automatic stop recording for background vehicles.** People record the stops.
- **The banner can lag.** It updates on its 15-second refresh, or straight away on reload.
- **Preview rehearsal** of the runbook is owned by the team before the presentation.
