# Run role browser suites in CI: plan

Issue [#120](https://github.com/kavindamihiran/Xception-WaypointGo/issues/120). Today `checks.yml` runs the frontend's typecheck, unit tests and build, but none of the Playwright suites, so a screen can break with every check green.

## Current state (measured on `dev` at a772396)

| Suite | Config | Viewport | Result | Time |
| --- | --- | --- | --- | --- |
| Shell | `playwright.config.ts` | 1440 | 8 pass, **1 fail** | 105 s |
| Dispatcher | `playwright.dispatcher.config.ts` | 1440 | 42 pass | 90 s |
| Driver | `playwright.driver.config.ts` | 393 | 10 pass, 7 skipped by dev (#21) | 39 s |
| Loader | `playwright.loader.config.ts` | 393 | 20 pass | 50 s |
| Store | `playwright.store.config.ts` | 1440 | 32 pass | 83 s |

- Every suite serves a production build (`npm start` on its own port) with a mocked API, so CI needs no backend or database.
- The viewports the issue asks for are already set in the configs.
- `playwright.loader.live.config.ts` drives a live deployment through `LOADER_LIVE_BASE_URL`, so it stays out of CI.
- **The one failure:** `shell.spec.ts` "loads under its Content-Security-Policy" waits for `networkidle`. With no backend behind `BACKEND_URL`, the page keeps retrying its session check and never goes idle. It fails the same way in CI.
- **Constraints:**
  - the repository is private, so Actions minutes are limited;
  - `checks.yml` is also called by `preview-deploy.yml` and `ci-deploy.yml`, so whatever it runs gates every deploy.

## Decisions

1. **One job, "Browser suites"**, not a matrix of five.
   - The job builds once, then runs the five suites as separate steps, each with `if: success() || failure()`. A failing suite still lets the others run and report, and each step's name says which role broke.
   - Expected about 10 minutes of wall time, in parallel with the backend shards. A five-way matrix would finish sooner but cost about five times the runner minutes on a private repository, plus five extra builds.
   - Revisit with two shards if the PR wait becomes the bottleneck.
2. **Chromium on Linux.**
   - The configs already pick Edge only on Windows.
   - The job runs `npx playwright install --with-deps chromium`, cached in `~/.cache/ms-playwright` and keyed by the installed Playwright version.
3. **One retry in CI** (`retries: process.env.CI ? 1 : 0` in each config).
   - A single timing flake should not block a deploy.
   - Playwright still reports a test that passed on retry as "flaky", so it is visible rather than hidden.
   - Locally nothing changes.
4. **Reports.**
   - In CI the reporter is `github` (failures as annotations on the PR) plus `list`.
   - On failure, the job uploads `playwright-report` and `test-results` (traces, screenshots) as an artifact kept for 7 days.
5. **The shell CSP test mocks `/api/session` as signed out**, as the role suites mock their API, so the page settles and the test checks only what it is for: the headers and no CSP violations. No product code changes.
6. **The suites gate deploys too.** That is the point of the issue: a broken screen should not reach preview or production. The cost is a few minutes on each deploy, in parallel with the backend tests.
7. **Driver's 7 skipped tests stay skipped**, as dev decided. They wait on #21, and the plan does not hide or re-enable them.
8. **No new required-check rule here.** Branch protection is not readable with this access. Whoever owns the repository settings can add "Browser suites" as a required check after it has been green for a while.

## Work

- **`.github/workflows/checks.yml`**: the new `browser` job (Node 22, npm cache, `npm ci`, Playwright cache and install, `npm run build`, five suite steps, report artifact on failure, `timeout-minutes: 25`).
- **`frontend/playwright*.config.ts`** (the five CI configs): `retries` and `reporter` for CI.
- **`frontend/tests/e2e/shell.spec.ts`**: the CSP test answers `/api/session` itself.
- **Docs:**
  - `AGENTS.md` and `README.md` stop saying the role suites are "not in CI";
  - `docs/development-docs/development.md` gets a line on reading a failed browser job (the artifact);
  - STATUS, the log, and this issue's `WALKTHROUGH.md`.

## PR

One PR into `dev`: `chore/120-browser-suites-ci`.

## Verification

- Locally: the five suites pass on Linux-like settings (`CI=1`).
- On the PR, the new "Browser suites" job runs and passes, and its time is recorded.
- One deliberate failure on a throwaway commit (for example, a wrong label in one spec) shows the job turn red with the failing role named and the report attached; the commit is then dropped before merge.
- After merging, the preview deploy runs the suites before shipping.
