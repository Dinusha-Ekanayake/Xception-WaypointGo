# Issue #6: Event backbone: walkthrough of the delivery slice

What was built. The plan is [PLAN.md](PLAN.md). Cases are PLT-02, PLT-03 and PLT-12 to PLT-14 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md); the convention is in AGENTS.md "Platform Conventions". The scheduler jobs and audit completion of the issue are **not** built; see "Known gaps".

## Layers

Under `backend/src/main/java/com/waypoint/dispatch/platform/`:

- `messaging/OutboxRelay.java`: claim, deliver, settle. The only code that reads `integration.outbox_events`.
- `messaging/RelayBackoff.java`: the wait after a failure. Pure.
- `messaging/OutboxRelayWorker.java`: the thread, and the gauges.
- `messaging/RelaySignal.java`: wakes the worker after a commit in this process. `OutboxEventPublisher` calls it through `Database.afterCommit`.
- `messaging/ReplayEventHandler.java`: the command `platform:ReplayEvent`.
- `messaging/DeadLetterQuery.java` and `web/EventAdminController.java`: `GET /api/platform/events/dead`.
- `config/RelayProperties.java`: `app.relay.*`.

Migration `20261001T2300_platform_outbox_relay.sql`: `outbox_events.seq`, three partial indexes, `platform:ReplayEvent` marked implemented.

No module changed. The 26 subscribers that already existed in Ordering, Planning and Warehouse are picked up as beans.

## Flows

- **Publish.** Unchanged: a handler calls `EventPublisher.publish` in its transaction and the row commits with the change. New: after the commit the publisher signals the relay.
- **Deliver.** The worker calls `OutboxRelay.deliverBatch()` until it returns zero, then waits for a signal or one poll interval (1 s).
  1. Claim: due rows (`pending`, `failed`, or `processing` with a lapsed lease) that are the oldest undelivered event of their aggregate, `FOR UPDATE SKIP LOCKED`, become `processing` with `attempts + 1` and a five minute lease.
  2. For each event, each subscriber of its type gets a transaction of its own as its module role and the system actor: inbox claim, then `on(envelope)`. The envelope is rebuilt from the row, and the correlation id is put back in the logging context so events the subscriber publishes carry it.
  3. Settle: all subscribers done means `published`. Any failure means `failed` with `last_error` and a backoff (2 s doubling to 5 min, 20% jitter), or `dead` on the eighth attempt.
- **Redelivery.** Every subscriber is offered the event again; the inbox answers for those that had applied it, so only the failed ones run.
- **Replay.** An administrator reads `/api/platform/events/dead`, fixes the cause, and sends `platform:ReplayEvent {eventId}` to `/api/commands`. The event returns to `pending` with its attempts reset; the last error is kept.

## Run and verify

```
cd backend
TEST_DATABASE_URL=postgresql://... mvn verify
```

`RelayBackoffTest` (unit) and `OutboxRelayIntegrationTest` cover the definition of done: a rolled-back event is never delivered, a redelivery applies once per consumer, two relays never claim the same event, a poison event dead-letters without blocking the queue and replays, one aggregate's events arrive in write order across a failure, an abandoned claim is retaken when its lease lapses, the worker delivers on the publisher's signal and stays off for a CLI command. The replay command and the dead-letter list are exercised over HTTP, including a dispatcher being refused both.

Checked by hand, not kept as a test: after the Ordering, Planning, Warehouse and command path integration tests ran against a fresh database, the wired relay drained what they had left in the outbox, 43 events of 11 types, to the real subscribers. All 43 were delivered, none failed.

To watch it locally, run the backend and place an order: `integration.outbox_events.status` moves from `pending` to `published` within a second, and `integration.consumed_events` gains a row per subscriber. Metrics at `/prometheus`: `waypoint_outbox_delivered_total`, `waypoint_outbox_retried_total`, `waypoint_outbox_dead_lettered_total`, `waypoint_outbox_consumed_total`, `waypoint_outbox_lag_seconds`, `waypoint_outbox_open`, `waypoint_outbox_dead`.

## Decisions

All nine are in [PLAN.md](PLAN.md). The ones a later change is most likely to trip over:

