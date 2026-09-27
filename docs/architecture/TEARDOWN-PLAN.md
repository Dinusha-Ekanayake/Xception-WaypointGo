# Teardown and skeleton plan

What gets deleted when the prototype is replaced, what is kept and why, and what the first commits of the rewrite contain.

**Nothing in this plan has been executed.** Read it, disagree with it, then it runs.

Context: the decision is to rewrite the backend and rebuild the frontend against [FOUNDATION-PLAN.md](FOUNDATION-PLAN.md), [RULES-AND-POLICIES.md](RULES-AND-POLICIES.md) and [ASSUMPTIONS.md](ASSUMPTIONS.md). The prototype's schema cannot carry the target model, so extending it would mean fighting it on every feature.

---

## 0. Before anything is deleted

| # | Step | Why |
| --- | --- | --- |
| 1 | `git tag prototype-v0` and push the tag | The reference oracle. When the new allocator and the old one disagree, one is wrong and you need to diff them. Costs nothing, gets used at least once |
| 2 | Tell Mihiran before pushing the teardown commit | He merged PR #3 yesterday. A pull into a repo with no `backend/src` looks like a broken clone, not a plan |
| 3 | ~~Confirm A-02 and A-03~~ **Not a precondition.** They block the constraint registry in WS4 Planning, not Reference or IAM. Answer them before WS4, not before the teardown | Correcting an over-cautious earlier draft of this plan |

The tag is the whole safety net. Everything below is recoverable from it.

---

## 1. What is deleted

### 1.1 Backend implementation, 3,376 lines of Java

| Path | Reason |
| --- | --- |
| `backend/src/main/java/.../service/DispatchService.java` | 1,742 lines holding eight responsibilities. The thing the rewrite exists to remove |
| `backend/src/main/java/.../api/ApiController.java` | One controller for every endpoint. Replaced by a command endpoint plus per-module query controllers |
| `backend/src/main/java/.../platform/db/Database.java` | Correct in design, but the new one sets module role and actor per transaction. Rewritten, not moved |
| `backend/src/main/java/.../platform/db/Migrator.java` | Replaced: the new one enforces checksums and forward-only ordering across five schemas |
| `backend/src/main/java/.../platform/config/DataConfig.java` | Replaced: the new pool connects as `waypoint_app` with `NOINHERIT` |
| `backend/src/main/java/.../referencedata/**` | Replaced by the versioned reference module (D1) |
| `backend/src/main/java/.../identity/application/AccountAdminUseCase.java` | Replaced by the IAM module with a real PDP |
| `backend/src/main/java/.../shared/**` | Small, but the new kernel has different value objects |
| `backend/src/test/java/.../service/*Test.java` | Test the classes being deleted |

### 1.2 Frontend implementation

| Path | Reason |
| --- | --- |
| `frontend/src/roles/**` | All four role screen sets. Figma is redesigning them |
| `frontend/src/app-shell/**` | Rebuilt around the new auth and sync model |
| `frontend/src/shared/ui/**` | Replaced by the Figma design system |
| `frontend/src/shared/domain/types.ts` | Regenerated from the new API contract |
| `frontend/src/shared/offline/**` | Rewritten as the tiered write queue: full offline for drivers, resilient-only for loader and store |
| `frontend/app/page.tsx` | Rendered the old shell |
| `frontend/lib/**` (5 files) | The duplicate Node implementation of the domain rules. This is the second copy of the constraint logic that the architecture has flagged since day one |

### 1.3 Schema and tests tied to the old model

| Path | Reason |
| --- | --- |
| `migrations/001_initial.sql`, `002_account_management.sql` | The 9-table JSONB schema. Superseded by `docs/architecture/schema/migrations/` once D1 to D9 are applied |
| `frontend/tests/constraints.test.ts`, `service.test.ts`, `route-order.test.ts`, `postgres.test.ts` | Test the deleted Node service. **Their assertions are harvested first**, see 2.2 |
| `frontend/tests/spring/workflow.test.ts` | Tests the deleted REST surface |
| `frontend/tests/helpers/**` | Bootstraps the old stack |
| `frontend/scripts/database.ts` | Migrate and seed for the old schema |

