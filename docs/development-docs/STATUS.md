# Status: where things stand and where to start

One page that answers "how far has this got, and what can I pick up". It is a map, not a record: the reasons live in the [development log](development-log.md), the detail in each issue's `WALKTHROUGH.md`, and the rules in [AGENTS.md](../../AGENTS.md).

Last reviewed: 2026-10-02, against `dev` at `7442e5d` (pull request #70 merged).

**Keeping it true.** A pull request that changes a row's state updates that row in the same pull request, beside its development log entry. A row that disagrees with the code is a bug in this page: fix the row, do not work around it.

## Start here

1. Read [AGENTS.md](../../AGENTS.md). Its ten architecture rules are enforced by tests and by review.
2. Get it running: [development.md](development.md). `scripts/dev.sh setup` once, then `scripts/dev.sh`.
3. Find your work in the tables below, then open the issue on GitHub and the folder under [docs/issues/](../issues/).
4. Read the top five entries of the [development log](development-log.md). Several people work here at once and the log is how they hear about each other.
5. Before any structural change, read the module's section in [MODULES.md](../architecture/MODULES.md) and the rules it names in [RULES-AND-POLICIES.md](../architecture/RULES-AND-POLICIES.md).

Branch from `dev`, open the pull request into `dev`. `main` is what production runs and moves only by a release pull request from `dev`.

## The whole thing in one table

State words: **built** (merged to `dev` with tests), **partial** (merged, with named parts missing), **in flight** (on an unmerged branch), **not started**.

### Backend modules

| Module | Issue | State | What is left | Detail |
| --- | --- | --- | --- | --- |
| Platform: config, `Database`, command bus, problem details, telemetry, CI, deploy | #4 closed | built | nothing open on #4 | [log](development-log.md) |
| Event backbone: outbox, relay, dead letters, scheduler, audit | #6 open | partial | Relay, replay, scheduler run records, partition/retention/calendar jobs and `audit:Read`/recorded decision APIs are built. Left: detached-partition archive target, handler opt-in for most `before` snapshots, historical attachment evidence and older correlation call sites | [walkthrough](../issues/006-event-backbone/WALKTHROUGH.md) |
| Reference data | none | built | A calendar override made on one replica is not seen by another until restart | [FOUNDATION-PLAN](../architecture/FOUNDATION-PLAN.md) Part 1 |
| Identity and access | #5 closed | built, hardened | Nothing open on #5: the schema's owner is `waypoint_migrator`, not a superuser, and reference reads stop at the actor's scope. `init` still logs in with the image's bootstrap account to act as that owner. The longer upgrade path is #64 and is not scheduled | [walkthrough](../issues/005-identity-hardening/WALKTHROUGH.md) |
| Read-only MCP | #87 open | in flight | Dedicated personal sessions and 12 curated stdio tools, merged to `dev` (#93, #94); default off, on in preview. Local stdio clients connect by the guide; every role has a Connect AI button with the remote address and steps. Remote Streamable HTTP and personal OAuth consent implemented on `feat/complete-remote-mcp`, awaiting integration. Left: bounded work discovery, custody composition, hosted-client validation and the production release | [connect guide](../../mcp/README.md), [walkthrough](../issues/087-readonly-mcp/WALKTHROUGH.md) |
| Warehouse integration | #7 closed | built | Polling and matching stand in for a webhook and an idempotency key; the change requests to the warehouse team are open. The chaos drills are manual | [walkthrough](../issues/007-warehouse/WALKTHROUGH.md) |
| Ordering | #8 closed | built | Partial redelivery (A-24). A cancellation made in the warehouse outside Waypoint is counted, not raised as an issue | [walkthrough](../issues/008-ordering/WALKTHROUGH.md) |
| Planning | #9 closed | built | What-if runs, rule-set authoring, a replay command, the explanation for an outlet skipped twice (PLN-03). The engine now has a second pass that plans the reefers again (#92: S1 70 to 73 served); a general optimiser over every vehicle class is not built. Learned times stay out of allocation by design (R-ML-05); `plannedWithoutPredictor` now says whether Intelligence scored the published plan with a model (#16) | [walkthrough](../issues/009-planning/WALKTHROUGH.md) |
| Loading | #10 open | partial | Start, check, shortfall, hand back and release are built. Left: vehicle interchange, dispatcher handover, driver-assignment gating | [walkthrough](../issues/010-loading/WALKTHROUGH.md) |
| Execution | #12 open | built | A read for the last reported vehicle status. Virus scan. ETA through the #16 estimator once #16 has a travel-time method | [walkthrough](../issues/012-execution/WALKTHROUGH.md) |
| Receipt and Issues | #13 closed | built; handover PIN (R-RCP-09) and issue photos added 2026-10-02 | Partial redelivery, shared with Ordering and Warehouse. No driver screen enters the handover PIN yet | [walkthrough](../issues/013-receipt-issues/WALKTHROUGH.md) |
| Sync | #15 closed, #28 open | partial | Batch ingest and acknowledge are built. Left: `sync:Discard` and `sync:Resolve` (they wait on decision D-O: who reviews another person's conflict), Background Sync, queue age telemetry | [log](development-log.md), 2026-10-01 |
| Receipt and Issues | #13 closed | built | Partial redelivery, shared with Ordering and Warehouse | [walkthrough](../issues/013-receipt-issues/WALKTHROUGH.md) |
| Sync | #15 closed, #28 closed | built | Batch ingest, acknowledge, discard and resolve (owner only, D-O), queue age and time to drain. Background Sync drains only with a page open (A-39) | [walkthrough](../issues/028-offline-sync-followups/WALKTHROUGH.md) |
| Notification | #14 open | built (backend) | Each role UI must place its inbox, live badge (`/api/notifications/stream`), push opt-in and service worker `push` handlers (decided on the issue: no shared component). No admin API to publish a routing version. Dock in `trip.released` (R-EXE-08) and next planned date in `order.deferred` wait on Loading and Planning | [walkthrough](../issues/014-notification/WALKTHROUGH.md) |
| Intelligence | #16 open | built (backend) | The screens: Forecast and late risk (#19), supply probability (#18), model registry (#22). No retraining pipeline (the training export is its input). The VPS needs `git-lfs` once. Road conditions end 2026-06-28, so later dates use the fallback model. Execution ETA still has no travel-time method | [walkthrough](../issues/016-intelligence/WALKTHROUGH.md) |

### Screens

| Role | Issue | State | What is left | Detail |
| --- | --- | --- | --- | --- |
| Shell, sign-in, role routing, design system | #17 closed | built | Dark theme tokens for the shared system and a component gallery (#28) | [log](development-log.md), 2026-10-01 |
| Offline queue, kept reads, queued uploads | #15 closed, #28 open | built | Background Sync, and "resolve" for a held write, which needs `sync:Resolve` | `frontend/src/shared/offline/tiers.ts` |
| Store manager | #18 closed | in flight on `feat/store-manager-figma`: Figma sections 1 to 6 built | Built: main flow, driver and ETA, handover PIN, Receive with one report per problem, photos and the loader's check per item, the Issues tab and report dialog, the ordering picker, deliveries per vehicle with the make-up drawer, the deferred page and the account menu. Notifications have a backend (#14, built by its owner) that this screen does not place yet; calls, voice and the live map stay out, as do sign-in and forgot password. Browser suite: `playwright.store.config.ts` (22 tests), not in CI | [log](development-log.md), 2026-10-03 |
| Shell, sign-in, role routing, design system | #17 closed, #28 closed | built: shared components follow the `go-dark` tokens, and `/gallery` shows them light and dark in development | The dispatcher and store screens have no dark mode of their own | [walkthrough](../issues/028-offline-sync-followups/WALKTHROUGH.md) |
| Offline queue, kept reads, queued uploads | #15 closed, #28 closed | built, with redo and discard of a held write in the shared review panel and Background Sync | Redo is offered where a role registers a resolver: the loader does; the driver (#21) can through `registerResolver` | `frontend/src/shared/offline/tiers.ts` |
| Store manager | #18 closed | built, contracts checked against the backend 2026-10-02 | Notifications have a backend (#14) for the screen to place; driver and ETA details, call options and draft orders have none yet. No browser suite of its own | [log](development-log.md), 2026-10-02 |
| Loader | #20 closed | built, matched to Figma: phone (08) light and dark, and tablet, portrait tablet, desk and terminal (07, 09, 10) with the departures table and the sign-in keypad | Sinhala and Tamil are drafts awaiting a native speaker. The notifications bell is not wired yet though its backend exists (#14); issue photo (no Loading upload endpoint). Interchange waits on #10 | [walkthrough](../issues/010-loading/WALKTHROUGH.md) |
| Driver | #21 open | built | English only. Vehicle pick-up by QR, the inbox, fuel, call and map have no backend and are left out | [walkthrough](../issues/021-driver-ui/WALKTHROUGH.md) |
| Dispatcher | #19 open | partial | Orders, Plan, Live, Overview, Vehicles with weekly fuel, the Issues inbox and skipped outlets are built. Left: assigning an issue to someone else (no staff read), interchange approval (#10), the sync conflict queue, Forecast and late risk (backend built, #16) | [walkthrough](../issues/019-dispatcher-ui/WALKTHROUGH.md) |
| Admin console | #22 open | in flight | The admin role shows the interactive sample console after admin sign-in, on preview and production. It runs on mock data and says so on screen; live backend wiring remains open | issue #22 |
| Auditor console | #23 open | mock UI built on `23-auditor-console` | Overview, searchable activity, security events and event details at `/access-demo`; live API wiring remains open; `audit:Read` now exists (#6) | [UI plan](../issues/022-admin-console/AUDIT-CONSOLE-UI-PLAN.md) |

### Across everything

| Gap | Why it matters | Owner |
| --- | --- | --- |
| The only database backups are the dumps a deploy takes before it migrates | They are on the server's own disk: enough to undo a bad migration, not to survive a lost disk. Nothing takes one between deploys, and the restore steps in [deployment.md](../deployment.md#backup-and-recovery) have not been exercised on the server | no issue yet |
| No seed that walks a fresh install from an order to a receipt | Every role's browser suite runs against a mocked API. The same flows against the real backend have been run module by module in integration tests, not as one journey in a browser | no issue yet |
| The role browser suites are not in CI | A screen can break without a failed check. Run the affected suite by hand: see [development.md](development.md#tests) | no issue yet |
| The full `docker compose up --build` judge path has not been re-walked since the modules landed | It is the path the brief requires and the one nobody runs daily | before the submission |

## What to pick up next

In dependency order. An item nobody is assigned to on GitHub is free; say so on the issue before you start.

1. **Walk the role flows on production.** Release #83 (2026-10-02) put everything on `dev` at `waypointgo.live` and the six role addresses; nobody has yet walked each role there end to end.
2. **A fresh-install seed** that leaves one depot-day with a published plan, a released trip and a delivered stop. It unblocks a live browser run for every role and the judge walkthrough. `loading-fixture` and `scripts/seed-scenarios.sql` are partial precedents; the second bypasses the command bus and must not be the model.
3. **Dispatcher, the rest of #19.** The Issues inbox is built; what is left waits on #10 (interchange), a staff read for assigning to others, and the Forecast and late-risk screens, whose backend is now built (#16).
4. **Notifications in each role UI (#18, #19, #21).** The backend is built (#14): inbox, unread count, live stream, push config and the four commands. Each role places its own inbox and badge, and the push opt-in with its service worker handlers in `scripts/build-sw.mjs`.
5. **Event backbone, the rest of #6.** Wire the auditor console (#23) to the existing read API; choose the detached-partition archive target and fill the historical audit gaps listed in its walkthrough.
6. **Loading interchange (#10)**, then its approval screen in #19. Planning's `previewInterchange` already exists.
7. **Admin console (#22).** The account, scope and policy commands it needs are built; today they are sent from a terminal.
8. **Intelligence screens (#16 backend done).** Show plan late risk and the forecast on the dispatcher, supply probability at the store, and the model registry on the admin console. Install `git-lfs` on the VPS before the first deploy that carries the model service.

## How a piece of work goes

The rules are in [AGENTS.md](../../AGENTS.md); this is only the order.

1. `docs/issues/<NNN>-<slug>/PLAN.md` before code: current state, which layer owns each dependency, the decisions taken, the pull request breakdown.
2. Contract first if another module will call you: `<module>/contract/`, mirrored in `frontend/src/shared/domain/`.
3. Domain with tests that need no database, then the handler, its catalogue row, its migration, and an integration test through the command bus that includes one denied case.
4. `WALKTHROUGH.md` before the issue closes, the rule in RULES-AND-POLICIES, the case and its test in EDGE-CASES, an entry in the development log, and the row on this page.

Good examples to copy: [008-ordering](../issues/008-ordering/) for a first module on the foundation, [013-receipt-issues](../issues/013-receipt-issues/) for two modules connected by events, [021-driver-ui](../issues/021-driver-ui/) for an offline role.
