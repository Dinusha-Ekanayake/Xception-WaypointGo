# Issue #12: Execution module: walkthrough

What was built. Rules are R-EXE-01 to R-EXE-15 in [RULES-AND-POLICIES.md](../../architecture/RULES-AND-POLICIES.md), cases EXE-05 to EXE-25 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md), assumptions A-29 to A-31 and parameter P-14 in [ASSUMPTIONS.md](../../architecture/ASSUMPTIONS.md). The plan and its twelve decisions are in [PLAN.md](PLAN.md).

## Layers

Under `backend/src/main/java/com/waypoint/dispatch/execution/`:

- `contract/`: unchanged commands and events. `RunSheetStopView` and `DeliveryRecordView` gained fields (additive), and `ProofView` is new. Mirrored in `frontend/src/shared/domain/execution.ts`.
- `domain/`: `DeliveryRecord` (the state machine), `LatenessPolicy`, `EtaPolicy`, `ServiceWindow`, `FailureReason`, `ProofOfDelivery`, `ProofLink` (HMAC link signing), `ImageKind` (type from the bytes), `ProofStore` (port).
- `infrastructure/`: `JdbcDeliveryRepository` (writes, and the load for a command), `JdbcExecutionReads`, `LocalProofStore`.
- `application/`: `StartStopHandler`, `RecordArrivalHandler`, `RecordDeliveryHandler`, `CaptureProofHandler`, `ReportVehicleStatusHandler`, `ReportFaultHandler`, `ExecutionConsumers`, `RunSheetBuilder`, `ExecutionDataQuery`, `ProofUploads`, `ProofLinks`.
- `web/ExecutionController.java`: reads, the attachment upload, the signed content link.

Elsewhere: `platform/config/ExecutionProperties.java` (`app.execution.*`), migration `20261002T0100_execution_tables.sql`, `PROOF_DIR` and a `waypoint-proofs` volume in `backend/Dockerfile`, `compose.yaml` and `compose.prod.yaml`.

## Flows

- **Run sheet.** `trip.released` reaches `execution.on-trip-released` through the relay. `RunSheetBuilder` writes the trip and one delivery record per stop, copying the order's unit count (Ordering's contract) and the outlet's effective window and mall flag (Reference's contract). A redelivered event builds nothing.
- **Start.** `delivery:Start {deliveryId}` stamps the start and publishes `delivery.started`.
- **Arrive.** `delivery:RecordArrival {deliveryId, deviceArrivedAt?}` settles waiting and lateness from the server clock against the window, and marks the record uncertain when the device clock is more than five minutes away. If the trip is ten or more minutes further behind plan than last announced, each pending stop gets an `expected_arrival` and an `eta.changed`.
- **Record.** `delivery:Record {deliveryId, outcome, deliveredUnits?, reason?, dispositionNote?}`. `DELIVERED` and `PARTIAL` need an arrival and publish `delivery.completed`; `FAILED` is allowed before arrival and publishes `delivery.failed`. Ordering moves the order on either event.
- **Proof.** The device mints an attachment id, sends the bytes with `PUT /api/execution/deliveries/{id}/attachments/{attachmentId}?kind=photo|signature`, and names the id in `delivery:CaptureProof`. Either may arrive first. `GET .../deliveries/{id}/proof` returns links valid for five minutes; `GET /api/execution/attachments/{id}/content?exp=&sig=` serves the artifact with no session, because the signature is the authorization.
- **Reports.** `delivery:ReportVehicleStatus` and `delivery:ReportFault` append a report for a vehicle the actor drives today and publish `vehicle.fault_reported` or `road.disruption_reported`. No stop changes.
- **Replan.** `plan.revised` reaches `execution.on-plan-revised`: a pending stop whose order is no longer on its trip becomes `SKIPPED`.

Every stop command carries `expectedVersion`, and each accepted one moves `row_version` on by exactly one.

Reads: `GET /api/execution/run-sheets?date=` (the caller's vehicles) or `&depot=` (a depot's vehicles), `/vehicles?date=`, `/deliveries?outlet=&date=` or `?order=`, `/deliveries/{id}`.

## Run and verify

```
cd backend
TEST_DATABASE_URL=postgresql://... mvn verify
```

`DeliveryRecordTest` and `ExecutionPoliciesTest` cover the domain with no database. `ExecutionIntegrationTest` publishes `plan.published`, `loading.started` and `trip.released`, lets the real relay deliver them, works the stops over HTTP as a driver, and checks the order reaches `DELIVERED`, `PARTIALLY_DELIVERED` or `FAILED` in Ordering.

By hand, with a released trip: sign in as the driver assigned to its vehicle, `GET /api/execution/run-sheets?date=<service date>`, then send the commands above to `/api/commands` with the stop's `rowVersion`.

## Decisions

Recorded in [PLAN.md](PLAN.md). Three that shape what a client must do:

- A rule violation is `409` with code `CONSTRAINT_VIOLATED` and the rule in `violations`, as in Loading. `422` is a malformed command; `409` with `VERSION_CONFLICT` is a stale version.
- Lateness needs a reason from the driver unless the record is timing-uncertain (A-29).
- An attachment upload is a `PUT` of the raw bytes, not a command, so the offline queue must send it itself. It is idempotent by id and content.

## Known gaps

- The driver screens (#21).
- Receipt and Issues (#13) consume `delivery.completed`, `delivery.failed` and `vehicle.fault_reported`; until they land a failed delivery raises no issue and nothing confirms receipt.
- Nobody consumes `eta.changed`, `delivery.started` or `road.disruption_reported` yet (Notification, #14).
- No virus scan: `scan_status` stays `not_scanned`. No retention job purges artifacts past `retain_until` (#6).
- `LocalProofStore` is one host's disk (A-31).
- Not run on the server; the Docker image with the new volume was not built here.
