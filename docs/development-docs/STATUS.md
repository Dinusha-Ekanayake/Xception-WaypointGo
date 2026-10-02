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
| Event backbone: outbox, relay, dead letters, scheduler, audit | #6 open | partial | Relay, consumer inbox and `platform:ReplayEvent` are built. Left: scheduler run records, the audit partition job (the last partition ends 2027-07-01, after which every command fails), retention jobs, the calendar exhaustion alert, audit `before`/`after` and `command_id`, the `audit:Read` API, decision replay | [walkthrough](../issues/006-event-backbone/WALKTHROUGH.md) |
| Reference data | none | built | A calendar override made on one replica is not seen by another until restart. Reads are not filtered by the actor's depot | [FOUNDATION-PLAN](../architecture/FOUNDATION-PLAN.md) Part 1 |
| Identity and access | #5 open | built, hardened | A non-superuser `waypoint_migrator` owner. Depot-scoped reference reads. The longer upgrade path is #64 and is not scheduled | [walkthrough](../issues/005-identity-hardening/WALKTHROUGH.md) |
| Warehouse integration | #7 closed | built | Polling and matching stand in for a webhook and an idempotency key; the change requests to the warehouse team are open. The chaos drills are manual | [walkthrough](../issues/007-warehouse/WALKTHROUGH.md) |
| Ordering | #8 closed | built | Partial redelivery (A-24). A cancellation made in the warehouse outside Waypoint is counted, not raised as an issue | [walkthrough](../issues/008-ordering/WALKTHROUGH.md) |
| Planning | #9 closed | built | What-if runs, rule-set authoring, a replay command, the explanation for an outlet skipped twice (PLN-03), an optimiser. Every plan is `plannedWithoutPredictor = true` until #16 | [walkthrough](../issues/009-planning/WALKTHROUGH.md) |
| Loading | #10 open | partial | Start, check, shortfall, hand back and release are built. Left: vehicle interchange, dispatcher handover, driver-assignment gating | [walkthrough](../issues/010-loading/WALKTHROUGH.md) |
| Execution | #12 open | built | A read for the last reported vehicle status. Virus scan. ETA through the #16 estimator once #16 has a travel-time method | [walkthrough](../issues/012-execution/WALKTHROUGH.md) |
| Receipt and Issues | #13 closed | built | Partial redelivery, shared with Ordering and Warehouse | [walkthrough](../issues/013-receipt-issues/WALKTHROUGH.md) |
| Sync | #15 closed, #28 open | partial | Batch ingest and acknowledge are built. Left: `sync:Discard` and `sync:Resolve` (they wait on decision D-O: who reviews another person's conflict), Background Sync, queue age telemetry | [log](development-log.md), 2026-10-01 |
| Notification | #14 open | not started | Contract only. Nothing consumes `eta.changed`, `delivery.started`, `road.disruption_reported` or routes `issue.raised`, `issue.escalated`, `receipt.disputed` to a person. Decided on the issue: no shared inbox component, each role places its own | [MODULES](../architecture/MODULES.md) section 9 |
| Intelligence | #16 open | not started | Contract only. The deterministic estimator, the serving adapter and the `ml` tables | [MODULES](../architecture/MODULES.md) section 12 |

### Screens

