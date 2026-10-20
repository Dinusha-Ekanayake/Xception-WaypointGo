# Live refresh: every screen on the change stream, polling only as a safety net

Owner: @Oxshadha. Builds on roadmap F-07b (the change stream). The folder takes its issue number once the issue is opened.

## Current state (2026-10-20)

- The backend publishes a signal for every committed event (`platform/messaging/ChangeSignals`, fed by `OutboxEventPublisher` after commit): a topic (the event type before the dot) and the payload's depot, or its outlets for a store. Changes are coalesced for 500 ms; a `ping` goes out every 25 s. `GET /api/changes/stream` serves it as server-sent events, `depot=` under `plan:Read` or `outlet=` under `receipt:Read`, scope-checked before the stream opens.
- The signal carries no data. A screen re-reads its usual endpoint, so the read, under the reader's scope, decides what is shown.
- On the stream before this plan: the dispatcher's day reads (`useDay.ts`) and the store's orders, receipts and deliveries. They polled every 15 s while the stream was up and every 2 s when it was down.
- Not on the stream: the loader's dock board and load sheet (every 2 s, always), trip messages (10 s), the store's issues (60 s). Notifications have their own stream (`/api/notifications/stream`, 30 s fallback).
- Polling already pauses in a hidden tab and while offline (`useResource`).
- Gaps: a signal reaches only screens connected to the backend instance that committed the change; a signal has no version, so every listening screen re-reads; after a reconnect every screen re-reads everything; a dispatcher tab may hold three streams (changes, notifications, positions).

## Goals

A change shows on other screens within 1-2 s while online; an idle screen costs almost nothing; a screen never looks live while stale (rule 9); no-signal mode is unchanged; a signal never carries data.

## Who owns what

| Piece | Layer |
| --- | --- |
| Signal production and fan-out | `platform/messaging` (ChangeSignals, OutboxEventPublisher, later the LISTEN/NOTIFY bridge) |
| Stream endpoint, authorization, scope | `platform/web/ChangesController`, `platform/messaging/ChangeWatchers` |
| Topics a screen re-reads on | each role's `data/` (for example `roles/loader/data/live.ts`) |
| Poll intervals, stream status, re-read | `shared/api/useResource.ts`, `shared/api/changes.ts` |
| Conditional reads (ETag, 304) | each module's `web/` controller, through a shared helper in `platform/web`; the Next proxy forwards the two headers |

## Decisions

1. **Safety net 60 s while the stream is up, at least 10 s while it is down.** Chosen over 15 s and 2 s: the stream says when to read, and every screen polling every 2 s is the load the stream exists to remove. A read with no stream keeps its own interval.
2. **The driver stays on polling for now.** A driver's scope is a vehicle, not a depot, so the depot stream refuses them; a vehicle watch is phase 3.
3. **Server-sent events, not WebSockets.** Signals flow one way; SSE reconnects by itself, carries `Last-Event-ID` for phase 5 and passes through the proxy as plain HTTP.
4. **Postgres LISTEN/NOTIFY for several backend instances, not Redis or Kafka.** One database already; a signal is under 200 bytes, well inside NOTIFY's 8 kB.

## Phases (one pull request each)

1. **Every screen on the stream** (frontend only). Loader board and load sheet on the depot stream (`plan, trip, loading, shortfall, issue, vehicle`); trip messages on the thread's depot, or the store's outlets (`message`); store issues (`issue, receipt`). Safety net 60 s up, 10 s down. Test: a loader board re-reads on a signal and does not poll every 2 s (`tests/e2e-loader/stream.spec.ts`). Result: a loader tablet goes from about 60 requests a minute to about 1 when idle.
2. **One stream per tab.** Changes, notifications and positions as named events on one connection, so two tabs never exhaust the browser's six connections per host on HTTP/1.1.
3. **Narrow signals with a version.** A signal names the record and its new `row_version` (never content); a screen that already holds that version skips the read. Add a vehicle watch for the driver.
4. **Cheap unchanged reads.** List and detail reads answer `ETag` and `304 Not Modified`; better, a read checks the newest version for its depot and day before querying. The Next proxy's header allow-lists gain `If-None-Match` and `ETag`.
5. **Catch-up after a gap.** Each signal carries the outbox sequence as its SSE `id`; on reconnect the server replays signals since `Last-Event-ID` for up to 15 minutes, else sends `reset` and screens read once. No-signal order unchanged: the device queue drains first, then the stream reconnects, with jittered retries.
6. **Several backend instances.** The relay issues `NOTIFY waypoint_changes` per committed event; each instance `LISTEN`s and feeds its own ChangeSignals.

## Measuring

Requests per screen per minute, signal-to-screen time, 304 share, replays against resets, and `waypoint.changes.listeners`. Each phase states its before and after in its pull request.

## Not in this plan

Pushing data itself down the stream (screens keep reading through their scoped endpoints), and offline behaviour, which `shared/offline/` already decides.
