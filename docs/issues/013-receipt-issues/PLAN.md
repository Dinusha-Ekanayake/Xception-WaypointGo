# Issue #13: Receipt and operational Issues modules (`receipt`, `issues` schemas) — plan

Written before code, per AGENTS.md "Issue Documents". What was actually built goes in `WALKTHROUGH.md`.

## Progress (handoff)

Updated after every finished step. Branch `feat/receipt-issues`, from `dev` at `54eeb77`. One commit per step, `feat(receipt): ...`, `feat(issues): ...` or `docs: ...`. Do not add co-author or tool trailers.

| Step | Status | Commit | Notes |
| --- | --- | --- | --- |
| 0 Plan | **done** | `84a09c7` | |
| 1 Domains | **done** | `feat: receipt and issues domains` | `receipt/domain/*`, `issues/domain/*`; `ReceiptTest`, `ReceiptStateMachineTest`, `IssueTest` (24 tests) |
| 2 Schema, repositories, reads | todo — start here | | Two migrations, read endpoints, additive contract changes |
| 3 Receipt commands, consumer, auto-close, Ordering `OnReceiptDisputed` | todo | | |
| 4 Issues commands, consumers, escalation, catalogue and raise rights | todo | | |
| 5 Docs closeout | todo | | WALKTHROUGH, EDGE-CASES, RULES, ASSUMPTIONS, MODULES, development log |

**Environment.** Database tests need `TEST_DATABASE_URL` pointing at a dedicated database, for example `postgresql://waypoint:local-testing-only@127.0.0.1:55432/waypoint_test`. Migrations are checksummed: after editing an unmerged migration, drop and recreate the test database.

## Where `dev` stood

- **Already exists:**
  - the `receipt/contract` and `issues/contract` packages;
  - the `receipt` and `issues` schemas and their `waypoint_receipt` and `waypoint_issues` roles (`20260930T1200`);
  - the catalogue rows (`20260930T1201`), all `implemented = false`;
  - the frontend mirrors `frontend/src/shared/domain/receipt.ts` and `issues.ts`.