| Role | Issue | State | What is left | Detail |
| --- | --- | --- | --- | --- |
| Shell, sign-in, role routing, design system | #17 closed | built | Dark theme tokens for the shared system and a component gallery (#28) | [log](development-log.md), 2026-10-01 |
| Offline queue, kept reads, queued uploads | #15 closed, #28 open | built | Background Sync, and "resolve" for a held write, which needs `sync:Resolve` | `frontend/src/shared/offline/tiers.ts` |
| Store manager | #18 closed | built | Notifications, driver and ETA details, call options and draft orders: nothing backs them yet (#14) | [log](development-log.md), 2026-10-01 |
| Loader | #20 closed | built | Sinhala and Tamil are drafts awaiting a native speaker. Interchange waits on #10 | [walkthrough](../issues/010-loading/WALKTHROUGH.md) |
| Driver | #21 open | built | English only. Vehicle pick-up by QR, the inbox, fuel, call and map have no backend and are left out | [walkthrough](../issues/021-driver-ui/WALKTHROUGH.md) |
| Dispatcher | #19 open | partial | Orders, Plan, Live, Overview shell and Vehicles are built. Left: Overview's live tiles, skipped outlets with counts, weekly fuel per vehicle, the Issues inbox, interchange approval, the sync conflict queue, Forecast | [walkthrough](../issues/019-dispatcher-ui/WALKTHROUGH.md) |
| Admin console | #22 open | in flight | Nothing on `dev`: `RoleRouter` shows "not built yet". Work exists on `22-admin-console` and `frontend/admin_test_UI`, unmerged | issue #22 |
| Auditor console | #23 open | not started | Blocked on the `audit:Read` API (#6) | issue #23 |

### Across everything

| Gap | Why it matters | Owner |
| --- | --- | --- |
| `main` is 114 commits behind `dev` (last release was pull request #45, 2026-10-01) | Production at `waypointgo.live` runs none of Planning, Loading, Execution, Receipt, Issues or the role screens. Only the preview does | whoever cuts the release pull request |
| No seed that walks a fresh install from an order to a receipt | Every role's browser suite runs against a mocked API. The same flows against the real backend have been run module by module in integration tests, not as one journey in a browser | no issue yet |
| The role browser suites are not in CI | A screen can break without a failed check. Run the affected suite by hand: see [development.md](development.md#tests) | no issue yet |
| The full `docker compose up --build` judge path has not been re-walked since the modules landed | It is the path the brief requires and the one nobody runs daily | before the submission |

## What to pick up next

In dependency order. An item nobody is assigned to on GitHub is free; say so on the issue before you start.

1. **Release `dev` to `main`**, then walk the role flows on production. Nothing a judge opens on the production address reflects the last three days of work.
2. **A fresh-install seed** that leaves one depot-day with a published plan, a released trip and a delivered stop. It unblocks a live browser run for every role and the judge walkthrough. `loading-fixture` and `scripts/seed-scenarios.sql` are partial precedents; the second bypasses the command bus and must not be the model.
3. **Dispatcher, the rest of #19.** The Issues inbox first: its backend (#13) is done and dispatchers cannot yet see or resolve an issue on screen.
4. **Notification (#14).** The largest unbuilt module, and what the store manager, driver and dispatcher gaps above all wait on.
5. **Event backbone, the rest of #6.** The `audit:Read` API unblocks the auditor console (#23); the partition job is a hard date.
6. **Loading interchange (#10)**, then its approval screen in #19. Planning's `previewInterchange` already exists.
7. **Admin console (#22).** The account, scope and policy commands it needs are built; today they are sent from a terminal.
8. **Intelligence (#16).** Planning works without it and says so on every plan.

## How a piece of work goes

The rules are in [AGENTS.md](../../AGENTS.md); this is only the order.

1. `docs/issues/<NNN>-<slug>/PLAN.md` before code: current state, which layer owns each dependency, the decisions taken, the pull request breakdown.
2. Contract first if another module will call you: `<module>/contract/`, mirrored in `frontend/src/shared/domain/`.
3. Domain with tests that need no database, then the handler, its catalogue row, its migration, and an integration test through the command bus that includes one denied case.
4. `WALKTHROUGH.md` before the issue closes, the rule in RULES-AND-POLICIES, the case and its test in EDGE-CASES, an entry in the development log, and the row on this page.

Good examples to copy: [008-ordering](../issues/008-ordering/) for a first module on the foundation, [013-receipt-issues](../issues/013-receipt-issues/) for two modules connected by events, [021-driver-ui](../issues/021-driver-ui/) for an offline role.
