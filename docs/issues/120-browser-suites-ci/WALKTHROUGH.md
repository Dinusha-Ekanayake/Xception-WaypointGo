# Run role browser suites in CI: walkthrough

Issue [#120](https://github.com/kavindamihiran/Xception-WaypointGo/issues/120). The [plan](PLAN.md) has the decisions.

## What is built

| Part | Files |
| --- | --- |
| CI job "Browser suites" | `.github/workflows/checks.yml` (`browser`) |
| CI settings per suite | `frontend/playwright.config.ts`, `playwright.dispatcher.config.ts`, `playwright.driver.config.ts`, `playwright.loader.config.ts`, `playwright.store.config.ts` |
| The shell test that needed a backend | `frontend/tests/e2e/shell.spec.ts` |
| Docs | `AGENTS.md`, `README.md`, `docs/development-docs/development.md` |

## The flow

1. A pull request to `dev` or `main`, or a deploy (both deploy workflows call `checks.yml`), starts the job beside the backend and frontend jobs.
2. The job runs `npm ci`.
3. Chromium is restored from the cache, keyed by the Playwright version, or installed with its system libraries.
4. The build runs once.
5. Five steps run in turn, each against the production build with its API mocked:
   - Shell;
   - Dispatcher (1440 px);
   - Driver (393 px);
   - Loader (393 px);
   - Store manager (1440 px).
6. Each step runs even when an earlier one failed, as long as the build passed. The red step names the role.
7. Under `CI`:
   - a failing test is retried once, and one that then passes is listed as flaky;
   - failures are annotated on the exact spec line in the pull request;
   - each suite writes `playwright-report/<suite>` and `test-results/<suite>`.
8. On failure, those folders are uploaded as `browser-suites-report` for 7 days.
9. Chromium is saved to the cache even after a failure.

## Run and verify

- **Locally, as CI does:** `CI=true npx playwright test -c playwright.<suite>.config.ts` after `npm run build`.
- **Reading a failed run** is described in [development.md](../../development-docs/development.md#tests).
- **On PR #202:**
  - a throwaway wrong unread count in a loader test turned the job red;
  - only "Loader (393 px)" failed, and the other four suites still ran;
  - the failing line `notifications.spec.ts:58` was annotated, and the report was attached;
  - the revert ran green;
  - a run takes about 4.5 minutes.

## Known gaps

- Making "Browser suites" a required check is a repository setting.
- `playwright.loader.live.config.ts` drives a live deployment and stays out of CI.
- The driver's 7 skipped tests wait on #21.
