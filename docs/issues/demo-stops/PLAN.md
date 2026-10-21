# Demo trucks record their own stops

Owner: @Oxshadha. Item 15 of @jv-ransika's plan of 2026-10-21. Demo mode only (issue #231). The folder takes its issue number once the issue is opened.

## Current state

- `demo:StartSimulation` starts one simulation per released trip with an assigned driver; `SimulationJob` sends one `delivery:RecordPositions` per tick through the real bus, as that driver. `RouteWalker` makes the path: straight legs from the depot through each stop, with 4 pause points at every stop.
- R-DEMO-04: the simulator never records an arrival, a delivery, proof or receipt. A simulated trip therefore never progresses past "on the way", and a demo needs a person on every phone.

## Decisions (owner, 2026-10-21)

1. A simulated truck records each stop: arrived at its first pause point, delivered (all units) at its last.
2. The store still confirms receipt by hand. No proof is taken; the delivery says "demo vehicle".
3. The demo driver account's truck is never simulated: a person drives it. The demo driver account is `driver@waypoint.local` (the seeded account of `demo-accounts`).

## How

- `RouteWalker.stops(waypoints)` (pure) gives, for each stop leg, the tick of its first and last pause point. `demo:StartSimulation` keeps them per delivery in `demo.simulations.stops` (jsonb, nullable for simulations started before).
- `SimulationJob`: after the tick's position, at a stop's first pause point it sends `delivery:Start` (when the stop is not under way) and `delivery:RecordArrival`; at the last, `delivery:Record` `DELIVERED` with no lines (every unit) and the note "demo vehicle". Each as the assigned driver, through the bus, with the stop's current version read through `ExecutionQuery.runSheet`; the command id comes from simulation, delivery and step, so a repeated tick is answered by its receipt and records nothing twice. A stop no longer open (skipped by the dispatcher, already recorded by a person) is passed by. A refusal stops the simulation as failed with the reason, as for a position.
- Identity's contract answers the demo driver account (`IdentityQuery.demoAccount("driver")`); Start skips a truck assigned to it with "a person drives it".
- The control room shows "Stops recorded: 3 of 8" per simulation.

## Tests

Domain: the stop ticks fall on the first and last pause point of each leg. Integration: a simulated trip runs to the end with every stop delivered; a repeated tick records nothing twice; the demo driver's truck is not started. Browser: the control room shows the count.

## Documents

R-DEMO-04 rewritten (records arrival and delivery, never receipt or proof, only through real commands); DEMO edge case for a stop skipped while simulated.
