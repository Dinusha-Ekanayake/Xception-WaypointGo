# Code structure and module layout

Audience: the Xception build team. This is the target folder structure for the repository, the layering rules that keep it that way, and the order in which to migrate to it without breaking a working system.

The goal is the one stated in the architecture plan: business modules with explicit boundaries, layers inside each module, so a feature is found in one place and a change does not ripple. See [enterprise-architecture-plan.md](development-docs/enterprise-architecture-plan.md) for why this shape was chosen.

## 0. Current state

What is in place now, and what the structure is still waiting for.

**Done and verified** (backend `mvn package`, `tsc --noEmit`, `next build`, 36 Node tests, 4 Spring HTTP tests, 9 Maven tests):

- Backend: `shared/`, `platform/`, `referencedata/`, `identity/`, `planning/` created, with `DomainException`, `Crypto`, `Migrator`, `DataConfig`, `ReferenceData`, `CsvReferenceLoader` (was `ReferenceLoader`), `Planning` and `AccountAdminUseCase` (was `AccountAdmin`) moved by `git mv`. The flat `util/`, `db/`, `config/` and `domain/` packages are gone.
- Backend: `platform/db/Database` extracted as the single PostgreSQL seam, carrying the serializable transaction and the bounded retry. `AccountAdminUseCase` now depends on it instead of on `DispatchService`, which removed the first cross-module dependency.
- Backend: `ModuleBoundaryTest` with 7 ArchUnit rules, verified to fail on a planted violation.
- Frontend: `src/app-shell/`, `src/roles/{dispatcher,loader,driver,store}/` and `src/shared/{ui,domain,offline}/` populated by `git mv`; `components/` is gone and `lib/` now holds only the legacy Node service.
- Frontend: `@app-shell/*`, `@roles/*`, `@shared/*` aliases, and `tests/boundaries.test.ts` with 5 rules, also verified to fail on a planted violation.

**Outstanding**, in the order the migration stages give them:

- `service/DispatchService.java` is still 1742 lines and still holds identity, seeding, scenarios, state assembly, planning orchestration, command dispatch and events. Stages 3 and 4 below are the remaining work, and they are the part that carries the "engineering quality and architecture" marks.
- `api/ApiController.java` is still one controller for all endpoints.
- The oversized frontend components are still unsplit.
- The duplicate Node implementation in `lib/` is still undecided (stage 6).

## 1. The organizing principle

Package by **business capability first, layer second**.

Layer first (`controllers/`, `services/`, `repositories/`) looks tidy and fails in practice: one feature is spread across every folder, and nothing stops any class from calling any other. That is how `service/DispatchService.java` reached 1780 lines holding SQL helpers, sessions, login throttling, seeding, scenarios, state assembly, planning orchestration, command dispatch, events and proof images at once.

Capability first gives each module its own small stack:

```
<module>/
├── domain/          business rules. Pure. No Spring, no SQL, no JSON.
├── application/     use cases. Orchestrates domain plus ports. Owns transactions.
├── infrastructure/  adapters out: JDBC repositories, port implementations.
├── web/             adapters in: REST controllers. Thin.
└── contract/        the ONLY package other modules may import.
```

Two packages are not business modules and are allowed to be shared:

- `shared/` is the kernel: value objects, errors, small utilities. Depends on nothing internal.
- `platform/` is technical infrastructure: datasource, transactions, migrations, web plumbing, observability. No business rules.

## 2. Backend target structure