---

## 2. What is kept

### 2.1 Kept untouched

| Path | Why |
| --- | --- |
| `docs/**` | The asset. 104 rules, 17 assumptions, 117 edge cases, the target schema, the foundation plan. The rewrite is built from these |
| `data/**` | The supplied reference CSVs plus provenance. Input to the reference importer |
| `AGENTS.md`, `SYSTEM-ARCHITECTURE.md`, `README.md` | Contracts for humans and agents |
| `.agent/rules/`, `.gitignore`, `.vscode/` | Conventions |
| `compose.yaml`, `compose.prod.yaml`, `.env.example`, `.dockerignore` | The Docker path still works and is still the deployment story |
| `nginx/**` | TLS termination and config templates, independent of application code |
| `scripts/account.sh`, `backup.sh`, `restore-check.sh` | Operational scripts. `account.sh` needs its CLI updated once IAM exists |
| `frontend/public/**` | Manifest and assets, reusable |
| `Challenge Booklet.pdf`, `our team draft things simple.docx` | Source documents the rules cite |
| `LICENSE`, `start.sh` | Unchanged |

### 2.2 Harvested before deletion, not kept as code

Three things in the prototype are genuinely valuable. Extract them into the new work, then delete the originals.

| Source | What to take | Where it goes |
| --- | --- | --- |
| `planning/domain/Planning.java` | The constraint logic and its `REASONS` map. It is correct and validated against real fixtures | Becomes the constraint registry, one named constraint per rule, each carrying its `R-PLN-*` identifier |
| `frontend/tests/constraints.test.ts` and the Java tests | The **assertions**, not the code. They encode behaviour that must still hold | Rewritten as domain unit tests against the new registry |
| `tests/e2e/workflow.spec.ts` | The four-role walkthrough, offline reload, conflict recovery, proof download | Rewritten as browser tests once the Figma screens exist. Keep the scenarios, discard the selectors |

`ModuleBoundaryTest.java` and `frontend/tests/boundaries.test.ts` are **kept and extended**, not deleted. They are the only thing preventing the new structure from decaying the way the old one did.

---

## 3. Build config: kept, edited

Not deleted, but not untouched either.

| File | Change |
| --- | --- |
| `backend/pom.xml` | Keep. Add ArchUnit, Argon2, Testcontainers. Remove anything only the old code used |
| `frontend/package.json` | Keep Next, React, Zod, Playwright. Remove `pg` and `csv-parse`, which only the deleted Node service needed. Rewrite the `test` and `db:*` scripts |
| `frontend/tsconfig.json` | Keep strict settings and path aliases. Aliases change with the new folder shape |
| `backend/Dockerfile`, `frontend/Dockerfile` | Keep. Verify after the skeleton builds |
| `frontend/scripts/build-sw.mjs` | Keep the generator, rewrite the cached-asset list for the new shell |
| `frontend/playwright.config.ts` | Keep |

---

## 4. The skeleton

Three commits after the teardown. Each compiles and passes its own tests, so the repository is never in a state where nobody can build.

### Commit 1: backend skeleton

```
backend/src/main/java/com/waypoint/dispatch/
├── WaypointApplication.java          boot plus the migrate and seed commands
├── shared/{domain,error,util}/       kernel: value objects, DomainException, Crypto
├── platform/
│   ├── config/                       pool as waypoint_app, NOINHERIT
│   ├── db/                           Database seam, SET LOCAL ROLE + actor, Migrator
│   ├── web/                          RFC 9457 problem+json handler, correlation id
│   └── observability/                metrics, structured logging
├── referencedata/{contract,domain,application,infrastructure,web}/   empty, ready
└── identity/{contract,domain,application,infrastructure,web}/        empty, ready
```

Plus `src/test/java/.../architecture/ModuleBoundaryTest.java`, extended with the rules from [code-structure.md](../code-structure.md): no `*Service`, framework-free `domain`, no cross-module imports except `contract`, only `platform` touches `JdbcTemplate`.

**Done when:** `mvn package` is green, the boundary test passes, and the app starts and answers `/health/live` with no database.

### Commit 2: schema baseline