- **First start delivers the backlog.** Every event written before the relay existed is still `pending` and is delivered in write order. For Warehouse this includes real status calls for orders cancelled, released or delivered since the module landed.
- **Tests run with the worker off** (`backend/src/test/resources/config/application.properties`). A test that wants delivery builds a relay or calls the wired one; nothing is delivered behind a test's back.
- **A new subscriber does not see history.** An event with no subscriber at delivery time is settled.
- **A replayed dead letter arrives out of order** within its aggregate.

## Known gaps

Still open on #6:

- Scheduler: run records, the audit partition job (PLT-09, the last partition ends 2027-07-01), retention jobs including delivered outbox rows, calendar exhaustion alert. `ScheduledJobRunner` is still the minimal one from #7.
- Audit completion: `before`/`after`, `command_id`, stored rejections, `audit:Read`, decision replay (POL-03), PLT-10.
- No frontend mirror of `DeadLetterView`; the admin console (#22) adds it with the screen.
- The correlation id still travels through the logging context rather than as a parameter.
- Not run on the server. The backlog decision above has only been exercised against test data.

---

# Second slice: scheduler jobs and audit completion

Rules R-PLT-04 to 07, cases PLT-04, 07, 09, 10 and POL-03, parameters P-25 and P-26.

## What was built

### Scheduler (`platform/scheduling/` and two modules)

- `ScheduledJobRunner` writes `integration.job_runs` and counts `waypoint.job.duplicate`; a run that cannot be recorded still runs.
- `AuditPartitionJob` with the pure `PartitionPlanner`: creates the current month and three ahead, raises `waypoint.audit.partitions_short` under two future months, detaches partitions older than 24 months.
- `PlatformRetentionJob`: receipts, published outbox events (by `occurred_at`, because the relay does not stamp delivery), consumer inbox, job runs. A dead event is never purged.
- `identity/application/SessionRetentionJob` (expired sessions, old sign-in attempts) and `referencedata/application/CalendarExhaustionJob` (daily warning under 30 days).
- Migration `20261002T1100_platform_scheduler_jobs.sql`: `job_runs`, `ensure_audit_partition` and `detach_audit_partition` as `SECURITY DEFINER` functions, `DELETE` on three bookkeeping tables.

### Audit (`platform/audit/`, `platform/messaging/`, `platform/web/`)

- `AuditEntry` and `AuditLog` carry command id, target, before and after, policy generation. `AuditRedactor` (pure) replaces personal fields by name at any depth; `AuditContext` lets a handler record `before`.
- `CommandBus` stamps those fields, takes the correlation id as a parameter, and stores deterministic rejections as receipts (`RejectionReceipt`), replaying them on retry.
- `AuditQuery` and `AuditController`: `GET /api/audit` (filters: actor, target, action, decision, correlation id, command id, time range; keyset) and `GET /api/audit/decisions/{commandId}`. Both need `audit:Read`.
- `PolicyHistory` port, implemented by `identity/application/PolicyHistoryReader`.
- Migration `20261002T1200_platform_audit_completion.sql`: nullable columns and indexes only.

## Flows

- **Command.** The bus authorizes, checks the receipt (a stored rejection is rethrown, a stored success replayed), runs the handler, and commits the state change, receipt and an audit row with target, redacted outcome and policy generation together.
- **Job.** The runner takes the advisory lease, records the run, calls `ScheduledJob.run(now)`, records the outcome.

## Verify locally

```sh
cd backend
TEST_DATABASE_URL=postgresql://waypoint:local-testing-only@127.0.0.1:5432/waypoint_test mvn verify
```

New tests: `SchedulerIntegrationTest`, `AuditApiIntegrationTest`, `PartitionPlannerTest`, `AuditRedactorTest`, and new cases in `CommandBusTest`. Metrics: `waypoint_job_duplicate_total`, `waypoint_audit_partitions_ahead`, `waypoint_audit_partitions_short_total`, `waypoint_retention_purged_total`.

## Known gaps

| Gap | Owner |
| --- | --- |
| About 23 older `AuditEntry` call sites take the correlation id from the logging context; only bus-written rows get it as a parameter | Follow-up, per module |
| `before` is captured only by `vehicle:SetDayStatus`; other handlers opt in with `AuditContext.before` | Each module issue |
| A decision's policy versions are exact only while the policy generation is unchanged; attachments leave no history | Follow-up if auditors need it |
| Archiving detached audit partitions | Manual until an archive target is chosen |
| Audit and dead-letter screens | #23, #22 |
| Each retention run deletes at most 5,000 rows per table | Raise `BATCH` if a backlog is seen |