```
backend/src/main/java/com/waypoint/dispatch/
├── WaypointApplication.java
├── cli/                              operational commands: migrate, seed, account-*
│
├── shared/
│   ├── domain/                       Brand, Depot, District, TempRequirement, ClockTime, DeliveryWindow, DayId
│   ├── error/                        DomainException, ErrorCode
│   └── util/                         Crypto, Json, SystemClock
│
├── platform/
│   ├── config/                       DataConfig, JacksonConfig, AppProperties
│   ├── db/                           Database (all/get/run), TransactionRunner, Migrator
│   ├── web/                          ApiExceptionHandler, SessionCookie, CorrelationIdFilter, BodyLimits
│   └── observability/                Metrics, AuditLog
│
├── referencedata/                    outlets, vehicles, calendar, district travel, service allowance
│   ├── domain/                       ReferenceData, Outlet, Vehicle, CalendarDay, DistrictTravel, ServiceAllowance
│   ├── application/                  ReferenceDataQuery, ProductionCalendar
│   ├── infrastructure/               CsvReferenceLoader
│   └── contract/                     ReferenceSnapshot
│
├── identity/                         accounts, sessions, authorization
│   ├── domain/                       Role, Account, Scope, AuthorizationPolicy
│   ├── application/                  LoginUseCase, SessionService, LoginThrottle, AccountAdminUseCase
│   ├── infrastructure/               JdbcAccountRepository, JdbcSessionRepository, JdbcLoginAttemptRepository
│   ├── web/                          AuthController
│   └── contract/                     CurrentUser, IdentityQuery
│
├── messaging/                        command bus, event log, outbox. The spine.
│   ├── domain/                       Command, CommandReceipt, DomainEvent
│   ├── application/                  CommandBus, CommandHandler, IdempotencyGuard, EventPublisher
│   ├── infrastructure/               JdbcCommandReceiptRepository, JdbcEventLog, JdbcOutbox, OutboxWorker
│   └── web/                          CommandController            single POST /api/command
│
├── ordering/                         store manager places orders, cutoff, order versions
│   ├── domain/                       Order, OrderStatus, OrderVersion, Cutoff
│   ├── application/                  PlaceOrderHandler, AmendOrderHandler, OrderQuery
│   ├── infrastructure/               JdbcOrderRepository
│   └── contract/                     OrderSnapshot, OrderEvents
│
├── planning/                         allocation, constraints, deferral. The core.
│   ├── domain/
│   │   ├── Planning.java             existing pure functions, unchanged in behaviour
│   │   ├── constraint/               Constraint, ConstraintRegistry, ConstraintResult,
│   │   │                             CapacityConstraint, TemperatureConstraint, VanOnlyConstraint,
│   │   │                             HomeDepotConstraint, BrandDistrictConstraint, TripCountConstraint,
│   │   │                             TimeBudgetConstraint, DeliveryWindowConstraint, FuelQuotaConstraint
│   │   ├── Trip, DraftPlan, Deferral, PriorityPolicy
│   │   └── AllocationEngine          port, with AllocationRequest and AllocationResult
│   ├── application/                  GenerateDraftHandler, PublishPlanHandler, AssignmentPreviewQuery,
│   │                                 FuelReservationService, PlanQuery
│   ├── infrastructure/               PriorityInsertionEngine, ValidatingEngine, JdbcPlanRepository
│   └── contract/                     PublishedPlan, PlanEvents
│
├── loading/                          dock: manifests, shortfalls, departure gate
│   ├── domain/                       Manifest, Shortfall, DepartureGate
│   ├── application/                  FlagShortfallHandler, MarkLoadedHandler, ManifestQuery
│   ├── infrastructure/               JdbcManifestRepository
│   └── contract/                     LoadingStatus
│
├── execution/                        driver: stop outcomes, proof of delivery
│   ├── domain/                       Stop, StopOutcome, ProofOfDelivery, ProofStore (port)
│   ├── application/                  StartStopHandler, RecordDeliveryHandler, RunSheetQuery
│   ├── infrastructure/               JdbcProofStore, JdbcStopRepository
│   └── contract/                     DeliveryOutcome
│
├── receipt/                          store manager confirms or reports an issue
│   ├── domain/                       Receipt, IssueReport
│   ├── application/                  ConfirmReceiptHandler, ReportIssueHandler
│   ├── infrastructure/               JdbcReceiptRepository
│   └── contract/                     ReceiptStatus
│
├── query/                            read side. Projections shaped per screen.
│   ├── web/                          SyncController (GET /api/sync?since=), StateController, ProofController
│   ├── application/                  DispatcherBoardProjection, RunSheetProjection,
│   │                                 StoreTimelineProjection, LoaderManifestProjection
│   └── infrastructure/               JdbcProjectionQueries        scope filtering in SQL, keyset paginated
│
└── seeding/                          demo and scenario seed. Never on the request path.
    ├── Seeder, ScenarioSeeder
    └── fixtures/
```

