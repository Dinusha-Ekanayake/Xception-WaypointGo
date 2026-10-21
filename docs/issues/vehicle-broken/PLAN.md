# A vehicle breaks down: the driver says so, the plan follows

Owner: @Oxshadha. Item 5 of @jv-ransika's plan of 2026-10-21. The folder takes its issue number once the issue is opened.

## Current state (found 2026-10-22)

- Planning already drafts a revision when a vehicle is lost after publication (`PlanningConsumers.OnVehicleStatusChanged`, PLN-04, FLT-01), and Notification already routes `vehicle.status_changed` to the depot's dispatchers. **But nothing published `vehicle.status_changed`**: `vehicle:SetDayStatus` wrote the day status and stopped (MODULES.md: "Reference does not publish it yet"). So a vehicle marked out of use never produced the draft or the alert.
- The driver's "Available" switch sent `delivery:ReportVehicleStatus`, a report only (decision B18), while its sheet told the driver "The dispatcher sees it at once and stops planning trips on it".
- Loading already builds a fresh load sheet from a revised plan, and carries a check over only when the vehicle is the same, so checks made on the broken vehicle never count for another.
- Locked orders on the broken vehicle: already settled by @jv-ransika on 2026-10-21, a lock is "must go out", so they go out on another vehicle.

## Decisions (owner, 2026-10-21)

1. The driver taps broken: the vehicle is out of use at once, for today, until the driver taps OK (back at once, dispatcher told). Before and after the plan is published.
2. The driver may mark only the vehicle they drive today, broken (`unavailable`) or OK (`available`); a dispatcher or an administrator any vehicle and day, as before. This replaces decision B18.
3. The new vehicle's own driver drives it; the loader gets a fresh load sheet; `loading:RequestInterchange` is not built.
4. On screen the word is "vehicle", not "truck" (GLOSSARY: truck only where the type matters).

## Who owns what

| Piece | Layer |
| --- | --- |
| Driver limits, publishing `vehicle.status_changed` once per change | `referencedata/application/SetVehicleDayStatusHandler` (reads `IdentityQuery` for roles and today's vehicle) |
| Driver policy allows `vehicle:SetDayStatus` | `migrations/20261022T1000_reference_vehicle_broken.sql` |
| The dispatcher told at once, in plain words | notification routing version 19, `NotificationConsumers.OnVehicleStatusChanged` |
| Why a draft exists | `PlanView.revisionReason`; `roles/dispatcher/screens/PlanVehicleLost.tsx` |
| The driver's switch | `roles/driver/screens/HomeNoVehicle.tsx`, `VehicleStatusConfirmSheet.tsx`, `useDriver.reportStatus` (through the offline queue) |

A vehicle already on the road is the trip-problems work (End trip), not this.

## Tests

`ExecutionIntegrationTest.theDriverMarksTheirOwnVehicleBrokenAndOkTodayAndTheDispatcherIsToldEachTime` (own vehicle only, today only, broken or OK only, announced once, dispatcher told both ways); `PlanningRevisionIntegrationTest.aVehicleLostAfterPublicationDraftsARevisionWithoutIt` (the draft and its reason); `e2e-driver/vehicle-status.spec.ts`; `e2e-dispatcher/vehicle-broken.spec.ts`; `tests/dispatcher-data.test.ts`.
