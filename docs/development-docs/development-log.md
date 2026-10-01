# Development log

Why things changed and what state they left behind. Git history says what changed; this says why, and what is still open. Several people and agents work here in parallel without seeing each other's sessions, so read the top entries before starting.

## How to write an entry

Short imperative subject, then a few terse lines. Newest first. Credit the GitHub user who owns the work; never record agent, tool or model names. Skip typo and formatting fixes.

```markdown
## YYYY-MM-DD - type: short imperative subject

`<branch>` · @<github-user>

What changed, one or two lines.
Why: one line.
Verified: commands and result, or "not verified" and why.
Open: what is left, or "nothing".
```

Entries before 2026-09-26 are in `git log`.

---

## 2026-10-02 - feat(issues): commands, consumers, escalation and raise rights (issue #13, step 4)

`feat/receipt-issues` · @Oxshadha

- **Commands:** all seven `issue:*` commands through the bus. Scope is the issue's depot or outlet, read as the system, so out of scope is 403 plus audit.
- **Raise rights are policy data:** the resource is `wpt:issue:type:<TYPE>`, and migration `20261002T0400` publishes version 3 of the Loader, Driver and StoreManager policies, scoping `issue:Raise` by type.
- **Redelivery:** `issue:ScheduleRedelivery` emits exactly one `redelivery.requested`.
- **Replacement:** `issue:RecordReplacement` emits `shortfall.resolved`.
- **Consumers:** seven events each raise one issue (deduplicated by source key) at the policy's default severity. A partial or disputed receipt opens one investigation linked to the order, receipt, delivery and trip (R-RCP-07).
- **Escalation:** `IssueEscalationJob` stamps an unassigned issue once at its severity's deadline and refreshes the backlog gauges.

Why: issue #13. See [PLAN.md](../issues/013-receipt-issues/PLAN.md).
Verified: `TEST_DATABASE_URL=... mvn test`, 349 tests, 0 failures; frontend `npm run typecheck` and `npm test` pass.
Open: step 5 (walkthrough and registers).

## 2026-10-02 - feat(receipt): confirm, dispute, delivery consumer and auto-close (issue #13, step 3)

`feat/receipt-issues` · @Oxshadha

- **Commands:** `receipt:Confirm`, `receipt:ConfirmPartial` and `receipt:Dispute` share one flow, `ReceiptAnswerHandler`. A missing delivery is 404 (RCP-04). Another outlet is 403 plus audit (RCP-05); scope is read as the system in its own transaction, so the bus can answer forbidden rather than absent.
- **Delivery consumer:** `delivery.completed` opens a PENDING receipt with the order's lines and the driver named, once per delivery.
- **Auto-close:** `ReceiptAutoCloseJob` closes silence after 24 h as the system (RCP-02). A shortage after auto-close is accepted as late and announced as `receipt.disputed` (RCP-08).
- **Ordering:** gains `OnReceiptDisputed`. A dispute marks the order RECEIVED; a late one leaves it UNCONFIRMED and is counted.
- **Migration** `20261002T0300` flips the receipt actions.

Why: issue #13. See [PLAN.md](../issues/013-receipt-issues/PLAN.md).
Verified: `TEST_DATABASE_URL=... mvn test`, 338 tests, 0 failures.
Open: steps 4 and 5.

## 2026-10-02 - feat: receipt and issues schema and reads (issue #13, step 2)

`feat/receipt-issues` · @Oxshadha

Migrations `20261002T0100` (receipt) and `T0200` (issues): FORCE RLS by outlet, depot and system (issues also by who raised it), no DELETE, and effective-dated parameters that the runtime role cannot edit. Database CHECKs back the rules: an answered receipt has a person behind it, and the system never resolves an investigation. Adds the repositories and the reads `/api/receipts/pending`, `/{orderId}`, `/{orderId}/custody`, `/api/issues?depot=` (keyset, most severe first), `/by-subject`, `/{id}` and `/{id}/history`. Contract changes are additive: `ReceiptView` fields, `CustodyChainView` and `IssueHistoryView`, mirrored in the frontend.
Why: issue #13. See [PLAN.md](../issues/013-receipt-issues/PLAN.md).
Verified: `TEST_DATABASE_URL=... mvn test`, 327 tests, 0 failures; `npm run typecheck` passes.
Open: steps 3 to 5.

## 2026-10-02 - feat: receipt and issues domains (issue #13, step 1)

`feat/receipt-issues` · @Oxshadha

Pure domains for both modules.
- **Receipt:** the `Receipt` aggregate, plus `ReceiptStateMachine` (PENDING to CONFIRMED, PARTIAL, DISPUTED or AUTO_CLOSED; AUTO_CLOSED still accepts a late shortage), `AutoClosePolicy` and `ReceiptParameters`.
- **Issues:** the `Issue` aggregate, plus `IssueLifecycle`, `ResolutionAction` (no RETURN) and `SeverityPolicy`. The system actor can never resolve a shortage investigation (R-RCP-07).

Why: issue #13. The decisions are in [PLAN.md](../issues/013-receipt-issues/PLAN.md).
Verified: `mvn test -Dtest=ReceiptTest,ReceiptStateMachineTest,IssueTest,ModuleBoundaryTest`, 33 tests, 0 failures.
Open: steps 2 to 5 (schema, commands, consumers, jobs, docs).

## 2026-10-01 - fix: store screens read the paged orders list

`fix/store-orders-page` · @kavindamihiran

The store gateway treated `GET /api/orders?outlet=` as an array, but Ordering returns a keyset page, so the store workspace crashed with "filter is not a function" as soon as an account had an outlet. It now reads every page through `requestAll`, which takes the cursor parameter name because Ordering reads `cursor` where reference data reads `after`. The order timeline is fetched from `/timeline`, the path Ordering serves, not `/history`.
Why: the store manager's workspace would not open on preview once the demo account was granted OUT001.
Verified: `npm run typecheck`, `npm run build`; signed in as the store manager in headless Chrome on the fixed build against the preview API: Home, Orders and Deliveries render the seeded orders with no page error.
Open: `/api/reference/outlets/{id}` and the calendar answer 403 for a store manager (no policy allows `reference:Read`), and the warehouse catalogue status and pending receipts endpoints do not exist yet; the screens show those as unavailable. The preview database was seeded by hand with `scripts/seed-scenarios.sql`.

## 2026-10-01 - feat: preview opens on a role picker

`feat/preview-role-picker` · @kavindamihiran

`preview.waypointgo.live` now shows a short description of Waypoint and four buttons (store manager, dispatcher, loader, driver), each opening that role's `-preview` address; it has no sign-in of its own ([PreviewLanding.tsx](../../frontend/src/app-shell/PreviewLanding.tsx)). This replaces the redirect after sign-in from the entry below.
Why: the redirect left nobody able to stay on the preview address, and sign-in happened twice.
Verified: `npm test` (12 pass), `npm run typecheck`, `npm run build`; the page seen in headless Chrome against the production build on a `preview.` hostname.
Open: admin and auditor have no button; their preview addresses still work when opened directly.

## 2026-10-01 - feat: preview sign-in moves to the role address

`feat/preview-redirect-to-role` · @kavindamihiran

Signing in on `preview.waypointgo.live` sends the account to its role's `-preview` address (`previewHomeFor` in [hostRole.ts](../../frontend/src/app-shell/hostRole.ts)); production's shared address is unchanged. See [deployment.md](../deployment.md).
Why: preview should be tried through the role addresses, not a shared workspace.
Verified: `npm test` (12 pass), `npm run typecheck`, `npm run build`. Not checked in a browser.
Open: the account signs in a second time on the role address (sessions are per address). An account with several roles lands on the address of the role it used last.

## 2026-10-01 - chore: one wildcard DNS record, simpler certificate request

`chore/simplify-certificate-names` · @kavindamihiran

