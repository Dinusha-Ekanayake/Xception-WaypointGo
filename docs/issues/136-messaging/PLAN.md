# Issue #136: Messaging, a thread per trip

Epic #150. The model was chosen on #135 and recorded as ADR-004 in [SYSTEM-ARCHITECTURE.md](../../../SYSTEM-ARCHITECTURE.md): **threads anchored to a subject**. Issues stay an aggregate. The first subject is a trip.

## Why

The Figma timeline (189:21127) puts a red "Exception · click to open" sign on a run where someone reported a problem, and nothing opened it. People could not write to each other at all. Live's "Notify store", "Send an update" and "Voice" were drawn but disabled (#190).

## Decisions (2026-10-04)

| Question | Chosen |
| --- | --- |
| Where messages live | A new `messaging` module, per #136, not Execution: one design the team extends to issues and deliveries |
| Who is on a trip's thread | The depot's dispatcher, the depot's loaders, the vehicle's driver on the date, the stores on the trip |
| Who reads what | The dispatcher reads everything; anyone else reads broadcasts, messages addressed to them, and their own (R-MSG-01) |
| Who writes to whom | Dispatcher: driver, loaders, one outlet, everyone. Driver: dispatcher, an outlet on the trip. Loader, store: dispatcher only (R-MSG-02) |
| Reports | A loader's, driver's or store's report is for the dispatcher alone (R-MSG-03). Every raised issue about the trip becomes one, once, without a second notification (R-MSG-05) |
| When the thread exists | From plan publication, so a loader's shortfall has somewhere to go; read only after the day following the service date (R-MSG-04) |
| Voice | A message or report may be a voice note, uploaded first under the phone's id (R-MSG-06) |
| The bell | Every message reaches the dispatcher's notifications (R-NOT-14) |
| Screens | Dispatcher, driver, loader and store manager |

## Who owns what

| Dependency | Layer | How |
| --- | --- | --- |
| A trip's depot, vehicle, date, outlets | event | `plan.published`, `plan.revised` from Planning, kept on `messaging.threads` |
| A report's wording and raiser | contract query | `IssueQuery.issue` (added, additive) |
| A report's trip | contract query | `ExecutionQuery.deliveryRecord`, `deliveryForOrder`; or the vehicle's thread that day |
| A person's name, a vehicle's driver | contract query | `PersonQuery`, `IdentityQuery.driverOn`, `scopeOf` |
| Membership and visibility | SQL | row-level security through `app.actor_oversees_depot`, `actor_has_depot` with the loader role, `actor_drives`, `actor_has_outlet` |
| Telling people | event | `message.posted` to Notification, routing version 3 |

## Pull requests

1. **Backend:** the migrations (`messaging` schema and role, IAM actions, routing v3), the module, `IssueQuery.issue`, the Notification consumer, tests, registers.
2. **Frontend:**
   - the mirror and the shared thread view;
   - **dispatcher:** timeline warning signs, the thread sheet, the trip page's "Send an update", "Notify store", every message under the bell;
   - **driver, loader and store manager:** the thread with reply, report and voice.

## Verification

- `MessagePolicyTest` (domain).
- `MessagingIntegrationTest` on PostgreSQL:
  - who reads what;
  - who may write;
  - 403 with audit;
  - idempotent resend;
  - notifications per role;
  - reports from a shortfall, once;
  - voice.
- `ModuleBoundaryTest` and `EventCatalogueTest`.
- Frontend: node tests and each role's browser suite.
