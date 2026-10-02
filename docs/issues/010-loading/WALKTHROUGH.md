# Issue #10: Loading implementation walkthrough

Status: the scoped Loader backend, shared-device flow and live browser path are implemented and verified on an isolated local stack. Interchange, dispatcher handover and automated driver-assignment gating remain deferred by the issue plan.

## Implemented backend flow

`LoadingConsumers.OnPlanPublished` receives a published plan inside a system-actor Loading transaction. `ManifestBuilder` reads orders through `OrderQuery` and vehicles through `ReferenceQuery`, then `JdbcManifestWriter` writes Loading-owned trip, stop and item snapshots. The consumer inbox wraps delivery; existing trip versions are not rebuilt.

`LoadingController` authorizes `loading:Read`; `LoadingDataQuery` reads through `JdbcLoadingReads` under the current actor. `LoadingViewMapper` produces the board and manifest contracts, including reverse stop order, item lines, holders, authoritative order totals, vehicle capacities and the session row version. Out-of-scope depot lists return an audited 403. Individual manifest reads hide inaccessible trips with 404.

Loading commands use the existing command bus. `LoadingMessages` refuses an inaccessible trip with 403 before comparing versions. Absent and inaccessible mutation targets share the same response to avoid disclosing existence. A visible trip also receives an explicit SQL depot-scope check. The command bus audits the refusal after rollback.

On a revision, checks carry only for unchanged orders on the same vehicle, temperature and stop position. A vehicle swap, reordered stop or changed order requires checking that work again. The original append-only checks remain historical evidence. The session version advances once, invalidating commands based on the older manifest.

The release command now requires only three confirmations: doors sealed, orders secured, and driver present. The release sheet collects those confirmations and enables its green action once all three are checked. The former seal number and reefer temperature columns remain nullable historical fields; migration `20261001T1505_loading_release_checklist.sql` removes the seal-number database gate for new releases.

Migration `20261001T1507_loading_checks_append_only.sql` revokes update access to item checks and adds a trigger that refuses updates and deletes even from a privileged writer. The HTTP/database suite also verifies an auditor with the trip's depot in scope cannot use HandBack.

## Dock configuration

`LoadingProperties` retains `app.loading.docks-per-depot` (default 4) and adds `app.loading.docks[DEPOT_CODE]` overrides. Both default and overrides must be between 1 and 20. `ManifestBuilder` assigns docks in departure order. Dock selection in the UI will be a view filter, not a plan mutation.

## Verification evidence

Run from `backend` with a dedicated `TEST_DATABASE_URL` different from `DATABASE_URL`:

```text
mvn -o -Dtest=LoadingIntegrationTest,LoadingSessionTest,LoadingPropertiesTest,ModuleBoundaryTest,EventCatalogueTest test
```

At the earlier manifest/read checkpoint, 48 tests passed against a separate PostgreSQL 18 instance. Subsequent code changes are not covered by that database result. Java 24 caused ArchUnit warnings while resolving JDK classes; use Java 21 for the next database run if available.

Current checks (2026-10-01): a dedicated PostgreSQL 18 test database ran the full backend suite after the two fixture corrections: 231 tests, zero failures, errors or skips. The fixes each had a failing reproduction and passing focused tests. The guarded `loading-fixture` CLI then built one live manifest from synthetic confirmed demand after reference import and PIN provisioning in a separate disposable database. Against that backend and a production Next.js build, the 393x852 browser test passed device sign-in, wrong and correct PIN, trip loading, offline check, reconnect and sync, three-check release and lock. The 768x1024 locked tablet screen had no horizontal overflow. The latest frontend run passed seven unit/boundary tests, typecheck, production build and two mocked Loader browser tests. Browser screenshots were inspected locally; exact pixel equivalence to every Figma state is not claimed. No external warehouse data was mutated.

| Guarantee | Evidence |
| --- | --- |
| Out-of-scope mutation returns audited 403 before version comparison | `anOutOfScopeWriteIsForbiddenAndAuditedBeforeVersionChecking`: RED returned 404, GREEN returned 403 |
| Vehicle swap invalidates checks | `aVehicleChangeRequiresLoadingTheItemsAgain`: RED retained LOADED, GREEN returned PENDING |
| Stop reordering invalidates checks | `aReorderedStopRequiresLoadingTheItemsAgain`: RED retained LOADED, GREEN returned PENDING |
| Unchanged work carries, new work remains unchecked | `aRevisionCarriesUnchangedTicksAndResetsChangedOnes` |
| Two concurrent checks cannot consume one version | `simultaneousChecksCannotBothConsumeTheSameVersion`: one 200, one 409, one check attempt |
| Re-delivered publication does not reset work | `replayingAPublishedVersionDoesNotResetWorkOrAdvanceTheVersion` |
| Wrong-depot SQL cannot see the trip or its children | `rowLevelSecurityHidesTripAndChildrenFromAnActorWithoutDepotScope` |
| Per-depot overrides retain the default elsewhere and reject invalid counts | `LoadingPropertiesTest`: compile-time RED for missing override API, GREEN for all three tests |
| Flags allow continued loading and checklist-gated release | `aFlaggedItemIsNotLoadedLoadingCarriesOnAndReleaseFollowsTheChecklist` |
| Offline sync replay applies a check once | `aQueuedCheckReplayedThroughSyncAppliesOnce` |

## Remaining work

- Finish the 393x852/768x1024 visual comparison against the Figma frames. The tablet check so far covers a locked state and overflow, not every sheet.
- Review against the current `dev` branch before delivery; the local `dev` tracking ref is behind `origin/dev`. Do not push or open a PR without a separate delivery step.
- Handover, interchange and driver-assignment gating remain deferred. Offline PIN switching is intentionally excluded. Coverage percentage has not been measured.
