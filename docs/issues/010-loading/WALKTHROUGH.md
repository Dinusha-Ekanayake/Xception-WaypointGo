# Issue #10: Loading implementation walkthrough

Status: backend loader HTTP/database flow verified; frontend build and browser verification remain. The entire issue is not complete.

## Implemented backend flow

`LoadingConsumers.OnPlanPublished` receives a published plan inside a system-actor Loading transaction. `ManifestBuilder` reads orders through `OrderQuery` and vehicles through `ReferenceQuery`, then `JdbcManifestWriter` writes Loading-owned trip, stop and item snapshots. The consumer inbox wraps delivery; existing trip versions are not rebuilt.

`LoadingController` authorizes `loading:Read`; `LoadingDataQuery` reads through `JdbcLoadingReads` under the current actor. `LoadingViewMapper` produces the board and manifest contracts, including reverse stop order, item lines, holders, authoritative order totals, vehicle capacities and the session row version. Out-of-scope depot lists return an audited 403. Individual manifest reads hide inaccessible trips with 404.

Loading commands use the existing command bus. `LoadingMessages` refuses an inaccessible trip with 403 before comparing versions. Absent and inaccessible mutation targets share the same response to avoid disclosing existence. A visible trip also receives an explicit SQL depot-scope check. The command bus audits the refusal after rollback.

On a revision, checks carry only for unchanged orders on the same vehicle, temperature and stop position. A vehicle swap, reordered stop or changed order requires checking that work again. The original append-only checks remain historical evidence. The session version advances once, invalidating commands based on the older manifest.

The release command now requires only three confirmations: doors sealed, orders secured, and driver present. The release sheet collects those confirmations and enables its green action once all three are checked. The former seal number and reefer temperature columns remain nullable historical fields; migration `20261001T1505_loading_release_checklist.sql` removes the seal-number database gate for new releases.

## Dock configuration

`LoadingProperties` retains `app.loading.docks-per-depot` (default 4) and adds `app.loading.docks[DEPOT_CODE]` overrides. Both default and overrides must be between 1 and 20. `ManifestBuilder` assigns docks in departure order. Dock selection in the UI will be a view filter, not a plan mutation.

## Verification evidence

Run from `backend` with a dedicated `TEST_DATABASE_URL` different from `DATABASE_URL`:

```text
mvn -o -Dtest=LoadingIntegrationTest,LoadingSessionTest,LoadingPropertiesTest,ModuleBoundaryTest,EventCatalogueTest test
```

At the earlier manifest/read checkpoint, 48 tests passed against a separate PostgreSQL 18 instance. Subsequent code changes are not covered by that database result. Java 24 caused ArchUnit warnings while resolving JDK classes; use Java 21 for the next database run if available.

Current checks (2026-10-01): focused `LoadingSessionTest,PinPolicyTest` passed; `mvn -q test` passed with the environment-gated database tests skipped on that run; `npm.cmd test` passed 7 tests; `git diff --check` passed. A separate disposable PostgreSQL 18 cluster then ran `LoadingIntegrationTest`: 18 tests, zero failures, zero errors, zero skips. The first database run exposed an unauthorized Reference Data table lookup in test setup; the second exposed same-timestamp PIN failures being undercounted. Both were corrected, and the third run passed. Frontend typecheck, build and Playwright remain unverified: `npm ci --offline` lacked a cached package, and the networked retry failed with `ECONNRESET`.

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

- Add or verify any remaining append-only database enforcement and the explicit auditor HandBack denial.
- Verify the development fixture and operator PIN flow with a live browser; the HTTP/database paths pass integration tests.
- Typecheck, build and verify Part C against current Figma frames and the live backend.
- Finish rule/assumption/departure documentation, full database suite, frontend checks, browser tests and manual walkthrough before issue closure.
- Handover and interchange remain deferred by the plan. Offline PIN switching is intentionally excluded.

Coverage percentage has not been measured. No UI, PIN, fixture or full-system completion is claimed by this checkpoint.
