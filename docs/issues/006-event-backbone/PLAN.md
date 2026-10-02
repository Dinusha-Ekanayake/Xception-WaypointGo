# Issue #6: Event backbone, scheduler and audit completion: plan

Written before code, per AGENTS.md "Issue Documents". What was built is in [WALKTHROUGH.md](WALKTHROUGH.md).

This plan covers the first slice of the issue: **delivery**. The scheduler jobs and audit completion stay open on #6 and are listed under "Not in this slice".

## Where `dev` stood

- `OutboxEventPublisher` writes every event to `integration.outbox_events` inside the caller's transaction. `ConsumerInbox` and `integration.consumed_events` exist.
- 26 `EventSubscriber` beans exist across Ordering, Planning and Warehouse. **Nothing calls them outside tests**: no code reads the outbox. On a running instance a placed order never reaches Planning or Warehouse, and a published plan never reaches Ordering or Loading.
- Tests deliver by hand (`deliver(...)` in `OrderingConsumersIntegrationTest`, `PlanningRevisionIntegrationTest`), and the Loading branch ships a `loading-fixture` command for the same reason.
- `platform:ReplayEvent` is in the action catalogue and the administrator policy, with no handler.
- `ScheduledJobRunner` exists (minimal, from #7) and is left as it is.

## Which layer owns each dependency

| Concern | Owner | Why there |
| --- | --- | --- |
| Claiming, delivering, retrying, dead-lettering | `platform/messaging/OutboxRelay` | The relay is platform plumbing; modules only implement `EventSubscriber` |
| Backoff arithmetic | `platform/messaging/RelayBackoff` | Pure, time and randomness are parameters, tested with no database |
| Polling thread and gauges | `platform/messaging/OutboxRelayWorker` | Kept apart from the relay so tests drive delivery deterministically |
| Wake-up after a commit | `platform/messaging/RelaySignal`, called by `OutboxEventPublisher` through `Database.afterCommit` | An event should not wait a poll interval when the process that wrote it can deliver it |
| Replay command and dead-letter read | `platform/messaging/ReplayEventHandler`, `platform/web/EventAdminController` | Platform owns the `integration` schema |
| Settings | `platform/config/RelayProperties` | Typed and validated like every other setting |

## Decisions

1. **In-process dispatch, claimed in SQL.** A batch is claimed with `FOR UPDATE SKIP LOCKED` and marked `processing` with a lease, so several replicas share the work and a crashed one's batch becomes claimable again when the lease lapses (PLT-02). A broker later replaces the dispatch loop, not the subscribers (SYSTEM-ARCHITECTURE section 11 #3).
2. **One transaction per subscriber**, opened by the relay as the subscriber's module role for the system actor, with the inbox claim inside it. One subscriber failing does not undo another's work; on redelivery the inbox makes the ones that succeeded a no-op.
3. **Per-aggregate order, by a sequence column.** `occurred_at` and the UUIDv7 id cannot order two events written in the same millisecond, so the outbox gains `seq` (identity). Only the oldest undelivered event of an aggregate is claimable, so a failing event holds back later events of the same aggregate and nothing else.
4. **Eight attempts, exponential backoff from 2 s capped at 5 min, 20% jitter, then `dead`** (open decision 2 of the issue). Roughly eleven minutes of retries: long enough to ride out a restart or a warehouse outage, short enough that a poison event is visible the same shift. A dead event stops holding back its aggregate (PLT-03).
5. **Bookkeeping runs read committed**, not serializable. The relay's reads of the outbox would otherwise form read-write dependencies with every command that publishes, and cost commands serialization retries for no benefit. Subscribers still run serializable through `Database.asSystem`.
6. **An event with no subscriber is marked delivered.** Subscribers added later do not see history; that is what renaming a consumer is documented to do, and replaying history into a new consumer is a decision, not a default.
7. **The backlog is delivered.** Events written before the relay existed are pending and will be delivered in order on first start. Consumers were written for at-least-once delivery; what cannot be applied reaches dead letter and is listed.
8. **Replay is a command**, `platform:ReplayEvent`, through the bus: it puts a dead event back to pending with its attempts reset, audited. The dead-letter list is read under the same action; a separate read action would need a new policy version and has no second audience.
9. **The worker does not start for CLI commands** (`migrate`, `import-reference`, ...): the schema may not exist yet and the process is about to exit.

## Not in this slice (still open on #6)

- Scheduler: run records, audit partition job (PLT-09), retention jobs, calendar exhaustion alert.
- Audit completion: `before`/`after`, `command_id`, stored rejections, `audit:Read` API, decision replay (POL-03), partition retention (PLT-10).
- Retention of delivered outbox rows.

## Work breakdown

One pull request into `dev`:

1. Migration: `seq`, claim indexes, `platform:ReplayEvent` marked implemented.
2. `RelayBackoff` and its unit test.
3. `OutboxRelay`, `RelaySignal`, `OutboxRelayWorker`, `RelayProperties`; publisher signals after commit.
4. `ReplayEventHandler`, `EventAdminController`.
5. Integration tests: rolled-back event never delivered, exactly once per consumer under redelivery, two relays do not double-deliver, poison event dead-letters and replays, per-aggregate order, a real cross-module flow.
6. EDGE-CASES rows, walkthrough, development log.

---

# Second slice: scheduler jobs and audit completion

Written before code. The relay above landed first (PR #65); this slice is the rest of the issue's "Not in this slice" list, minus relay work.

## Where `dev` stood

- The relay delivers events, but the audit table has partitions only to 2027-07-01 (every command fails after that day), no retention job exists, `ScheduledJobRunner` records nothing, audit rows lack target and before/after, rejected commands leave no receipt, and `audit:Read` has no endpoint.

## Ownership and boundaries

- Platform owns the jobs, the audit table and the receipt. A module that needs retention registers its own `ScheduledJob`: Identity deletes expired sessions, Reference warns about its calendar.
- DDL is never granted to the application role. Creating and detaching an audit partition are `SECURITY DEFINER` functions executable only by `waypoint_integration`.
- Platform may not import Identity, so the audit read asks "which policy versions governed this actor then" through a `PolicyHistory` port that Identity implements.

## Decisions

| # | Decision | Recorded in |
| --- | --- | --- |
| 1 | `before` and `after` are full snapshots with personal fields redacted, bounded to 32,768 characters | R-PLT-07 |
| 2 | Audit partitions kept 24 months, then detached, never dropped; `0` keeps everything | P-26 |
| 3 | Retention windows are configuration: receipts 30 days, published outbox 14, consumer inbox 30 (never shorter than the outbox), job runs 90 | P-25 |
| 4 | Only deterministic rejections become receipts; a denial is never stored | R-PLT-06 |
| 5 | The audit row carries the policy generation; the decision replay says whether the policy versions it returns are exact (generation unchanged) or reconstructed | R-PLT-07, POL-03 |
| 6 | The bus takes the correlation id as a parameter; older call sites still read the logging context | WALKTHROUGH known gaps |

## Work breakdown

1. Migration `20261002T1100`: `job_runs`, partition functions, retention `DELETE` grants. Runner records runs and counts duplicates. `AuditPartitionJob`, `PlatformRetentionJob`, `SessionRetentionJob`, `CalendarExhaustionJob`.
2. Migration `20261002T1200`: nullable audit columns and indexes. `AuditEntry`, `AuditLog`, `AuditRedactor`, `AuditContext`, `CommandBus` (receipts for rejections), `AuditQuery`, `AuditController`, `PolicyHistory`.
3. Tests: partition planner (unit), scheduler and retention (integration), audit API and rejection replay (integration over HTTP), redactor and bus (unit).