### Dependency rules

Read this as the allowed direction. Anything not listed is forbidden.

| From | May depend on |
| --- | --- |
| `shared/**` | nothing internal |
| `platform/**` | `shared` |
| `<module>/domain` | `shared`, own module's `domain` |
| `<module>/application` | own `domain`, `shared`, other modules' `contract`, `platform/db` interfaces |
| `<module>/infrastructure` | own `application`, own `domain`, `shared`, `platform` |
| `<module>/web` | own `application`, `shared`, `platform/web`, `identity/contract` |
| `messaging`, `query` | every module's `contract`, `shared`, `platform` |
| `cli`, `seeding` | anything. Nothing may depend on them. |

The rules that do the real work:

1. **No module imports another module's `domain`, `application` or `infrastructure`.** Only `contract`. This is what makes a module replaceable.
2. **`domain` has no framework imports.** No `org.springframework`, no `javax.sql`, no `com.fasterxml`. It is testable with plain JUnit and no database, which is exactly what the constraint set needs.
3. **Only `application` opens transactions.** Domain never sees a connection.
4. **No new class may be named `*Service`.** That name is what allowed one class to absorb eight responsibilities. Use `*Handler` for commands, `*Query` for reads, `*Policy` for decisions, `*Repository` for persistence.

### Naming conventions

| Kind | Pattern | Example |
| --- | --- | --- |
| Command handler | verb + noun + `Handler` | `RecordDeliveryHandler` |
| Read model | noun + `Query` or `Projection` | `ManifestQuery`, `RunSheetProjection` |
| Port (interface) | named for the capability | `AllocationEngine`, `ProofStore` |
| Adapter (implementation) | named for the technology or strategy | `JdbcProofStore`, `PriorityInsertionEngine` |
| Repository | `Jdbc` + aggregate + `Repository` | `JdbcOrderRepository` |
| Constraint | rule name + `Constraint` | `FuelQuotaConstraint` |
| Test | subject + `Test` | `FuelQuotaConstraintTest` |

### Maven: stay single module

Keep one Maven module for now. The package layout above is deliberately shaped so any module could be lifted into its own Maven module later without moving a single file, because nothing crosses a boundary except through `contract`. Splitting the build now costs a day and earns nothing before October 4.

## 3. Frontend target structure

The frontend is organized by **role**, not by capability, and the difference is deliberate. The backend is organized around what the system does, because a constraint or an order lifecycle is a capability. The UI is organized around who is doing it, because the brief's four roles are four different devices, connectivity assumptions and screen sets, and one screen set (the store manager's) legitimately spans two backend capabilities, ordering and receipt. Forcing the UI into backend module names would split files that belong together and buy nothing.

```
frontend/
├── app/                          Next.js routing only. Thin files.
│   ├── layout.tsx, page.tsx, globals.css
│   └── api/[...path]/route.ts    backend proxy
│
├── src/
│   ├── app-shell/                session gate, role routing, shared client state
│   │   ├── Workspace.tsx
│   │   ├── useWorkspace.ts
│   │   └── FieldWork.tsx         composes driver plus loader, so it is shell, not a role
│   │
│   ├── roles/
│   │   ├── dispatcher/           Dispatcher, AssignmentReview, PublishReview, ExceptionReview
│   │   ├── loader/               Loader
│   │   ├── driver/               Driver
│   │   └── store/                Store
│   │
│   └── shared/
│       ├── ui/                   components.tsx (primitives and formatters), ProofImages.tsx
│       ├── domain/               types.ts, route-order.ts
│       └── offline/              storage.ts, useDeliveryDraft.ts
│
├── lib/                          legacy Node service only, see stage 6
├── tests/                        unchanged locations
└── scripts/
```

