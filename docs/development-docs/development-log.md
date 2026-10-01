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

## 2026-10-01 - fix: make the platform trustworthy (issue #4)

`feat/module-contracts` · @jv-ransika

Error contract: every problem body carries `code`, `correlationId` and `violations: [{rule, field?, message}]`; client mistakes are 400/409/413/422/429, never 500; every 500 logs one line with its stack. Correlation id accepted only UUID-shaped and tied to the trace. Emails removed from audit rows, problem details and the accounts cursor. `Metrics.gauge` fixed, command timers with p95, and the detection signals for PLT-01, PLT-07, SEC-03/06/09/10/12/13/16, ORD-05/PLN-06/EXE-14 (names in EDGE-CASES). Tracing export off unless configured; JDBC spans. Shared keyset `Cursor`/`Page` on accounts, policies, assignments and reference lists. Body limit in backend, Next proxy and nginx; CSP in Next and nginx. `AppProperties` types sessions, throttle, body size, tracing and the problem type base. Compose `init` runs `migrate`, `import-reference`, `account-create` (now idempotent); health checks use real endpoints; nginx limits `POST /api/session`. CI in `.github/workflows/ci.yml`. Integration tests fall back to Testcontainers. Prototype docs deleted; README, development, deployment, verification rewritten.
Why: a 500 left no trace, personal data reached logs and audit, `docker compose up` could not start, and nine modules were about to build on all of it.
Verified: `mvn test` green for unit and architecture tests (integration tests skipped, no database configured); backend booted and answered with the new problem bodies and metrics; `npm run typecheck` and `npm test` pass; both compose files validate.
Open: integration tests against PostgreSQL, the backend image build, `nginx -t`, a fresh `docker compose up` and CI are unverified (Docker Hub unreachable during this session). The login lockout rolls back with its own transaction and never triggers, and the pool still connects as the owner role: both are issue #5. Lockout now answers 429 with `Retry-After`; `GET /api/accounts` and the other lists now return `{items, nextCursor}`.

---

## 2026-10-01 - feat: add an opt-in log store (Loki, Alloy, Grafana)

`feat/module-contracts` · @jv-ransika

Compose profile `observability` in `compose.yaml` and `compose.prod.yaml` runs Loki (14-day retention), Alloy and Grafana on 127.0.0.1. Alloy collects containers labelled `com.waypoint.logs=true` and, for a natively run backend, `var/log/*.log` written when `LOG_FILE` is set. Backend services now set `LOG_FORMAT=ecs`. Config in `observability/`, usage in development.md "Searching logs" and deployment.md "Logs". No module code changed.
Why: logs only reached a console, so nothing could be searched and a correlation id could not be followed across requests or services.
Verified: both compose files validate with and without the profile. Backend jar run with `LOG_FORMAT=ecs` and `LOG_FILE`: one request's line reached Loki from the container and from the file, found by `correlationId`, with `level` as the only new label. Logs survive a Loki restart; backend liveness stays 200 with Loki stopped.
Open: the backend Docker image does not build (`mvn dependency:go-offline` fails in `backend/Dockerfile`), and `init` still runs the missing `seed`, so full-stack `docker compose up` is unproven (issue #4). Metrics and traces have no store yet.

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
