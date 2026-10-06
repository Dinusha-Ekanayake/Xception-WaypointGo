# 02. Foundation fixes: Phase 0, Phase 1.5, Phase 2 and Phase 2b

The fixes the audit ([00](00-BASELINE-AUDIT.md)) calls for, in build order. The field features depend on these, so they come first. Every item follows the block in the [README](README.md#every-item-records-the-same-things).

## Phase 0: hygiene and safety

Starts the day the roadmap is approved and finishes in week 1. Nothing else starts until H-00 is done, because every later branch would otherwise conflict with the unmerged work.

| ID | Item | Done when |
| --- | --- | --- |
| H-00 | Reconcile `origin/claude/role-ui-intelligence` (19 commits, 89 files) into `main`, resolve conflicts, then create `dev` from it | `git log main..origin/claude/role-ui-intelligence` is empty; CI green on `dev` |
| H-01 | Restore the README from `961d89a`, the newest real version, not from `5ca4aef^` | README has setup, accounts, judge walkthrough and departures |
| H-02 | Run gitleaks over the full history. Rotate every secret it finds, starting with the production administrator password. Set a fixed `PROOF_URL_SECRET` of at least 16 characters per environment. Add gitleaks to CI | Gitleaks clean on new commits; rotation recorded in the log |
| H-03 | Move deploy secrets into one GitHub Environment per deployment (preview, production). Write a rotation runbook beside [deployment.md](../deployment.md) | Both deploys run from Environment secrets |
| H-04 | Refresh [STATUS.md](../development-docs/STATUS.md): auditor console is live on `/api/audit`, remove the duplicate Receipt row, correct EXE-04 (jitter is not used yet), set the review date | STATUS matches the code |
| H-05 | Daily off-server backup of the database and of media (once M-01 lands), to a second provider. Run a timed restore drill | Drill passes with RPO 24 h or less and RTO 4 h or less, written down |

## Phase 1.5: quick wins

Value straight after the baselines, before the larger fixes.

### F-01 Jittered backoff that honours Retry-After

- **Goal:** stop every phone retrying at the same moment after an outage.
- **Change:** call `backoffMs()` (`frontend/src/shared/offline/queue.ts:78`) per entry with full jitter. Respect `Retry-After` from a 429 or 503.
- **KPI:** sync p95 time to confirm under 30 s after reconnect.
- **Tests:**
  - unit test on the delay sequence;
  - browser test: 20 queued writes, server returns 429 with `Retry-After`, no request before it.
- **Done:** EXE-04 is true and its test exists.

### F-02 Request timeouts

- **Change:** `AbortController` timeout in `frontend/src/shared/api/client.ts`:
  - 8 s for reads;
  - 15 s for writes;
  - the kept read is served on timeout, exactly as on a network error.
- **Done:** on a throttled "connected but useless" profile, the kept read appears within 8 s.

### F-03 A separate queue lane for positions

- **Goal:** a GPS backlog never delays a delivery write.
- **Change:**
  - Positions get their own lane in the offline store, coalesced: the latest fix plus a thinned backlog.
  - Delivery, receipt and issue writes keep strict per-device order in the main lane.
- **KPI:** zero delivery writes waiting behind positions.
- **Tests:** browser test with 2,000 queued positions and one delivery; the delivery lands first.

### F-09a Worker executor with a bulkhead per external port

- **Goal:** long calls never block the 16:00 cutoff or other jobs.
- **Change:** one platform worker executor with a fixed number of threads per port (model service, speech-to-text, Jev, ETA), separate from the two-thread scheduler (`B/platform/scheduling/ScheduledJobRunner.java:83`).
- **The job pattern every external call follows:**
  1. An event consumer inserts a pending job row.
  2. A worker claims it with `SKIP LOCKED`.
  3. The worker calls the port with no transaction open.
  4. The worker writes the result in a new transaction.
- **Done:** a 180 s model call never delays a scheduled job (integration test with a slow stub).

### M-03 WebP static assets

- **Change:**
  - Convert raster images in `frontend/public/assets` to WebP. The sign-in skylines are about 1.4 MB each today.
  - No `<picture>` fallback: every target browser decodes WebP.
  - App icons stay PNG.
  - Fingerprinted assets get `Cache-Control: public, max-age=31536000, immutable`.
- **Test:** a Node test fails on any new raster file over 300 KB, or any new PNG or JPG, in `public/assets`.

### Also in Phase 1.5

The following items are defined in their own documents:
- MF-01, Admin Models screen and environment guard, in [05](05-DATATHON-MODELS.md#foundation).
- T1-01, Publish risk section, in [05](05-DATATHON-MODELS.md#task-1-service-time-and-lateness). Built first as a check on the draft's own times: each stop's minutes between planned arrival and window close, amber under 30 and red under 15, on the board, the trip and the Publish step, which lists them and never blocks. No model call before publishing; the model's late risk replaces it once the published plan is scored.
- T2-01, early capacity plan, in [05](05-DATATHON-MODELS.md#task-2a-demand-forecast).
- FF-06a, the fuel log, in [04](04-FIELD-FEATURES.md#ff-06-fuel-log-and-fuel-pass-qr).

### Deferred: reefer swap after a lost vehicle

When a lost reefer's chilled trip finds no reefer, the replan defers it; it never moves an ambient trip off another reefer to free it. Reviewed 2026-10-06 and deferred: ambient rides a reefer on 0.04% of historical reefer routes, and on a day short of reefers every reefer already runs chilled, so the case barely occurs; the dispatcher can do it by hand with two trip moves. Build it only as a suggestion the dispatcher accepts, once Planning knows when a trip's loading has started, with re-loading time and the 08:00 windows in the check, both moves in one revision, released trips frozen (R-PLN-44), and the offer counted so it can be dropped if it fires less than monthly.

## Phase 2: foundation fixes

### F-04 Snapshots, eviction, sign-out and storage quota

**Built 2026-10-07** (EXE-36). Writes skip unchanged content for a minute, rather than hashing on every poll forever, so the saved time shown offline stays honest. Sign-out deletes every saved read; queued writes and uploads stay, because they are work for the same account, and the sign-out sheet already warns about them. `persist()` was already asked for by queuing roles (EXE-33).

- **Snapshots:** write only when the content hash changes, not on every poll.
- **Eviction:** by age (7 days for dated keys) and by count per prefix.
- **Sign-out:** after the outbox drains, clear the account's IndexedDB: saved reads, uploads, crew list.
- **Quota:**
  - Call `navigator.storage.estimate()`.
  - Warn at 80%.
  - Handle `QuotaExceededError` with a clear message.
  - Call `navigator.storage.persist()` for queuing roles.
- **Done:** the code matches the comment at `frontend/scripts/sw-cache.mjs:14`, and tests cover eviction, sign-out and a full disk.

### F-05 Service worker

**Built 2026-10-07** (EXE-37), except the per-role precache: the four roles are imported into one bundle, so there is nothing per role to precache until they are split with dynamic imports (the whole precache is 3.3 MB). Kept as a later item, measured first on a real phone.

- **Precache per role:** each role address caches only its own bundles. One failed file no longer blocks install.
- **Updates:** show "Update ready, reload" instead of reloading under a half-filled form.
- **Background Sync:** the `sync` handler waits for the page's drain result, so the browser retries on failure.
- **Navigation:** 6 s timeout before the cached shell.
- **iOS:** has no Background Sync, so every role drains on `visibilitychange` and `online`. The rollout tells iPhone users to install the app to the home screen.

### F-06 Driver redo and prefetch

**Built 2026-10-07** (EXE-38). A run sheet reaches the phone only once the trip is released after loading, often at night, so the evening prefetch reads tomorrow's published plan instead: from 16:00 the phone keeps the map tiles of the driver's planned stops. The released run is still read and kept as soon as it exists (the look-ahead already did this).

- **Redo:** register a driver resolver (`registerResolver`), so a held write can be redone, not only discarded.
- **Prefetch:** use `prefetchesWorkingSet` to download tomorrow's run, outlets and map tiles on the evening before.

### F-07 Less polling, conditional reads, one stream

**F-07a built 2026-10-07** (PLT-16): the driver screen dropped from about 170 requests a minute on a two-stop run (about 450 on twelve stops) to about 31, held by a browser test at 60 or fewer; polled reads carry an ETag and answer 304 when unchanged (`CacheablePaths`, `ConditionalReadsFilter`); sign-out sends `Clear-Site-Data: "cache"`. **F-07b, still to do:** the dispatcher on server-sent events with a 15 s fallback, and one stream per session with topics.

- **Conditional reads:**
  - ETag and `If-None-Match` on every polled read.
  - Replace the blanket `no-store` (`B/platform/web/SecurityHeadersFilter.java:23`) with `private, no-cache` on those reads, so a 304 is possible.
- **Driver:** one aggregated run read instead of one request per outlet.
- **Dispatcher:** moves to server-sent events with a 15 s fallback poll.
- **One stream per session**, with topics (notifications, positions, threads):
  - heartbeat every 30 s or less, because Cloudflare closes idle connections at 100 s;
  - each watcher's re-read counts against the worker budget.
- **Exit gate:** the driver screen at 60 requests a minute or fewer.

### F-08 Position cadence and light audit

- **Cadence:**
  - Online: send the latest fix every 10 s.
  - Offline: coalesce the backlog.
- **Receipts and audit:**
  - One receipt per batch.
  - An accepted batch gets a light audit row, with no before and after snapshot. A position fix is not a decision under architecture rule 8.
  - A denied batch keeps the full audit.
- **Rule:** amend R-EXE-23 (it currently says every 5 s).
- **Done:** position commands per day fall to about a third.

### F-09b Pool split, only on evidence

- **When:** split the Hikari pool into request and worker pools only if B-04 shows pending connections above zero after F-09a.
- **Why it waits:** a second data source touches `Database`, `SET LOCAL` and transaction handling everywhere.
- **Evidence so far (2026-10-06 baseline):** twelve parallel handover PIN entries against the test pool of 5 left 7 requests waiting past the 3 s connection timeout ("total=5, active=5, idle=0, waiting=7"), answered 503. The PIN check itself is a fast SHA-256, so find out first why a command holds its connection that long. A command that takes a second connection (`REQUIRES_NEW`) while holding its first would deadlock a small pool.

### F-10 Reference generation counter

- **Change:**
  - Add a reference generation number, bumped by any in-place edit (calendar override, outlet details).
  - Publish it **after** commit, not inside the transaction.
- **Planning:** learns the number through `ReferenceQuery` or an event, never by reading Reference's tables. `ReferenceSnapshotCache` (`B/planning/application/ReferenceSnapshotCache.java:31`) keys on it.
- **Done:** an outlet window edit reaches planning on every replica without a restart.

### F-11 Planning claim token

- **Change:** `JdbcGenerationJobs.done()` and `requeue()` check the claim token, so a worker whose claim expired cannot overwrite another worker's result.

### F-12 Retention and indexes

- **Retention jobs:** loop in batches until done, instead of 5,000 rows a night (`B/platform/scheduling/PlatformRetentionJob.java:33`).
- **Partial indexes** for the retention predicates:
  - published outbox rows by `occurred_at`;
  - position payloads by `received_at`.
- **No partitioning.** Partitioning `integration.command_receipts` would put the month into its key and break idempotency for a retry across months (architecture rule 6). This needs an ADR amendment of SYSTEM-ARCHITECTURE §9.3.
- **Revisit** `execution.vehicle_positions` only if it still holds more than 50 million rows after F-08.
- **Dropping indexes:** only with `pg_stat_user_indexes` and `EXPLAIN` evidence.

### F-15 Container memory limits

- **Change:**
  - Memory limits per container in `compose.yaml` and `deploy/vps/compose.vps.yaml`.
  - JVM `-XX:MaxRAMPercentage` for the backend.
- **Why:** the whole stack fits one 16 GB server. Scale the server up before adding replicas. Replicas and a managed database cost about six times more ([08](08-BUSINESS-CASE.md#5-running-cost-per-month)).

## Phase 2b: media and storage

Not on the pilot's critical path. FF-02, FF-03 and FF-06 ship first on the existing database storage, then move here.

### M-01 Object store

- **Ports:** each module keeps its own port in its domain package:
  - `ProofStore` (exists);
  - `IssuePhotoStore`;
  - `VoiceStore`;
  - `FuelPassStore`;
  - `AvatarStore`.

  Their adapters share one technical client, `platform/storage/S3Objects` (AWS SDK v2, configurable endpoint). One shared platform port would break the module rule that platform reaches modules only through ports they own.
- **Config:** `OBJECT_STORE=database|local|s3`, validated in `platform/config`, per the [environment matrix](README.md#environment-matrix).
- **Media streams through the backend:**
  - Authorise and read the metadata in a short transaction, close it, then stream. No database connection is held during the transfer, so a slow 3G upload cannot starve the pool.
  - RLS and audit stay in one place.
  - This supersedes the "signed URLs" line in SYSTEM-ARCHITECTURE §9.3, by ADR amendment.
- **Degrade:**
  - The server answers 503 `DEPENDENCY_UNAVAILABLE`.
  - The phone keeps uploads queued.
  - The screen says "Evidence unavailable".
- **Provider:**
  - Cloudflare R2: private by default, free egress, encrypted at rest. Needs a PDPA cross-border note.
  - Self-hosted alternative: Garage or SeaweedFS.
  - Not MinIO: its community edition is in maintenance mode with no community binaries.
- **Backup:** nightly `rclone` copy to a second location, restore-tested with Postgres.
- **Retention:** the application decides retention and legal hold (P-DG-3). A bucket lifecycle rule is only a backstop.
- **Migration**, expand then contract, issues and voice first, proofs last after two weeks on S3:
  1. Add a nullable `storage_key`.
  2. Read from both places.
  3. Backfill in batches.
  4. Check every object's sha256.
  5. Roll back by setting `OBJECT_STORE=database`.
  6. Drop the `bytea` columns in a separate pull request 30 days later.
- **Exit gate:** restore drill passes; 100% of objects match sha256; the judge walk still works with `database`.

### M-02 One photo encoder

- **One encoder:** `frontend/src/shared/media/photo.ts` replaces `roles/store/data/photo.ts` and `roles/driver/data/image.ts`.
- **Encoding:**
  1. Resize to 1600 px.
  2. Call `canvas.toBlob('image/webp')`.
  3. **Check `blob.type`**, because Safari silently returns PNG when it cannot encode WebP.
  4. Fall back to JPEG.
  5. Cap at 3 MB.
- **Extras:**
  - Re-encoding strips EXIF, including GPS.
  - A 320 px thumbnail is made for lists.
  - The Fuel Pass QR is kept lossless.
- **Blur and darkness check** on the phone (Laplacian variance): asks for a retake before upload. This replaces an AI photo check.
- **Voice notes:** re-encoded to Opus at 24-32 kbps, about 2 MB to about 80 KB.
- **Server:** checks file type by magic bytes, not by the header.

## Sources

- [PWA iOS limitations, Safari support](https://www.magicbell.com/blog/pwa-ios-limitations-safari-support-complete-guide)
- [Offline web apps for field teams](https://www.emberblocks.com/blog/offline-web-apps-for-field-teams)
- [canvas.toBlob falls back to PNG silently](https://dev.to/dailytechid/canvastoblob-can-silently-hand-you-back-a-png-when-it-cant-encode-what-you-asked-for-4blo)
- [MediaRecorder formats across browsers](https://blog.addpipe.com/recording-audio-in-the-browser-using-pure-html5-and-minimal-javascript/)
- [MinIO community edition status and alternatives](https://wz-it.com/en/blog/minio-successor-s3-storage-comparison/)
- [Cloudflare R2 pricing](https://developers.cloudflare.com/r2/pricing/)
