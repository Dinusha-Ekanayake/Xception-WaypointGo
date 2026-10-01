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
