# Foundation plan: baseline, Reference data, Identity and access

Audience: the Xception build team. This plans the two modules everything else depends on, and the conventions underneath them that must not change later.

Scope is deliberately narrow. Ordering, Planning, Loading, Execution, Receipt and everything else are out of scope here. They are specified in [MODULES.md](MODULES.md) and are built after these two land.

**This is a plan. No code, no migrations, no application changes are part of it.** The decisions below are what gets implemented next, once agreed.

---

## Part 0: What "baseline" means

A baseline is the set of choices that are expensive to reverse because everything else is built on top. Some are cheap to change forever; some are not. The test is simple: **if changing this later forces a data migration across many tables, or invalidates stored history, it belongs in the baseline.**

### 0.1 The stability contract

Seven conventions. Once agreed they do not change, and every later module inherits them.

| # | Convention | Why it is baseline |
| --- | --- | --- |
| B1 | **Identifiers.** Business identifiers from the dataset (`OUT001`, `VEH014`, `Fresh`) are the primary keys of reference tables. Operational tables use UUIDv7 surrogates and carry the business identifier as a unique column | Changing a key type later rewrites every foreign key in the database |
| B2 | **Time.** `timestamptz` for instants, `date` for service dates, `time` for wall-clock windows. One operating timezone, `Asia/Colombo`, stored on the depot, never assumed in code | A timezone decision discovered late is a lateness bug in every historical record |
| B3 | **Measures.** `numeric` with explicit precision for weight, volume, distance, fuel and minutes. Never `real` or `double precision` | A capacity constraint decided by floating point rounding is a truck that physically does not close |
| B4 | **Concurrency.** Every mutable row carries `row_version`. Every update is `WHERE id = ? AND row_version = ?` and fails on zero rows affected | Retrofitting concurrency control onto live data means reconciling writes that already raced |
| B5 | **History.** Operational rows are never deleted and never overwritten in place when the change is a decision. Terminal states and supersession, not `DELETE` | Deleted evidence cannot be recovered when a dispute arrives three months later |
| B6 | **Access.** Two-part identity per transaction: `SET LOCAL ROLE waypoint_<module>` for what the code may touch, `SET LOCAL app.actor_id` for which rows the person may see | Retrofitting row-level security means auditing every query ever written |
| B7 | **Provenance.** Every derived decision records the versions of its inputs: reference data, rules, model | Without it, a past decision cannot be explained once its inputs change |
| B8 | **Configuration is typed and validated, and the process refuses to start rather than run misconfigured** | A missing value found at 03:30 through odd behaviour costs more than one found at startup through a clear message. Note the distinction from a dependency being down: `DATABASE_URL` must be present, but it may point at a database that is unreachable. That is an outage, reported by readiness, not a misconfiguration |

### 0.2 Five baseline defects to fix before building on it

The target schema in [schema/migrations/](schema/migrations/) is good work, and these are the places where it will force change later, which is exactly what a baseline must not do.

| # | Defect | Consequence | Decision needed |
| --- | --- | --- | --- |
| **F1** | **`ref.reference_versions` exists, but no reference row is attached to a version.** The table records a content hash and an import time; the outlets and vehicles themselves have no version link and no validity period | A plan can stamp `reference_version_id`, but nothing can answer "what was OUT001's delivery window in March". Provenance is recorded and unusable. This breaks B7 | Choose the versioning model. See 1.4 |
| **F2** | **Lookup tables carry a `CHECK` list of their own values.** `ref.brands` has a table and `CHECK (brand_code IN ('Fresh','Style','Tech'))`. `iam.roles` has a table and a five-value `CHECK` | Adding a brand or a role requires a schema migration, which is precisely what a lookup table exists to avoid. The architecture already names actors the `CHECK` forbids: auditor, and the stock controller served by the warehouse | Drop the value lists from the `CHECK`s; keep the tables and the foreign keys. Constrain the shape, not the membership |
| **F3** | **`depot_id` and `district_id` are `GENERATED ALWAYS AS IDENTITY`.** They are generated at import, and operational tables store them | Re-importing reference data into a fresh environment can produce different numbers for the same depot, so a `depot_id` in a backup is not portable between environments. Violates B1 | Use `depot_code` and `district_name` as the keys, or keep the surrogate and make the natural key the import identity, never regenerated |
| **F4** | **Reference rows have `is_active` but no validity period.** An outlet that changes district, window or depot is updated in place | History is silently rewritten: a plan from March re-read in June shows the outlet's June district. Violates B5 and makes F1 unfixable | Adopt the versioning model in 1.4 |
| **F5** | **IAM has no session and no login-attempt storage.** `iam` contains users, roles, scopes, assignments and devices, but nothing for authentication state | Authentication has nowhere to live, so the module cannot be built as specified. Sessions must be server-side for revocation to be immediate | Add `iam.sessions` and `iam.login_attempts`. See 2.3 |

