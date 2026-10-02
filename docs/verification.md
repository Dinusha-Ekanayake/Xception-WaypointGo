# Verification status

Latest review: 2026-10-02, on `dev` at `7442e5d` (pull request #70 merged), with only documentation changed on top. `dev` is the integration branch for CI.

## Latest observed checks

- `TEST_DATABASE_URL=... mvn clean verify`: 564 tests, 0 failures, 0 errors, **0 skipped**, so the integration tests ran. The database was a dedicated local PostgreSQL 16, separate from any application database.
- `npm test`: 41 tests pass. `npm run typecheck`: clean. `npm run build`: the production build completes and the service worker caches 67 assets.
- Browser suites, each against that production build with a mocked API: dispatcher 7 passed, driver 10 passed, loader 4 passed, shell 2 passed.

Not observed in this review, and not to be read as verified:

- `docker compose up --build` from a fresh clone and a new volume. Docker was not available on the verifying machine.
- Any role flow in a browser against the real backend. The role suites mock the API; the same flows are covered on the server side by integration tests, module by module.
- The loader's live suite (`playwright.loader.live.config.ts`), which needs a running instance.
- The preview and production deployments. Production runs `main`, which is 114 commits behind `dev`.

## Completing verification

1. `cd backend && mvn verify` with Docker running, or with `TEST_DATABASE_URL` set to a dedicated database. Read the report for skipped tests: a green run with the integration tests skipped proves nothing about the database.
2. `cd frontend && npm run verify`, then `npm run build`, `npm run test:e2e` and the role suite for anything you touched, after `npx playwright install chromium`.
3. `docker compose -p waypoint-verify up --build` with free ports (`DB_PORT`, `BACKEND_PORT`, `PORT`) and a new volume; sign in as the administrator and walk one order through the four roles; `docker compose -p waypoint-verify down -v`.
4. Open a pull request to `dev` and confirm CI is green.
5. Before real operations: restart persistence, offline reload on representative phones, a backup restore into an empty separate database, certificate renewal and monitoring.

Everything below this line is history. Each section records what was observed at the time and was not reproduced by the review above.

---

# Previously recorded platform review, 2026-10-01

On `feat/module-contracts` with the issue #4 platform changes uncommitted.

## Observed checks

- `mvn test` (JDK 17 container): all unit and architecture tests pass, including the new problem-contract, correlation-id, configuration-redaction, metrics, paging and login-audit tests. The 29 database integration tests were skipped: no test database was configured for this run.
- Backend jar booted with no database: liveness 200, readiness `{"status":"DOWN"}` without details, problem bodies carry `code` and `correlationId`, a non-UUID `X-Correlation-Id` is replaced, `/prometheus` lists the new detection signals, no OTLP export errors.
- `npm run typecheck` and `npm test`: pass.
- Log store: a request's log line reached Loki from both the container and the native log file, found by correlation id.

Not yet observed: integration tests against PostgreSQL (with the new Testcontainers fallback), `docker compose up` from a fresh clone, the nginx configuration under `nginx -t`, the backend image build, browser tests and CI. Docker Hub was unreachable from the verifying machine.

## Steps recorded then

1. `cd backend && mvn verify` with Docker running, or with `TEST_DATABASE_URL` set to a dedicated database. Confirm the integration tests ran rather than skipped.
2. `cd frontend && npm run verify`, then `npm run build && npm run test:e2e` after `npx playwright install chromium`.
3. `docker compose -p waypoint-verify up --build` with free ports (`DB_PORT`, `BACKEND_PORT`, `PORT`) and a new volume; sign in as the administrator; `docker compose -p waypoint-verify down -v`.
4. Open a pull request to `dev` and confirm CI is green.
5. Before real operations: public HTTPS, secure cookies, restart persistence, offline reload on representative phones, a backup restore into an empty separate database, certificate renewal and monitoring.

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