`migrations/` repopulated from `docs/architecture/schema/migrations/`, with D1 to D9 applied as the correction files the schema README already anticipates: natural keys for depots and districts, `district_travel` keyed by district alone, value `CHECK`s dropped from lookup tables, `auditor` added, `iam.sessions` and `iam.login_attempts` added, `ref.traffic_speed` and `ref.road_conditions` added.

**Done when:** a migrate against an empty database succeeds, the role separation test passes (`waypoint_app` without a module role is a permission error), and the published-plan immutability trigger rejects an in-place edit.

### Commit 3: frontend skeleton

```
frontend/
├── app/                    routing only: layout, page, /api proxy
└── src/
    ├── app-shell/          auth gate, role router, no screens
    ├── shared/
    │   ├── api/            client, command envelope, problem+json parsing
    │   ├── offline/        tiered write queue: full for driver, resilient elsewhere
    │   └── ui/             empty, waiting for the Figma design system
    └── roles/{dispatcher,loader,driver,store}/   empty, one placeholder each
```

**Done when:** `npm run build` succeeds, `tsc --noEmit` is clean, the boundary test passes, and the service worker registers.

No screens in commit 3. They are what Figma changes, and building them now guarantees rework.

---

## 5. Order and branches

On `dev`, per the branching decision. Short-lived branches, merged the same day where possible.

```
prototype-v0 tag ──► chore/teardown ──► chore/skeleton-backend
                                          └─► chore/schema-baseline
                                               └─► chore/skeleton-frontend
                                                    └─► ref/* then iam/*  (FOUNDATION-PLAN 3.1)
```

`main` is untouched until Reference and IAM both pass their definition of done, so it is never half-rewritten.

---

## 6. Configuration after the teardown

Yes, build the env files as part of the skeleton, not later. Four files, each read by exactly one thing.

| File | Committed | Read by | Holds |
| --- | --- | --- | --- |
| `.env` | **no**, gitignored | Docker Compose only | real local values |
| `.env.example` | yes | nobody at runtime | placeholders and comments. The documentation of what exists |
| `frontend/.env.local` | **no**, gitignored | Next.js dev and build | `BACKEND_URL` and nothing else |
| `frontend/.env.local.example` | yes | nobody | one placeholder line |

Spring reads **none** of these. The backend takes exported shell variables locally, and the `environment:` block in Compose when containerised. That asymmetry is already documented in [../development-docs/development.md](../development-docs/development.md) and is the usual cause of "cannot connect to the database".

New keys the rewrite introduces:

```
WAREHOUSE_BASE_URL   https://triathon-warehouse-simple.vercel.app/api/v1
WAREHOUSE_API_KEY    wh_...            backend only, never NEXT_PUBLIC_
WAREHOUSE_TIMEOUT_MS 3000
DATABASE_URL         postgresql://waypoint_app@...
SEED_PASSWORD        set before creating accounts on any exposed instance
COOKIE_SECURE        1 behind HTTPS
```

Three rules about the warehouse key specifically:

1. **It is a backend secret.** The browser never calls the warehouse. Every warehouse call goes through the backend adapter, so the key never enters a client bundle. A `NEXT_PUBLIC_` prefix on it would publish it to every visitor.
2. **It never enters the repository**, including `.env.example`, which carries `wh_...` as a placeholder.
3. **Rotate any key that has been pasted into a chat, a ticket or a screenshot.** Treat exposure as certain rather than likely.

The `StockPort` adapter is the only code that reads `WAREHOUSE_API_KEY`, which keeps the blast radius of a rotation to one class.

## 7. What could go wrong

| Risk | Mitigation |
| --- | --- |
| Something in the deleted code turns out to be load-bearing | `prototype-v0` tag. Recover the file, do not un-delete the commit |
| The rewrite stalls half-finished | The foundation is two modules, not twelve. Reference and IAM are both small and independently shippable |
| Harvested constraint logic is transcribed with a subtle change | Port the assertions first, watch them fail, then port the logic until they pass |
| A teammate pulls mid-teardown | Step 0.2. Announce before pushing |
| Docker path silently rots | `docker compose up --build` from a clean clone after commit 3, before any module work starts |
