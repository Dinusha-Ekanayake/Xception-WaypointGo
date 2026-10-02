# Issue #6: Event backbone, scheduler and audit completion: walkthrough

What was built, and how to check it. The plan is in [PLAN.md](PLAN.md); the rules are R-PLT-01 to 07 in [RULES-AND-POLICIES.md](../../architecture/RULES-AND-POLICIES.md), the cases PLT-02, 03, 04, 07, 09, 10 and POL-03 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md), the parameters P-14, P-24 and P-25 in [ASSUMPTIONS.md](../../architecture/ASSUMPTIONS.md).

## What was built

### Event relay (`platform/messaging/`)

- `OutboxRelay`: one pass claims due rows `FOR UPDATE SKIP LOCKED` as a lease, delivers each event outside that transaction, then settles it. Each subscriber runs under its own module role in its own transaction, after `ConsumerInbox.claim`.
- `RetryBackoff` (pure), `SubscriberRegistry` (startup refuses a duplicate consumer name or an event without `TYPE`), `OutboxRelayWorker` (polling loop, off with `OUTBOX_ENABLED=false`).
- `DeadLetterQuery`, `ReplayEventHandler` (`platform.replay-event`, reason required), `web/DeadLetterController`.
- Migration `20261002T1000_platform_outbox_relay.sql`: lease and publish columns, `outbox_attempts`, retention `DELETE` grants.

### Scheduler (`platform/scheduling/` and two modules)

- `ScheduledJobRunner` writes `integration.job_runs` and counts `waypoint.job.duplicate`.
- `AuditPartitionJob` with the pure `PartitionPlanner`; `PlatformRetentionJob`; `identity/application/SessionRetentionJob`; `referencedata/application/CalendarExhaustionJob`.
- Migration `20261002T1100_platform_scheduler_jobs.sql`: `job_runs`, `ensure_audit_partition` and `detach_audit_partition` as `SECURITY DEFINER` functions executable only by `waypoint_integration`.

### Audit (`platform/audit/`, `platform/messaging/`, `platform/web/`)

- `AuditEntry` and `AuditLog` carry command id, target, before and after, policy generation; `AuditRedactor` (pure) and `AuditContext` (handler opts in to `before`).
- `CommandBus` stamps those fields, takes the correlation id as a parameter, and stores deterministic rejections as receipts (`RejectionReceipt`), replaying them on retry.
- `AuditQuery`, `web/AuditController` (`GET /api/audit`, `GET /api/audit/decisions/{commandId}`), `PolicyHistory` port implemented by `identity/application/PolicyHistoryReader`.
- Migration `20261002T1200_platform_audit_completion.sql`: nullable columns and indexes only.

## Flows

- **Event.** A command's handler calls `EventPublisher.publish` in its transaction, which inserts the outbox row. After commit the relay claims it, runs each subscriber, and marks it `published`. A failing subscriber leaves the event `failed` with a retry time; after 8 attempts it is `dead`, visible at `/api/platform/dead-letters`, and an administrator replays it with a reason.
- **Command.** The bus authorizes, checks the receipt (a stored rejection is rethrown, a stored success is replayed), runs the handler, and commits the state change, receipt and an audit row carrying target, outcome and policy generation together.
- **Job.** The runner takes the advisory lease, records the run, calls `ScheduledJob.run(now)` and records the outcome.

## Run and verify locally

```sh
docker compose up -d db
docker compose exec db createdb -U waypoint waypoint_test
cd backend
TEST_DATABASE_URL=postgresql://waypoint:local-testing-only@127.0.0.1:5432/waypoint_test mvn verify
```

New tests: `OutboxRelayIntegrationTest`, `SchedulerIntegrationTest`, `AuditApiIntegrationTest`, `RetryBackoffTest`, `SubscriberRegistryTest`, `PartitionPlannerTest`, `AuditRedactorTest`, and new cases in `CommandBusTest`. Metrics at `/prometheus`: `waypoint_outbox_lag_seconds`, `waypoint_outbox_dead`, `waypoint_job_duplicate_total`, `waypoint_audit_partitions_ahead`, `waypoint_retention_purged_total`.

## Decisions taken

Recorded in PLAN.md and in R-PLT-01 to 07: full redacted snapshots, 8 attempts with a 5 min cap, 24 month audit retention that detaches rather than drops, dead-letter reads share `platform:ReplayEvent`, only deterministic rejections become receipts.

## Known gaps

| Gap | Owner |
| --- | --- |
| About 23 older `AuditEntry` call sites still take the correlation id from the logging context; only bus-written rows get it as a parameter | Follow-up, per module |
| `before` state is captured only by `vehicle:SetDayStatus`; other handlers opt in with `AuditContext.before` | Each module issue |
| A decision's policy versions are exact only while the policy generation is unchanged; attachments leave no history | Follow-up if auditors need it |
| Archiving detached audit partitions | P-14 |
| Audit and dead-letter screens | #23, #22 |
| `consumed_events` and `command_receipts` purge deletes in batches of 5,000 per run; a very large backlog takes several nights | Raise `BATCH` if seen |