- **Already depending on it:**
  - **Ordering** consumes `receipt.confirmed` (→ RECEIVED), `receipt.auto_closed` (→ UNCONFIRMED) and `redelivery.requested`. The redelivery consumer is idempotent on a unique `source_issue_id`. Nothing consumes `receipt.disputed`.
  - **The store UI (#18)** calls `GET /api/receipts/pending?outlet=`, `GET /api/receipts/{orderId}`, and the three `receipt:*` commands with the receipt's `rowVersion`.
- **Not built:**
  - Execution and Notification are contracts only.
  - Loading is on an unmerged branch (`feat/loading`, migrations up to `20261001T1507`).
  - Nothing relays events or runs jobs (#6). Consumers and jobs are beans exercised by tests, as in Ordering and Planning.

## Which layer owns each dependency

| Dependency | Owner | Resolution |
| --- | --- | --- |
| Expected lines, depot, outlet of an order | Ordering | `OrderQuery.order(orderId)` through `Database.readAs`. `delivery.completed` carries neither lines nor a depot |
| Delivery happened | Execution | `delivery.completed` event; Receipt keeps the facts it carries |
| Loading check and proof, for the custody view | Loading, Execution | `ObjectProvider<LoadingQuery>` and `ObjectProvider<ExecutionQuery>`. With no bean, the section reads "unavailable: module not deployed" (rule 9) |
| Problems raised elsewhere | Loading, Execution, Warehouse, Receipt | Events, one issue per source event, deduplicated by a unique `source_key` |
| Who may raise which issue type | Identity | Policy as data: `issue:Raise` on `wpt:issue:type:<TYPE>` |
| Event delivery and job timing | Platform, #6 | Consumers and jobs are beans; tests call `on(envelope)` and `runAt(instant)` |

## Decisions

1. **Dispute flow.** `receipt:Dispute` makes the receipt DISPUTED, which is terminal and never auto-closes, and publishes `receipt.disputed`.
   - A new Ordering consumer moves the order to RECEIVED with the reason "received, disputed".
   - A dispute that arrives after auto-close leaves the order UNCONFIRMED and is counted. The dispute itself lives in Issues.
2. **Raise rights by role, as policy data.** `RaiseIssueHandler.resource()` is `wpt:issue:type:<TYPE>`. Version 3 of three role policies scopes `issue:Raise`:
   - **Store manager:** DAMAGED_GOODS, LATE_DELIVERY, OTHER. A shortage is a `receipt:Dispute`.
   - **Loader:** LOADING_SHORTFALL, DAMAGED_GOODS, OTHER.
   - **Driver:** FAILED_DELIVERY, VEHICLE_FAULT, ROAD_DISRUPTION, LATE_DELIVERY, DAMAGED_GOODS, OTHER.
   - **Dispatcher:** keeps `issue:*`.
3. **P-10 is 24 hours.** It is an effective-dated row in `receipt.parameters`. `closes_at` is stamped when a receipt is created, so a later change never rewrites an existing one.
   - The window doesn't hold up delivery. The order is DELIVERED the moment the driver records it. The window is how long the store has to say that something is missing before the system records that it never answered (R-RCP-05).
4. **Receipt model: per-line received quantities** (D-E), with the statuses in the contract: PENDING, CONFIRMED, PARTIAL, DISPUTED, AUTO_CLOSED.
   - AUTO_CLOSED is the "unconfirmed" state of R-RCP-05.
   - This settles conflict B16 in favour of the contract.
5. **A late report is accepted (RCP-08).** AUTO_CLOSED moves to PARTIAL or DISPUTED, with `late = true`.
   - Such a report publishes `receipt.disputed`, never `receipt.confirmed`, because Ordering's UNCONFIRMED is terminal.
6. **Issue lifecycle** (conflict B17), with the contract's states.
   - OPEN → ASSIGNED, and a reassign is allowed.
   - OPEN or ASSIGNED → RESOLVED → CLOSED.
   - OPEN or ASSIGNED → CANCELLED.
   - The legal graph is in the domain.
7. **Shortage investigation (R-RCP-07).**
   - A disputed receipt, or a confirmed receipt marked partial, opens exactly one RECEIPT_DISPUTE investigation per receipt.
   - It links the order, receipt, delivery and trip.
   - The system actor can never resolve, close or cancel it.
8. **Severity and escalation, as `issues.parameters`.**
   - **Default severity** of an issue a consumer raises:
     - CRITICAL: vehicle fault.
     - HIGH: shortfall, failed delivery, receipt dispute.
     - MEDIUM: road disruption, stock discrepancy.
   - **Escalation deadline** for an issue still OPEN: CRITICAL 15 min, HIGH 60, MEDIUM 240, LOW 1440.
   - Escalation stamps `escalated_at`, writes a history row and counts a metric. It publishes no new event, until Notification exists.
9. **Custody chain view (R-RCP-08, A-17).** `GET /api/receipts/{orderId}/custody` puts the receipt next to the delivery facts it captured, the loading check and the proof. The last two come through optional providers (see the dependency table).
10. **Receipt does not consume `delivery.failed`.** There is nothing to accept. Issues consumes it.
11. **An issue links at least one subject.** A vehicle counts, because fault and disruption events carry no trip.
12. **Additive contract changes only (D-O).**
    - `ReceiptView` gains `tripId`, `depotCode`, `deliveredAt`, `autoClosesAt` and `late`.
    - There is a new `CustodyChainView` and `ReceiptQuery.custodyChain`.
    - These are mirrored in `frontend/src/shared/domain/receipt.ts`.
    - No event record changes.

## Work breakdown

| Step | Content | Tests |
| --- | --- | --- |
| 1 | Domains: `Receipt`, `ReceiptStateMachine`, `AutoClosePolicy`, `ReceiptParameters`; `Issue`, `IssueLifecycle`, `ResolutionAction`, `SeverityPolicy` | State machine edges and `onEvent`; partial arithmetic; late report; a confirmation with no delivery (RCP-04); a resolution needs an action and a reason; the system cannot resolve an investigation |
| 2 | Migrations `20261002T0100_receipt_confirmations`, `20261002T0200_issues_issues`; repositories; `ReceiptDataQuery`, `IssueDataQuery`; read controllers; additive contract changes | Scope by outlet, depot and system; version guard; keyset paging; another outlet's manager gets 403 plus audit (RCP-05) |
| 3 | Receipt handlers, `OnDeliveryCompleted`, `ReceiptAutoCloseJob`, Ordering `OnReceiptDisputed` | Every command over HTTP; a duplicate delivery makes one receipt; auto-close one second either side of 24 h, and a re-run is a no-op (RCP-02); a dispute after auto-close is accepted and linked (RCP-08); a disputed receipt never auto-closes |
| 4 | Issues handlers, consumers, `IssueEscalationJob`, migration `20261002T0300` (catalogue flags, raise rights) | Every command; a driver resolving gets 403; a store raising VEHICLE_FAULT gets 403; a redelivery emits exactly one `redelivery.requested`; a replacement emits `shortfall.resolved`; each consumed event makes one issue; a partial receipt opens one investigation (RCP-01, RCP-06); escalation |
| 5 | WALKTHROUGH and register updates | Full suite and frontend checks |

## Out of scope, and who owns it

| Item | Owner |
| --- | --- |
| Running consumers and jobs in production | Platform, #6 |
| Proof and loading-check details in the custody view | Execution and Loading, filled automatically once their query beans exist |
| A partial redelivery (lines on `redelivery.requested`, A-24) | Follow-up with Ordering |
| Notifying the dispatcher of raised and escalated issues (R-NOT-02, R-NOT-03) | Notification |
| Store and dispatcher screens | #18, #19 |