None of these is large. All five are cheap now and expensive after the operational tables are populated.

---

### 0.3 The platform baseline

Cross-cutting machinery that every module uses. It lands before either module, because retrofitting any of it means touching every write path already written. Described in full in [../../SYSTEM-ARCHITECTURE.md](../../SYSTEM-ARCHITECTURE.md) sections 6.5 to 6.7 and 7; listed here so the foundation is legible from one document.

| Concern | What exists | Where |
| --- | --- | --- |
| Configuration | Typed, validated, fail fast; a startup report with secrets redacted | `platform/config/` |
| Database seam | Pool as `waypoint_app`, module role and actor set per transaction, bounded retry on serialization failure | `platform/db/Database` |
| Migrations | Forward only, checksummed, advisory lock, explicit command | `platform/db/Migrator` |
| Logging | Structured, correlation and trace id in context, no personal data | `logging.structured.format.console` |
| Metrics | Micrometer with a Prometheus endpoint; the domain-facing API is `Metrics` | `platform/observability/Metrics` |
| Tracing | OpenTelemetry over OTLP, client through command to database | Micrometer Tracing |
| Health | Liveness and readiness separated: a slow database stops traffic, it does not kill the process | `/health/liveness`, `/health/readiness` |
| Command bus | One envelope, idempotency receipt, audit and state committed together. **Fails closed** with no authorizer wired | `platform/messaging/` |
| Audit | Append only, written in the same transaction, denials included | `platform/audit/` |
| Errors | RFC 9457 `application/problem+json` carrying violated rule identifiers | `platform/web/` |

## Part 1: Reference data module

### 1.1 Purpose and why it is first

Reference data is the shared vocabulary: which outlets exist, what each vehicle can carry, which days operate, how long a district takes to reach. Planning cannot validate a single constraint without it, Ordering cannot resolve a delivery window, and Identity cannot express "this loader works at Peliyagoda" without a depot to point at.

It is also the only module with no upstream dependency, which is why it is built first.

### 1.2 Ownership boundary

| Owns | Caches, does not own | Never touches |
| --- | --- | --- |
| brands, depots, districts, outlets, vehicles, vehicle day status, calendar days, district travel, service allowances | products, from the external warehouse catalogue | anything operational |

The module is **read-mostly**. Writes happen at import, at a vehicle status change, and at a calendar override. Nothing in a request path writes to `ref`.

### 1.3 Data model decisions

The shape is settled by the dataset and should not be re-litigated. What needs deciding is listed here.