Cloudflare now has two proxied A records, the bare name and `*`, in place of one per address. With every name resolving, the deploy no longer checks each name's DNS before the certificate request: it asks for the full list whenever the certificate on disk is missing one. See [deployment.md](../deployment.md).
Why: fifteen hand-made DNS records and a resolve check per name were more than the job needs.
Verified: `bash -n deploy/vps/deploy.sh`; from the server, the role names resolve through the wildcard and the challenge path answers over HTTP through Cloudflare. The request itself runs only in a production deploy and has not run yet.
Open: the role addresses, the preview ones and `www` answer only after a production deploy from `main`. The per-name records for `-preview` and the entries below that call for them are superseded.

## 2026-10-01 - feat: preview role addresses

`feat/preview-role-hostnames` · @kavindamihiran

Preview gets `dispatcher-preview.` to `auditor-preview.waypointgo.live`, served by the preview stack and pinned to the role the same way; the certificate request includes them. See [deployment.md](../deployment.md).
Why: the role addresses could only be tried on production.
Verified: `npm test` (10 pass), `npm run typecheck`, `npm run build`; `nginx -t` and the preview role names reaching the preview server block in a throwaway container.
Open: six proxied A records for the `-preview` names. nginx is deployed with production, so these names answer only once the proxy is replaced.

## 2026-10-01 - feat: one address per role

`feat/role-hostnames` · @kavindamihiran

`dispatcher.`, `loader.`, `driver.`, `store.`, `admin.` and `auditor.waypointgo.live` serve production and show that one role: the shell reads the hostname ([hostRole.ts](../../frontend/src/app-shell/hostRole.ts)), drops the role switcher, and sends an account without the role to its own address. nginx answers the six names and the deploy adds each to the certificate once it resolves, without dropping a name already there. See [deployment.md](../deployment.md).
Why: each role gets a link that opens straight into its own workspace.
Verified: `npm test` (9 pass), `npm run typecheck`, `npm run build`; `nginx -t` and the six names answering in a throwaway container. Not checked in a browser on a role address: they exist only after a production deploy.
Open: the six proxied A records in Cloudflare; nginx and the certificate change only with a production deploy from `main`. Preview has no role addresses.

## 2026-10-01 - docs(planning): walkthrough and register updates (issue #9, step 7)

`feat/planning-module` · @Oxshadha