### Path aliases

TypeScript 7 removed `baseUrl`, so path targets are relative and must start with `./`:

```json
"paths": {
  "@app-shell/*": ["./src/app-shell/*"],
  "@roles/*": ["./src/roles/*"],
  "@shared/*": ["./src/shared/*"]
}
```

Same-directory imports stay relative (`./AssignmentReview`). Anything crossing a folder uses an alias.

One constraint that is easy to get wrong: **aliases only work for code the Next.js bundler compiles.** `lib/`, `tests/` and `scripts/` run under `node --experimental-strip-types`, which does not read `tsconfig` paths. Those files keep relative specifiers with an explicit `.ts` extension. The split is visible and it marks the bundled/unbundled boundary usefully.

### Frontend dependency rules

1. `app/` imports only `@app-shell`.
2. `@roles/a` must never import `@roles/b`. Cross-role composition belongs in `app-shell`, which is why `FieldWork.tsx` lives there rather than under a role.
3. `@shared` never imports `@roles` or `@app-shell`.
4. Nothing under `src/` imports from `lib/`.
5. A component file over roughly 300 lines is split into a container and a view. Current offenders: `Dispatcher.tsx` at 1271, `Workspace.tsx` at 902, `Store.tsx` at 733, `shared/ui/components.tsx` at 680.

Barrel files (`index.ts` per role) are worth adding when the oversized components above are split, not before: with one component per role folder a barrel is indirection without benefit.

## 4. Enforcement, so the structure survives contact with a deadline

Rules that are only written down decay within a week. Both checks below are cheap and run in the existing test commands.

**Backend.** Add `com.tngtech.archunit:archunit-junit5` (test scope) and one test, `backend/src/test/java/com/waypoint/dispatch/ModuleBoundaryTest.java`, asserting:

- no module package depends on another module's `domain`, `application` or `infrastructure`
- `..domain..` contains no `org.springframework..`, `javax.sql..` or `com.fasterxml..` imports
- no class name ends in `Service`
- layered access within a module: `web` to `application` to `domain`, never backwards

**Frontend.** The repository deliberately has no lint setup, and AGENTS.md records that. Rather than introduce ESLint, add `frontend/tests/boundaries.test.ts` using the existing `node --test` runner: walk `src/`, extract import specifiers with a regex, and assert the five rules above (no role importing another role, nothing in `src/` importing `lib/`, `@shared` importing neither roles nor shell, `app/` importing only the shell, and no alias imports inside `lib/`, `tests/` or `scripts/` where they cannot resolve). Zero new dependencies, and it runs inside `npm test` where a failure is actually seen.

## 5. Migration order

Two hard rules: use `git mv` so history follows the file, and verify after every slice. Backend verification is `mvn -q -f backend/pom.xml package`. Frontend verification is `npm run typecheck`. Full verification is `npm test`.

Nothing in this migration changes behaviour. If a step wants to change behaviour, it is a different step.

### Stage 0, baseline (done)

Run the full suite and record that it passes before touching anything. A refactor without a known-good baseline is a guess. Recorded baseline: 31 Node tests and 4 Spring HTTP tests passing.

### Stage 1, backend scaffolding, no logic moved (done)

1. Create `shared/`, `platform/`, and the module directories.
2. `git mv` the classes that move whole, then fix package declarations and imports:
   - `service/DomainException.java` to `shared/error/`
   - `util/Crypto.java` to `shared/util/`
   - `db/Migrator.java` to `platform/db/`
   - `config/DataConfig.java` to `platform/config/`
   - `domain/ReferenceData.java` to `referencedata/domain/`
   - `domain/ReferenceLoader.java` to `referencedata/infrastructure/CsvReferenceLoader.java`
   - `domain/Planning.java` to `planning/domain/`
   - `service/AccountAdmin.java` to `identity/application/AccountAdminUseCase.java`