| Table | Grain | Key decision |
| --- | --- | --- |
| `brands` | one per brand | Lookup table, no value `CHECK` (F2) |
| `depots` | one per depot | `depot_code` is the stable identity (F3). Carries `timezone_name` (B2) |
| `districts` | one per district | Same treatment as depots |
| `outlets` | one per outlet, per version | `OUT001` is the key. Windows are `time`, interpreted in the depot timezone. Coordinates deferred; additive later |
| `vehicles` | one per vehicle, per version | `VEH014` is the key. Capacities `numeric`, never float (B3) |
| `vehicle_day_status` | one per vehicle per date | Operational, not versioned. Records who set it and why |
| `calendar_days` | one per date | Supplied range is authoritative; beyond it, policy applies (1.5) |
| `district_travel` | one per district-depot | Planning inputs; versioned with the rest |
| `service_allowances` | one per brand and dock type | Versioned with the rest |
| `traffic_speed` | one per district, hour and monsoon flag | 576 rows. Structural, versioned with the rest |
| `road_conditions` | one per district and date | 10,920 rows. Date-keyed series, **not** versioned. See D1 |
| `products` | one per catalogue entry | Cache. Never edited locally. Rules in [DATA-MODEL-REVIEW.md](DATA-MODEL-REVIEW.md#external-product-catalogue) |

### 1.4 The versioning model: the one decision that matters

This resolves F1 and F4 and is the heart of "a baseline that does not change". Three options:

| Option | How | Cost | Verdict |
| --- | --- | --- | --- |
| **A. Snapshot per version** | Every import writes a complete new copy of every reference row, tagged with `reference_version_id` | Simple to reason about and to read as-of. Storage is trivial at 120 outlets and 60 vehicles. Foreign keys from `ops` must point at the business key, not at a version row | **Recommended** |
| B. Effective-dated rows (SCD2) | Each row carries `valid_from`/`valid_to`; queries filter by date | Standard, but every read of reference data becomes temporal, and a forgotten predicate silently returns two rows | Rejected: the failure mode is silent |
| C. Audit-only | Keep current rows, log changes separately | Cheapest, and cannot reconstruct a past decision | Rejected: does not satisfy B7 |

**Recommendation: Option A.** Reference data is small, changes rarely, and arrives as whole files. A version is an immutable snapshot; the current version is a pointer. Reads take the current version by default and a specific version when replaying history. `ops` rows keep pointing at `OUT001`, not at a versioned row, so nothing in the operational model has to know versions exist.

What must be decided with it:

1. Does a version cover **all** reference tables together, or per table? Recommendation: **all together**, one import, one hash, one version. Partial versions multiply the combinations a plan must record.
2. Is `vehicle_day_status` inside the version? Recommendation: **no**. It is daily operational state, not master data, and versioning it would create a new reference version every time a truck enters the workshop.

### 1.5 Calendar policy

The supplied calendar ends within the dataset range. The policy must be explicit because planning silently stops working when it runs out.

- Supplied dates are authoritative and are never recomputed.
- Beyond the supplied range, generate Monday to Saturday as operating, Sunday not operating, no payday, no festival, monsoon flag unset.
- `CALENDAR_FILE` overrides take precedence over both.
- Alert **before** exhaustion, not on the morning it happens.
- Generated dates are marked as generated, so a dispatcher can tell a real holiday from an assumed one.

### 1.6 Import pipeline

The import is the module's only significant behaviour, and it must be atomic and repeatable.

```
CSV or warehouse API
      │
      ▼
  stage          load into staging tables, no constraints on the live model
      │
      ▼
  validate       every rule in 1.7. Reject the whole import on any failure
      │
      ▼
  hash           content hash over the normalized contents
      │
      ▼
  publish        insert the new version, flip the current pointer, one transaction
```

Rules: an import either publishes completely or changes nothing. Re-importing identical content is a no-op, detected by hash. Every import records who ran it and from what source. Import is an explicit operational command, never a side effect of a build, a deployment or a request.

### 1.7 Import validation

Rejection is better than a silent bad plan at 03:30. The import fails, loudly, if any of these is false:

| Check | Rule |
| --- | --- |
| Completeness | 120 outlets, 60 vehicles, both depots, all 12 districts present |
| Referential | Every outlet's brand, district and depot exist. Every vehicle's depot exists |
| Windows | `delivery_window_open < delivery_window_close`. A `mall_dock` outlet has a mall window |
| Feasibility | Every outlet's window is at least as long as its brand and dock service allowance. Otherwise it can never be served |
| Capacity | Weight and volume capacities positive; `km_per_l > 0`; fuel quota non-negative |
| Coverage | A district-depot pair exists in `district_travel` for every outlet's district and depot |
| Allowances | A service allowance exists for every brand and dock type combination in use |
| Fleet sanity | At least one van per depot serving `van_only` outlets, at least one reefer per depot |
| Calendar | Dates contiguous, no gaps, `iso_week` consistent with the date |

The feasibility and coverage checks are the valuable ones: they catch data that is structurally valid and operationally impossible.

### 1.8 Module contract

**Queries** (synchronous, cached, the only way other modules read reference data):

| Query | Returns |
| --- | --- |
| `currentVersion()` | The active reference version identifier |
| `snapshot(versionId?)` | Everything a planning run needs, for the current or a named version |
| `outlet(outletId, versionId?)` | One outlet with windows and access restrictions |
| `vehicle(vehicleId, versionId?)` | One vehicle with capacities and home depot |
| `isOperating(date)` / `nextOperatingDay(date)` | Calendar policy applied |
| `travelProfile(districtId, depotId)` | Depot-to-district and inter-stop times |
| `serviceAllowance(brand, dockType)` | Planning allowance in minutes |
| `availableVehicles(depotId, date)` | Fleet minus workshop and unavailable |

**Commands:** `ImportReferenceData`, `PublishReferenceVersion`, `SetVehicleDayStatus`, `OverrideCalendarDay`.

**Events:** `reference.version_published`, `vehicle.status_changed`, `calendar.overridden`.

**Consumes:** nothing. This is what makes it the foundation.

### 1.9 Caching

Reference data is read on every constraint evaluation, thousands of times per allocation, so it is held in memory.

- The whole current version loads at startup into an immutable in-memory snapshot.
- A snapshot is never mutated; publishing a version swaps the pointer.
- `reference.version_published` invalidates and reloads.
- Historical versions are read from the database on demand, not cached.
- Health reports the loaded version, so a stale instance is visible rather than mysterious.

### 1.10 Edge cases owned here

| Case | Behaviour |
| --- | --- |
| Import fails validation | Nothing published, previous version remains current, failure reported with the failing rows |
| Identical re-import | No-op by hash, no new version |
| Outlet moves depot between versions | New version. Plans built on the old version still read the old depot |
| Calendar exhausted | Generated by policy and marked generated; alert raised before exhaustion |
| Vehicle marked `in_workshop` mid-day | Affects the next planning run, never retroactively |
| Vehicle status set for a non-operating date | Rejected |
| Instance holds a stale version | Visible in health and in the dispatcher's version indicator |

### 1.11 Definition of done

1. Import is atomic, validated, hashed and repeatable, with the whole rule set in 1.7 enforced.
2. Reading as-of a version returns exactly what that version contained.
3. The domain layer has no framework imports and its calendar and window logic is tested without a database.
4. Cache invalidation on publish is proven by a test, not assumed.
5. F1 to F4 are closed.

---

## Part 2: Identity and access module

### 2.1 Purpose

Answer two questions on every request: **who is this**, and **may they do this to this row**. Every other module depends on the answers, which is why it is second and not later.

### 2.2 Actors and scope model

Role alone is never sufficient here, because every role is scoped to something.

| Role | Verb scope | Row scope | Source of scope |
| --- | --- | --- | --- |
| `store_manager` | place, amend, cancel, confirm receipt, report issue | one or more outlets | `user_outlet_access` |
| `dispatcher` | close orders, generate, override, defer, publish, resolve | one or more depots | `user_depot_access` |
| `loader` | start and finish loading, record checks, request interchange | one depot | `user_depot_access` |
| `driver` | start stop, record outcome, capture proof, report fault | **one vehicle on one date** | `vehicle_driver_assignments`, temporal |
| `admin` | accounts, roles, scopes, reference import, calendar override | global | role only |
| `auditor` | read-only everything | global, read | role only |

Two decisions:

1. **`auditor` must exist in the role list.** The architecture names it; the schema `CHECK` forbids it (F2). A read-only investigative role is also the safe way to give someone access during a dispute without granting write.
2. **The stock controller is not a Waypoint role.** That actor lives in the external warehouse system. Waypoint receives their decisions as events.

### 2.3 Authentication

| Concern | Decision |
| --- | --- |
| Credential | Argon2id, per-user salt, parameters recorded with the hash so they can be raised later without invalidating existing passwords |
| Identity | `user_id` is the immutable key. **Email is a mutable attribute, never a key**, and never a foreign key target |
| Session | Opaque server-side token; a row in `iam.sessions` (F5). Server-side because revocation must be immediate, which a self-contained token cannot do |
| Cookie | `HttpOnly`, `Secure`, `SameSite=Strict`, no session data in the body |
| Expiry | Absolute and idle expiry, both configured. Rotation on privilege change |
| Throttle | `iam.login_attempts` (F5), per identity, shared across instances so a second replica is not a bypass |
| Device | `iam.devices`, separate from user identity. A shared dock tablet is one device used by many people, and audit must distinguish them |
| Offline | Expiry never clears a device's local queue. Re-authentication restores the account and drains it. **First login requires connectivity**, stated in the UI, not discovered in the field |

### 2.4 Authorization

One decision point, many enforcement points, plus a database backstop.

```
              ┌──────────────────────────────────────────────┐
  command ───►│ PEP (application layer): permits(actor, cmd, target)?
              └───────────────┬──────────────────────────────┘
                              ▼
              ┌──────────────────────────────────────────────┐
              │ PDP: role grants the verb?                    │
              │      scope covers the row?                    │
              │      resource state allows it?                │
              └───────────────┬──────────────────────────────┘
                     allow ───┴─── deny + reason (403 + audit)
                              │
  every read ────────────────►│ PEP (data layer): RLS policy on the actor
```

Rules, in priority order:

1. **Deny by default.** An unlisted command or unmatched scope is `403` with an audit entry, never an empty result that reads as "no data".
2. **Decide in one place.** `AuthorizationPolicy.permits(actor, command, target)` is the only place an access decision is made.
3. **Filter in SQL.** Scope predicates are part of the query. RLS is the backstop for a forgotten predicate, not the primary mechanism.
4. **Driver scope is temporal.** Access is to a vehicle **on a date**. Yesterday's driver cannot post today's delivery. This is what the exclusion constraint in `008` protects.
5. **Re-check inside the transaction.** A permission revoked a second ago must not lose a race.
6. **Scope changes are data; new command types are code.** Granting a depot is a row. Adding a verb is a release.
7. **Externalizable later.** The PDP is an interface. Moving to OPA or Cedar is an adapter, and is not justified today.

### 2.5 Relationship to database roles

Two independent mechanisms that are easy to confuse:

| Mechanism | Answers | Set by |
| --- | --- | --- |
| `SET LOCAL ROLE waypoint_<module>` | Which **tables** this code path may touch | The module executing the command |
| `SET LOCAL app.actor_id` | Which **rows** this person may see | The authenticated session |

Both are transaction-scoped. `SET LOCAL`, never plain `SET`: a pooled connection would otherwise carry one request's identity into the next. `waypoint_app` is `NOINHERIT`, so a forgotten module role is a permission error rather than a silent full-access query. Table owners bypass RLS, so tables use `FORCE ROW LEVEL SECURITY` and the runtime role owns nothing.

### 2.6 Module contract

**Queries:** `permits(actor, command, target)`, `actorForSession(token)`, `scopeOf(actor)`, `driverVehicleOn(userId, date)`, `accountsFor(scope)`.

**Commands:** `Login`, `Logout`, `CreateAccount`, `UpdateAccount`, `ResetPassword`, `DisableAccount`, `GrantScope`, `RevokeScope`, `AssignDriverToVehicle`, `EndDriverAssignment`, `RegisterDevice`, `RetireDevice`.

**Events:** `account.created`, `account.disabled`, `access.granted`, `access.revoked`, `authorization.denied`, `driver.assigned`.

**Consumes:** nothing. Like Reference, it is a foundation, not a consumer.

**Depends on:** `ref.depots`, `ref.outlets`, `ref.vehicles` for scope targets. This is the only cross-module foreign key permitted, and it is why Reference is built first.

### 2.7 Invariants

1. A session maps to exactly one account, and disabling an account revokes every session in the same transaction.
2. One driver per vehicle per period, enforced by the exclusion constraint, not by application code.
3. A scope grant points at a reference row that exists.
4. Every authorization decision, including denials, is auditable with actor, device, command and target.
5. `user_id` never changes. Every other user attribute may.

### 2.8 Edge cases owned here

| Case | Behaviour |
| --- | --- |
| Session expires with pending offline work | Re-authenticate without clearing the queue; sign-out blocked while work is pending |
| Role changed or account disabled mid-session | All sessions revoked, audit written, local queue preserved for review |
| Permission revoked during an in-flight command | Re-checked inside the transaction; the command fails |
| Scope violation attempt | `403` plus audit, never an empty list |
| Credential stuffing | Per-identity lockout shared across replicas |
| Overlapping driver assignments | Rejected by the database |
| Driver posts for a vehicle assigned to someone else | `403`, because scope is vehicle plus date |
| Shared tablet, two loaders in one shift | Both attributed, device plus user |
| Pooled connection reused across users | Impossible: `SET LOCAL` inside the transaction |
| Last active dispatcher disabled | Refused. The operation cannot be left without a planner |

### 2.9 Definition of done

1. Every command has an authorization test that asserts a **denied** case, not only the happy path.
2. RLS is proven: a connection carrying the wrong actor returns zero rows for another outlet's data.
3. `waypoint_app` without a module role is a permission error, proven by test.
4. Driver temporal scope is tested across a date boundary.
5. Disable revokes sessions atomically, proven by test.
6. F5 is closed.

---

## Part 3: Sequence and acceptance

### 3.1 Order of work

```
Step 1  Baseline decisions          F1 to F5 agreed, stability contract signed off
          │                          no code, one review session
          ▼
Step 2  Reference schema            versioning model, lookup CHECK removal, key decisions
          │
          ▼
Step 3  Reference module            import, validate, publish, cache, queries
          │                          done when 1.11 passes
          ▼
Step 4  IAM schema                  sessions, login attempts, role list
          │
          ▼
Step 5  IAM module                  authentication, PDP, scopes, RLS policies
                                     done when 2.9 passes
```

Steps 2 and 4 are schema; steps 3 and 5 are behaviour. Nothing downstream starts until step 5 passes, because every later module's authorization tests depend on it.

### 3.2 What blocks what

| This | Blocks |
| --- | --- |
| Baseline decisions | Everything. A changed key type after step 3 is a rewrite |
| Reference module | Planning entirely; Ordering's window and cutoff logic; IAM scope targets |
| IAM module | Every command in every module, because each needs an authorization test |

### 3.3 Acceptance for the foundation as a whole

1. A fresh database can be migrated, a reference version imported, and accounts created, with no application running.
2. Reading reference data as of a past version returns that version's values.
3. An unauthorized actor cannot read another depot's rows even with a hand-written SQL query through the application role.
4. The reference domain and the authorization policy are both tested with no database.
5. Nothing in `ref` or `iam` depends on any operational table. The dependency arrow points one way only.

### 3.4 Decisions, resolved

The seven from the first draft, plus two forced by the full dataset. Each is a recommendation with its reason; change any of them here rather than in code.

| # | Decision | Resolution | Reason |
| --- | --- | --- | --- |
| **D1** | Versioning model for reference data | **Snapshot per version, structural tables only.** One import, one content hash, one version, covering brands, depots, districts, outlets, vehicles, district_travel, service_allowances and traffic_speed. **Calendar and road_conditions are excluded**: they are date-keyed series, append-only, and a correction to one date is an explicit override, not a new version of the world | Reference data is small and arrives as whole files. Versioning a date series would mint a new version every time a single day's disruption index is corrected |
| **D2** | Keys for depots and districts | **Natural codes.** `depot_code` and `district_name` are the identity. No generated identity that could renumber on re-import | A `depot_id` that differs between environments makes a backup unrestorable and a stored reference meaningless (F3) |
| **D3** | Remove value `CHECK`s from `brands` and `roles` | **Yes.** Keep the tables and the foreign keys; drop the embedded value lists | A lookup table whose values are also fixed in a `CHECK` needs a migration to add a row, which is the thing lookup tables exist to avoid (F2) |
| **D4** | Add `auditor` to the role list | **Yes.** Read-only, global | Named in the architecture, forbidden by the current `CHECK`. It is also the safe way to grant investigative access during a dispute |
| **D5** | Session storage | **Server-side rows, opaque token.** Add `iam.sessions` and `iam.login_attempts` | Revocation must be immediate, which a self-contained token cannot do. Throttling must be shared across replicas or a second replica is a bypass (F5) |
| **D6** | Is `vehicle_day_status` versioned | **No.** It is daily operational state | Versioning it would create a new reference version every time a truck enters the workshop |
| **D7** | Calendar beyond the supplied range | **Generate by policy**, Monday to Saturday operating, mark generated, alert before exhaustion | Planning silently stops working when the calendar runs out. A dispatcher must be able to tell a real holiday from an assumed one |
| **D8** | District to depot relationship | **`districts.depot_id`.** Depot is a function of district, and `district_travel` is keyed by **district alone** | Verified in the data: all 120 outlets have `outlet.depot` equal to their district's depot, zero exceptions, and `district_travel` has exactly one row per district. The supplied validator also indexes it by district alone. `outlet.depot_id` becomes derived and is kept only as a checked redundancy |
| **D9** | Where `traffic_speed` and `road_conditions` live | **Reference module.** They are General Data and are read during planning and estimation | They are supplied master data, not operational records, and nothing else owns them |

### 3.5 Consequences worth stating

- **D8 changes the schema baseline.** `ref.district_travel` currently has `PRIMARY KEY (district_id, depot_id)`, which implies a district can be served by more than one depot. The data says otherwise. Fixing this before operational tables exist is cheap; afterwards it is a rewrite of every join that assumed the composite key.
- **D1 means a plan stamps one `reference_version_id`** and that is sufficient to reproduce every structural input. Calendar and road conditions are reproduced by date, which is already immutable.
- **D3 and D4 together** mean the role list is data. Adding `stock_controller` later, if the warehouse is ever brought in-house, is an insert.
