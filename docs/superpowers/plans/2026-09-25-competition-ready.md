# Competition readiness implementation plan

Historical plan from September 25, 2026. It records the earlier implementation approach, not instructions to rerun the work. The current runtime is Next.js plus Spring Boot and PostgreSQL; use the [README](../../../README.md), [deployment guide](../../deployment.md) and [verification record](../../verification.md) for current commands and status.

Goal: Deliver an honest, reproducible Designathon and Hackathon entry connecting ordering, allocation, loading, delivery, receipt and recovery.

Approved brief: The user approved the preceding six-point review and requested implementation with realistic operational behavior. Execute directly in this session; no additional approval gate. Retain Next.js, supplied reference data and four roles. Persistence moved from SQLite to PostgreSQL during implementation; no Datathon models, invented metrics, messaging, or public deployment as part of this local implementation.

## Global constraints
- Preserve existing records and auditable history.
- Revalidate all assignments and published plans on the server.
- Never discard pending offline work during authentication or conflict recovery.
- Use supplied synthetic competition data consistently; label added fixtures.
- Use ASCII hyphens, no em or en dashes in authored files.

## Tasks
1. Reproducibility: package the small required reference tables and representative delivery days in data/ with provenance; test startup without the ignored competition folder; update Docker and seed documentation.
2. Planning and deferrals: tests first for workshop exclusion, repeat deferral carryover, next eligible queue, capacity/time/fuel checks, idempotent publishing, and immutable historical plans. Add server-validated assignment previews to compare feasible options and rejection causes. Use realistic demand pressure.
3. Truthful workspace: replace static dispatcher scenarios, forecasts, alerts and probability claims with real plan state. Render draft stop times from routes. Put scenarios in a collapsed demo chooser. Show an actionable constraint/deferral decision board and real weekly reservations.
4. Recovery and field workflow: reproduce expired-session recovery in browser; make reauthentication shared, preserve queue; fix driver completed/disputed stop behavior; verify offline proof across reload and conflict handling.
5. Verification: provide node integration tests and Playwright tests using disposable PostgreSQL schemas; run typecheck, production build, browser workflow, phone checks, fresh distribution and Docker when available.
6. Submission evidence: architecture/data model, design rationale and four personas, degradation story, implementation mapping, truthful AI disclosure, judge walkthrough, demo script, limitations and runnable submission package.

## Review focus
- Publishing later days before a deferred order is carried forward must not hide the order.
- A repeated request must not increment skip count twice or duplicate proof.
- Two drafts must not silently overwrite an approved decision.
- Session expiry while pending work exists must retain the account and command IDs.
- Empty days, no exceptions and all-completed runs must show honest empty/completed states.

## Progress and decisions
- Baseline: clean checkout; typecheck and production build passed during assessment; test scripts and docs missing.
- Work initially used a dedicated branch. Current integration is on `main`; `dev` replaces the former `master` branch.
- Completed all six implementation tasks. The initial completion checkpoint recorded 20 backend tests, 8 production browser tests, typecheck, build and an extracted-package build. A later publication-review checkpoint recorded 9 browser definitions. PostgreSQL migration and proof-image work later raised this to 31 backend tests and 11 browser executions. These are historical checkpoints; see docs/verification.md for their scope, later changes and remaining external submission work.
