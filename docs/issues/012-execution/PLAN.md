# Issue #12: Execution module (`execution` schema): plan

Written before code, per AGENTS.md "Issue Documents". What was built is in [WALKTHROUGH.md](WALKTHROUGH.md). The driver screens are issue #21 and follow in their own pull request.

## Where `dev` stood

- Only `execution/contract` existed: commands, events, views, `ExecutionQuery`. No tables, no handler, no reads. The driver role could sign in and do nothing.
- Loading publishes `trip.released` with the trip's stops (#10). Ordering and Warehouse already consume `delivery.completed` and `delivery.failed`. The relay that delivers them is #6 (PR #65), which this branch is built on.
- `app.actor_drives(vehicle, date)` exists (#5) and `waypoint_execution` may read the assignment table.
- The six `delivery:*` actions and `delivery:Read` are catalogued, unimplemented, and granted: `delivery:*` to drivers, `delivery:Read` to dispatchers and store managers.

## Which layer owns each dependency

| Concern | Owner | Why there |
| --- | --- | --- |
| Stop state machine, lateness, waiting, ETA shift, proof evidence level, failure reasons | `execution/domain` | Pure; every time is a parameter; tested with no database |
| `ProofStore` port | `execution/domain` | The domain names what it needs from storage; the adapter is infrastructure |
| Handlers, consumers, run sheet building, reads, proof upload | `execution/application` | Opens transactions, decides |
| JDBC, local file proof store | `execution/infrastructure` | Only place that knows SQL or the file system |
| Run sheet, record, proof and attachment endpoints | `execution/web` | Routing only |
| Outlet windows | `referencedata` contract | Copied onto the record at release, so a later reference change never rewrites what the driver was held to |
| Item count of an order | `ordering` contract | Read once when the run sheet is built |
| Who drives what, when | `iam`, through `app.actor_drives` in row-level security | Scope is decided in SQL (rule 7) |

## Decisions

1. **One aggregate: the delivery record**, one per order on a released trip, created by `trip.released`. Outcomes: `PENDING → ARRIVED → DELIVERED | PARTIAL | FAILED`, `PENDING → FAILED` (never reached the outlet), `PENDING → SKIPPED` (replanned away). An outcome is recorded once and never changed. `row_version` moves on by exactly one per accepted command, which the offline queue relies on.
2. **Server time decides, always** (R-EXE-10). Arrival and completion are the server's clock when the command is accepted; the device's `clientRecordedAt` is stored beside each. A command replayed after hours offline is therefore timed at the replay. That is the rule as written, and its cost is stated rather than hidden: when the two clocks differ by more than five minutes the record is marked `timing_uncertain`, lateness carries the reason `recorded_after_reconnect`, and the skew is a histogram (EXE-12).
3. **Lateness is against the window close** (R-EXE-14), and waiting is `window open − arrival`, kept apart from service (R-EXE-04). Both are computed at arrival from the window copied onto the record.
4. **A late arrival at a mall outlet cannot be delivered** (EXE-20, decided in #13): `DELIVERED` and `PARTIAL` are refused there once the window has closed, and the driver records `FAILED` with `mall_window_closed`. Ordinary outlets are delivered late and flagged (R-EXE-05).
5. **Open decision 1:** `low_evidence`, `late_reason`, `disposition_note` are columns on the delivery record.
6. **Open decision 2:** proof is kept 400 days (`PROOF_RETENTION`, P-14) and stored through `ProofStore`. The first adapter writes files under `PROOF_DIR`; an S3-compatible adapter replaces it without touching the module. Attachments are capped at 3 MB, JPEG, PNG or WebP, addressed by a client-minted id and verified by SHA-256, so an upload repeated from the offline queue is a no-op.
7. **Open decision 3:** a partial delivery records a unit total, not per-line quantities. The lines are a reconstruction (AGENTS.md "External Product Catalogue") and must not be presented as counted SKUs.
8. **Open decision 4:** ETA is deterministic: the delay observed at the latest arrival, carried to the stops still pending. `eta.changed` is published per pending stop only when the delay moves by ten minutes or more since the last one announced, so a slow road is one message, not one per minute.
9. **Proof never blocks and never replaces the outcome** (R-EXE-11). It is captured after arrival, as an append-only row; with neither photo nor signature a fallback reason is required and the record is flagged lower-evidence. A completed delivery with no proof is visible as such and counted.
10. **Faults are reports, not state changes.** `delivery:ReportFault` records the report and publishes `vehicle.fault_reported` or `road.disruption_reported`; the dispatcher applies `vehicle:SetDayStatus` and replans. `plan.revised` then marks pending stops that left their trip `SKIPPED`. Reference stays a module that consumes nothing.
11. **Scope is row-level security:** a driver sees records of a vehicle they are assigned to on its service date, a dispatcher their depots, a store manager their outlets, the process everything. A record outside scope is `403` plus an audit row, never `404` (EXE-13).
12. **Signed, short-lived proof URLs:** HMAC over attachment id and expiry, five minutes. With no `PROOF_URL_SECRET` the key is random per process, so links die with a restart rather than being forgeable.

## Work breakdown

1. Contract: additive fields on `RunSheetStopView` and a `ProofView`; frontend mirror.
2. Domain and its unit tests (EXE-05, 06, 12, 15, 16, 17, 18, 20).
3. Migration: tables, row-level security, catalogue flags.
4. Infrastructure and application: repository, reads, consumers, six handlers, proof upload, queries.
5. Web: run sheets, record, proof, attachment content.
6. Integration tests: every command, replay through sync, wrong clock, EXE-13 and EXE-14, proof round trip, the events.
7. Registers, walkthrough, development log.

Then #21, the driver screens, on top.
