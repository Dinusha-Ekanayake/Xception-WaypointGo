# Offline sync follow-ups: walkthrough

Issue [#28](https://github.com/kavindamihiran/Xception-WaypointGo/issues/28), branch `feat/sync-followups`, targeting `dev`. The [plan](PLAN.md) has the audit of what was already built and the decisions.

## What is built

| Layer | Files and responsibility |
| --- | --- |
| Migration | `migrations/20261003T1300_sync_discard_resolve.sql`: `RESOLVED` added to the status check; `replaced_by`, `settled_reason`, `settled_at`; `sync:Discard` and `sync:Resolve` implemented; a new version of the loader, driver and store manager policies with statement `ReviewOwnHeldWrites` |
| Domain | `sync/domain/OperationOutcome.java`: `canDiscard` (a conflict or a refusal), `canResolve` (a conflict only) |
| Application | `sync/application/DiscardOperationHandler.java`, `ResolveOperationHandler.java`. `SubmitBatchHandler` returns each operation's `rowVersion` and records `waypoint.sync.time_to_drain` |
| Contract | `SyncViews.OperationStatus.RESOLVED`; `ResolveOperation(operationId, replacedBy)`, mirrored in `frontend/src/shared/domain/sync.ts` |
| Device queue | `frontend/src/shared/offline/review.ts` (pure: the redo, the resolve, the discard, recorded order), `resolvers.ts` (a role says what version to redo on), `queue.ts` (`discard`, `redo`, recorded-order batches, Background Sync request), `useSync.ts` (`canRedo`, `redo`, `discard`; drains on the service worker's message) |
| Review panel | `frontend/src/app-shell/SyncStatus.tsx`: "Redo on the current version" where a resolver exists, and "Discard…" with a reason |
| Loader | `roles/loader/index.tsx` registers the `loading:` resolver; `roles/loader/data/redo.ts` works out the version |
| Service worker | `frontend/scripts/build-sw.mjs`: on `waypoint-drain`, posts `waypoint:drain` to every open page |
| Design system | `shared/ui/primitives.tsx` on the `go-card` token; `app/globals.css` inverts GO icons under any `.go-dark`; `app/gallery/page.tsx` and `app-shell/GalleryRoute.tsx` |

## Flows

**Discard.**
1. A held write in the review panel, then "Discard…", then a reason, then "Discard".
2. `queue.discard` queues `sync:Discard {operationId, reason}` with the held operation's version and drops the local copy.
3. On the next drain the server settles the operation `DISCARDED`, keeping the reason and the time. Another account's operation reads as not found (R-EXE-16).

**Redo (resolve).**
1. "Redo on the current version" asks the role's resolver for the version. The loader's is the trip manifest's `rowVersion` plus that trip's checks still waiting, under the loader signed in now.
2. `queue.redo` queues the same command under a new id on that version, then `sync:Resolve {operationId, replacedBy}` one millisecond later, and drops the held copy.
3. The server applies the redo like any write; it is checked against the current record and held again if it still conflicts. The resolve then settles the old operation `RESOLVED` with `replaced_by` (R-EXE-17).

**Background Sync.**
1. Queuing a write registers `waypoint-drain` where the browser supports it.
2. On reconnect the service worker posts `waypoint:drain` to open pages, and `useSync` drains through the same path as the online event.
3. With no page open the service worker drains each account's queue itself (`scripts/sw-drain.mjs`), under the device id the page kept in the snapshot store, by the page's rules. A loader queue waits for a page, which replays its offline operator switches first (A-39).

**A Windows build.** `build-sw.mjs` now writes asset URLs with `/` on every OS; a Windows build used to list `\` paths, so the worker never installed there.

**Time to drain.** After a batch that did not stop on an outage, if the device has nothing `RECEIVED` left, the server records the time from the oldest write in the batch.

## Run and verify locally

- Backend: `TEST_DATABASE_URL=postgresql://... mvn verify` from `backend/`. `SyncIntegrationTest` covers:
  - discard: reason required, owner only, applied writes refused, stale versions;
  - resolve: the redo applied once, `RESOLVED` with the trail, refusals for a self, missing or other-kind redo;
  - time to drain, and its absence after an outage.
  - `OperationOutcomeTest` covers the transitions.
- Frontend: `npm test` (`tests/sync-review.test.ts`), `npm run typecheck`, `npm run build`.
- Loader browser suite: `npx playwright test -c playwright.loader.config.ts`. `held.spec.ts` checks redo and discard, and that the drain message sends a waiting check (it fails without the message).
- Gallery: `npm run dev`, then `/gallery`; a production build answers 404.

## Decisions recorded

D-O and the redo rule: [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md) R-EXE-16, R-EXE-17. Background Sync: [ASSUMPTIONS](../../architecture/ASSUMPTIONS.md) A-39. Cases: [EDGE-CASES](../../architecture/EDGE-CASES.md) EXE-02, EXE-03. Module: [MODULES](../../architecture/MODULES.md) section 10.

## Known gaps

- Redo is offered where a role registers a resolver. The loader does. The driver (#21) can, through `registerResolver("delivery:", ...)`. Until then a driver discards a held write and records it again.
- Writes held before this change carry no server version. A discard or redo looks it up first (`GET /api/sync/{operationId}`, the owner's own rows); only with no connection is such a write dropped on the device alone.
- A loader queue waits for an open page to drain (A-39); every other queue drains from the service worker too.
- The dispatcher and store screens have no dark mode of their own; the gallery shows the shared components ready for it.
