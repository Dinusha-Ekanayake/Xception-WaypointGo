# Issue #13 walkthrough: the Receipt and Issues modules

What was built for [issue #13](https://github.com/kavindamihiran/Xception-WaypointGo/issues/13), how each flow runs, and what is left.

- **Approach and decisions:** [PLAN.md](PLAN.md).
- **Rules:** [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md), under `R-RCP-*` and `R-ISS-*`.
- **Edge cases:** [EDGE-CASES](../../architecture/EDGE-CASES.md), under `RCP-*` and `ISS-*`.
- **Parameters and assumptions:** [ASSUMPTIONS](../../architecture/ASSUMPTIONS.md), P-10, P-20 to P-23, A-29 and A-30.
- **Log entries:** the [development log](../../development-docs/development-log.md).

Branch `feat/receipt-issues`, one commit per step.

## What exists, layer by layer

All paths are under `backend/src/main/java/com/waypoint/dispatch/` unless they start with `migrations/` or `frontend/`.

| Layer | Receipt (`receipt/`) | Issues (`issues/`) |
| --- | --- | --- |
| contract | `ReceiptViews` gains `tripId`, `depotCode`, `deliveredAt`, `autoClosesAt`, `late`, `CustodyChainView`, `DeliveryFacts`; `ReceiptQuery.custodyChain`. Additive only | `IssueViews.IssueHistoryView`; new event `IssueEscalated` (`issue.escalated`); `ShortfallResolved` gains an optional `shortfallId`. Additive only |
| domain | `Receipt`, `ReceiptLine`, `ReceiptStateMachine`, `AutoClosePolicy`, `ReceiptParameters` | `Issue`, `IssueLifecycle`, `ResolutionAction`, `SeverityPolicy` |
| application | `ReceiptAnswerHandler` and `ReceiptHandlers` (three commands), `ReceiptConsumers.OnDeliveryCompleted`, `ReceiptAutoCloseJob`, `ReceiptDataQuery` | `RaiseIssueHandler`, `IssueCommandHandler` and `IssueHandlers` (six decisions), `IssueScope`, `IssuesConsumers` (seven events), `IssueEscalationJob`, `IssueDataQuery` |
| infrastructure | `JdbcReceiptRepository` | `JdbcIssueRepository` |
| web | `ReceiptController`, `/api/receipts` | `IssueController`, `/api/issues` |
| schema | `migrations/20261002T0100_receipt_confirmations.sql`, `T0300_receipt_actions_implemented.sql` | `migrations/20261002T0200_issues_issues.sql`, `T0400_issues_actions_and_raise_rights.sql` |

Outside the two modules:
- **Ordering** gains `OrderingConsumers.OnReceiptDisputed`.
- **Frontend** mirrors the contract additions in `frontend/src/shared/domain/receipt.ts` and `issues.ts`. The store fixture in `frontend/src/roles/store/data/fixtures.ts` fills the new fields.

## The schema

**Receipts.**
- `receipt.confirmations` holds one receipt per order and per delivery. It keeps the delivery facts it was opened with (driver, units) and the auto-close deadline stamped when it opened.
- Lines and history are child tables.
- `receipt.parameters` holds P-10. The runtime role can read it but never edit it.

**Issues.**
- `issues.issues` carries a generated `severity_rank` for the inbox order, and a unique `source_key` so one event raises one issue.
- Subjects and history are child tables, and subjects cannot be edited after the raise.
- `issues.parameters` holds the default severities and the escalation deadlines.

**Scope.** Both schemas use forced row-level security:
- **Receipts:** the system, the outlet's manager, and the depot's dispatcher.
- **Issues:** the same, plus whoever raised the issue.

**Database backstops.** Neither role has DELETE. CHECKs back the domain:
- An answered receipt has a person behind it.
- A resolution has all four parts: action, reason, person and time.
- The system actor can never resolve an investigation.

## Flows

### A delivery opens a receipt (`delivery.completed`)

`OnDeliveryCompleted` opens a PENDING receipt:
- **Lines** come from `OrderQuery.order(orderId)`, because the event carries none.
- **Facts:** the depot, the trip, the driver named on the envelope, the units delivered, and the auto-close deadline from P-10.
- **Idempotent:** a redelivered event finds the receipt and does nothing.

### The store answers (`receipt:Confirm`, `receipt:ConfirmPartial`, `receipt:Dispute`)

`POST /api/commands` → `CommandBus` → `ReceiptAnswerHandler`, in one transaction as `waypoint_receipt`:

1. **Find the receipt.** If there is none, no delivery was recorded: 404 (RCP-04).
2. **Check scope.** The receipt's outlet is read as the system, in a read-only transaction of its own. The actor must hold that outlet, or the bus answers 403 and audits it (RCP-05).
3. **Check the version.** A stale `expectedVersion` is 409.
4. **Let the domain decide, then announce.** The receipt, its history row and the event commit together.
   - **Confirm:** `receipt.confirmed`.
   - **Partial:** `receipt.confirmed` with `partial`. After auto-close the order is already UNCONFIRMED, so a late shortage is announced as `receipt.disputed` instead (RCP-08).
   - **Dispute:** `receipt.disputed`. Ordering marks the order RECEIVED, and the dispute lives in Issues.

### Silence (`receipt.auto-close`, every 15 minutes)

`ReceiptAutoCloseJob` closes each PENDING receipt past its deadline as AUTO_CLOSED, in its own transaction, as the system.
- It publishes `receipt.auto_closed`, and Ordering marks the order UNCONFIRMED (R-RCP-05).
- Re-running it changes nothing.
- A disputed receipt never closes.
- It keeps the `waypoint.receipt.unconfirmed` gauge current.

### Problems become issues

**Raised by a person** (`issue:Raise`):
- The bus checks the type against the role policies (R-ISS-07).
- The handler checks that any outlet named belongs to the depot, and that the actor holds the depot or that outlet.

**Raised by an event** (`IssuesConsumers`), one issue per event at the policy's default severity:

| Event | Issue |
| --- | --- |
| `loading.shortfall` | LOADING_SHORTFALL, about the shortfall, trip and order |
| `delivery.failed` | FAILED_DELIVERY |
| `vehicle.fault_reported` | VEHICLE_FAULT, about the vehicle alone |
| `road.disruption_reported` | ROAD_DISRUPTION |
| `warehouse.discrepancy_found` | STOCK_DISCREPANCY |
| `receipt.disputed`, or `receipt.confirmed` with `partial` | one RECEIPT_DISPUTE investigation per receipt, linked to the order, receipt, delivery and trip (R-RCP-07) |

### Decisions on an issue

`IssueCommandHandler` uses the same scope, version and transaction pattern as receipts. Every decision writes a history row with the actor, the action and the reason.
- **Assign:** OPEN → ASSIGNED, and a reassign is allowed.
- **Resolve:** needs an action and a note. An investigation is refused for the system actor.
- **Record a replacement:** loading shortfalls only. Publishes `shortfall.resolved` for Loading, naming the shortfall when the issue was raised from one, so Loading resolves that shortfall alone (ISS-07).
- **Schedule a redelivery:** exactly once per issue, never for a past date. Publishes `redelivery.requested`, and Ordering creates one linked order.
- **Close:** RESOLVED → CLOSED.
- **Cancel:** needs a reason.
- Resolving events also publish `issue.resolved`.

### Escalation (`issues.escalation`, every 5 minutes)

`IssueEscalationJob` stamps an OPEN, unassigned issue once at its severity's deadline (P-20 to P-23), with a history row and a metric, and publishes one `issue.escalated` for Notification to route to the depot's dispatchers. It also refreshes the gauges for open issues by severity and the oldest open age.

### Reads

- **`/api/receipts/pending?outlet=`** and **`/api/issues?depot=`**: a list outside scope is 403 plus an audit row, never an empty list.
- **`/api/receipts/{orderId}`**, **`/api/issues/{id}`** and **`/{id}/history`**: one row outside scope is 404.
- **`/api/issues/by-subject`**: returns what the actor can see.
- **`/api/receipts/{orderId}/custody`**: the receipt, the delivery facts, the loading check from Loading and the proof from Execution. A module that isn't deployed is named in `unavailable` rather than shown as empty (rule 9). It fills in once Loading and Execution ship their query beans.

## Running and verifying it locally

```bash
cd backend
TEST_DATABASE_URL=postgresql://<user>:<password>@127.0.0.1:<port>/<dedicated_test_db> mvn test
cd ../frontend && npm run typecheck && npm test
```

| Test | Proves |
| --- | --- |
| `receipt/domain/*Test`, `issues/domain/IssueTest` | Both state machines, partial arithmetic, late reports, the window, resolution rules, investigations, escalation. No database |
| `ReceiptSchemaIntegrationTest`, `IssuesSchemaIntegrationTest` | Scope, the version guard, no DELETE, parameters, the inbox order and keyset, deduplication, the CHECK backstops |
| `ReceiptCommandIntegrationTest`, `IssuesCommandIntegrationTest` | Every command over HTTP: replay, 409, 403 plus audit, raise rights by type, exactly one redelivery |
| `ReceiptConsumersIntegrationTest`, `IssuesConsumersIntegrationTest` | Consumers through a stand-in relay, auto-close and escalation run at chosen instants |

**By hand:**
1. Close a delivery by delivering `delivery.completed` to `receipt.on-delivery-completed`.
2. As the store, `GET /api/receipts/pending?outlet=`.
3. Send `receipt:ConfirmPartial`.
4. Deliver the resulting `receipt.confirmed` to `issues.on-receipt-confirmed`.
5. As the dispatcher, `GET /api/issues?depot=` shows the investigation.

## Decisions and where they are recorded

- **Dispute flow, raise rights, P-10 and the receipt model:** PLAN.md decisions 1 to 4. B16 and B17 are settled for the contract's states.
- **Lifecycle, raise rights, escalation and redelivery once:** R-ISS-01 to R-ISS-07 in RULES-AND-POLICIES.
- **New edge cases:** RCP-09 and ISS-01 to ISS-06 in EDGE-CASES. The RCP, EXE-07, EXE-08, LOD-01 and STK-11 rows now name their tests.
- **Parameters and assumptions:** P-10 and P-20 to P-23 in ASSUMPTIONS. A-29 (drivers hold their depot's scope) and A-30 (a whole-order confirmation means every line arrived).
- **MODULES §7 and §8** corrected:
  - `receipt.partial` is `receipt.confirmed` with `partial`;
  - `redelivery.scheduled` is `redelivery.requested`;
  - Receipt does not consume `delivery.failed`;
  - the consumes lists and invariants match the code.

## Known gaps and who owns them

Checked against the full description of each module issue. A gap goes to the module that owns the code: a comment or checklist item on that issue, never a separate issue for its own sake.

| Gap | Owner | State |
| --- | --- | --- |
| No relay or scheduler runs the consumers and the two jobs; the tests stand in for both | #6 Event backbone, open. Its checklist names receipt auto-close | Covered |
| The custody view's loading check and proof are "unavailable" until their query beans exist (`LoadingQuery.manifest`, `ExecutionQuery.deliveryRecord`); RCP-06 and RCP-07 then become testable end to end | #10 Loading and #12 Execution, open; both list these queries | Covered |
| Routing `issue.raised`, `issue.escalated` and `receipt.disputed` to dispatchers | #14 Notification, open. Its matrix covers every catalogue event; MODULES §9 now has the `issue.escalated` row | Covered |
| Loading's consumer should resolve only the shortfall `shortfall.resolved` names (ISS-07) | #10 Loading, open. Not in its description: comment needed | Needs a comment on #10 |
| Driver raise scope by vehicle and date instead of the temporary depot grant (A-29) | #13 code, using `driverVehicleOn` from #5, open | Needs a comment on #5; #13 follow-up |
| An assignee is checked to exist, not to hold the issue's depot | #13, using `scopeOf` from #5 | #13 follow-up, blocked on #5 |
| A redelivery is always the whole order (A-24 asks the Issues owner to decide) | #13 | Decision pending in #13 |
| The dispatcher's issues inbox screen | #19 Dispatcher UI lists it, but #19 closed on 2026-09-30 before this backend existed; on `dev` the screen is a placeholder | Ask the owner to reopen #19, or open a follow-up linked to it |
| The store's receipt screen | #18 Store manager UI, closed; the screen exists and calls these endpoints | Done |
