# Issue #12: Execution module: walkthrough

What was built. Rules are R-EXE-01 to R-EXE-15 in [RULES-AND-POLICIES.md](../../architecture/RULES-AND-POLICIES.md), cases EXE-05 to EXE-25 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md), assumptions A-31 to A-33 and parameter P-14 in [ASSUMPTIONS.md](../../architecture/ASSUMPTIONS.md). The plan and its twelve decisions are in [PLAN.md](PLAN.md).

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
- Lateness needs a reason from the driver unless the record is timing-uncertain (A-31).
- An attachment upload is a `PUT` of the raw bytes, not a command, so the offline queue must send it itself. It is idempotent by id and content.

## Known gaps

- The driver screens (#21).
- Receipt and Issues (#13) landed on `dev` while this was built and consume `delivery.completed`, `delivery.failed` and `vehicle.fault_reported`. This branch's tests cover Execution's half and Ordering's; the two together are covered only by running both suites on the merged tree.
- Nobody consumes `eta.changed`, `delivery.started` or `road.disruption_reported` yet (Notification, #14).
- No virus scan: `scan_status` stays `not_scanned`.
- ETA is deterministic (`EtaPolicy`). The owner chose to route it through the #16 estimator, but `TravelAndServiceEstimator` has no travel-time method and no implementation yet; the method is to be agreed with #16 before Execution calls it.
- Not run on the server; the Docker image with the new volume was not built here.

## Follow-up, 2026-10-02

Three owner decisions on top of the module as merged, and one scope fix found while making them.

- **Scope fix (EXE-13).** The read policies let anyone with a depot grant read every vehicle in the depot, and `demo-accounts` gives drivers a depot grant, so a driver could read another driver's run sheet and recipients. Depot-wide reads now need a role that oversees the depot (`app.actor_oversees_depot`: depot grant and dispatcher, admin or auditor), migration `20261002T0900_execution_depot_staff_scope.sql`. `app.actor_holds_role` is `SECURITY DEFINER` and reads only `iam.user_roles`, which has no row-level security, so it answers the same when migrations run as a non-superuser owner, as on Neon. Test: `aDriverWithDepotScopeStillSeesOnlyTheirOwnVehicle`, confirmed to fail without the migration.
- **Delivery product by product.** `20261002T0910_execution_delivery_lines.sql` copies the order's lines at release; `RecordDelivery.lines` names what arrived of each; `DeliveryLines` checks them (every product once, `0 ≤ delivered ≤ ordered`, a partial has a short product and something delivered, a full delivery has every unit) and derives the total when the lines add up to the unit count. Run sheet and record views carry `lines`. PLAN decision 7, revised.
- **Proof in the database.** `DatabaseProofStore` (default, `PROOF_STORE=database`) keeps the bytes in `execution.proof_content`, migration `20261002T0920_execution_proof_content.sql`; files already under `PROOF_DIR` are still read. PLAN decision 6 and A-33, revised.
- **Retention.** `ProofRetentionJob` (`execution.proof-retention`, 02:30) clears bytes past `retain_until` and marks `purged_at`; the attachment row and its hash stay, and the proof view stops offering a link. Gauge `waypoint.execution.proof_bytes_held`. P-14.

Kept as merged, deliberately: a stop recorded offline is timed when the server receives it and marked `timing_uncertain` (A-31, R-EXE-10). Trusting a bounded device clock instead was considered and not done, because R-EXE-10 is binding policy.