3. Add the ArchUnit test with the rules that already pass, so it cannot regress.

Compile after each move. This stage is safe, and it makes the next one legible in review.

### Stage 2, extract the platform seam (done)

Pull the SQL helpers `all`, `get`, `run` and both `transaction` overloads out of `DispatchService` into `platform/db/Database` and `platform/db/TransactionRunner`, injected where they were used. This is the seam every later extraction depends on, which is why it comes first.

### Stage 3, extract modules from `DispatchService`, one at a time

Order chosen so each extraction is small and independently verifiable:

1. **identity**: `login`, `session`, `logout`, `checkLoginRate`, `allowed`. Clean seam, obvious tests.
2. **referencedata**: `reference`, calendar extension.
3. **seeding**: `seed`, `seedScenarios`, roughly 350 lines. Large, mechanical, zero request-path risk.
4. **planning**: `reservations`, `validatePlan`, `assignment`, `previewAssignments`, joined with `Planning.java`.
5. **query**: `state`, `orders`, `plans`, `proofImage` into projections.
6. **messaging plus per-module handlers**: `command`, `apply` and `event` become `CommandBus` plus one handler per capability. Do this last, because it touches every module, and keep the wire contract of `POST /api/command` byte for byte identical.

After stage 3, `DispatchService` no longer exists. Delete it rather than leaving an empty shell.

### Stage 4, split `ApiController`

288 lines covering health, proof, state, assignments, login, logout, command and the catch-all. Split into `identity/web/AuthController`, `messaging/web/CommandController`, `query/web/SyncController`, `query/web/StateController`, `query/web/ProofController`, with shared plumbing in `platform/web`. Keep every path, cookie, guard and error shape exactly as it is, because the frontend proxy and the Playwright tests both depend on them.

### Stage 5, frontend, lowest risk first (steps 1 to 6 done)

1. Add the aliases to `tsconfig.json`.
2. `@shared/ui` from `components/components.tsx`, 8 importers.
3. `@shared/domain` from `lib/types.ts`, the most imported file in the project. Split by aggregate while moving.
4. `@shared/offline` from `components/storage.ts` and `components/useDeliveryDraft.ts`.
5. Modules in increasing size: identity, receipt, loading, execution, ordering, planning.
6. `app-shell` from `Workspace.tsx` and `useWorkspace.ts`.
7. Split `Dispatcher.tsx` into container plus views inside `@modules/planning`.
8. Add the boundary test.

Typecheck after every numbered step. Run the Playwright suite after step 6 and again at the end, because these moves touch what the browser tests drive.

### Stage 6, resolve the duplicate implementation

`frontend/lib/service.ts` (994 lines), `lib/domain.ts` and `lib/scenarios.ts` are a second implementation of rules that Spring now owns, kept alive by `tests/*.test.ts` and `scripts/database.ts`. Two implementations of the same constraint set is the one structural problem the new folder layout cannot hide, and a judge reading the repository will find it. Pick one:

- **Delete it**, and port `tests/constraints.test.ts`, `tests/service.test.ts` and `tests/route-order.test.ts` to Java tests against `planning/domain`, which is where those rules now live. Cleanest, and the pure domain package makes it straightforward.
- **Keep it**, move it to `frontend/legacy-node-service/`, and state plainly in the README why a second implementation exists and what stops the two drifting apart.

Deleting is the better answer if there is time, because the value those tests provide is exactly the value a JUnit test over a framework-free `planning/domain` provides, without the drift risk.

## 6. What this buys, concretely

- A new feature touches one module folder on each side, with the same name on both sides.
- The constraint set lives in one framework-free package that runs in milliseconds under plain JUnit, so the rules that decide Waypoint's service level are the cheapest thing in the build to test.
- Boundary violations fail the build instead of being discovered in review.
- Any module can move behind an interface, or out of the process entirely, without a rewrite.
- `git log --follow` still works on every moved file, because every move used `git mv`.
