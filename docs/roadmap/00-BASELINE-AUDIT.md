# 00. Baseline audit at handover

What the repository looked like when the team took it over (October 2026), with evidence. Every later document points back here. Paths are from the repository root; `B/` means `backend/src/main/java/com/waypoint/dispatch/`.

This is a snapshot. When a finding is fixed, the fixing pull request says so in the development log; this page is not edited to track progress.

## Repository and operations

| Finding | Evidence | Fixed by |
| --- | --- | --- |
| A branch with 19 commits and 89 changed files is not on `main`. It includes the real README, issue and store fixes, and the removal of the legacy deploy path | `git log main..origin/claude/role-ui-intelligence`; README content at `961d89a` | H-00, H-01 |
| `main` has a 1-byte README; the last commit (`5ca4aef`) removed 258 lines, including the judge walkthrough | `git show --stat 5ca4aef` | H-01 |
| The old README carried a production administrator password, which stays in history | `git show 5ca4aef^:README.md` | H-02 |
| No `dev` branch on this remote, although AGENTS.md and CI expect one | `git branch -a` | H-00, H-03 |
| STATUS.md was last reviewed 2026-10-02, lists the Receipt row twice and calls the auditor console a mock, but `AuditConsole.tsx` reads `/api/audit` live | [STATUS.md](../development-docs/STATUS.md); `frontend/src/roles/admin/access/audit/AuditConsole.tsx` | H-04 |
| The only backups are dumps on the server's own disk | STATUS.md "Across everything" | H-05 |
| A blank `PROOF_URL_SECRET` gives each process its own random key, so proof links break across restarts and replicas | `B/platform/config/ExecutionProperties.java:26`, `:53` | H-02 |
| Six migration timestamp prefixes are duplicated (`20261001T1500`, `T1501`, `20261002T0100`, `20261004T1600`, `20261005T1500`, `T1501`). They sort correctly only because of the module name after them | `ls migrations` | CI check (see [03](03-INFRA-DECISIONS.md#migrations)) |
| Notification routing is already at version 8. Two planned items each add a version | `migrations/*notification_routing_v8.sql` | one owner hands out versions |

## Offline client (frontend)

| Finding | Evidence | Fixed by |
| --- | --- | --- |
| The jittered backoff exists but is never called. EDGE-CASES EXE-04 claims it is in place | `frontend/src/shared/offline/queue.ts:78` (`backoffMs`), only re-exported | F-01 |
| `Retry-After` on a 429 is read but never used | `frontend/src/shared/api/client.ts` | F-01 |
| No request timeout, so the offline fallback waits for the browser to give up | `frontend/src/shared/api/client.ts` | F-02 |
| GPS positions use the same queue and the same per-device order as delivery writes. About 12 a minute; about 5,800 in an offline day, sent 100 per batch, ahead of the delivery writes | `frontend/src/shared/ui/map/position.ts`, `shared/offline/queue.ts` | F-03, F-08 |
| Live screens poll every 2 s with no ETag. About 450 requests a minute on the driver run (one request per outlet each refresh), 90 or more on the store, about 210 per depot on the dispatcher | `shared/api/useResource.ts`; `roles/driver/data/useRun.ts`; `roles/dispatcher/data/useDay.ts`; `roles/store/index.tsx` | F-07 |
| Every successful poll writes an IndexedDB snapshot; snapshots are never evicted | `shared/offline/keptReads.ts`, `snapshots.ts` | F-04 |
| Sign-out does not clear the account database, although a comment says it does | `frontend/scripts/sw-cache.mjs:14`; `app-shell/session.ts` | F-04 |
| The service worker precaches every role's bundles, reloads with no prompt on update, and resolves `sync` before the page has drained | `frontend/scripts/build-sw.mjs`, `sw-drain.mjs`; `app-shell/AppShell.tsx` | F-05 |
| iOS has no Background Sync, so iPhones send only while the app is open | platform limit | F-05 (foreground drain), rollout guidance |
| The driver cannot redo a held write, only discard it; tomorrow's run is never downloaded ahead (`prefetchesWorkingSet` is unused) | `shared/offline/tiers.ts`; 028 walkthrough | F-06 |

What is solid and must be kept:

- A write is "saved" only once IndexedDB confirms it.
- Conflicts always go to a person.
- Resends use the same command id, so a repeat is harmless.
- Uploads go before the writes that name them.
- Each account has its own database.
- Offline reads fall back only on a real outage and always show their time.

## Backend

| Finding | Evidence | Fixed by |
| --- | --- | --- |
| The outbox already has `FOR UPDATE SKIP LOCKED`, per-aggregate order, dead letters and dedupe. No broker is needed at this scale | `B/platform/messaging/OutboxRelay.java`, `ConsumerInbox.java` | kept ([03](03-INFRA-DECISIONS.md)) |
| The scheduler has two threads; ML scoring can hold one for up to 180 s, delaying the 16:00 cutoff and other jobs | `B/platform/scheduling/ScheduledJobRunner.java:83` | F-09a |
| One Hikari pool of 8 connections serves requests, scheduler, planners, relay and live push | `backend/src/main/resources/application.properties:15` | F-09a, F-09b |
| Planning's reference cache is keyed by version and outlet, but outlet windows are edited in place within a version, so planning uses stale windows | `B/planning/application/ReferenceSnapshotCache.java:31-36` | F-10 |
| `ReferenceCache` republishes inside the transaction, and other replicas notice only a new version, so calendar overrides and outlet edits need a restart | `B/referencedata/infrastructure/ReferenceCache.java`; STATUS.md Reference row | F-10 |
| Plan-generation `done()` and `requeue()` do not check who holds the claim | `B/planning/infrastructure/JdbcGenerationJobs.java` | F-11 |
| Every response is `Cache-Control: no-store`; no ETag or 304 anywhere | `B/platform/web/SecurityHeadersFilter.java:23` | F-07 |
| Positions are about 430k commands a day, each with a receipt, an audit row and a sync row. Retention removes 5,000 rows a night in one batch, with no loop | `B/platform/scheduling/PlatformRetentionJob.java:33`; `PositionRetentionJob` | F-08, F-12 |
| The retention predicates (published outbox rows by age, position payload redaction) have no supporting index | migrations for `integration.outbox_events`, `sync.operations` | F-12 |
| Live push re-reads every listener from the database every 20-25 s, the only path for changes made on another replica. Messaging has no stream and polls every 10 s | `B/execution/application/PositionSignals.java`, `B/notification/application/InboxSignals.java`; `frontend/src/shared/messaging/useThread.ts` | F-07, FF-04 |
| No Prometheus alert rules | `observability/prometheus/` holds scrape configs only | B-04 |
| No container memory limits or JVM `MaxRAMPercentage` | `compose.yaml`, `deploy/vps/compose.vps.yaml` | F-15 |

## Features

| Finding | Evidence | Builds on it |
| --- | --- | --- |
| `DAMAGED_GOODS` is already an issue type, and the loader, driver and store manager may raise it | `B/issues/contract/IssueViews.java:18-28`; R-ISS-07 | FF-01, FF-02 |
| The loader reports through `loading:Shortfall` with no photo. The command accepts a photo id, but there is no upload endpoint, the UI sends `null`, and the `LoadingShortfall` event drops it | `frontend/src/roles/loader/screens/IssueSheet.tsx:115`; `B/loading/contract/LoadingCommands.java:50-57`; `LoadingEvents.java:33-41` | FF-02 |
| There is no shared report, photo or PIN component. Photo shrinking is written twice | `roles/store/data/photo.ts`, `roles/driver/data/image.ts` | FF-01, M-02 |
| The trip thread UI is already shared by every role | `frontend/src/shared/ui` (`TripThread`, `ThreadComposer`, `VoiceCapture`, `VoiceNote`), `shared/messaging/*` | FF-04 |
| Voice notes are stored, but nothing transcribes them. The driver's player reads text aloud (text-to-speech), not speech-to-text | `roles/driver/ui.tsx:451`; `messaging.voice_notes` | FF-05 |
| The admin has no issue screen; the audit API is live (`/api/audit`, `/decisions/{commandId}`) | `B/platform/web/AuditController.java` | FF-03 |
| The driver's Fuel QR encodes only the vehicle id, so it is not a real Fuel Pass | `roles/driver/screens/FuelPassSheet.tsx:53` | FF-06 |
| New tables in `ref` are readable by every module role by default | the `ref` grant migrations | FF-06 (revoke in the same file) |
| `app.actor_oversees_depot` already gates trips, delivery records, vehicle reports and messages | `migrations/20261002T0900_execution_depot_staff_scope.sql` | FF-03 (a separate function) |

## Media

| Finding | Evidence |
| --- | --- |
| Issue photos and voice notes are stored as `bytea` in Postgres | `issues.attachment_content`, `messaging.voice_notes.content` |
| Proofs already sit behind a port with database and local-disk adapters | `B/execution/domain/ProofStore.java`, `DatabaseProofStore`, `LocalProofStore` |
| The sign-in skylines are 1.4 MB PNGs, loaded on every sign-in | `frontend/public/assets/signin_skyline_{light,dark}.png` |
| App icons must stay PNG for manifests and iOS | `frontend/public/icons/app/` |

## Models

| Finding | Evidence |
| --- | --- |
| The model service serves the Task 1 blend and the Task 2A forecast, both trained on the synthetic competition data, which is licensed for the competition only | `ml-server/manifest.json`; `data/README.md` |
| The reported lateness AUC of 0.979 is likely optimistic. Validation groups by route, so routes from the same date sit on both sides, and `disruption_index` is the realised road condition, not known when the plan is made. The fallback model's log loss (0.2385 against 0.1516) is closer to what production will see after 2026-06-28 | `ml-server/datathon/src/task1_a.py:29`; manifest metrics |
| The repository holds a 560-row training sample, not the full data | `data/provenance.json` |
| The models return service minutes and P(late), not an arrival time | `ml-server/app/schemas.py` |
| Models are not active on any deployment, and nothing measures accuracy against actuals | 016 walkthrough, "Known gaps" |
| `ml:ActivateModel` already exists | `B/intelligence/application/ModelHandlers.java` |

## Data

| Finding | Evidence |
| --- | --- |
| Each vehicle's weekly fuel quota in the data is 340-620 L. The National Fuel Pass (reintroduced 15 March 2026) allows 200 L a week per lorry and 50 L per van. Finance must confirm whether the fleet has a commercial allocation | `data/General Data/vehicles.csv`; [Newsfirst](https://newsfirst.lk/2026/03/15/qr-weekly-fuel-limits-motorcycles-5l-cars-15l) |
| Road conditions end on 2026-06-28; later dates use the fallback model | `data/General Data/road_conditions.csv` |
