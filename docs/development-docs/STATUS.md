# Status: where things stand and where to start

One page that answers "how far has this got, and what can I pick up". It is a map, not a record: the reasons live in the [development log](development-log.md), the detail in each issue's `WALKTHROUGH.md`, and the rules in [AGENTS.md](../../AGENTS.md).

Last reviewed: 2026-10-02, against `dev` at `7442e5d` (pull request #70 merged).

**Keeping it true.** A pull request that changes a row's state updates that row in the same pull request, beside its development log entry. A row that disagrees with the code is a bug in this page: fix the row, do not work around it.

## Start here

1. Read [AGENTS.md](../../AGENTS.md). Its ten architecture rules are enforced by tests and by review.
2. Get it running: [development.md](development.md). `scripts/dev.sh setup` once, then `scripts/dev.sh`.
3. Find your work in the tables below, then open the issue on GitHub and the folder under [docs/issues/](../issues/).
4. Read the top entry of each person's file in the [development log](development-log.md) (`log/`). Several people work here at once and the log is how they hear about each other.
5. Before any structural change, read the module's section in [MODULES.md](../architecture/MODULES.md) and the rules it names in [RULES-AND-POLICIES.md](../architecture/RULES-AND-POLICIES.md).

Branch from `dev`, open the pull request into `dev`. `main` is what production runs and moves only by a release pull request from `dev`.

## The whole thing in one table

State words: **built** (merged to `dev` with tests), **partial** (merged, with named parts missing), **in flight** (on an unmerged branch), **not started**.

### Backend modules

| Module | Issue | State | What is left | Detail |
| --- | --- | --- | --- | --- |
| Platform: config, `Database`, command bus, problem details, telemetry, CI, deploy | #4 closed | built | nothing open on #4 | [log](development-log.md) |
| Event backbone: outbox, relay, dead letters, scheduler, audit | #6 open | partial | Relay, replay, scheduler run records, partition/retention/calendar jobs and `audit:Read`/recorded decision APIs are built. Left: detached-partition archive target, handler opt-in for most `before` snapshots, historical attachment evidence and older correlation call sites | [walkthrough](../issues/006-event-backbone/WALKTHROUGH.md) |
| Reference data | #161 | built | Depot and district coordinates are sourced; exact outlet coordinates still need real supplied data (A-11). A calendar override made on one replica is not seen by another until restart | [FOUNDATION-PLAN](../architecture/FOUNDATION-PLAN.md) Part 1 |
| Identity and access | #5 closed | built, hardened | Nothing open on #5: the schema's owner is `waypoint_migrator`, not a superuser, and reference reads stop at the actor's scope. `init` still logs in with the image's bootstrap account to act as that owner. The longer upgrade path is #64 and is not scheduled | [walkthrough](../issues/005-identity-hardening/WALKTHROUGH.md) |
| MCP | #177 closed | built | Reads over stdio and remote OAuth, `day_summary`, prompts, paged plans; client scopes, confirmed `raise_issue` and `assign_issue` with an hourly limit, personal fields withheld unless granted, block an app, own connections and an admin AI assistants screen. Left: `get_thread` (#136) and production enablement | [connect guide](../../mcp/README.md), [walkthrough](../issues/177-mcp/WALKTHROUGH.md) |
| Warehouse integration | #7 closed | built | Polling and matching stand in for a webhook and an idempotency key; the change requests to the warehouse team are open. The chaos drills are manual | [walkthrough](../issues/007-warehouse/WALKTHROUGH.md) |
| Ordering | #8 closed | built | Partial redelivery (A-24). A cancellation made in the warehouse outside Waypoint is counted, not raised as an issue | [walkthrough](../issues/008-ordering/WALKTHROUGH.md) |
| Planning | #9 closed | built | What-if runs, rule-set authoring, a replay command, the explanation for an outlet skipped twice (PLN-03). The engine now has a second pass that plans the reefers again (#92: S1 70 to 73 served); a dispatcher's decisions are recorded on each order and a regenerate can keep them, with saved plans, compare, swap, held orders, a fixed stop order and a message to a store (R-PLN-33 to 37); planning v2 adds a cost stage after it, ALNS over every vehicle class keeping the served set (S1: 16 vehicles and 725 L to 13 and 611 L, R-PLN-38, R-PLN-39), the rules plan kept beside it for Compare, outlet GPS for stop order when exact (R-PLN-40), generation as a queued job outside the command transaction (R-PLN-41) and a reference cache. The engines were measured against an exact MIP (CP-SAT, SCIP), OR-Tools routing and HiGHS on S1, historic and synthetic days (`docs/issues/219-planning-v2/benchmark/planning-benchmark.ipynb`). Learned times stay out of allocation by design (R-ML-05); `plannedWithoutPredictor` now says whether Intelligence scored the published plan with a model (#16) | [walkthrough](../issues/009-planning/WALKTHROUGH.md) |
| Loading | #10 open | partial | Start, check, shortfall, hand back and release are built. Left: vehicle interchange, dispatcher handover, driver-assignment gating | [walkthrough](../issues/010-loading/WALKTHROUGH.md) |
| Execution | #12 open | built; GPS positions (#161) | A read for the last reported vehicle status. Virus scan. ETA through the #16 estimator once #16 has a travel-time method | [walkthrough](../issues/012-execution/WALKTHROUGH.md) |
| Receipt and Issues | #13 closed | built; handover PIN (R-RCP-09) and issue photos added 2026-10-02 | Partial redelivery, shared with Ordering and Warehouse | [walkthrough](../issues/013-receipt-issues/WALKTHROUGH.md) |
| Receipt and Issues | #13 closed | built | Partial redelivery, shared with Ordering and Warehouse | [walkthrough](../issues/013-receipt-issues/WALKTHROUGH.md) |
| Sync | #15 closed, #28 closed | built | Batch ingest, acknowledge, discard and resolve (owner only, D-O), queue age and time to drain. Background Sync drains with or without a page open, except a loader queue, which waits for a page (A-39) | [walkthrough](../issues/028-offline-sync-followups/WALKTHROUGH.md) |
| Notification | #14 open, #118 | built: backend, routing version 2 (R-NOT-10, R-NOT-11), facts kept with each message, the inbox in the dispatcher, loader, store and driver screens with a live badge and polling fallback, and phone and desktop push (switch in each role's settings; the service worker shows pushes) | Push works only where the server has VAPID keys (`PUSH_VAPID_PUBLIC_KEY`, `PUSH_VAPID_PRIVATE_KEY`), and says so otherwise; no admin API to publish a routing version; dock in `trip.released` (R-EXE-08) and next planned date in `order.deferred` wait on Loading and Planning | [walkthrough](../issues/118-notifications-inbox/WALKTHROUGH.md) |
| Intelligence | #16 open | built (backend); Forecast screen built | The screens: late risk (#19), supply probability (#18), model registry (#22). The two models are not yet registered and activated on preview or production, so forecasts there are the fallback. No retraining pipeline (the training export is its input). Road conditions end 2026-06-28, so later dates use the fallback model. Execution ETA still has no travel-time method | [walkthrough](../issues/016-intelligence/WALKTHROUGH.md) |

### Screens

| Role | Issue | State | What is left | Detail |
| --- | --- | --- | --- | --- |
| Shell, sign-in, role routing, design system | #17 closed, #28 closed, #201 closed | built: shared components follow the `go-dark` tokens, and `/gallery` shows them light and dark in development. Each role address installs as its own app (Waypoint Driver, Loader, Store) with its own manifest and icon, and the service worker keeps icons, fonts, images and map tiles for use with no coverage (#201 PR 1) | The dispatcher and store screens have no dark mode of their own. #201 built: [walkthrough](../issues/201-role-pwa/WALKTHROUGH.md) | [walkthrough](../issues/028-offline-sync-followups/WALKTHROUGH.md) |
| Offline queue, kept reads, queued uploads | #15 closed, #28 closed, #201 closed | built, with redo and discard of a held write in the shared review panel and Background Sync. The loader and store keep their reads too (`readThrough`), carry on offline from the remembered session, and their top bars say when kept data is shown (#201 PR 2) | Redo is offered where a role registers a resolver: the loader does; the driver (#21) can through `registerResolver` | `frontend/src/shared/offline/tiers.ts` |
| Store manager | #18 closed | built, merged to `dev` (#107); self-service on `fix/store-figma-visual` | Built: Figma sections 1 to 6 (main flow, ordering picker, deliveries and make-up drawer, receiving with photos and the PIN, issues, deferred page, account menu). Home shows no warning cards (Figma "02 Home"); Orders opens on what is still open, with Received, Cancelled and All filters. The manager edits their own name and phone (R-IAM-32) and the store's window, dock and contacts (R-REF-01) from the account menu. Notifications drawer and Home card, and "Synced" syncs now (#118). The Track screen has the live map card (#161). Left: calls and voice; sign-in and forgot password. Phones either way up and tablets checked on seven sizes (#201 PR 4: compact tab bar on a phone held sideways, New order clear of the sync pill from `lg`). Browser suite: `playwright.store.config.ts` (33 tests plus `devices.spec.ts` on each size), run in CI by the Browser suites job | [log](development-log.md), 2026-10-03 |
| Loader | #20 closed | built, matched to Figma: phone (08) light and dark, and tablet, portrait tablet, desk and terminal (07, 09, 10) with the departures table and the sign-in keypad; the notifications bell and inbox, and "Synced" syncs now (#118) | Sinhala and Tamil are drafts awaiting a native speaker. Issue photo (no Loading upload endpoint). Interchange waits on #10 | [walkthrough](../issues/010-loading/WALKTHROUGH.md) |
| Driver | #21 open, #117, #201 | built on the Figma screens (#174), fed by the run sheet; the handover PIN added 2026-10-03. Fills any phone either way up and tablets (no phone mock-up); a landscape tablet shows the trip map beside the run, and the run's map tiles are kept on the phone (#201 PR 3) | English only. Records GPS while a run is open and has the route map with Navigate for exact store locations (#161). The Home feed and the driving-mode badge show the driver's notifications (#118). Vehicle pick-up by QR, fuel and call have no backend and are left out | [walkthrough](../issues/021-driver-ui/WALKTHROUGH.md) |
| Dispatcher | #19 open | partial | The Plan screen follows the design: decide (counter, keep the rest deferred, swap window, too-big card), view plan (deferred column, filter, low-load, late risk once scored, with each risky trip and every stop tagged (#119), edit trip), publish (blocked until decided, send update), saved plans and compare. Orders, Plan, Live, Overview, Vehicles with weekly fuel, the Issues inbox, skipped outlets, Forecast, and the notifications panel and Overview card with "Synced" reloading the screen (#118), and Live as in the Figma frames (needs you, trip board, timeline, map with the vehicle panel, the trip page; #161 map) are built. Left: store and driver messages from Live (Notify store, Send an update, voice, calls, what each outlet was told: drawn, disabled, no backend yet), assigning an issue to someone else (no staff read), interchange approval (#10), the sync conflict queue, late risk on the plan (backend built, #16) | [walkthrough](../issues/019-dispatcher-ui/WALKTHROUGH.md) |
| Admin console | #22 open | partial | Local admin read routes now cover scoped depots, outlets, vehicles, orders, plans, role and action catalogues. Accounts, audit and forecasts use existing APIs. Permission editing and effective access explanations remain unavailable pending versioned policy APIs; production also needs admin depot grants and deployment of these routes. Auditor console remains open. | [log](development-log.md) |
| Auditor console | #23 open | mock UI built on `23-auditor-console` | Overview, searchable activity, security events and event details at `/access-demo`; live API wiring remains open; `audit:Read` now exists (#6) | [UI plan](../issues/022-admin-console/AUDIT-CONSOLE-UI-PLAN.md) |

### Across everything

| Gap | Why it matters | Owner |
| --- | --- | --- |
| The only database backups are the dumps a deploy takes before it migrates | They are on the server's own disk: enough to undo a bad migration, not to survive a lost disk. Nothing takes one between deploys, and the restore steps in [deployment.md](../deployment.md#backup-and-recovery) have not been exercised on the server | no issue yet |
| A fresh install is seeded with confirmed orders only (#114), not a published plan or a delivered stop | `seed-delivery-day` gives the judge a peak day to plan, so planning, loading, delivery and receipt are walked live from there; all eleven README steps were walked in a browser on a fresh `docker compose up --build` (#114). The dock board and the driver find the seeded day ahead of today. The role browser suites still run against a mocked API | #114 |
| The full `docker compose up --build` judge path has not been re-walked since the modules landed | It is the path the brief requires and the one nobody runs daily | before the submission |

## What to pick up next

In dependency order. An item nobody is assigned to on GitHub is free; say so on the issue before you start.

1. **Walk the role flows on production.** Release #83 (2026-10-02) put everything on `dev` at `waypointgo.live` and the six role addresses; nobody has yet walked each role there end to end.
2. **A fresh-install seed** that leaves one depot-day with a published plan, a released trip and a delivered stop. It unblocks a live browser run for every role and the judge walkthrough. `loading-fixture` and `scripts/seed-scenarios.sql` are partial precedents; the second bypasses the command bus and must not be the model.
3. **Dispatcher, the rest of #19.** The Issues inbox is built; what is left waits on #10 (interchange), a staff read for assigning to others, and late risk on the plan, whose backend is built (#16).
4. **Notifications in each role UI (#18, #19, #21).** The backend is built (#14): inbox, unread count, live stream, push config and the four commands. Each role places its own inbox and badge, and the push opt-in with its service worker handlers in `scripts/build-sw.mjs`.
5. **Event backbone, the rest of #6.** Wire the auditor console (#23) to the existing read API; choose the detached-partition archive target and fill the historical audit gaps listed in its walkthrough.
6. **Loading interchange (#10)**, then its approval screen in #19. Planning's `previewInterchange` already exists.
7. **Admin console (#22).** The account, scope and policy commands it needs are built; today they are sent from a terminal.
8. **Intelligence screens (#16 backend done, Forecast built).** Register and activate the two models on each deployment; show plan late risk on the dispatcher, supply probability at the store, and the model registry on the admin console.

## How a piece of work goes

The rules are in [AGENTS.md](../../AGENTS.md); this is only the order.

1. `docs/issues/<NNN>-<slug>/PLAN.md` before code: current state, which layer owns each dependency, the decisions taken, the pull request breakdown.
2. Contract first if another module will call you: `<module>/contract/`, mirrored in `frontend/src/shared/domain/`.
3. Domain with tests that need no database, then the handler, its catalogue row, its migration, and an integration test through the command bus that includes one denied case.
4. `WALKTHROUGH.md` before the issue closes, the rule in RULES-AND-POLICIES, the case and its test in EDGE-CASES, an entry in the development log, and the row on this page.

Good examples to copy: [008-ordering](../issues/008-ordering/) for a first module on the foundation, [013-receipt-issues](../issues/013-receipt-issues/) for two modules connected by events, [021-driver-ui](../issues/021-driver-ui/) for an offline role.
