# Verification status

Latest source and documentation review: September 26, 2026, on `main` after the Maven-output cleanup (`1721cd1`). `dev` replaces the former `master` branch and is not evidence of the latest integration state.

## Latest observed checks

- `npm run typecheck`: passed.
- `npm run build`: passed; Next.js production output and the generated offline shell completed.
- `mvn -q test` from `backend/`: 2 Java unit tests passed.
- `npm test`: 11 legacy tests passed; 20 database-dependent tests failed during setup because the configured `waypoint_test` database did not exist. This is an environment blocker, not evidence those application assertions failed or passed.
- Spring HTTP integration tests were not reached by that `npm test` run. Browser tests were not rerun after the missing test database was identified.
- Docker runtime verification was blocked by permission denied on the Docker socket.

The build is verified locally. Full regression success, a fresh Docker installation and public production readiness are not established by this review. No application behavior changed during the documentation refresh.

## Completing verification

1. Create a separate test database and export `TEST_DATABASE_URL`; never use the application database. See [deployment.md](deployment.md#verification).
2. From `frontend/`, install Chromium and run `npm run verify`. It runs 31 legacy Node tests, Maven unit tests, 4 Spring HTTP test definitions, typechecking, the production build and 12 browser test instances. The database-outage browser case is conditional on the local plaintext proxy being available; record skips as well as failures.
3. Start the full demo using Docker on a host with daemon access and a fresh isolated volume. Follow the [README walkthrough](../README.md#judge-walkthrough) across all four accounts.
4. Verify public HTTPS, secure cookies, restart persistence and offline reload/reconciliation on representative phones. Exercise a backup restore against an empty separate database and check certificate renewal and monitoring before real operations.

## Documentation audit

All 15 tracked Markdown files were reviewed against the active Spring/Next.js source, package scripts, SQL migrations and Compose configuration. Local file links, changed-file dash style and `git diff --check` passed. Both Compose files passed configuration validation; this does not exercise the Docker daemon. Historical notes below are retained as historical records. They must not be read as new test runs or proof of a hosted deployment. Final design-file fidelity and competition submission remain outside local build verification.

---

# Previously recorded Spring migration verification

Earlier repository record from September 26, 2026. The following results are historical claims retained for context; they were not reproduced by the latest review:

- Order creation uses UUIDs and an insert-only path; the Java regression first failed on the old short IDs, then passed.
- Spring HTTP tests cover concurrent command replay, stale draft rejection, authorization and exception redelivery with preserved proof and one linked replacement.
- `npm run verify` passed: 31 legacy Node tests, 2 Java tests, 4 Spring HTTP tests, TypeScript, production build and 12 browser tests. Browser coverage includes four-role delivery, offline reload, expired-session recovery, stale commands, proof downloads, database outage retry and exception resolution with a linked replacement.
- Staff account provisioning, password reset, assignment change, session revocation, disabled-account rejection and last-dispatcher protection pass against Spring/PostgreSQL.
- Backup/restore scripts and certificate reload hook pass shell syntax checks; actual restore and TLS renewal remain unverified.
- TypeScript checking and the production Next.js build passed.
- Both Compose files pass configuration validation. Docker runtime execution is not verified: the local Docker socket is inaccessible.
- Production calendar supports current dates with an explicit Monday-Saturday policy and configured closure overrides. Synthetic datasets remain unchanged.

The remaining rollout limits are listed in deployment.md. Historical results below describe the earlier Node implementation and are not evidence for the Java port.

---

# Earlier Node verification record

Historical verification recorded on September 26, 2026. Scope: Designathon and Hackathon implementation through the PostgreSQL + proof-images checkpoint (`3f3166c`). Earlier publication-review results through `c0d8706` are retained below as history; they do not certify later application changes.

## Previously recorded passing checks

- `npm run verify`: 31 backend tests, TypeScript checking, production build and 11 Chromium browser tests (9 test definitions; the sign-in recovery definition runs once per role for driver, loader and store).
- Backend coverage includes independent vehicle constraints, workshop exclusion, delivery-day carryover, immutable plan history, duplicate command replay, concurrent command/draft/version/fuel/carryover racing, migration plus concurrent-seed preservation, shared login throttling, proof scoping and transaction rollback, draft revisions, role-scoped snapshots and the shared delivery lifecycle.
- Production browser journey: store order, dispatcher draft and publish, loader shortfall, replacement and recheck, driver departure offline, reload, arrival and delivery proof, another reload, session expiry and sign-in recovery, synchronization, store receipt. Exactly one delivery event is recorded.
- Conflict test: a stale offline action remains marked for review, later actions remain queued, sign-out is blocked and the server record is unchanged.
- Proof-image test: store proof images download separately through the authenticated order-scoped endpoint and remain available after offline reload; state and plan snapshots carry references, not embedded image bytes.
- Outage test: a database connection outage retains a queued command and retries with the same command ID once reconnected.
- Phone viewport checks at 390 by 844 cover loader, driver and store authentication recovery, horizontal overflow and starting at the top of the workspace after login.
- Desktop dispatcher and alternative-assignment screens were visually inspected. Evidence screenshots are in the local `artifacts/screens/` directory.
- A source ZIP was extracted into `/tmp/waypoint-fresh-review`; `npm ci`, all 18 backend tests and a production build passed without the original ignored competition directory. The final login scroll, trip-order and fuel-balance adjustments were verified in the main checkout.
- An earlier checkpoint used `scripts/package-submission.py` to check its archive. That helper is absent from the current checkout; this is not a runnable current command.
- Independent code review identified trip ordering and a next-day fuel-balance reset; both were corrected, regression-tested and re-reviewed.
- `git diff --check` passed.

## Boundaries

- Offline durability was tested after the UI acknowledged saving locally. Browser storage can still be removed by device owners, browser eviction or private browsing; this is not a managed-device durability guarantee.
- Tests use disposable PostgreSQL schemas inside a dedicated `TEST_DATABASE_URL` database and synthetic competition scenarios. They never fall back to the application database. No real fleet trial, user research, route optimality or business savings are claimed.
- Chromium emulation is not physical-device or cross-browser verification.
- Docker execution could not be checked because the session cannot access the host Docker daemon and sudo requires a password. The image recipe and tracked data paths were updated; runtime verification is outstanding.
- Public HTTPS hosting, persistence on the selected host, final design/prototype file, videos and official submission links remain to be completed by the team.

Run `npm run verify` after changing the application. Use a fresh database for the new demonstration fixture; existing records are deliberately preserved.

## Publication review checkpoint (`c0d8706`, historical)

The dispatcher now reviews all-depot assigned and deferred totals before publication. Browser tests verify cancellation leaves the draft unpublished, a Kandy filter still shows both depots, and a concurrent draft change rejects publication without releasing orders. The complete 20 backend tests, 9 browser definitions, typecheck and production build passed for this checkpoint.

## Later changes now covered by the current checkpoint

The green control styling, driver Auto/Day/Night appearance modes, recoverable unfinished delivery drafts, shared readability changes, PostgreSQL persistence, checksum migrations and separate proof-image storage all landed after `c0d8706` and are included in the current `3f3166c` scope above. Backend coverage now adds concurrent command replay, concurrent draft revision, shared login throttling, proof scoping, transaction rollback, weekly-fuel racing, version racing, concurrent seeding and carryover racing. Browser coverage now adds separate proof-image download/offline reload and connection-outage retry with the same command ID.

For documentation-only updates after a passing `npm run verify`, at minimum `npm run typecheck`, `npm run build`, local Markdown link checking and `git diff --check` must pass. Backend and browser tests must be rerun after any application change, and manual role-flow checks repeated for affected flows.
