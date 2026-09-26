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
