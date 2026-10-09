# 03. Infrastructure decisions

Answers to "do we need RabbitMQ, Redis, partitioning, a bucket, an AI gateway?" for this system at its real scale:
- 120 outlets, 60 vehicles, about 200 orders a day;
- one VPS, about 150 users.

Each decision states its evidence and the trigger that would reopen it. They are written in ADR style and extend ADR-001 to ADR-004 in [SYSTEM-ARCHITECTURE.md](../../SYSTEM-ARCHITECTURE.md). Where one amends that document, the amendment lands with the pull request that builds it.

## Message broker (RabbitMQ, Kafka): not now

**Decision.** Keep the transactional outbox in Postgres with in-process delivery.

**Evidence.**
- The outbox already has:
  - `FOR UPDATE SKIP LOCKED` claims, safe across replicas;
  - per-aggregate ordering;
  - dead letters with replay;
  - backoff with jitter;
  - consumer dedupe in `integration.consumed_events` (`B/platform/messaging/OutboxRelay.java`, `ConsumerInbox.java`).
- Outbox volume is low, because positions are not published as events.
- Postgres as a queue is the right call for a single application whose job is written in the same transaction as the business change. A broker earns its place when many independent teams or services need replayable streams.

**Reopen when** one of these is measured, not asserted:
- an ADR-001 extraction trigger fires;
- an external consumer needs a replayable stream;
- outbox lag p95 stays above 2 s after F-09a.

## Redis and caching: not now

**Decision.** No Redis. Fix staleness with generation counters and HTTP conditional reads.

**Evidence.**
- The real cache bugs are inside the application:
  - planning's per-outlet cache ignores in-place edits;
  - reference republishes inside the transaction.

  A generation counter fixes both (F-10). `PolicyCache` already uses that pattern safely across replicas.
- The load problem is 2 s polling with `no-store`. ETag and 304 (F-07) remove most of it without a new server.

**Reopen when** the backend runs more than one replica and the 20-25 s cross-replica lag on live push matters. Then choose between:
- Redis pub/sub for fan-out;
- `LISTEN/NOTIFY` on one dedicated, unpooled connection. Postgres notifications do not reach standbys, and every listener holds a connection, so one listener per replica fans out to its clients.

## One live stream per session: not now (decided 2026-10-08)

