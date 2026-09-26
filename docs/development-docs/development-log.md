# Development log

A running record of what changed in this repository and why. Several people and several AI agents work on this repository in parallel, often without seeing each other's sessions. Git history says what changed; this log says **why, what state it left behind, and what is still open**, which is the context an agent or a teammate needs before touching the same area.

## How to use this file

**Read the top few entries before starting work.** They tell you what is half-finished and what someone else is holding.

**Append an entry when you finish a unit of work** that changes code, structure, configuration or a decision. Skip it for typo fixes and pure formatting.

Newest entry first. Keep each entry short: five lines is normal, fifteen is too many. Put detail in the relevant document under `docs/` and link to it.

```markdown
## YYYY-MM-DD - short title

**Who:** name, or the agent and model
**Branch:** branch name, or commit
**What:** one or two sentences on what changed.
**Why:** the reason, not a restatement of the what.
**Verified:** the commands run and their result, or "not verified" and why.
**Open:** what is deliberately left undone, or "nothing".
```

Entries before 2026-09-26 were not logged; use `git log` for that period.

---

## 2026-09-26 - backend organized by module, frontend by role

**Who:** Claude Opus 5 (agent), directed by Oxshadha
**Branch:** `refactor/module-structure`, commit `43e8111`
**What:** Moved 8 Java classes into `shared/`, `platform/`, `referencedata/`, `identity/` and `planning/` packages, removing the flat `util/`, `db/`, `config/` and `domain/` packages. Extracted `platform/db/Database` as the single PostgreSQL seam carrying the serializable transaction and bounded retry. Moved the frontend into `src/app-shell/`, `src/roles/{dispatcher,loader,driver,store}/` and `src/shared/{ui,domain,offline}/` behind `@app-shell/*`, `@roles/*` and `@shared/*` path aliases; `frontend/components/` is gone and `frontend/lib/` now holds only the legacy Node service. Added `ModuleBoundaryTest` (7 ArchUnit rules) and `frontend/tests/boundaries.test.ts` (5 rules).
**Why:** `DispatchService` had absorbed eight responsibilities at 1780 lines because the packages were organized by layer, so nothing prevented any class calling any other. Engineering quality and architecture is 25 percent of the Hackathon score.
**Verified:** `mvn package` 9 tests pass, `tsc --noEmit` clean, `next build` clean, `npm test` 36 Node plus 4 Spring HTTP pass, `npm run test:e2e` 11 of 12 pass. Both boundary guards were confirmed to fail on a planted violation. The one browser failure (`database connection outage`) reproduces identically on untouched `HEAD`, so it is pre-existing and environmental.
**Open:** `DispatchService` is still 1742 lines and still holds identity, seeding, scenarios, state assembly, planning orchestration, command dispatch and events. `ApiController` is still one controller. Extraction order and rationale are in [code-structure.md](../code-structure.md) stages 3 and 4.

## 2026-09-26 - architecture plan and code structure spec written

**Who:** Claude Opus 5 (agent), directed by Oxshadha
**Branch:** `refactor/module-structure`
**What:** Added [enterprise-architecture-plan.md](enterprise-architecture-plan.md) (problem framing, target architecture, honest gap table against the current code, scale analysis, phased plan against the three deadlines) and [code-structure.md](../code-structure.md) (the folder layout, dependency rules, naming conventions and migration stages).
**Why:** The team needed one agreed target before refactoring, and the Hackathon requires an architecture document in `docs/`.
**Verified:** Not applicable, documentation only.
**Open:** Two items in the plan need a decision from the team: whether to delete or isolate the duplicate Node implementation in `frontend/lib/`, and the missing Datathon data files listed in Phase C.
