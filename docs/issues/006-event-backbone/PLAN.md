# Issue #6: Event backbone, scheduler and audit completion: plan

Written before code, per AGENTS.md "Issue Documents". What was actually built goes in `WALKTHROUGH.md`, at the end.

## Where `dev` stood

- **Already built:** `OutboxEventPublisher` (writes `integration.outbox_events` in the caller's transaction), `ConsumerInbox` (`integration.consumed_events`), the `EventSubscriber` and `ScheduledJob` ports, a minimal `ScheduledJobRunner` with an advisory-lock lease, and `AuditLog` writing six columns.
- **Already depending on it:** Ordering, Planning, Loading, Receipt, Issues and Warehouse consumers are written against a relay. Their tests drive the consumers by hand, because no relay delivers anything.
- **Missing:** the relay, dead letters and replay; every retention and partition job; audit target and before/after; the `audit:Read` API; receipts for rejected commands.
- **Deadline:** the last audit partition ends 2027-07-01 (`008_command_path.sql`). Every command writes its audit row in its own transaction, so on that day every command fails.

## Ownership and boundaries

- Platform owns the relay, the scheduler, audit and the receipt. Modules reach it only through `EventPublisher`, `EventSubscriber`, `ScheduledJob` and `CommandHandler`.
- A module that needs retention registers its own job (Identity owns `iam.sessions`, so Identity deletes them). Platform never reads another module's tables.
- The relay runs a subscriber under that subscriber's own module role, in a transaction of its own, so no consumer gains rights it does not already have.
- DDL is never granted to the application role. The two operations that need it (create and detach an audit partition) are `SECURITY DEFINER` functions that check their arguments and are executable by the integration role alone.

## Decisions

| # | Decision | Recorded in |
| --- | --- | --- |
| 1 | `before` and `after` are full snapshots with personal fields redacted, not diffs | RULES-AND-POLICIES |
| 2 | 8 attempts, backoff from 2 s doubling to a 5 min cap, full jitter; all under `app.outbox.*` | EDGE-CASES PLT-03 |
| 3 | Audit partitions kept 24 months then detached, never dropped; `app.audit.retention-months = 0` turns detaching off. The archive target is a manual step until P-14 is decided | ASSUMPTIONS P-14 |
| 4 | A claim is a lease. A relay that dies leaves `processing` rows that are reclaimed when `locked_until` passes | EDGE-CASES PLT-02 |
| 5 | Per aggregate order: an event waits while an earlier one of its aggregate is pending, failed or processing. A dead event does not block | RULES-AND-POLICIES |
| 6 | Dead-letter reads need `platform:ReplayEvent`, the same grant as replay. No new action | EDGE-CASES PLT-03 |
| 7 | A rejection is stored as a receipt only when it is deterministic (validation, constraint, conflict, version conflict, not found). Never `FORBIDDEN`, rate limits, timeouts or dependency failures | RULES-AND-POLICIES |
| 8 | The relay never stores a payload or a driver message in an error, only an exception class and, for a `DomainException`, its message | development log |

## Ordered work and acceptance checks

| Step | Work | Evidence required |
| --- | --- | --- |
| 1 | Relay, backoff, subscriber registry, dead-letter read, replay command (migration `20261002T1000`) | An event from a rolled-back transaction is never delivered. A replay is consumed once per consumer. Two relays never double-deliver. A poison event reaches dead letter with its history, does not block other aggregates, and can be replayed. A non-admin replay is denied and audited |
| 2 | Scheduler: run records, partition job, retention jobs, calendar alert (migration `20261002T1100`) | The partition job creates future partitions under an injected clock and is idempotent. Two runners on one job run it once and count a duplicate. Retention removes only rows past their window and never a dead event |
| 3 | Audit completion: target, before and after, redaction, explicit correlation id, rejection receipts, `audit:Read`, decision replay | Audit rows carry command id, target and a redacted snapshot. A rejected command retried replays the rejection and the handler runs once. A non-auditor gets 403 plus an audit row |
| 4 | Documents and walkthrough | Rule catalogue, edge cases, assumptions and the log agree with the code; `WALKTHROUGH.md` written |

Tests precede production fixes. Use a dedicated test database. Record what actually passed and what was skipped; do not call an unexecuted database path verified. Do not commit or push: the owner does that.

## Out of scope

- The audit and dead-letter screens: #23 and #22.
- A real message broker. The relay dispatches in process behind `SubscriberRegistry`, so a broker is an adapter swap with no module change (SYSTEM-ARCHITECTURE §11 #3).
- Archiving detached partitions: needs P-14.
- Adopting `before` capture in every module's handlers. The platform provides it and a first set of handlers use it; the rest follow per module.