**Decision.** Keep the three server-sent event streams separate: notifications (`/api/notifications/stream`), changes (`/api/changes/stream`, depots or a store's outlets) and positions (`/api/execution/positions/stream`). F-07's "one stream per session, as topics" is not built.

**Evidence.**
- A tab holds at most three: the dispatcher and the store have notifications, changes and, on a map, positions; the driver and the loader have notifications only.
- Production is HTTP/2 (Cloudflare to the browser, `http2 on` at nginx), so the three share one connection and the browser's six-per-site limit of HTTP/1.1 is never reached.
- Each already sends something every 25 s or less (changes 25, notifications 25, positions 20), under Cloudflare's 100 s idle limit, and each falls back to polling when silent.
- One stream would reopen whenever the map's day, depot or trip changes, put three modules' scope checks behind one endpoint, and rewrite the inbox hook the driver and loader screens share.
- Only local `next dev` is HTTP/1.1: two dispatcher tabs there can hold six streams and stall a seventh request. Use one tab, or `npm start`.

**Reopen when** a tab needs a fourth stream, a client reaches the backend over HTTP/1.1 in production, or open streams per backend replica pass what one process holds comfortably (watch `waypoint.changes.listeners`, `waypoint.notification.live_listeners` and `waypoint.execution.position_listeners`).

## Indexing and partitioning

**Decision.**
- Add partial indexes for the retention predicates.
- Make retention jobs loop until done.
- **Do not partition** (amends SYSTEM-ARCHITECTURE §9.3).
- Keep keyset pagination everywhere.

**Evidence.**
- 164 index statements already cover the list screens with keyset indexes. No unindexed foreign key that matters was found.
- Partitioning `integration.command_receipts` would put the month into its key, so a retry across a month boundary would no longer be caught as a duplicate (architecture rule 6).
- The growth problem is position volume and a retention job that removes only 5,000 rows a night (F-08, F-12).

**Reopen when** `execution.vehicle_positions` holds more than 50 million rows after F-08.

## Concurrency

**Decision.**
- **Keep as they are:**
  - serializable isolation with retry on `40001` and `40P01`;
  - `row_version` guards on every update;
  - idempotency receipts keyed by command id and payload fingerprint.
- **Add:**
  - claim tokens on planning jobs (F-11);
  - publish caches after commit (F-10);
  - a worker executor with a bulkhead per external port (F-09a).
- Split the connection pool only if pending connections are measured (F-09b).

**Evidence.**
- Two scheduler threads; ML scoring can hold one for 180 s.
- An 8-connection pool is shared by every workload, and background work alone can want 9 to 12 connections.

## Offline techniques

**Decision.** Five techniques:

1. **Lanes.** Positions are separate from business writes (F-03).
2. **Jittered backoff** that honours `Retry-After` (F-01).
3. **Coalescing.** Only the latest position, with the backlog thinned.
4. **Conditional reads.** ETag and 304 (F-07).
5. **Storage care.** `estimate()`, `persist()`, eviction, and clearing on sign-out (F-04).

iOS has no Background Sync or Periodic Sync, so every role drains in the foreground on `online` and `visibilitychange`. iOS also clears website storage after about seven days of non-use unless the app is installed to the home screen, so the rollout requires installation.

**Not chosen:** CRDTs or last-writer-wins. Proof of delivery is evidence, not collaborative text. A conflict goes to a person.

## Object storage

**Decision.**
- Per-module ports over one S3 client (M-01).
- Cloudflare R2 recommended; Garage or SeaweedFS if it must be self-hosted.
- Media streamed through the backend.
- Off-server copy every night.

**Evidence.**
- Photos and voice in `bytea` bloat the database and its backups.
- R2 charges nothing for downloads and its buckets are private by default.
- MinIO's community edition is in maintenance mode with no community binaries.

**Rejected: phones fetching media directly from the bucket by signed URL.** Streaming keeps row-level security and audit in one place. At this scale it costs only server bandwidth, and it avoids CORS, CSP and service-worker cache problems.

## AI model access

**Decision.**
- OpenRouter is the one gateway, with one API key whose credit limit is the hard spend cap.
- Jev answers typed decisions behind `DecisionPort`; a general LLM writes text behind `LlmGateway`.
- Both sit behind `CircuitBreaker`, are called by workers outside transactions, use pinned model versions, and are a stub in judge and CI.
- The detail is in [06](06-INTELLIGENCE.md).

**Evidence.**
- Swapping models becomes configuration, not code.
- Spend is capped in one place.
- The ports keep the vendor replaceable, which matters because Jev launched on an alpha endpoint in September 2026.

## Migrations

**Decision.**
- **CI check:** fails when two migration files share a timestamp prefix. Six already do, and sort correctly only by module name.
- **Notification routing:** one owner hands out routing version numbers in sequence. The current version is 8, and FF-04 and T1-02 each add one.
- **New `ref` tables:** a new table in `ref` revokes the default module read grants **in the same migration file**, with a test that asserts it (needed for the Fuel Pass).

## CI budget

**Decision.**
- New work is tested mostly with domain and integration tests, plus one browser spec per field feature.
- CI minutes are tracked per month.
- The Sinhala and Tamil screens are limited to the driver's critical path, so the browser suites do not multiply.

## Sources

- [Postgres as a queue](https://env.dev/guides/postgres-as-a-queue)
- [Outbox pattern implementation](https://oneuptime.com/blog/post/2026-01-30-outbox-pattern-implementation/view)
- [Kafka vs Postgres](https://www.conduktor.io/glossary/kafka-vs-postgres.md)
- [Scaling Postgres LISTEN/NOTIFY](https://pgdog.dev/blog/scaling-postgres-listen-notify)
- [Postgres LISTEN/NOTIFY in Spring Boot](https://dzone.com/articles/leveraging-postgres-listennotify-in-spring-boot)
- [PWA iOS limitations](https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide)
- [Offline-first sync patterns](https://developersvoice.com/blog/mobile/offline-first-sync-patterns/)
- [MinIO alternatives 2026](https://wz-it.com/en/blog/minio-successor-s3-storage-comparison/)
- [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/)
- [OpenRouter Jev guide](https://openrouter.ai/docs/guides/community/jev)
