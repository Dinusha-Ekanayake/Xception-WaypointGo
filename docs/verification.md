# Current Spring migration verification

Production-readiness branch, September 26, 2026:

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

# Verification record

Current verification recorded on September 26, 2026. Scope: Designathon and Hackathon implementation through the PostgreSQL + proof-images checkpoint (`3f3166c`). Earlier publication-review results through `c0d8706` are retained below as history; they do not certify later application changes.

## Currently passing

- `npm run verify`: 31 backend tests, TypeScript checking, production build and 11 Chromium browser tests (9 test definitions; the sign-in recovery definition runs once per role for driver, loader and store).
- Backend coverage includes independent vehicle constraints, workshop exclusion, delivery-day carryover, immutable plan history, duplicate command replay, concurrent command/draft/version/fuel/carryover racing, migration plus concurrent-seed preservation, shared login throttling, proof scoping and transaction rollback, draft revisions, role-scoped snapshots and the shared delivery lifecycle.
- Production browser journey: store order, dispatcher draft and publish, loader shortfall, replacement and recheck, driver departure offline, reload, arrival and delivery proof, another reload, session expiry and sign-in recovery, synchronization, store receipt. Exactly one delivery event is recorded.
- Conflict test: a stale offline action remains marked for review, later actions remain queued, sign-out is blocked and the server record is unchanged.
- Proof-image test: store proof images download separately through the authenticated order-scoped endpoint and remain available after offline reload; state and plan snapshots carry references, not embedded image bytes.
- Outage test: a database connection outage retains a queued command and retries with the same command ID once reconnected.
- Phone viewport checks at 390 by 844 cover loader, driver and store authentication recovery, horizontal overflow and starting at the top of the workspace after login.
- Desktop dispatcher and alternative-assignment screens were visually inspected. Evidence screenshots are in the local `artifacts/screens/` directory.
- A source ZIP was extracted into `/tmp/waypoint-fresh-review`; `npm ci`, all 18 backend tests and a production build passed without the original ignored competition directory. The final login scroll, trip-order and fuel-balance adjustments were verified in the main checkout.
- Archive integrity and required seed/configuration entries are checked by `python3 scripts/package-submission.py`.
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
