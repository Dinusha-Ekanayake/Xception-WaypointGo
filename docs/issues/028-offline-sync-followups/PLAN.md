# Offline sync follow-ups: plan

Issue [#28](https://github.com/kavindamihiran/Xception-WaypointGo/issues/28), branch `feat/sync-followups`, targeting `dev`.

## Current state (audit of `dev` at `2e354c9`)

| Item in #28 | State before this branch | Evidence |
| --- | --- | --- |
| Backend `sync` module: `sync.operations`, `POST /api/sync` through the command bus in device order, `GET /api/sync?since=`, 429 with `Retry-After` | built | `migrations/20261001T0900_sync_operations.sql`, `SubmitBatchHandler`, `SyncController`, `SyncIntegrationTest` |
| `sync:Acknowledge` | built | `AcknowledgeOperationHandler` |
| `sync:Discard`, `sync:Resolve` | contract records only, catalogue `implemented=false` | waited on decision D-O |
| Background Sync | missing | the service worker had install, activate and fetch only |
| Driver working-set prefetch | built | `prefetchesWorkingSet`, the driver's kept run sheet |
| Queue age telemetry (EXE-01) | built | `waypoint.sync.queue_age` |
| Time to drain (EXE-02) | missing | |
| Redo a held write on the device | broken | "Send again" resent the held command id; the server answers a replayed id with its first answer, so it could never succeed |
| Browser tests EXE-01..03, SEC-01 | built | `e2e-driver/day.spec.ts`, `session.spec.ts` |
| Dark theme tokens | built | `.go-dark` in `src/shared/ui/theme.css` |
| Component gallery | missing | |
| Lockout countdown from the backend | built | the login throttle answers 429 with `Retry-After`; `SignIn.tsx` counts down |

## Decisions

- **D-O (2026-10-02): only an operation's owner reviews it.** Row-level security `p_operations_own` already enforces this, so no cross-account policy is added. Recorded as R-EXE-16.
- **Resolve is two operations.** A bus handler cannot dispatch a second command (one command, one transaction), so the device queues the redo, then `sync:Resolve` naming it. Recorded as R-EXE-17.
- **The service worker does not send writes.** It asks an open page to drain; ordering, the offline operator replay and the account scope stay in the page. Recorded as A-39.

## Layers

| Layer | Owner | Change |
| --- | --- | --- |
| Migration | Sync | `RESOLVED` status, `replaced_by`, `settled_reason`, `settled_at`; catalogue rows implemented; the loader, driver and store policies gain `sync:Discard` and `sync:Resolve` |
| Domain | Sync | `OperationOutcome.canDiscard`, `canResolve` |
| Application | Sync | `DiscardOperationHandler`, `ResolveOperationHandler`; the version in each sync answer; `waypoint.sync.time_to_drain` |
| Device | shared offline | `discard` with a reason, `redo` from a role's resolver, recorded-order batches, Background Sync request |
| UI | shell, loader | the review panel offers redo and reasoned discard; the loader registers its resolver |
| Service worker | frontend build | answers the `waypoint-drain` tag |
| Design system | shared UI | components on tokens; `/gallery` in development |

## PR breakdown

One pull request into `dev`, committed in this order: backend; device queue and review panel; service worker; loader browser tests; dark theme fix; gallery; docs.
