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