Adds [the issue #9 walkthrough](../issues/009-planning/WALKTHROUGH.md). EDGE-CASES now names a test for every Planning row, adds PLN-18 and PLN-19, and supersedes POL-06 and POL-07 with what-if runs. RULES revises R-PLN-21 and closes C-2 and Q5. ASSUMPTIONS adds A-26 to A-28 and P-15 to P-19, and gives P-12 its value. MODULES describes Planning as built. Two tests were added so no row is left without one: `UnservableScreenTest` (PLN-09) and `aNonOperatingDayIsNotPlanned` (PLN-13).
Why: issue #9 closeout.
Verified: `TEST_DATABASE_URL=... mvn test`, 252 tests, 0 failures, 0 skipped; `check_allocation.py` on the peak-day CSV prints `FEASIBILITY: PASSED`.
Open: the walkthrough's "Known gaps" table (relay #6, what-if runs, authoring and replay commands, the explanation for a second skip, #16, #19).

## 2026-10-01 - feat(planning): revise, replan, previews and consumers (issue #9, step 6)

`feat/planning-module` · @Oxshadha

Adds `plan:Revise` and `plan:Replan`, `previewAssignments` and `previewInterchange` (also on `/api/plans/preview/*`), and the decision-7 consumers.
- Migration `20261001T0600` keys trips by `(plan_id, trip_id)`, so a trip keeps its id across versions while it carries the same orders, even on a substitute vehicle (PLN-04, R-LOD-06). It also allows one open draft per depot-day and records `revision_reason`.
- A revision carries the published plan's orders, drops cancelled ones and defers late arrivals under PLN-07. It is announced as `plan.revised`, and only new deferrals are announced again.
- Interchange is auto-published only when exactly that trip moved; a lost vehicle drafts a revision for the dispatcher.

Why: issue #9 step 6. Detail in [PLAN.md](../issues/009-planning/PLAN.md#step-6-result-do-not-re-do).
Verified: `TEST_DATABASE_URL=... mvn test` on PostgreSQL 16, 250 tests, 0 failures, 0 skipped.
Open: step 7 (walkthrough and registers). The relay that delivers events to consumers is #6.

## 2026-10-01 - feat(planning): generate, override, defer and publish (issue #9, step 5)

`feat/planning-module` · @Oxshadha

`plan:Generate`, `plan:Override`, `plan:Defer` and `plan:Publish` now run through the bus, backed by the `PlanningRun` aggregate, the `PublicationGate` and `DemandFingerprint`. Each override or deferral writes the next draft version and cancels the one edited, so a stale second edit is refused with the successor's id and a diff (PLN-06). Publication refuses when demand, the reference version, the rule set or the policy changed, and re-runs the whole registry. It then emits `plan.published`, `order.deferred` and `order.unservable`. `ReferenceQuery.vehiclesOfDepot` was added, so the unservable screen sees workshop vehicles. Migration `20261001T0500` marks the four actions implemented.
Why: issue #9 step 5. Detail in [PLAN.md](../issues/009-planning/PLAN.md#step-5-result-do-not-re-do).
Verified: `TEST_DATABASE_URL=... mvn test` on PostgreSQL 16, 236 tests, 0 failures, 0 skipped.
Open: step 6. Revise and Replan, `plan.revised`, stable trip ids across versions, previews, and consumers.

## 2026-10-01 - feat(planning): schema, repository and plan reads (issue #9, step 4)

`feat/planning-module` · @Oxshadha

Migration `20261001T0400` adds the planning tables with forced RLS. It also adds the triggers that freeze a published run and its children, one published plan per depot-day, effective-dated rule sets and priority policies (seeded with the booklet values), and `plan:Read` implemented. Adds `JdbcPlanRepository`, `PlanDataQuery` behind `PlanQuery` (previews throw until step 6), and read-only `/api/plans`. `FoundationIntegrationTest` now retires reference versions instead of deleting them, because a run holds a foreign key to the version it stamped.
Why: issue #9 step 4. Detail in [PLAN.md](../issues/009-planning/PLAN.md#step-4-result-do-not-re-do).
Verified: `TEST_DATABASE_URL=... mvn test` on PostgreSQL 16, 216 tests, 0 failures, 0 skipped.
Open: step 5 (Generate, Override, Defer and Publish), with an open decision on overrides recorded in the plan. EDGE-CASES test columns for POL-04 and POL-08 get updated at step 7.

## 2026-10-01 - fix: make the platform trustworthy (issue #4)

`feat/platform-hardening` · @jv-ransika

Error contract: every problem body carries `code`, `correlationId` and `violations: [{rule, field?, message}]`; client mistakes are 400/409/413/422/429, never 500; every 500 logs one line with its stack. Correlation id accepted only UUID-shaped and tied to the trace. Emails removed from audit rows, problem details and the accounts cursor. `Metrics.gauge` fixed, command timers with p95, and the detection signals for PLT-01, PLT-07, SEC-03/06/09/10/12/13/16, ORD-05/PLN-06/EXE-14 (names in EDGE-CASES). Tracing export off unless configured; JDBC spans. Shared keyset `Cursor`/`Page` on accounts, policies, assignments and reference lists. Body limit in backend, Next proxy and nginx; CSP in Next and nginx. `AppProperties` types sessions, throttle, body size, tracing and the problem type base. `account-create` is idempotent and `account-grant-depot` logs no email; health checks use real endpoints; nginx limits `POST /api/session`. CI `checks.yml` now runs `mvn verify` and parses both compose files. Integration tests fall back to Testcontainers. Prototype docs deleted; README, development, deployment, verification rewritten.
Why: a 500 left no trace, personal data reached logs and audit, `docker compose up` could not start, and nine modules were about to build on all of it.
Verified: after the rebuild on `dev`, `TEST_DATABASE_URL=... mvn verify` on PostgreSQL 16: 210 tests, 0 failures, 0 skipped; `npm run typecheck` and `npm test` pass.
Rebuilt on `dev` after ordering and sync: `Database` keeps `readAs` and the system actor beside the new counters; compose keeps `scripts/compose-init.sh`; the client `Problem` keeps `extensions` beside `code`, `correlationId` and structured violations. Ordering and sync adapted: `CutoffJob` gauge reads a live value, sync's 429 goes through `DomainException.rateLimited`, `OperationOutcome` covers the new error codes, and the fleet and loader outlet reads follow `nextCursor` via `requestAll`.
Open: integration tests against PostgreSQL, the backend image build, `nginx -t`, a fresh `docker compose up` and CI are unverified (Docker Hub unreachable during this session). The login lockout rolls back with its own transaction and never triggers, and the pool still connects as the owner role: both are issue #5. Lockout now answers 429 with `Retry-After`; `GET /api/accounts` and the other lists now return `{items, nextCursor}`.

---

## 2026-10-01 - feat: add an opt-in log store (Loki, Alloy, Grafana)

`feat/platform-hardening` · @jv-ransika

Compose profile `observability` in `compose.yaml` and `compose.prod.yaml` runs Loki (14-day retention), Alloy and Grafana on 127.0.0.1. Alloy collects containers labelled `com.waypoint.logs=true` and, for a natively run backend, `var/log/*.log` written when `LOG_FILE` is set. Backend services now set `LOG_FORMAT=ecs`. Config in `observability/`, usage in development.md "Searching logs" and deployment.md "Logs". No module code changed.
Why: logs only reached a console, so nothing could be searched and a correlation id could not be followed across requests or services.
Verified: both compose files validate with and without the profile. Backend jar run with `LOG_FORMAT=ecs` and `LOG_FILE`: one request's line reached Loki from the container and from the file, found by `correlationId`, with `level` as the only new label. Logs survive a Loki restart; backend liveness stays 200 with Loki stopped.
Open: the backend Docker image does not build (`mvn dependency:go-offline` fails in `backend/Dockerfile`), and `init` still runs the missing `seed`, so full-stack `docker compose up` is unproven (issue #4). Metrics and traces have no store yet.

---

## 2026-10-01 - ci: www redirects to the bare name

`ci/www-redirect` · @kavindamihiran

nginx answers `www.SITE_ADDRESS` with a 301 to `SITE_ADDRESS`, and the deploy now requests the certificate again when the one on disk is missing any of the three names, instead of only when there is none. Since the entry below, `preview.waypointgo.live` has its record and is on the certificate, and `CLOUDFLARE_ONLY=1` is set, so the server's address no longer answers directly.
Why: `www.waypointgo.live` has a proxied record and answered 520/525, because the server drops names it does not know.
Verified: `nginx -t` and both redirects in a throwaway container on the server. The certificate request for `www` runs on the first production deploy and is not verified until then.
Open: Cloudflare's address ranges in `00-cloudflare.conf` are a dated static list.

## 2026-10-01 - ops: production moves to waypointgo.live

`docs/domain-waypointgo-live` · @kavindamihiran

Production is `https://waypointgo.live`, proxied by Cloudflare, with a Let's Encrypt certificate on the server. The temporary wildcard-DNS hostnames no longer answer; their certificate, the unused Caddy volumes and every mention of them in the documents are removed. The preview's `SITE_ADDRESS` is `preview.waypointgo.live`. Details in [deployment.md](../deployment.md#judge-deployment-on-the-vps).
Why: decision of @kavindamihiran to serve the app from a bought domain behind Cloudflare.
Verified: `https://waypointgo.live` answers 200 through Cloudflare, the server presents the Let's Encrypt certificate for it, and the dispatcher signs in.
Open: closed by the entry above.

## 2026-10-01 - chore: test data for every order status

`chore/seed-scenarios` · @kavindamihiran

`scripts/seed-scenarios.sql` writes 329 orders with timelines and lines directly into the database: one of each of the 13 statuses at OUT001, plus a day's demand at both depots (tomorrow's chilled demand at Peliyagoda exceeds the refrigerated fleet, with two reefer trucks in the workshop), deferrals, a redelivery, an amended order and a date rolled past a Sunday. It also grants the store manager OUT001, the dispatcher both depots and the driver VEH035. Dates are relative to the day it runs; `-v reset=1` removes the seeded orders first. The header of the file has the commands.
Why: Planning, Loading, Execution and Receipt do not exist yet, so nothing else can put an order past `confirmed`.
Verified on production: store manager sees 21 orders in 13 statuses through `/api/orders` and a full timeline, and is refused another outlet (403); dispatcher sees demand for both depots; no timeline is out of order or in the future.
Open: it bypasses the command bus, so there are no audit rows, receipts or events, and every warehouse reference is invented (`SEED-WH-...`). Cancelling or amending a seeded order from the UI will ask the real warehouse about a reservation it never made. Not run on the preview.

## 2026-10-01 - ci: nginx replaces Caddy at the edge, ready for Cloudflare

`ci/nginx-edge` · @kavindamihiran

The VPS edge is now an nginx image built from `deploy/vps/nginx/`, with certbot for Let's Encrypt. It adds what Caddy lacked: per-address rate limits (sign-in, API, pages), no answer for the bare IP or unknown hostnames, a method allowlist, TLS 1.2+ only. It restores the visitor address from Cloudflare and can refuse traffic that bypasses Cloudflare (`CLOUDFLARE_ONLY`, off until DNS is proxied). The deploy tests the new nginx configuration before replacing the running proxy, and brings the proxy up before the database step. Details in [deployment.md](../deployment.md#judge-deployment-on-the-vps).
Why: decision of @kavindamihiran to front the app with nginx behind Cloudflare on a bought domain.
Verified on the server, on localhost-only ports beside the live site and against the real frontends: both hostnames route, HTTP redirects, bare IP gets no response, `/.env` 404, TRACE 405, 5 MB body 413, sign-in works and the sixth rapid bad attempt is 429 while session GETs are not limited, a spoofed `CF-Connecting-IP` is ignored, TLS 1.1 refused. Compose frees the removed service's ports before starting the new one. **Not verified: certificate issuance, the cutover itself, and the Cloudflare lock from outside.**
Open: cutover on merge, then the domain and Cloudflare records. `nginx/` at the root and `compose.prod.yaml` are the prototype's proxy and are not used by the VPS. The `app_caddy-data` and `app_caddy-config` volumes are left on the server.

## 2026-10-01 - ci: preview environment for dev

`ci/preview-deploy` · @kavindamihiran

A push to `dev` now runs the checks and deploys to a preview on the same VPS, with its own database and accounts. The checks moved to a reusable `checks.yml` and also run on pull requests into `dev`. `deploy.sh` serves both environments, chosen by the checkout it sits in; each has its own CI key. No stack publishes a host port any more, including production's PostgreSQL and backend; Caddy reaches both frontends over a shared network. Details in [deployment.md](../deployment.md#judge-deployment-on-the-vps).
Why: problems should show on a real deployment before `dev` is merged into `main`.
Verified: both compose configurations and the Caddyfile validate, the latter inside the running Caddy. **Not verified: neither the production change to the proxy nor a preview deploy has run yet.**
Open: first production deploy with the new proxy layout, then the first preview deploy once `dev` has these files.

## 2026-10-01 - ci: deploy main to the VPS, repair the compose init step

`ci/vps-deploy` · @kavindamihiran

A merge to `main` now runs backend tests against PostgreSQL and the frontend typecheck, boundary test and build, then deploys to the VPS (62.171.128.70) over one SSH connection bound to `deploy/vps/deploy.sh`. Caddy fronts the stack with TLS through `deploy/vps/compose.vps.yaml`. The host is hardened: ufw, fail2ban, unattended upgrades; SSH password login stays on for the team by decision of @kavindamihiran. Details in [deployment.md](../deployment.md#judge-deployment-on-the-vps).
`compose.yaml`'s `init` ran `migrate && seed`, and `seed` went with the prototype, so the backend could never start under Compose. It now runs `scripts/compose-init.sh`: migrate, import-reference, six demo accounts, depot grants, as `scripts/dev.sh setup` does. The frontend healthcheck pointed at `/api/health`, which no longer exists; it now checks `/`.
Why: the Hackathon needs a public URL that stays live, and `docker compose up` is the judged path.
Verified: the merge of #38 deployed `1d5dc15` through the workflow (181 backend tests, frontend checks, then the deploy job). On a fresh volume `init` applied 15 migrations, published 120 outlets and 60 vehicles and created six accounts. The public URL answers 200 with a Let's Encrypt certificate, HTTP redirects, and dispatcher, loader, driver and store manager sign in through it; a wrong password is 401. The server listens publicly on 22, 80 and 443 only.
Open: nobody has walked the judge walkthrough on the live URL. The store manager account has no outlet scope, because there is no CLI command to grant one. `README.md`, `development.md` and `backend/README.md` still document the removed `seed` command and `/api/health`. `compose.prod.yaml` has the same stale healthcheck. No automatic rollback and no database backup schedule on the VPS.

## 2026-10-01 - fix: sign-in matches Figma "01 Sign in"

`fix/sign-in-figma-match` · @kavindamihiran

Sign-in follows the dispatcher and store "01 Sign in" frames: GO in the corner, "Welcome back" above the card, filled fields with placeholders (each still has an aria-label), a password-reset line, and the note about working offline. The error, lockout and outage handling is unchanged.
Why: the sign-in card did not match any design frame.
Verified: `npm run typecheck`, `npm run build`. In the browser at 1440x900 and 393x852: a wrong password shows the generic error, the right one signs in, and "Switch user" in the loader header signs out with the notice.
Open: the background map artwork, the theme toggle, staff ID sign-in (the backend signs in by email), and the loader's employee and PIN sign-in (Figma 07/08) are not built.

## 2026-10-01 - fix: dispatcher shell controls in the sidebar, compact nav below lg

`fix/dispatcher-figma-match` · @kavindamihiran

Dispatcher rechecked against Figma page 05. "Switch user" and the role switcher move into the sidebar's user block; the shell strip that pushed the page down and cut off the sidebar foot is gone. Below `lg` the sidebar becomes a top bar: brand, depot scope, and a scrollable nav. Page headers wrap. With this, every built role draws its own header and the shell strip is only a fallback.
Why: the strip was not in the design, and the dispatcher had no layout below 1024px.
Verified: `npm run typecheck`, `npm run build`, boundaries test. Screenshots at 393x852, 768x1024 and 1440x900.
Open: Orders, Plan, Live, Forecast and Issues content wait on their modules. Figma has no tablet or phone frames for the dispatcher, so the compact nav is a proposal.

## 2026-10-01 - fix: store manager desktop layout matches Figma

`fix/store-figma-match` · @kavindamihiran

Store manager rechecked against Figma pages 14 (Desktop) and 15 (Mobile). From `lg` there is a sidebar with Home, Orders and Deliveries plus their counts, the outlet card, and the signed-in person with "Switch user". It replaces the floating tab bar. Home becomes two columns with a Notifications panel (pending #14). Deliveries become one row each. Place order puts the list on the left and a sticky "Order summary" card on the right, replacing the bottom bar. The sync pill sits top right. On phones the shell controls move into the store header, and dialogs centre from `md`.
Why: on desktop the store showed the phone column centred on the page.
Verified: `npm run typecheck`, `npm run build`, boundaries test. Screenshots at 393x852, 768x1024 and 1440x900 compared with the Figma frames.
Open: Issues, notifications, driver and ETA details, call options and draft orders need modules not built yet (#13, #14, #12).

## 2026-10-01 - fix: loader matches Figma on phone, portrait and landscape tablet

`fix/loader-figma-match` · @kavindamihiran

Loader rechecked against Figma pages 07, 08 and 09. The dock board is a trip table from `md` (768px) and cards on phones. The top bar follows the designs: brand or trip title, a sync pill, an avatar pill, and "Switch user". The load sheet gets a "Locked to you" strip with Hand back, side-by-side actions, "orders loaded" with time to departure, and outlined stop markers. The issue dialog is top-anchored at 560px with tile reasons and a stepper panel. Sign-out, the role switcher and the sync badge move from a strip above the page into the role header through `ShellProvider` in `shared/ui`. The shell still draws its strip for roles not yet converted.
Why: the loader drifted from the design; the tablet dock board was a card grid where Figma has a table.
Verified: `npm run typecheck`, `npm run build`, boundaries test. Screenshots at 393x852, 768x1024 and 1280x800 compared with the Figma frames.
Open: route, stops, load, loader and temperature columns need those fields on `ReadyTripView` (#10). The "Mine" and "Available" filters need the assigned loader. Release as a full page with the seal and driver checklist needs data that does not exist yet. Lock, theme and notifications are not built.

## 2026-10-01 - feat: backend sync module and batch ingest

`feat/sync-module` · @kavindamihiran

New `sync.operations` table with row-level security limiting each account to its own rows. `POST /api/sync` applies a device's queued writes in sequence order. Each write goes through `CommandBus` in its own transaction. Conflicts and refusals are recorded and the batch continues; an outage stops it. A replayed batch is answered from the record. At capacity it answers 429 with a jittered `Retry-After`. Also `GET /api/sync?since=` (keyset) and `sync:Acknowledge`. The frontend queue now drains through `/api/sync` with a per-browser device id. `SyncQuery.pendingFor` now takes the user id, because RLS needs an actor.
Why: #28. The device-side queue from #15 had no server record of what it sent.
Verified: `TEST_DATABASE_URL=... mvn package`, 116 tests, 0 failures, including the new `SyncIntegrationTest` (7) and `OperationOutcomeTest`. `npm run typecheck` and `npm run build`. Against the local backend: a loader batch is recorded, replayed without running again, and listed.
Open: `sync:Discard` and `sync:Resolve` wait on D-O (who reviews another person's conflict; RLS is own-rows only for now). Also open: Background Sync, working-set prefetch, browser e2e tests, and the lockout `Retry-After`.

## 2026-10-01 - fix: loader layout on landscape tablets, and one-command local dev

`fix/loader-tablet-and-local-dev` · @kavindamihiran

Loader widens to 1280px at `lg`: the dock board shows trips in two or three columns, the load sheet pins the truck summary beside the load list, and the release and issue sheets become centred dialogs. Phones are unchanged. `scripts/dev.sh` runs the local stack (`setup`, default, `sample`) against the Docker database only. New `account-grant-depot` CLI command. Sign-in no longer sends a device id, which the backend rejected with a 500 through the `iam.devices` foreign key. Unhandled API exceptions are now logged.
Why: the loader showed a phone-width strip on dock tablets, and the app could not be run locally end to end.
Verified: `npm run typecheck`. In the browser at 1180x820 and 390x844 with loader sample data: dock board and load sheet. Sign-in against the local backend returns 200; depot grants applied.
Open: no outlet-grant command, so the store role needs `sample` mode locally; device registration; sign-in shows "not answering" for a 500.

## 2026-10-01 - feat: frontend sync engine schedule and review list

`feat/app-shell` · @kavindamihiran

`useSync` in `src/shared/offline` drains the queue on reconnect, on focus and visibility, right after a write is queued (`waypoint:queued` event) and every 30 s. `drain()` is now single-flight per account, so it never double-sends beside a role's own flush. The shell shows "n to send" or "n saved on this device" and "n to review". The review list shows each refused write with the server's reason, and offers "send again" or "discard" (a person decides; the engine never drops one). Sign-out reads the same pending count. The loader and store no longer count held writes as still sending.
Why: frontend half of #15.
Verified: typecheck, `npm test` and `npm run build` pass. In the browser with the live store gateway and stubbed APIs: placed offline, sign-out blocked, reconnected, the server answered 409 (sent once), then review and discard.
Open: Background Sync, driver working-set prefetch, queue-age telemetry, and the backend `sync` module (`POST /api/sync`, `sync:Resolve`).

## 2026-10-01 - feat: sign-in, sign-out and role switcher in the app shell

`feat/app-shell` · @kavindamihiran

Sign-in screen on `POST /api/session`, with separate messages for wrong credentials, lockout (countdown from `Retry-After` when sent) and outage. The shell tells apart signed out, server unreachable and offline; an outage no longer reads as "sign in". Sign-out uses `POST /api/session/end` and warns while writes are still queued on the device (SEC-01). Multi-role users switch roles and the last role is remembered per user. Scope is read as prefixed grants (`depot:`, `outlet:`) and each role gets its own values. Admin and auditor routes are placeholders. Queued writes survive a 401 instead of being held for review.
Why: #17, and the sign-out and 401 parts of #15.
Verified: `npm run typecheck`, boundary tests and `npm run build` pass. In the browser at 393px with `/api/session` stubbed: outage, wrong password, 429 countdown, sign in, role switch remembered across reload, sign out, offline.
Open:
- #17: the component gallery and the dark theme.
- #15: scheduled drain, the shared pending indicator and conflict list, and the backend `sync` module.
- The backend returns a 403 for lockout without `Retry-After`, so no countdown is shown there.

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

## 2026-09-30 - feat: loader phone screens from the Figma design

`feat/loader-ui` · @kavindamihiran

Dock board, load sheet (stops in reverse order, per-order check), report an issue, confirm and release, from Figma "08 Loader · Phone". Writes are `loading:*` commands carrying the manifest `rowVersion`. Resilient tier: checks made offline go to the device queue and are sent on reconnect; release always needs a connection. A new plan version shows "plan changed" and marks the reset orders for recheck (R-LOD-03). Release is refused while an order is unchecked (R-LOD-07).
Why: #20, built frontend first while Loading (#10) is not served.
Sample data: departs from D-D by decision. `NEXT_PUBLIC_LOADER_FIXTURES=1` swaps in an in-memory gateway (`roles/loader/data/fixtures.ts`) enforcing the same rules; never active in a production build, and the screen shows "Sample data".
Verified: `npm run typecheck`, `npm test`, `npm run build` green; full flow driven at 393px in `next dev` with sample data, including an offline check and a plan change. Not verified against a live backend.
Open: `/api/loading/trips` and `/api/loading/trips/{id}/manifest` are assumed paths until #10; `ReadyTripView` has no route, stop count, temperature or claimant, so the board card omits them; damage photo, interchange, shift-handover sign-in and Playwright e2e remain.

## 2026-09-30 - feat: dispatcher shell, overview and vehicles from the Figma design

`feat/dispatcher-ui` · @kavindamihiran

GO design tokens and Google Sans Flex in `src/shared/ui/theme.css`, shared components (card, pill, tiles, buttons, sync pill, notice) and the Figma icons in `public/icons/go/`. Dispatcher sidebar, depot scope and hash navigation. Overview and Vehicles read `/api/reference/vehicles`; the vehicle drawer sends `vehicle:SetDayStatus` with a required reason and reuses the command id on retry. Orders, Plan, Live, Forecast and Issues say which module they wait on (D-D, no mocks). Offline turns the screens read only and says so. Mirrored the reference data contract in `src/shared/domain/referencedata.ts`.
Why: first slice of #19 that the backend can serve today.
Verified: `npm run typecheck`, `npm test` and `npm run build` green; screens checked in a production build against the Figma frames with API reads stubbed from `vehicles.csv`. Not verified against a live backend.
Open: the rest of #19 waits on #8, #9, #12, #13, #14 and #16. Reference data has no read listing workshop vehicles, `VehicleView` has no `rowVersion` so the command goes with a null `expectedVersion`, and `BigDecimal` is serialised as a JSON number. No Playwright e2e yet.

## 2026-10-01 - docs: record the revised warehouse API and wire its key

`feat/ordering-module` · @Oxshadha

The warehouse API now has two warehouses (KDY, PLG), requires `warehouse` on `POST /orders`, and answers a short order with a `202` partial reservation that expires. RULES §2 (contract, R-STK-08 to R-STK-10, lifecycle mapping), MODULES, A-07 and A-20 corrected; A-25 added. `WAREHOUSE_*` passed through both Compose files, documented in `.env.example` and [development.md](development.md#the-external-warehouse-api).
Why: the recorded contract said all-or-nothing and no TTL; both are now false.
Verified: read endpoints probed live with a key (200, 401 without); no order was placed.
Open: `StockPort` needs a depot parameter, and the #7 adapter must cancel a `202` and answer `Insufficient` (R-STK-08). Both owned by #7.

## 2026-10-01 - feat: build the Ordering module (issue #8)

`feat/ordering-module` · @Oxshadha

Ordering end to end: pure domain, `ordering` schema with forced row-level security (`20261001T0200`), `JdbcOrderRepository`, `OrderDataQuery` behind `OrderQuery`, read-only `/api/orders`, the `order:Place/Amend/Cancel/CloseForDay` handlers, twelve event consumers and the 16:00 `ordering.cutoff` job. Platform pieces it needed: outbox writer, consumer inbox, system actor with `app.actor_is_system()` (`20261001T0100`), `Database.readAs` so a contract read never switches its caller's role, the bus auditing scope denials raised inside the transaction, and `UnconfiguredStockPort` for a blank warehouse key. New event `order.auto_deferred`; Ordering now consumes `loading.started`.
Why: issue #8. The walkthrough is [docs/issues/008-ordering/WALKTHROUGH.md](../issues/008-ordering/WALKTHROUGH.md); decisions are in EDGE-CASES (ORD-03 revised, ORD-13, ORD-14) and ASSUMPTIONS (A-22 to A-24).
Verified: `TEST_DATABASE_URL=... mvn test` on PostgreSQL 16, 170 tests, 0 failures, 0 skipped. Frontend `npm run typecheck` and `npm test` pass.
Open: no relay or scheduler runs the consumers and the job yet (#6); no real `StockPort` or `CatalogueQuery` (#7); partial redelivery (A-24).

---

## 2026-09-30 - feat: contracts, schemas and roles for every remaining module

`feat/module-contracts` · @jv-ransika

Wrote the `contract` package for ordering, planning, loading, execution, receipt, issues, notification, sync, warehouse and intelligence: views, query interfaces, command payloads and 31 event records. Added `DomainEvent`, `EventEnvelope`, `Page` and `UuidV7` to the kernel, and the `EventPublisher`, `EventSubscriber` and `ScheduledJob` ports to platform. Migration `20260930T1200` creates one schema and one `waypoint_<module>` role per module. `20260930T1201` catalogues every new action and moves the six role policies to version 2. Added TypeScript mirrors in `frontend/src/shared/domain/`.
Why: five people build nine modules in parallel. That only works if every connection between modules exists as merged code first. The team also settled the cross-module decisions on this date: schema per module, timestamped migrations, synchronous stock placement, deferral keeping the reservation, holiday roll-forward, one temperature per trip, and fuel including the return leg.
Verified: `mvn test` in a JDK 17 container, 103 tests green: 74 unit and architecture tests, including the new boundary, event catalogue, UuidV7 and publisher tests, plus 29 integration tests against a throwaway PostgreSQL 16 with `TEST_DATABASE_URL`, which apply both new migrations and run RLS as `waypoint_ordering`. Frontend `npm run typecheck` and `npm test` pass.
Open: no handlers, no tables and no relay yet. Each module writes its own. The shared `waypoint_ops` role is retired but kept, because roles are cluster-wide.

**Decisions are in the documents, not only here.** They are in ADR-002, the Ordering state machine, the warehouse contract and the event catalogue in MODULES. RULES-AND-POLICIES withdraws R-STK-01..03, R-ORD-09 and R-LOD-08, and adds R-PLN-31 and R-STK-14. ASSUMPTIONS closes A-03, A-04, A-18 and A-09. EDGE-CASES renumbers the duplicate SEC-09..13 set to SEC-15..19.

**`ModuleBoundaryTest` now discovers modules instead of listing them.** Any top-level package other than `shared` and `platform` is a module. A module may import another only through its `contract`. The old hard-coded rule only forbade `referencedata` reaching into `identity`, not the reverse, and would not have covered a single new module.

---

## 2026-09-28 - feat: administer accounts, scope and the calendar over HTTP

`dev` · @Oxshadha

Eight identity command handlers in `IdentityCommandHandlers`: create, update, disable, reset password, grant and revoke scope, assign a driver and end an assignment. `AccountQuery` plus `AccountAdminController` and `ReferenceController` for the reads, both authorized through a new `RequestAuthorizer` port that identity implements, so a module's web layer never imports another module. `PolicyAdminController` now shares that guard instead of its own copy. `calendar:Override` is a command; its override lives in `ref.calendar_overrides` and is applied when a snapshot is loaded. Migration `009`.
Why: every account change was a host command, so nothing outside a terminal could make one, and the four read surfaces the frontend needs did not exist.
Verified: 97 tests green with `TEST_DATABASE_URL` set, twice in a row. `AdministrationIntegrationTest` proves over HTTP that a stale `expectedVersion` is `409`, an overlapping driver assignment is `409` naming R-IAM-13 while an abutting one is accepted, a scope naming `OUT999` is `404` rather than stored, an account cannot disable itself, a reset revokes live sessions, and a driver reading `/api/accounts` is `403` rather than an empty list.
Open: devices are still unbuilt, deliberately. Ordering is next.

**Writes and reads split deliberately.** Every change goes through `POST /api/commands`; the controllers are read only. A second write path would have no receipt, no version guard and no audit row, and it would be the one a client reached for. The consequence is that every account read returns `rowVersion`: a read surface that hides the version makes the version guard unusable.

**An override had to be a table, not a column.** `ref.calendar_days` is rewritten by every reference import, so an override stored there would vanish the first time the supplied CSV changed. It also has nowhere to put an actor or a reason, which architecture rule 8 requires of any override. Recorded as R-CAL-04, with R-IAM-13 through R-IAM-17 for the assignment, session and scope rules the handlers enforce.

---

## 2026-09-27 - feat: serve POST /api/commands and route the first commands through the bus

`dev` · @Oxshadha

Added `platform/web/CommandController` and an `ActorResolver` port that identity implements, so platform serves the endpoint without importing a business module. `CommandHandler` now declares its own `ModuleRole`, which keeps the web layer from knowing which module owns a kind. Converted `SetVehicleDayStatusHandler` and `ImportReferenceDataHandler` to handlers; the import keeps a separate CLI entry point because `import-reference` runs with no session and no receipt. Migration `008` corrects `iam.action_catalogue.implemented` to match what is actually enforced.
Why: the command bus, the idempotency guard and the fail-closed authorizer were wired and unit tested, and nothing served `/api/commands` although the frontend write path and the offline queue both post there. Ordering should not be the first thing to run a command.
Verified: 86 tests green with `TEST_DATABASE_URL` set. `CommandPathIntegrationTest` goes over HTTP with a real session cookie: a command posted twice applies once and the retry is answered from the receipt with the same shape, the same id with a changed payload is `409` and changes nothing, a driver posting `reference:Import` is `403` with the denial in `integration.audit_log` and no receipt, and an unsigned caller is `401`.
Open: nothing in this unit. Account administration and reference reads followed in the next entry.

**Two defects the first real dispatch exposed.** The fail-closed check was a chain of `Optional.map`, and mapping to null collapses to empty: with an authorizer present and the command allowed, the bus took the fail-closed branch and denied everything. A replay also returned the receipt's `result_body` as raw jsonb text, so a retry answered with a string where the first call answered with an object. Both are now unit tested in `CommandBusTest` without a database. A mechanism nobody has run is not a mechanism that works.

**The audit log would have stopped accepting writes on 1 November 2026.** `005` created two monthly partitions and said a scheduler would create more. There is no scheduler, and every command commits its audit row in the same transaction as the change, so a missing partition fails the command rather than losing the row. `008` extends the range to July 2027 and closes a related hole: the parent was append only but each partition carried UPDATE from the schema-wide grant.

---

## 2026-09-27 - docs: make the documentation set answerable

`dev` · @Oxshadha

Removed `plan.md`, which had been committed empty. Untracked `TEARDOWN-PLAN.md`, keeping the file on disk: the teardown happened, so it is a record rather than a plan. Replaced the README's flat list with a three-question map, and marked the four documents that predate the rewrite.
Why: "where is the plan we follow" had no clear answer, and four tracked documents still described the prototype deleted at `prototype-v0`.
Verified: 79 links across all tracked markdown, 0 broken.
Open: `docs/architecture.md`, `docs/data-model.md`, `docs/code-structure.md` and `enterprise-architecture-plan.md` describe the deleted prototype. They are now labelled as history; deleting or rewriting them is a separate decision.

`SYSTEM-ARCHITECTURE.md` stays at the repository root deliberately. It is the entry point the way `README.md` is, and `docs/architecture/` holds the detail behind it.

---

## 2026-09-27 - feat: policy administration, and the integration tests that were missing

`dev` · @Oxshadha

Added `PolicyAdminUseCase` and `PolicyAdminController` for authoring, versioning, attaching and detaching policy at runtime, and `FoundationIntegrationTest`: ten tests against a real PostgreSQL covering migrations, reference import, sign-in, Argon2 storage, policy decisions, runtime policy change, catalogue validation, row-level security and session revocation on disable.
Why: every earlier database claim in this log was verified by hand with curl and psql, never by a test. That is not the same thing, and the gap was mine.
Verified: 74 tests green with `TEST_DATABASE_URL` set, of which 64 are unit and 10 integration. Without that variable the integration tests skip, so `mvn package` alone does not prove the database paths.
Open: nothing in the foundation. Ordering is next.

**The integration tests found a real bug on their first run.** `Database.asModule` set the actor with `jdbc.update("SELECT set_config(...)")`, and calling a SELECT through `update()` throws. The actor was therefore never set through the Java path, so row-level security would have seen nobody and returned nothing for every request. Manual testing missed it because RLS was exercised through psql, where the plumbing is different. This is the argument for integration tests in one paragraph.

---

## 2026-09-27 - feat: row level security, and the docs it changes

`dev` · @Oxshadha

Migration `007_row_level_security.sql` adds `app.current_actor()`, `app.actor_has_depot` and `app.actor_has_outlet`, creates the `waypoint_ops` role, and enables RLS with `FORCE` on the two scope tables. Shipped the eleven doc edits this stage owes: policy as data in SYSTEM-ARCHITECTURE 6.2 and 6.8, FOUNDATION-PLAN 2.4, 2.6 and 2.9, MODULES section 2, a new `R-IAM-*` rule group, five new `SEC-*` edge cases, assumption A-21 and an Authorization section in AGENTS.md.
Why: the composition rule needs both halves. Policies decide actions, RLS decides rows, and without the second half a forgotten WHERE clause is a leak.
Verified against the live database: as `waypoint_ops` with the admin actor set, only Peliyagoda is visible; with the driver actor, only Kandy; with no actor, zero rows. `app.actor_has_depot` returns false for everything when no actor is set. Both tables report `relrowsecurity` and `relforcerowsecurity` true, with four policies in place.
Open: policy administration endpoints. A caveat worth knowing: a PostgreSQL superuser bypasses RLS whatever `FORCE` says, so the deployed owner must not be superuser.

---

## 2026-09-27 - feat: identity module, authentication and the decision point

`dev` · @Oxshadha

Policy parsing and repository, a policy cache, and `PolicyDecisionPoint` implementing `CommandAuthorizer`, which is what unblocks the command bus. Authentication: Argon2id hashing, server-side sessions with absolute and idle expiry, a database-backed login throttle, `/api/session` for sign in, resolve and sign out, and an `account-create` command for the first administrator.
Why: nothing can run until an authorizer exists, because the command bus fails closed by design.
Verified: 64 tests green. Live checks against a seeded database: unauthenticated `/api/session` is 401 problem+json; a wrong password and an unknown account return the identical message, with a dummy hash burned on the unknown path so timing does not enumerate accounts; a correct sign in returns the session and sets an HttpOnly cookie holding a 43 character opaque token; the cookie resolves the session. Passwords are stored as `$argon2id$v=19$m=16384...`.
Open: row-level security, the policy administration endpoints and the doc edits remain.

The `SeededPolicyTest` parses `006_iam_policies.sql` and evaluates the shipped documents rather than a copy of them, so the six role policies cannot drift from what the tests claim. It proves the dispatcher is fenced out of administration by an explicit Deny and the auditor writes nothing.

Two corrections. `SessionService` violated the rule against `*Service` names and the boundary test caught it, so it is `SessionRegistry`. `Clock` had no bean, so nothing that injected it could start; it is now registered in `platform/config/TimeConfig`.

---

## 2026-09-27 - feat: policy schema and evaluator

`dev` · @Oxshadha

Migration `006_iam_policies.sql` adds `action_catalogue`, `policies`, immutable `policy_versions` with one enforced default, and `policy_attachments`, seeded with six role policies as data. Added the pure evaluator: patterns with wildcards, six condition operators, and the ordering contract of default Deny, explicit Deny wins, then Allow.
Why: authorization becomes data an administrator edits at runtime rather than code that needs a release. Evaluation stays in process, so the ruling against an external policy service still holds.
Verified: 54 tests green, 18 on the evaluator alone. Migration applied to an empty database: six policies, each with one default version, each attached to its role, and 32 catalogued actions of which 3 are implemented.
Open: the decision point, authentication and row-level security are next. Nothing calls the evaluator yet, so the command bus still fails closed.

Two things the tests caught. A request with no specific resource was matching nothing at all; it is now normalised to `*`, which makes an unscoped request match only an unscoped grant rather than quietly satisfying a scoped one. A missing context key deliberately fails its condition rather than passing it, because treating absence as satisfied would grant access whenever a caller simply forgot to supply the value.

---

## 2026-09-27 - feat: reference read API

`dev` · @Oxshadha

Added the `ReferenceQuery` contract with its own view types, `ReferenceDataQuery` reading from cache for the current version and from the database for a historical one, `SetVehicleDayStatusHandler`, and `ReferenceBootstrap` which loads the current version once the application is ready.
Why: other modules must read reference data through a contract, never through its domain, or the boundary test will stop Planning before it starts.
Verified: 36 tests green, including 8 new window tests covering early arrival waiting, late measured against the close rather than the plan, and the mall window intersection. Booting against the imported database loads version d8a5ba89 into the cache and readiness returns 200.
Open: calendar override is not implemented; the `CALENDAR_FILE` path from R-CAL-03 is still only policy-based generation.

Note: loading the cache is deliberately tied to application-ready rather than startup, so an unreachable database leaves the instance up and reporting the problem instead of boot-looping.

---

## 2026-09-27 - feat: reference data module

`dev` · @Oxshadha

Pure domain (windows, vehicles, outlets, calendar policy, snapshot) plus the nine-rule validator, a CSV importer that hashes its source, a version writer and reader implementing snapshot-per-version, an in-memory cache with pointer swap, and the `import-reference` command.
Why: reference data is the shared vocabulary every other module reads, and it has no upstream dependency, so it is built first.
Verified: import of the supplied data published 120 outlets, 60 vehicles, 12 districts, 2 depots, 9 allowances, 910 calendar days, with exactly one current version. Re-import was a no-op by content hash. Fleet composition matches the booklet exactly: 12 reefer trucks, 40 dry trucks, 4 reefer vans, 4 ambient vans. All 12 mall outlets carry their mall window. 28 tests green, including 13 validator tests that each reject a deliberately broken fixture with no database.
Open: `ReferenceDataQuery` and the vehicle day status and calendar override handlers are not written yet; the import path is complete.

Two corrections while building. The `auditIsReachedThroughItsOwnComponent` boundary rule written in the previous stage was wrong: it forbade every module from naming `Database`, which is exactly what a repository must do. It now says what it meant, that `domain`, `web` and `contract` may not touch the seam. Separately, the working-directory bug fixed earlier in the migrator existed again in the importer, so both now share `DirectoryLocator`.

---

## 2026-09-27 - feat: platform baseline

`dev` · @Oxshadha

Typed validated configuration with a redacted startup report, structured logging, Micrometer metrics behind our own `Metrics` API, OpenTelemetry tracing, the command bus with idempotency receipts and audit, security headers, and migration `005_platform.sql` creating the `integration` schema (command receipts, partitioned audit log, outbox with worker state). Convention B8 and Part 0.3 added to FOUNDATION-PLAN.
Why: these are cross-cutting, so retrofitting any of them means touching every write path already written.
Verified: `mvn package` green, 15 tests including 9 boundary rules. Missing `DATABASE_URL` refuses to start with a named message; a database that is merely down starts fine with liveness UP and readiness DOWN. Unknown path returns 404 as problem+json, `/prometheus` serves 68 samples, all three security headers present, and the startup report shows `warehouseApiKey=absent` rather than a value. Migration 005 applied to an existing database.
Open: the command bus fails closed until the identity module supplies a `CommandAuthorizer`, so no command can run yet. That is intended.

Note: the boundary test caught `platform` depending on `identity.contract.CurrentActor`. Rather than weaken the rule, `CurrentActor` became `shared/domain/Actor`: naming the caller is kernel vocabulary, and platform may not depend on a business module.

---

## 2026-09-27 - feat: frontend skeleton

`dev` · @Oxshadha

Rebuilt `frontend/src/`: session gate and role router in `app-shell/`, API client with RFC 9457 parsing and correlation ids, command envelope carrying command id and expected version, and the tiered offline write queue over IndexedDB. Role folders hold placeholders. No screens.
Why: the shell is what the Figma design hangs in, and the write queue is the piece that is expensive to retrofit. Screens are what the design changes, so building them now would guarantee rework.
Verified: `tsc --noEmit` clean, production build compiles with a 56-asset offline shell, 6 boundary tests pass. The frontend boundary test now fails when it finds no files, mirroring ArchUnit, and that was confirmed by hiding `src/`.
Open: offline tiers are declared but only the driver tier is exercised once screens exist. `/api/session` and `/api/commands` are not implemented yet; they arrive with the IAM module.

---

## 2026-09-27 - feat: foundation schema for reference and identity

`dev` · @Oxshadha

Four migrations creating `ref` (14 tables) and `iam` (9 tables) with decisions D1 to D9 applied, plus a checksummed forward-only `Migrator` and the `migrate` command. `ops`, `ml` and `integration` land with their own modules.
Why: the prototype's nine-table schema was deleted; this is the baseline everything else is built on, so it is created clean rather than as corrections stacked on the old design.
Verified against PostgreSQL 16 from an empty database: 4 migrations apply, re-run is a no-op, editing an applied file is rejected as immutable. `waypoint_app` without adopting a module role gets "permission denied for schema ref"; after `SET ROLE waypoint_ref` it reads. `waypoint_ref` cannot reach `iam`; `waypoint_iam` reads `ref` but cannot write it. A fourth brand inserts with no migration (D3). Overlapping driver assignments on one vehicle are rejected by the exclusion constraint.
Open: reference import and the IAM module behaviour are next. Frontend skeleton not started.

---

## 2026-09-27 - docs: rule, assumption and parameter registers

`dev` · @Oxshadha

Added `RULES-AND-POLICIES.md` (104 rules with source and status, 6 source conflicts), `ASSUMPTIONS.md` (17 assumptions plus a 14-entry parameter register) and resolved decisions D1 to D9 in `FOUNDATION-PLAN.md`. Propagated to MODULES, SYSTEM-ARCHITECTURE, DATA-MODEL-REVIEW, EDGE-CASES (now 117 cases) and the schema README.
Why: the booklet, the supplied validator, the datasets and the team draft disagreed in six places, and nothing recorded which rules were ours versus mandated, or which values can change.
Verified: against `check_allocation.py` and the supplied data. Reefers carrying ambient confirmed by 4 of 9,734 reefer routes; chilled is Fresh-only across 34,742 orders; depot is a function of district with 0 exceptions in 120 outlets; 0 of 25,198 routes mix brand, district, vehicle or temperature; all 12 mall outlets have mall window identical to outlet window. Links checked, 0 broken.
Open: assumptions A-02, A-03, A-08 and A-09 are unconfirmed and each changes servable volume. Warehouse exposes no stock endpoint. Decision taken to rewrite the backend against this baseline rather than extend the existing one, and to defer frontend screens until the Figma design lands.

---

## 2026-09-27 - docs: plan the reference data and IAM foundation

`dev` · @Oxshadha

Added `docs/architecture/FOUNDATION-PLAN.md`: a seven-point stability contract, five baseline defects found in the target schema, and full specs for the Reference data and Identity modules (ownership, versioning model, import validation, contracts, edge cases, definition of done) plus the build sequence.
Why: these two modules are what every other module depends on, and the parts of the current target schema that would force change later needed fixing before anything is built on them.
Verified: not applicable, planning only. No code, no migrations, no application changes.
Open: seven decisions in section 3.4. The largest is the reference versioning model, recommended as snapshot-per-version.

---

## 2026-09-26 - schema: move into version control and add module roles

`dev` · @Oxshadha

Moved the schema out of Downloads into `docs/architecture/schema/`, split into 19 numbered forward-only migrations with `001_baseline.sql` holding the team's original untouched. Added a database role per module (018) and an inbound warehouse event inbox (019). Deleted the `.sql.bak`. Propagated the role model to SYSTEM-ARCHITECTURE, DATA-MODEL-REVIEW, AGENTS.md and seven new SEC edge cases, and added the warehouse integration contract to MODULES.md.
Why: a file defining the whole data model had no history and lived on one laptop, and schema separation is not enforced by anything until grants enforce it.
Verified: all 19 applied in order against PostgreSQL 16 from empty, 49 tables. `waypoint_app` without `SET LOCAL ROLE` is denied, `waypoint_ops` reads its schema and the kernel, is denied writing `iam.users` and deleting from `ops.orders`, and may insert to the outbox. Test databases and cluster roles dropped.
Open: reservation TTL, whether stock is per depot or per outlet, and whether availability is checked at confirm or at planning. These are business decisions, listed in MODULES.md.

## 2026-09-26 - schema: align industry schema with the architecture

`dev` · @Oxshadha

Added Part 2 to `waypoint_industry_schema.sql`: reference and policy versioning, `row_version` and immutability trigger on published plans, command receipts, order status transition table, trip vehicle assignment history, vehicle temporal exclusion, warehouse stock fields, catalogue provenance on `ref.products`, attachment scan and retention, return fuel plus a weekly rollup view, outbox worker state, notification delivery split, 16 missing foreign key indexes, RLS with FORCE and a fail-closed actor function.
Why: the schema was verified directly for the first time, which confirmed six findings and disproved two.
Verified: executed against PostgreSQL. Fresh run from an empty database exits 0, Part 2 re-run is idempotent, the published-plan trigger rejects an in-place edit, and the exclusion constraint rejects an overlapping driver assignment. Test databases dropped.
Open: the schema file still lives in Downloads, outside version control. Partitioning is documented as a migration recipe, not an ALTER, because converting a populated table needs a rebuild.

## 2026-09-26 - docs: add policy and rule change design

`dev` · @Oxshadha

Added SYSTEM-ARCHITECTURE.md section 6.8 (four-tier rule placement, effective dating, version stamping, shadow and canary rollout), versioned `PriorityPolicy` and `RuleSetVersion` in MODULES.md, finding 7 plus `ops.policy_versions` and `ops.rule_parameters` in DATA-MODEL-REVIEW.md, and a POL group of 10 edge cases.
Why: plans stamped the reference-data version and predictions stamped the model version, but nothing stamped the rules, so a deferral could not be reproduced once a threshold changed.
Verified: link check clean; findings renumbered 1 to 21 without corrupting the other numbered tables. Fixed two sections both numbered 3 in EDGE-CASES.md.
Open: whether deferral priority is authored by dispatchers or engineers decides if the decision table needs a UI.

## 2026-09-26 - docs: propagate catalogue rules across architecture docs

`dev` · @Oxshadha

Carried the external product catalogue rules into SYSTEM-ARCHITECTURE.md (external systems, anti-corruption boundary), MODULES.md (`ref.products` is a cached projection; capacity constraints read order-level weight and volume) and EDGE-CASES.md (six CAT cases plus STK-07). Recorded outlet coordinates and the driver-side temporal exclusion as deferred decisions.
Why: the rules only existed in the data model review, so the other documents still implied product lines could feed capacity maths.
Verified: link check across all markdown, 0 broken. Edge case register now 89 cases.
Open: nothing new.

## 2026-09-26 - docs: correct schema findings and add catalogue rules

`dev` · @Oxshadha

Reworded critical findings 1, 2, 3, 5 and 6 in DATA-MODEL-REVIEW.md after a second review against `waypoint_industry_schema.sql`: version columns and status CHECK constraints already exist, so the findings are about incomplete enforcement rather than absence. Added the RLS table-owner trap, `btree_gist`, `SKIP LOCKED`, and a section on the external product catalogue.
Why: the first wording overstated four findings and would have sent implementation after problems that are already half solved.
Verified: not applicable, documentation only.
Open: `waypoint_industry_schema.sql` is not in this repository, so the corrections rest on a second-hand reading. Commit it here. The catalogue carries no stock balances, so the `stock_held` flow has no data source behind it.

## 2026-09-26 - docs: rework architecture for enterprise scope

`dev` · @Oxshadha

Rewrote `SYSTEM-ARCHITECTURE.md` without deadline compromises and added `docs/architecture/`: MODULES.md (12 module specs with internal layers and connections), DATA-MODEL-REVIEW.md (team schema validated, 20 findings, corrected model, expand-contract migration) and EDGE-CASES.md (82 cases with enforcement, detection and test). Rewrote AGENTS.md around 10 architecture rules.
Why: timeline extended by months, so the design targets enterprise practice rather than a competition subset. Warehouse and stock is a separate system, modelled here as an external port behind an anti-corruption layer.
Verified: link check across all markdown, no broken links.
Open: six decisions in SYSTEM-ARCHITECTURE.md section 11, five in DATA-MODEL-REVIEW.md. Largest is migrating the nine-table JSONB schema to the target model.

## 2026-09-26 - docs: add system architecture

`refactor/module-structure` · @Oxshadha

Added `SYSTEM-ARCHITECTURE.md` at the root: six actors, thirteen modules, seven layers, aggregate to table mapping, authentication and authorization design, 45 edge cases and the workstream dependency order.
Why: consolidates the challenge booklet, the team requirements draft and the 40-table schema guide into one design, and separates what ships by October 4 from what the design supports later.
Verified: not applicable, documentation only.
Open: five decisions listed in section 12, including whether to migrate to the 40-table schema before the deadline. Four gaps found in the team schema: no inventory tables, no outlet coordinates, vehicle interchange unmodelled, return cost has no home.

## 2026-09-26 - docs: add local development guide and development log

`refactor/module-structure` · @Oxshadha

Added `development.md` (hybrid loop: PostgreSQL in Docker, app native) and this log, both under `docs/development-docs/`. Markdown is now ignored by default in `.gitignore` with an allow-list.
Why: agent sessions scatter scratch `.md`, and parallel work needs shared context.
Verified: 41 relative markdown links resolve, 0 broken.
Open: nothing.

## 2026-09-26 - refactor: organize backend by module and frontend by role

`refactor/module-structure` · @Oxshadha

Backend split into `shared/`, `platform/`, `referencedata/`, `identity/`, `planning/`; `platform/db/Database` extracted as the single PostgreSQL seam. Frontend moved to `src/app-shell/`, `src/roles/*`, `src/shared/*` behind path aliases. Added 7 ArchUnit rules and 5 frontend import rules.
Why: `DispatchService` had absorbed eight responsibilities at 1780 lines because packages were organized by layer.
Verified: `mvn package` 9 pass, `tsc` clean, `next build` clean, `npm test` 36+4 pass, e2e 11/12. Both boundary guards fail on a planted violation. The one e2e failure reproduces on untouched `HEAD`.
Open: `DispatchService` still 1742 lines; `ApiController` still one controller. Stages 3 and 4 in [code-structure.md](../code-structure.md).

## 2026-09-26 - docs: add architecture plan and code structure spec

`refactor/module-structure` · @Oxshadha

Added [enterprise-architecture-plan.md](enterprise-architecture-plan.md) and [code-structure.md](../code-structure.md).
Why: needed one agreed target before refactoring, and the Hackathon requires an architecture document.
Verified: not applicable, documentation only.
Open: two team decisions, whether to delete or isolate the duplicate Node service in `frontend/lib/`, and the missing Datathon data files in Phase C.
