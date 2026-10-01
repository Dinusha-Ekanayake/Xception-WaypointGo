# Issue #10: Loading backend and shared-device loader experience

## Current state

The `feat/loading` branch now contains the scoped Loading manifest/command flow, Identity-owned shared-device PIN flow, Loader role frontend and guarded fixture. The backend and live phone browser path have been verified against disposable local databases; see [WALKTHROUGH.md](WALKTHROUGH.md). Full tablet/Figma visual comparison and branch reconciliation remain before delivery.

## Ownership and boundaries

- Loading owns immutable plan snapshots, item check attempts, trip holds, shortfalls and release.
- Identity owns PIN hashes, lockout, device sessions, operator history and attribution validation.
- Platform owns command dispatch, transaction roles, audit, outbox and consumer inbox.
- Loading reads Ordering and Reference Data through contracts only.
- Frontend contracts mirror backend views; all queued writes use the shared sync engine.
- Domain rules remain framework-free. Schema amendments use new forward-only migrations.

## Confirmed decisions

Use item and units, with one check per product line. Loader issue choices are exactly Damaged, Doesn't fit, and Missing; Short and Wrong temp are not offered. Flag and continue: exceptions are recorded and release waits for no unchecked lines plus the release checklist. The release checklist confirms doors sealed, orders secured, and driver present. Only the holder writes; hand back keeps previous checks. Each accepted command and revision advances rowVersion once. A supervisor signs in the shared device and loaders select their identities using PINs. Lock clears the active operator. Offline checks and flags retain the operator who recorded them, verified against session history. PIN switching remains online only. Docks default to four per depot with configurable overrides and departure-ordered round robin. Handover and interchange remain explicitly deferred.

## Ordered work and acceptance checks

| Step | Work | Evidence required |
| --- | --- | --- |
| A | Complete loading scope enforcement, revision handling, consumers, reads, docks and guarded fixture | Domain, command, RLS, stale version, replay, concurrency and revision tests |
| B | Complete PIN endpoints, lockout, PIN administration, lock enforcement and offline attribution | PIN domain and HTTP integration tests, audit and wrong-actor refusal |
| C | Mirror contracts and implement loader screens against Figma using shared UI and tokens | Typecheck, tests, build, 393x852 visual and offline browser verification |
| D | Update module/rule/edge-case/assumption documents, departures and walkthrough | Documentation agrees with actual behavior and verified test outcomes |

Tests precede production fixes. Use a dedicated test database distinct from the application database. Record actual failed and passing checks, including any skipped checks; do not label unexecuted database or browser paths verified. Follow existing scoped commit subjects and explanatory bodies, grouping coherent work and excluding unrelated changes. Do not push or open a PR without a separate delivery step.

## UI and delivery

Use current context from the supplied Final Figma file for the loader phone flow, preserving tablet layouts. Use existing shared components and theme tokens. Keep components below roughly 300 lines. Complete the walkthrough and development log alongside implementation. PR target is `dev`; reconcile the branch and run the plan's verification before delivery. Never commit competition documents or credentials.
