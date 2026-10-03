# Development log: @kavindamihiran

@kavindamihiran's entries, newest first. Only @kavindamihiran adds to this file; how to write an entry is in the [log's index](../development-log.md).

---

## 2026-10-03 - feat: a Connect AI button for every role

`feat/mcp-connect-button` · @kavindamihiran

Each role shows "Connect AI": the dispatcher's page header, the store top bar, the driver's home bar, the loader's Settings and the shell strip for admin and auditor. It opens one shared sheet with the MCP address, a copy button and the Claude and ChatGPT steps. The address is the `resource` in the server's own protected-resource metadata, so it is always the shared host, and the button is hidden where MCP is off or unreachable.
Why: users had to find the remote MCP address in the README (#87).
Verified: `npm run typecheck`, `npm test` (85), `npm run build`, dispatcher (13, with the new `mcp.spec.ts`), driver (10), loader (12) and store (22) browser suites; placement checked on screenshots at desktop and phone widths.
Open: the sheet's text is English only, including in the loader's Sinhala and Tamil modes.

---

## 2026-10-02 - feat: complete remote read-only MCP authorization

`feat/complete-remote-mcp` · @kavindamihiran

Continue the unfinished #87 OAuth work on a new branch: resource-bound personal authorization, stateless HTTP transport, consent page, public discovery and container wiring. The same scoped twelve-tool catalogue serves external assistants.
Why: remote clients need an HTTPS MCP endpoint and OAuth; see [plan](../issues/087-readonly-mcp/PLAN.md) and [walkthrough](../issues/087-readonly-mcp/WALKTHROUGH.md), R-IAM-31 and SEC-34/35.
Verified: PostgreSQL 16 `mvn verify`, 691 tests with no skips; 45 targeted tests after final retention/concurrency fixes. MCP 13 tests; frontend 54 tests, typecheck/build and 7 browser checks; official SDK OAuth smoke through the real three-process stack; allocation validator and Compose parsing passed. Mobile consent screenshot reviewed.
Open: Docker image build and hosted-client validation require deployment infrastructure. GitHub was unreachable during local verification. No production enablement; #87 retains its broader discovery/composition work.

---

## 2026-10-02 - fix(test): stop the execution tests colliding on a random day

`fix/execution-test-day-collision` · @kavindamihiran

`ExecutionIntegrationTest` picks a random service day per test and assigns the same two vehicles around it. The assignments stay in the database, so two tests landing on neighbouring days broke the no-overlap constraint in `setUp`. It now picks again until both vehicles are free.
Why: the preview deploy of #100 failed on it (`aTripRunningBehindTellsTheStopsAhead`, exclusion constraint on `iam.vehicle_driver_assignments`), about one run in fifteen by the arithmetic.
Verified: `mvn -Dtest=ExecutionIntegrationTest test` on local PostgreSQL 16: 23 run, 0 failed, 0 skipped.
Open: nothing.

---

## 2026-10-02 - docs: add a connect guide for the read-only MCP server

`docs/mcp-connect-guide` · @kavindamihiran

New `mcp/README.md`: build, sign in, a configuration block for Claude Code, Claude Desktop, Codex, opencode and Cursor, the 12 tools with the action each needs, and the common failures. The walkthrough links to it instead of repeating it.
Why: #93 shipped the adapter with no user-facing way to connect a client, and the requirement is that users connect their own assistant (no chatbot in Waypoint).
Verified: `mcp/` `npm test` on a clean export of `dev` (10 passed); the server registered in Claude Code, Codex, Claude Desktop and opencode with the documented entries. Not verified: a read from an installed client, which needs an interactive sign-in.
Open: ChatGPT and other remote-only clients cannot connect to a stdio server; the remote transport with OAuth needs its own PLAN under [#87](../issues/087-readonly-mcp/WALKTHROUGH.md). Production still lacks `mcp/` and `MCP_ENABLED`.

---

## 2026-10-02 - ci: run the tests once per change, in one workflow

`ci/one-checks-workflow` · @kavindamihiran

The official Task 2B validator step moved from `ci.yml` into the backend job of `checks.yml`, and `ci.yml` is deleted.
Why: both workflows ran the full backend suite on every pull request and again on every push to `dev` and `main`, so one merge ran it four times and a flaky test had twice the chances to fail a run. The validator now also gates both deploys, which `ci.yml` never did.
Verified: the `Checks` run on this branch's pull request, including the validator step.
Open: two integration tests fail intermittently on leaked test data (`PlanningCommandIntegrationTest.aGeneratedDraftIsPublishedOnceWithItsEvents`, `ExecutionIntegrationTest.setUp`), no issue yet. `Checks` is not yet a required status on `main` or `dev`.

---

## 2026-10-02 - fix: let operational commands start with the MCP filter present

`fix/mcp-filter-non-web-start` · @kavindamihiran

`McpCredentialFilter` looks up the MVC exception resolver when it refuses a request instead of requiring it at construction.
Why: `migrate` and `import-reference` start without a web server, where that resolver does not exist, so the init step of the preview deploy for #93 failed before migrating and preview stayed on the previous build. Case [PLT-15](../architecture/EDGE-CASES.md).
Verified: `WaypointApplicationCommandStartTest` failed with the deploy's own error before the change and passes after; backend `mvn verify` on local PostgreSQL 16 (627 passed, no skips); the built jar ran `migrate import-reference demo-accounts` on an empty database (46 migrations), then served with `MCP_ENABLED=true` while the stdio adapter connected as dispatcher and auditor, read in scope, was refused out of scope (403) and on a command (403), and disconnected. `mcp/` `npm test`: 10 passed.
Open: `MCP_ENABLED=true` is set per environment in the server's `.env` ([deployment.md](../deployment.md)); production gets the feature with the next release from `dev`.

---

## 2026-10-02 - feat: connect personal read-only MCP clients (issue #87)

`feat/readonly-mcp` · @kavindamihiran

Identity issues dedicated, revocable MCP sessions; the local stdio adapter exposes 12 curated tools through existing policy and SQL scope. The feature defaults off. All six roles can connect without gaining new business permissions; loaders keep their own identity. Details and setup are in the [walkthrough](../issues/087-readonly-mcp/WALKTHROUGH.md).
Why: authorized users need assistant access to recorded facts, with no command or upload path and no copied browser cookie.
Verified: rebased onto `dev` at `ae6529f`, isolated PostgreSQL backend verify (626 passed, no skips); MCP build and 10 SDK tests; frontend 47 tests, typecheck, build and three shell browser tests. Final branch checks are recorded in the walkthrough.
Open: #87 remains open for bounded work discovery, custody composition and installed desktop-client validation. No remote MCP transport is built.

---

## 2026-10-02 - fix: show the admin sample console on production

`fix/admin-console-on-production` · @kavindamihiran

`RoleRouter` now gives the admin role the sample console on every address. It only did so when the hostname began with `admin-preview.`, so `admin.waypointgo.live` showed "not built yet".
Why: production and preview run the same build, and the hostname check was the only difference between them.
Verified: `shell.spec.ts` gains a case that failed before the change and passes after; `npm test`, typecheck and build pass.
Open: the console runs on mock data and says so on screen; live backend wiring remains in issue #22.

---

## 2026-10-02 - feat(shell): production's shared address hands each role to its own address

`feat/production-role-landing` · @kavindamihiran

`waypointgo.live` now shows the same role landing as `preview.waypointgo.live`: four buttons, each opening `store.`, `dispatcher.`, `loader.` or `driver.waypointgo.live`. `PreviewLanding` became `RoleLanding`, and `previewHomeFor` became `sharedHomeFor`. A bare name is a landing only in a build made with `NEXT_PUBLIC_ROLE_ADDRESSES=1`, which `deploy/vps/compose.vps.yaml` passes as a build argument; anywhere else every role stays on one address. CI now parses the VPS overlay too.
Why: after release #83 the role addresses answered on production, but signing in on the bare name still kept every role on one URL, unlike preview.
Verified: `npm test`, typecheck, and a build with the flag on opened in a headless browser as `waypointgo.live` (landing, production links, no preview badge), `preview.waypointgo.live` (landing, preview links, badge), `loader.waypointgo.live` and `localhost` (no landing). The overlay's build argument is not verified locally, there is no Docker here: the new CI step and the preview deploy are its first run.
Open: the landing offers the four field roles; administrators and auditors type their address. A session or offline queue left on the bare production name is stranded in that browser.

---

## 2026-10-02 - fix(deploy): dump the database before every migration, and keep proof links across a restart

`fix/deploy-backup-before-migrate` · @kavindamihiran

`deploy/vps/deploy.sh` now dumps the database to `/opt/waypoint/backups/<environment>/` before `init` runs, reads the dump back, keeps the newest 14, and stops with nothing replaced if it cannot take one. It also writes `PROOF_URL_SECRET` to `.env` the first time it is missing, the way it already did `APP_DB_PASSWORD`. Restore steps are in [deployment.md](../deployment.md#backup-and-recovery).
Why: release #83 took production from 15 migrations to 45 in one deploy, with forward-only migrations and no way back; the one dump that existed was taken by hand minutes before. Proof links were signed with a key that died at every restart.
Verified: the release was rehearsed first on a database built by `main` (15 migrations, six accounts) and upgraded by `dev`: 30 migrations applied, ownership moved to `waypoint_migrator`, the backend ready as `waypoint_app`, and every demo account signed in through its own role address with its old password. The new script functions were run against a stand-in `docker`: no database yet, a good dump, a failed dump, an unreadable dump, pruning, both environments. Not run against a real server: the preview deploy of this change is the first real one.
Open: the dumps are on the server's own disk, nothing takes one between deploys, and the restore steps have not been exercised there. `ExecutionIntegrationTest` picks a random day for its vehicle assignments and two tests can collide on the exclusion constraint; it failed one `ci` run on `dev` and passed the re-run.

---

## 2026-10-02 - fix: a non-superuser owner for the schema, and reference reads that stop at scope (issue #5)

`fix/identity-owner-and-reference-scope` · @kavindamihiran

Every schema, table and function now belongs to `waypoint_migrator`, which is not a superuser, and `migrate` applies each file as that role once a database is handed over. `/api/reference` outlets and vehicles are checked against the actor's depot, outlet or today's vehicle (`ReferenceScope`, R-IAM-28). The store manager policy gains `reference:Read` on outlets and calendar days, which the store screens already called. Details in the [plan](../issues/005-identity-hardening/PLAN.md#second-pass-the-owner-and-reference-scope-2026-10-02) and [walkthrough](../issues/005-identity-hardening/WALKTHROUGH.md).
Why: the last two open lines of #5. A migration could do anything a superuser can, and a dispatcher of one depot could read the other's outlets and fleet.
Verified: `mvn verify` against a new PostgreSQL 16 database and against one upgraded from `dev`; grants, policies and default privileges compared before and after the handover and found identical. Counts are in the pull request.
Open: migrations may no longer name `SUPERUSER` or `BYPASSRLS`, and a backfill of a table that forces row-level security must lift the force (AGENTS.md, Data and Migration Rules). An account with no depot grant, an administrator included, gets 403 on depot reference reads.

---

## 2026-10-02 - docs: a status page, and the documents brought level with the code

`docs/status-and-start-here` · @kavindamihiran

New [STATUS.md](STATUS.md): every module and screen as built, partial, in flight or not started, what is left in each, and what to pick up next. AGENTS.md, both READMEs, development.md, MODULES, SYSTEM-ARCHITECTURE, FOUNDATION-PLAN, deployment, verification, the submission checklist and the design mapping corrected where the code had overtaken them (Receipt and Issues described as contract only, role screens as placeholders, `main` as the integration branch, an account script usage that no longer exists, prototype-era operational limits).
Why: the only answer to "what is done" was 850 lines of log, and the document named for "what next" finished on 2026-09-28, so someone starting work could not tell where anything stood.
Verified: `TEST_DATABASE_URL=... mvn clean verify`, 564 tests, none skipped; `npm test` (41), `npm run typecheck`, `npm run build`; browser suites dispatcher 7, driver 10, loader 4, shell 2. No code changed. Relative links in the changed files resolve.
Open: `main` is 114 commits behind `dev`, so production shows none of the modules built since 2026-10-01. No seed takes a fresh install to a released trip. The role browser suites are not in CI. `docs/design-rationale.md` and `docs/ai-disclosure.md` are submission statements and were left for the team to review.

---

## 2026-10-02 - feat: dispatcher orders, plan and live screens (issue #19, first slice)

`feat/dispatcher-flow` · @kavindamihiran

The dispatcher can run the day from the screen: Orders (what became of every order due, close orders), Plan (generate, see each deferral's rule and checks, place by hand from the server's feasible places, take an order off, move a trip, publish with a confirm, revise) and Live (vehicles by urgency with stop progress, what needs the dispatcher, the dock). Three additive reads: `GET /api/orders/day`, `GET /api/plans/draft`, `GET /api/plans/preview/placements`. Detail in [docs/issues/019-dispatcher-ui/WALKTHROUGH.md](../issues/019-dispatcher-ui/WALKTHROUGH.md).
Why: Planning had every command and no screen, so on a running instance a plan could only be made by posting commands by hand, and nothing downstream of it could start.
Verified: `TEST_DATABASE_URL=... mvn verify`, 564 tests on a fresh database; `npm test` (41), `npm run typecheck`, `npm run build`; `npx playwright test -c playwright.dispatcher.config.ts`, 7 browser tests against a mocked API; driver, loader and shell browser suites still pass. Not run against a live backend with seeded data.
Open: the rest of #19 (overview tiles, skipped outlets, fuel, issues inbox, interchange approval, forecast). No map: outlets have no coordinates (A-11). Snapshots, compare and regenerate-with-locks from the design have no backend and are left out.

---

## 2026-10-02 - feat: driver phone screens, a full day with no signal (issue #21)

`feat/driver-ui` · @kavindamihiran

The driver role is built: Home, Route, Delivery report with proof (recipient, signature pad, shrunk photo, or a reason for neither), not delivered, Report a problem, Run complete, light and dark. `shared/offline` gains kept reads and queued uploads beside the command queue; the shell lets a full-offline role carry on from the last session the server confirmed when it cannot be asked. Detail in [docs/issues/021-driver-ui/WALKTHROUGH.md](../issues/021-driver-ui/WALKTHROUGH.md).
Why: Execution (#12) had no screen, so nothing after the dock could be done by a person.
Verified: `npm test` (30), `npm run typecheck`, `npm run build`; `npx playwright test -c playwright.driver.config.ts`, 10 browser tests at phone width against a mocked API, including a stop worked and reloaded with no signal then sent once in order; loader and shell browser suites still pass. Not run against a live backend: that needs a released trip, which the fresh-install seed will provide.
Open: English only. Design features with no backend are left out and listed in the walkthrough. Browser tests are not in CI.

---

## 2026-10-02 - feat: execution module, the driver's stops (issue #12)

`feat/execution` · @kavindamihiran

`trip.released` becomes the driver's run sheet, one delivery record per order. Six commands through the bus: start, arrive, record (delivered, partial, failed), capture proof, report vehicle status, report fault. Lateness against the window close, waiting kept apart, a mall outlet late is failed not delivered (EXE-20), ETA shift announced to the stops ahead, replanned stops skipped. Proof photos and signatures through a `ProofStore` port (files under `PROOF_DIR`, a volume in both compose files) read back by signed five-minute links. Detail in [docs/issues/012-execution/WALKTHROUGH.md](../issues/012-execution/WALKTHROUGH.md).
Why: the driver role could sign in and do nothing; nothing recorded what happened on the road, so orders stopped at in transit.
Verified: `TEST_DATABASE_URL=... mvn verify`, 562 tests on a fresh database with Receipt and Issues merged; the integration test runs plan published, loaded, released and delivered through the real relay to Ordering, Receipt and Issues. Not run on the server; the Docker image was not built here.
Open: built on the relay (PR #65), which must merge first. Server time decides, so a stop recorded offline and synced late reads as late and is marked uncertain (A-31). A rule violation is `409 CONSTRAINT_VIOLATED`, like Loading. `PROOF_URL_SECRET` unset means proof links die at a restart. The driver screens are #21. No retention job purges proofs yet (#6).

---

## 2026-10-01 - feat: outbox relay delivers events between modules (issue #6, delivery slice)

`feat/outbox-relay` · @kavindamihiran

`OutboxRelay` claims committed events (`FOR UPDATE SKIP LOCKED`, a lease), hands each to every `EventSubscriber` of its type in a transaction of its own, and settles it: published, retried with backoff, or dead-lettered after 8 attempts. One aggregate's events arrive in write order (new `outbox_events.seq`). `platform:ReplayEvent` and `GET /api/platform/events/dead` for dead letters. Detail in [docs/issues/006-event-backbone/WALKTHROUGH.md](../issues/006-event-backbone/WALKTHROUGH.md).
Why: events were written and never delivered, so no module's work reached the next one: a placed order never reached Planning or Warehouse, a published plan never reached Ordering or Loading.
Verified: `TEST_DATABASE_URL=... mvn verify`, 456 tests on a fresh database with Loading merged; by hand, the wired relay drained the 43 events the other integration tests left behind to the real subscribers with none failing. Not run on the server.
Open: the first start delivers every event still pending from before the relay existed, including Warehouse status calls. Tests run with the worker off (`app.relay.enabled=false`) and deliver explicitly. Scheduler jobs and audit completion remain on #6.

---

## 2026-10-01 - fix: identity and auth hardening (issue #5)

`fix/identity-hardening` · @kavindamihiran

Lockout fires and keeps its history (three counters, P-13); sessions stored by hash and expired on the injected clock; `__Host-` cookie and an Origin guard; a policy generation every decision and every command's transaction re-reads; policy, role, scope, assignment and device administration as versioned commands; the pool runs as `waypoint_app` with `migrate` on the owner's own connection. Detail in [docs/issues/005-identity-hardening/WALKTHROUGH.md](../issues/005-identity-hardening/WALKTHROUGH.md).
Why: an audit found the lockout could never trigger, nothing checked Origin, authorization was decided only before the transaction, and every deployment's pool was the table owner.
Verified: `TEST_DATABASE_URL=... mvn verify`, 338 tests, twice on the same database; the deploy path rehearsed locally from a `dev`-built database (old session survived the hash migration, forwarded address recorded, ninth wrong password 429, cross-site POST 403). Not yet run on the server.
Open: callers of `iam:GrantScope`, `iam:RevokeScope`, `iam:AssignDriver` and `iam:EndDriverAssignment` must send `expectedVersion`; an unknown kind is 403; sessions end by the injected clock, so a test that moves the clock signs in again. Behind HTTPS everyone signs in once after the deploy. A non-superuser `waypoint_migrator` owner and depot-scoped reference reads are not done.

---

## 2026-10-01 - perf: deploys run init once and before anything is replaced

`chore/faster-deploy` · @kavindamihiran

A preview deploy of a frontend-only change took about seven minutes, 2m40s of it in `init`, which started the application eleven times (migrate, import, six accounts, three depot grants) at about 13 s each. Backend commands can now be combined in one run, and a new `demo-accounts` command covers the accounts and grants, so `scripts/compose-init.sh` is one start. `deploy/vps/deploy.sh` runs `init` on its own before `up`, then replaces only `db backend waypoint` with `--no-deps`: before, Compose removed the changed containers first and the site was down for the whole of `init`, whatever the comment said about a failed migration. The frontend image keeps npm's and the compiler's cache between builds, and the backend and frontend health checks poll every two seconds while starting.
Why: the wait was mostly repeated JVM starts and a full health interval, not work; the checks before a deploy are unchanged.
Verified: `mvn verify` compiles and passes locally except the two planning integration classes, which need a database this machine does not have; `docker compose config` parses both stacks; `bash -n deploy/vps/deploy.sh`. Not run on the server: the first preview deploy after merge is the test, and it fails before replacing anything if `--no-deps` behaves differently than expected.
Open: `scripts/dev.sh setup` still runs one command per start. The checks before a deploy take about 1m50s, most of it the backend tests.

---

## 2026-10-01 - fix: store screens read the paged orders list

`fix/store-orders-page` · @kavindamihiran

The store gateway treated `GET /api/orders?outlet=` as an array, but Ordering returns a keyset page, so the store workspace crashed with "filter is not a function" as soon as an account had an outlet. It now reads every page through `requestAll`, which takes the cursor parameter name because Ordering reads `cursor` where reference data reads `after`. The order timeline is fetched from `/timeline`, the path Ordering serves, not `/history`.
Why: the store manager's workspace would not open on preview once the demo account was granted OUT001.
Verified: `npm run typecheck`, `npm run build`; signed in as the store manager in headless Chrome on the fixed build against the preview API: Home, Orders and Deliveries render the seeded orders with no page error.
Open: `/api/reference/outlets/{id}` and the calendar answer 403 for a store manager (no policy allows `reference:Read`), and the warehouse catalogue status and pending receipts endpoints do not exist yet; the screens show those as unavailable. The preview database was seeded by hand with `scripts/seed-scenarios.sql`.

---

## 2026-10-01 - feat: preview opens on a role picker

`feat/preview-role-picker` · @kavindamihiran

`preview.waypointgo.live` now shows a short description of Waypoint and four buttons (store manager, dispatcher, loader, driver), each opening that role's `-preview` address; it has no sign-in of its own ([PreviewLanding.tsx](../../frontend/src/app-shell/PreviewLanding.tsx)). This replaces the redirect after sign-in from the entry below.
Why: the redirect left nobody able to stay on the preview address, and sign-in happened twice.
Verified: `npm test` (12 pass), `npm run typecheck`, `npm run build`; the page seen in headless Chrome against the production build on a `preview.` hostname.
Open: admin and auditor have no button; their preview addresses still work when opened directly.

---

## 2026-10-01 - feat: preview sign-in moves to the role address

`feat/preview-redirect-to-role` · @kavindamihiran

Signing in on `preview.waypointgo.live` sends the account to its role's `-preview` address (`previewHomeFor` in [hostRole.ts](../../frontend/src/app-shell/hostRole.ts)); production's shared address is unchanged. See [deployment.md](../deployment.md).
Why: preview should be tried through the role addresses, not a shared workspace.
Verified: `npm test` (12 pass), `npm run typecheck`, `npm run build`. Not checked in a browser.
Open: the account signs in a second time on the role address (sessions are per address). An account with several roles lands on the address of the role it used last.

---

## 2026-10-01 - chore: one wildcard DNS record, simpler certificate request

`chore/simplify-certificate-names` · @kavindamihiran

Cloudflare now has two proxied A records, the bare name and `*`, in place of one per address. With every name resolving, the deploy no longer checks each name's DNS before the certificate request: it asks for the full list whenever the certificate on disk is missing one. See [deployment.md](../deployment.md).
Why: fifteen hand-made DNS records and a resolve check per name were more than the job needs.
Verified: `bash -n deploy/vps/deploy.sh`; from the server, the role names resolve through the wildcard and the challenge path answers over HTTP through Cloudflare. The request itself runs only in a production deploy and has not run yet.
Open: the role addresses, the preview ones and `www` answer only after a production deploy from `main`. The per-name records for `-preview` and the entries below that call for them are superseded.

---

## 2026-10-01 - feat: preview role addresses

`feat/preview-role-hostnames` · @kavindamihiran

Preview gets `dispatcher-preview.` to `auditor-preview.waypointgo.live`, served by the preview stack and pinned to the role the same way; the certificate request includes them. See [deployment.md](../deployment.md).
Why: the role addresses could only be tried on production.
Verified: `npm test` (10 pass), `npm run typecheck`, `npm run build`; `nginx -t` and the preview role names reaching the preview server block in a throwaway container.
Open: six proxied A records for the `-preview` names. nginx is deployed with production, so these names answer only once the proxy is replaced.

---

## 2026-10-01 - feat: one address per role

`feat/role-hostnames` · @kavindamihiran

`dispatcher.`, `loader.`, `driver.`, `store.`, `admin.` and `auditor.waypointgo.live` serve production and show that one role: the shell reads the hostname ([hostRole.ts](../../frontend/src/app-shell/hostRole.ts)), drops the role switcher, and sends an account without the role to its own address. nginx answers the six names and the deploy adds each to the certificate once it resolves, without dropping a name already there. See [deployment.md](../deployment.md).
Why: each role gets a link that opens straight into its own workspace.
Verified: `npm test` (9 pass), `npm run typecheck`, `npm run build`; `nginx -t` and the six names answering in a throwaway container. Not checked in a browser on a role address: they exist only after a production deploy.
Open: the six proxied A records in Cloudflare; nginx and the certificate change only with a production deploy from `main`. Preview has no role addresses.

---

## 2026-10-01 - ci: www redirects to the bare name

`ci/www-redirect` · @kavindamihiran

nginx answers `www.SITE_ADDRESS` with a 301 to `SITE_ADDRESS`, and the deploy now requests the certificate again when the one on disk is missing any of the three names, instead of only when there is none. Since the entry below, `preview.waypointgo.live` has its record and is on the certificate, and `CLOUDFLARE_ONLY=1` is set, so the server's address no longer answers directly.
Why: `www.waypointgo.live` has a proxied record and answered 520/525, because the server drops names it does not know.
Verified: `nginx -t` and both redirects in a throwaway container on the server. The certificate request for `www` runs on the first production deploy and is not verified until then.
Open: Cloudflare's address ranges in `00-cloudflare.conf` are a dated static list.

---

## 2026-10-01 - ops: production moves to waypointgo.live

`docs/domain-waypointgo-live` · @kavindamihiran

Production is `https://waypointgo.live`, proxied by Cloudflare, with a Let's Encrypt certificate on the server. The temporary wildcard-DNS hostnames no longer answer; their certificate, the unused Caddy volumes and every mention of them in the documents are removed. The preview's `SITE_ADDRESS` is `preview.waypointgo.live`. Details in [deployment.md](../deployment.md#judge-deployment-on-the-vps).
Why: decision of @kavindamihiran to serve the app from a bought domain behind Cloudflare.
Verified: `https://waypointgo.live` answers 200 through Cloudflare, the server presents the Let's Encrypt certificate for it, and the dispatcher signs in.
Open: closed by the entry above.

---

## 2026-10-01 - chore: test data for every order status

`chore/seed-scenarios` · @kavindamihiran

`scripts/seed-scenarios.sql` writes 329 orders with timelines and lines directly into the database: one of each of the 13 statuses at OUT001, plus a day's demand at both depots (tomorrow's chilled demand at Peliyagoda exceeds the refrigerated fleet, with two reefer trucks in the workshop), deferrals, a redelivery, an amended order and a date rolled past a Sunday. It also grants the store manager OUT001, the dispatcher both depots and the driver VEH035. Dates are relative to the day it runs; `-v reset=1` removes the seeded orders first. The header of the file has the commands.
Why: Planning, Loading, Execution and Receipt do not exist yet, so nothing else can put an order past `confirmed`.
Verified on production: store manager sees 21 orders in 13 statuses through `/api/orders` and a full timeline, and is refused another outlet (403); dispatcher sees demand for both depots; no timeline is out of order or in the future.
Open: it bypasses the command bus, so there are no audit rows, receipts or events, and every warehouse reference is invented (`SEED-WH-...`). Cancelling or amending a seeded order from the UI will ask the real warehouse about a reservation it never made. Not run on the preview.

---

## 2026-10-01 - ci: nginx replaces Caddy at the edge, ready for Cloudflare

`ci/nginx-edge` · @kavindamihiran

The VPS edge is now an nginx image built from `deploy/vps/nginx/`, with certbot for Let's Encrypt. It adds what Caddy lacked: per-address rate limits (sign-in, API, pages), no answer for the bare IP or unknown hostnames, a method allowlist, TLS 1.2+ only. It restores the visitor address from Cloudflare and can refuse traffic that bypasses Cloudflare (`CLOUDFLARE_ONLY`, off until DNS is proxied). The deploy tests the new nginx configuration before replacing the running proxy, and brings the proxy up before the database step. Details in [deployment.md](../deployment.md#judge-deployment-on-the-vps).
Why: decision of @kavindamihiran to front the app with nginx behind Cloudflare on a bought domain.
Verified on the server, on localhost-only ports beside the live site and against the real frontends: both hostnames route, HTTP redirects, bare IP gets no response, `/.env` 404, TRACE 405, 5 MB body 413, sign-in works and the sixth rapid bad attempt is 429 while session GETs are not limited, a spoofed `CF-Connecting-IP` is ignored, TLS 1.1 refused. Compose frees the removed service's ports before starting the new one. **Not verified: certificate issuance, the cutover itself, and the Cloudflare lock from outside.**
Open: cutover on merge, then the domain and Cloudflare records. `nginx/` at the root and `compose.prod.yaml` are the prototype's proxy and are not used by the VPS. The `app_caddy-data` and `app_caddy-config` volumes are left on the server.

---

## 2026-10-01 - ci: preview environment for dev

`ci/preview-deploy` · @kavindamihiran

A push to `dev` now runs the checks and deploys to a preview on the same VPS, with its own database and accounts. The checks moved to a reusable `checks.yml` and also run on pull requests into `dev`. `deploy.sh` serves both environments, chosen by the checkout it sits in; each has its own CI key. No stack publishes a host port any more, including production's PostgreSQL and backend; Caddy reaches both frontends over a shared network. Details in [deployment.md](../deployment.md#judge-deployment-on-the-vps).
Why: problems should show on a real deployment before `dev` is merged into `main`.
Verified: both compose configurations and the Caddyfile validate, the latter inside the running Caddy. **Not verified: neither the production change to the proxy nor a preview deploy has run yet.**
Open: first production deploy with the new proxy layout, then the first preview deploy once `dev` has these files.

---

## 2026-10-01 - ci: deploy main to the VPS, repair the compose init step

`ci/vps-deploy` · @kavindamihiran

A merge to `main` now runs backend tests against PostgreSQL and the frontend typecheck, boundary test and build, then deploys to the VPS (62.171.128.70) over one SSH connection bound to `deploy/vps/deploy.sh`. Caddy fronts the stack with TLS through `deploy/vps/compose.vps.yaml`. The host is hardened: ufw, fail2ban, unattended upgrades; SSH password login stays on for the team by decision of @kavindamihiran. Details in [deployment.md](../deployment.md#judge-deployment-on-the-vps).
`compose.yaml`'s `init` ran `migrate && seed`, and `seed` went with the prototype, so the backend could never start under Compose. It now runs `scripts/compose-init.sh`: migrate, import-reference, six demo accounts, depot grants, as `scripts/dev.sh setup` does. The frontend healthcheck pointed at `/api/health`, which no longer exists; it now checks `/`.
Why: the Hackathon needs a public URL that stays live, and `docker compose up` is the judged path.
Verified: the merge of #38 deployed `1d5dc15` through the workflow (181 backend tests, frontend checks, then the deploy job). On a fresh volume `init` applied 15 migrations, published 120 outlets and 60 vehicles and created six accounts. The public URL answers 200 with a Let's Encrypt certificate, HTTP redirects, and dispatcher, loader, driver and store manager sign in through it; a wrong password is 401. The server listens publicly on 22, 80 and 443 only.
Open: nobody has walked the judge walkthrough on the live URL. The store manager account has no outlet scope, because there is no CLI command to grant one. `README.md`, `development.md` and `backend/README.md` still document the removed `seed` command and `/api/health`. `compose.prod.yaml` has the same stale healthcheck. No automatic rollback and no database backup schedule on the VPS.

---

## 2026-10-01 - fix: sign-in matches Figma "01 Sign in"

`fix/sign-in-figma-match` · @kavindamihiran

Sign-in follows the dispatcher and store "01 Sign in" frames: GO in the corner, "Welcome back" above the card, filled fields with placeholders (each still has an aria-label), a password-reset line, and the note about working offline. The error, lockout and outage handling is unchanged.
Why: the sign-in card did not match any design frame.
Verified: `npm run typecheck`, `npm run build`. In the browser at 1440x900 and 393x852: a wrong password shows the generic error, the right one signs in, and "Switch user" in the loader header signs out with the notice.
Open: the background map artwork, the theme toggle, staff ID sign-in (the backend signs in by email), and the loader's employee and PIN sign-in (Figma 07/08) are not built.

---

## 2026-10-01 - fix: dispatcher shell controls in the sidebar, compact nav below lg

`fix/dispatcher-figma-match` · @kavindamihiran

Dispatcher rechecked against Figma page 05. "Switch user" and the role switcher move into the sidebar's user block; the shell strip that pushed the page down and cut off the sidebar foot is gone. Below `lg` the sidebar becomes a top bar: brand, depot scope, and a scrollable nav. Page headers wrap. With this, every built role draws its own header and the shell strip is only a fallback.
Why: the strip was not in the design, and the dispatcher had no layout below 1024px.
Verified: `npm run typecheck`, `npm run build`, boundaries test. Screenshots at 393x852, 768x1024 and 1440x900.
Open: Orders, Plan, Live, Forecast and Issues content wait on their modules. Figma has no tablet or phone frames for the dispatcher, so the compact nav is a proposal.

---

## 2026-10-01 - fix: store manager desktop layout matches Figma

`fix/store-figma-match` · @kavindamihiran

Store manager rechecked against Figma pages 14 (Desktop) and 15 (Mobile). From `lg` there is a sidebar with Home, Orders and Deliveries plus their counts, the outlet card, and the signed-in person with "Switch user". It replaces the floating tab bar. Home becomes two columns with a Notifications panel (pending #14). Deliveries become one row each. Place order puts the list on the left and a sticky "Order summary" card on the right, replacing the bottom bar. The sync pill sits top right. On phones the shell controls move into the store header, and dialogs centre from `md`.
Why: on desktop the store showed the phone column centred on the page.
Verified: `npm run typecheck`, `npm run build`, boundaries test. Screenshots at 393x852, 768x1024 and 1440x900 compared with the Figma frames.
Open: Issues, notifications, driver and ETA details, call options and draft orders need modules not built yet (#13, #14, #12).

---

## 2026-10-01 - fix: loader matches Figma on phone, portrait and landscape tablet

`fix/loader-figma-match` · @kavindamihiran

Loader rechecked against Figma pages 07, 08 and 09. The dock board is a trip table from `md` (768px) and cards on phones. The top bar follows the designs: brand or trip title, a sync pill, an avatar pill, and "Switch user". The load sheet gets a "Locked to you" strip with Hand back, side-by-side actions, "orders loaded" with time to departure, and outlined stop markers. The issue dialog is top-anchored at 560px with tile reasons and a stepper panel. Sign-out, the role switcher and the sync badge move from a strip above the page into the role header through `ShellProvider` in `shared/ui`. The shell still draws its strip for roles not yet converted.
Why: the loader drifted from the design; the tablet dock board was a card grid where Figma has a table.
Verified: `npm run typecheck`, `npm run build`, boundaries test. Screenshots at 393x852, 768x1024 and 1280x800 compared with the Figma frames.
Open: route, stops, load, loader and temperature columns need those fields on `ReadyTripView` (#10). The "Mine" and "Available" filters need the assigned loader. Release as a full page with the seal and driver checklist needs data that does not exist yet. Lock, theme and notifications are not built.

---

## 2026-10-01 - feat: backend sync module and batch ingest

`feat/sync-module` · @kavindamihiran

New `sync.operations` table with row-level security limiting each account to its own rows. `POST /api/sync` applies a device's queued writes in sequence order. Each write goes through `CommandBus` in its own transaction. Conflicts and refusals are recorded and the batch continues; an outage stops it. A replayed batch is answered from the record. At capacity it answers 429 with a jittered `Retry-After`. Also `GET /api/sync?since=` (keyset) and `sync:Acknowledge`. The frontend queue now drains through `/api/sync` with a per-browser device id. `SyncQuery.pendingFor` now takes the user id, because RLS needs an actor.
Why: #28. The device-side queue from #15 had no server record of what it sent.
Verified: `TEST_DATABASE_URL=... mvn package`, 116 tests, 0 failures, including the new `SyncIntegrationTest` (7) and `OperationOutcomeTest`. `npm run typecheck` and `npm run build`. Against the local backend: a loader batch is recorded, replayed without running again, and listed.
Open: `sync:Discard` and `sync:Resolve` wait on D-O (who reviews another person's conflict; RLS is own-rows only for now). Also open: Background Sync, working-set prefetch, browser e2e tests, and the lockout `Retry-After`.

---

## 2026-10-01 - fix: loader layout on landscape tablets, and one-command local dev

`fix/loader-tablet-and-local-dev` · @kavindamihiran

Loader widens to 1280px at `lg`: the dock board shows trips in two or three columns, the load sheet pins the truck summary beside the load list, and the release and issue sheets become centred dialogs. Phones are unchanged. `scripts/dev.sh` runs the local stack (`setup`, default, `sample`) against the Docker database only. New `account-grant-depot` CLI command. Sign-in no longer sends a device id, which the backend rejected with a 500 through the `iam.devices` foreign key. Unhandled API exceptions are now logged.
Why: the loader showed a phone-width strip on dock tablets, and the app could not be run locally end to end.
Verified: `npm run typecheck`. In the browser at 1180x820 and 390x844 with loader sample data: dock board and load sheet. Sign-in against the local backend returns 200; depot grants applied.
Open: no outlet-grant command, so the store role needs `sample` mode locally; device registration; sign-in shows "not answering" for a 500.

---

## 2026-10-01 - feat: frontend sync engine schedule and review list

`feat/app-shell` · @kavindamihiran

`useSync` in `src/shared/offline` drains the queue on reconnect, on focus and visibility, right after a write is queued (`waypoint:queued` event) and every 30 s. `drain()` is now single-flight per account, so it never double-sends beside a role's own flush. The shell shows "n to send" or "n saved on this device" and "n to review". The review list shows each refused write with the server's reason, and offers "send again" or "discard" (a person decides; the engine never drops one). Sign-out reads the same pending count. The loader and store no longer count held writes as still sending.
Why: frontend half of #15.
Verified: typecheck, `npm test` and `npm run build` pass. In the browser with the live store gateway and stubbed APIs: placed offline, sign-out blocked, reconnected, the server answered 409 (sent once), then review and discard.
Open: Background Sync, driver working-set prefetch, queue-age telemetry, and the backend `sync` module (`POST /api/sync`, `sync:Resolve`).

---

## 2026-10-01 - feat: sign-in, sign-out and role switcher in the app shell

`feat/app-shell` · @kavindamihiran

Sign-in screen on `POST /api/session`, with separate messages for wrong credentials, lockout (countdown from `Retry-After` when sent) and outage. The shell tells apart signed out, server unreachable and offline; an outage no longer reads as "sign in". Sign-out uses `POST /api/session/end` and warns while writes are still queued on the device (SEC-01). Multi-role users switch roles and the last role is remembered per user. Scope is read as prefixed grants (`depot:`, `outlet:`) and each role gets its own values. Admin and auditor routes are placeholders. Queued writes survive a 401 instead of being held for review.
Why: #17, and the sign-out and 401 parts of #15.
Verified: `npm run typecheck`, boundary tests and `npm run build` pass. In the browser at 393px with `/api/session` stubbed: outage, wrong password, 429 countdown, sign in, role switch remembered across reload, sign out, offline.
Open:
- #17: the component gallery and the dark theme.
- #15: scheduled drain, the shared pending indicator and conflict list, and the backend `sync` module.
- The backend returns a 403 for lockout without `Retry-After`, so no countdown is shown there.

---

## 2026-10-01 - feat: store manager screens from the Figma design

`feat/store-ui` · @kavindamihiran

The following screens, from Figma "15 Store Manager · Mobile":
- Home: next delivery, notices, and the order against the 16:00 cutoff.
- Place order and amend.
- Order sent.
- Orders, with history and cancel.
- Deliveries.
- Receive delivery.

Behaviour:
- One `order:Place` per temperature class (R-ORD-06).
- A short line shows per-line availability from the `409 INSUFFICIENT_STOCK` problem (D-F).
- A non-operating delivery day shows the rolled date before sending (D-I).
- With the warehouse down, the screen shows a degraded banner and `STOCK_UNKNOWN` (D-G).
- Receipt is confirm, confirm partial or dispute, and is kept apart from the driver's proof (R-RCP-04).
- Resilient tier: writes made offline are queued and sent on reconnect.

`Problem` now keeps extension members in `extensions`.
Why: #18, built frontend first while Ordering (#8) and Receipt (#13) are not served.
Sample data: `NEXT_PUBLIC_STORE_FIXTURES=1`, the same pattern as the loader, never active in production.
Verified: `npm run typecheck`, boundary tests and `npm run build` pass. I drove the full flow at 393px in `next dev` with sample data: short stock, holiday roll, cancel, partial receipt, offline place then sync, warehouse down. Not verified against a live backend.
Open:
- The `/api/orders`, `/api/receipts` and `/api/warehouse` paths are assumed.
- ETA shows the outlet window only (#18 decision 2).
- No product names in the contract, so product ids are shown, labelled "inferred".
- Not built yet: issues tab, notification inbox (#14), supply probability (#16), order template, and the Playwright e2e tests.

---

## 2026-09-30 - feat: loader phone screens from the Figma design

`feat/loader-ui` · @kavindamihiran

Dock board, load sheet (stops in reverse order, per-order check), report an issue, confirm and release, from Figma "08 Loader · Phone". Writes are `loading:*` commands carrying the manifest `rowVersion`. Resilient tier: checks made offline go to the device queue and are sent on reconnect; release always needs a connection. A new plan version shows "plan changed" and marks the reset orders for recheck (R-LOD-03). Release is refused while an order is unchecked (R-LOD-07).
Why: #20, built frontend first while Loading (#10) is not served.
Sample data: departs from D-D by decision. `NEXT_PUBLIC_LOADER_FIXTURES=1` swaps in an in-memory gateway (`roles/loader/data/fixtures.ts`) enforcing the same rules; never active in a production build, and the screen shows "Sample data".
Verified: `npm run typecheck`, `npm test`, `npm run build` green; full flow driven at 393px in `next dev` with sample data, including an offline check and a plan change. Not verified against a live backend.
Open: `/api/loading/trips` and `/api/loading/trips/{id}/manifest` are assumed paths until #10; `ReadyTripView` has no route, stop count, temperature or claimant, so the board card omits them; damage photo, interchange, shift-handover sign-in and Playwright e2e remain.

---

## 2026-09-30 - feat: dispatcher shell, overview and vehicles from the Figma design

`feat/dispatcher-ui` · @kavindamihiran

GO design tokens and Google Sans Flex in `src/shared/ui/theme.css`, shared components (card, pill, tiles, buttons, sync pill, notice) and the Figma icons in `public/icons/go/`. Dispatcher sidebar, depot scope and hash navigation. Overview and Vehicles read `/api/reference/vehicles`; the vehicle drawer sends `vehicle:SetDayStatus` with a required reason and reuses the command id on retry. Orders, Plan, Live, Forecast and Issues say which module they wait on (D-D, no mocks). Offline turns the screens read only and says so. Mirrored the reference data contract in `src/shared/domain/referencedata.ts`.
Why: first slice of #19 that the backend can serve today.
Verified: `npm run typecheck`, `npm test` and `npm run build` green; screens checked in a production build against the Figma frames with API reads stubbed from `vehicles.csv`. Not verified against a live backend.
Open: the rest of #19 waits on #8, #9, #12, #13, #14 and #16. Reference data has no read listing workshop vehicles, `VehicleView` has no `rowVersion` so the command goes with a null `expectedVersion`, and `BigDecimal` is serialised as a JSON number. No Playwright e2e yet.
