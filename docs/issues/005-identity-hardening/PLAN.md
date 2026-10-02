# Issue #5: Identity and auth hardening, plan

Written before code, per AGENTS.md "Issue Documents". What was actually built goes in `WALKTHROUGH.md`.

## Current state

Checked against `dev` at `ce963ef`. Some of the issue was already fixed by later work; the rest stands.

| Area | Already true | Still wrong |
| --- | --- | --- |
| Lockout | Throttle answers 429 with `Retry-After`; parameters are configuration | A failed sign-in records its attempt and its audit row, then throws, so both roll back: the lockout can never fire. One counter per identity, so anyone can lock anyone out. Success deletes the history |
| Client address | `server.forward-headers-strategy=native`; the Next proxy and nginx forward `X-Forwarded-For` | Nothing proves it |
| Origin | The proxy forwards `Origin` and the page host | No backend check exists; two comments claim one |
| Cookie | `HttpOnly`, `SameSite=Strict` on sign-in | No `__Host-` prefix, no `Max-Age`, sign-out cookie has no `SameSite`, `COOKIE_SECURE` defaults off |
| Sessions | Lifetimes are configuration | Plaintext token is the primary key; expiry compared with database `now()`; an `UPDATE` on every authenticated read; signing in again leaves the old session alive |
| Privilege changes | Disable and password reset revoke sessions | No role change command; scope revoke keeps sessions; the last dispatcher of a depot can be disabled; no revocation metric |
| Devices | Table exists | No commands, no reads; an unknown `deviceId` at sign-in is a foreign key violation and a 500; audit rows never carry a device |
| Authorization | Decisions are audited; denial inside the transaction is re-audited after rollback | Policy is decided only before the transaction; the cache is cleared before commit and is per JVM; policy administration writes outside the command bus; an unknown kind is 404; `implemented` is never read; scope and assignment commands take no version |
| Database roles | `waypoint_app` exists, NOINHERIT, owns nothing | It is `NOLOGIN` and nothing connects as it: every deployment's pool is the owner |
| Contract | `IdentityQuery` is declared | No implementation; no `app.actor_drives` |
| Resource names | Every resource is already `wpt:ref:*`, and no resource carries an email | Nothing to do |

## Which layer owns each dependency

| Concern | Layer | Class |
| --- | --- | --- |
| Lockout arithmetic (which counter trips, how long to wait) | `identity/domain` | `LockoutPolicy`, pure, clock as a parameter |
| Sign-in, the throttle's rows, sessions | `identity/application` | `LoginHandler`, `LoginThrottle`, `SessionRegistry` |
| Token hashing | `identity/infrastructure` | `SessionTokens` |
| Policy generation and statements | `identity/infrastructure` | `JdbcPolicyRepository`, `PolicyCache` |
| The decision, before and inside the transaction | `identity/application` | `PolicyDecisionPoint` behind `platform/messaging/CommandAuthorizer` |
| Policy and device commands | `identity/application` | `PolicyCommandHandlers`, `DeviceCommandHandlers` |
| Origin check | `platform/web` | `OriginGuardFilter` |
| Running work after commit | `platform/db` | `Database.afterCommit` |
| Pool identity, migration connection | `platform/config`, `platform/db` | `DataConfig`, `Migrator` |

## Decisions

### 1. An unknown `deviceId` at sign-in is refused with 422

A device identity is something an administrator grants (`iam:RegisterDevice`), not something a client claims. Auto-registering would let any signed-in browser mint rows in `iam.devices`, and the "pending" rows would need a review screen nobody has asked for. The credentials are checked first, so the answer does not reveal which devices exist to someone without an account. A retired device is refused the same way. The client's own offline queue id (`sync.operations.device_id`) is unrelated and has no foreign key.

### 2. Cross-replica invalidation is a generation counter, not `LISTEN/NOTIFY`

`iam.policy_generation` holds one number. Every change that can alter a decision increments it in the same transaction. Every decision reads it and compares it with the generation its cached statements were loaded at.

`LISTEN` needs a dedicated, unpooled connection per replica, and the documented hosted setup is a pooled URL where a session-level `LISTEN` is silently lost. The counter is one indexed read, works through any pooler, and gives the in-transaction re-check for free: the command reads the generation in its own snapshot.

The same number closes the stale-repopulation race. An entry loaded from an old snapshot is stored under the old generation, and the next reader sees the mismatch and reloads. The in-process clear still runs, after commit, so memory is released and the SEC-12 counter still moves.

### 3. Lockout parameters (P-13)

| Counter | Threshold | Window |
| --- | --- | --- |
| One identity from one address | 8 failures | 15 minutes |
| One address, any identity | 40 failures | 15 minutes |
| One identity, any address | 40 failures | 15 minutes |

The pair counter is the usual lockout, and it is why a stranger can no longer lock an account from somewhere else. The address counter stops one machine walking a list of accounts. The identity counter bounds a distributed guess at five times the pair threshold; without it a botnet has unlimited attempts. There is no separate lock duration: a counter clears as its failures leave the window, and `Retry-After` is the time until the oldest counted failure does. A success marks that pair's failures as cleared; nothing is deleted.

### 4. The in-transaction re-check re-evaluates, it does not compare tokens

Inside the command's transaction the bus asks the authorizer again. The authorizer reads `app.policy_generation()` on the command's own connection and evaluates against the cache for that generation, reloading only on a mismatch. Carrying "the generation I decided at" from the first check to the second would be cheaper by one in-memory evaluation and wrong whenever another thread refreshed the cache between the two.

### 5. Policy commands are versioned on the policy

`iam.policies` gains `row_version`. `iam:CreatePolicyVersion`, `iam:SetDefaultPolicyVersion`, `iam:AttachPolicy` and `iam:DetachPolicy` require it and bump it. Scope commands and `iam:ChangeRole` are versioned on the account; `iam:AssignDriver` is checked against the driver's account version (it creates a row, and what can go stale is the account's role); `iam:EndDriverAssignment` is versioned on the assignment.

### 6. The pool drops to `waypoint_app` whoever logs in

Deployments log in as `waypoint_app` with its own password, set by `migrate` from the pool's URL, so the backend container never holds the owner's credentials. `MIGRATION_DATABASE_URL` is the owner and is read only by `migrate`; unset, `DATABASE_URL` is used for both, as before.

Every pooled connection also runs `SET ROLE waypoint_app` when it is opened. Logged in as `waypoint_app` that changes nothing. Logged in as the owner (local development, CI) it makes a forgotten `SET LOCAL ROLE` fail there too, so the test suite catches it instead of production.

**Left out of the first pull request: a non-superuser `waypoint_migrator` that owns the tables.** In the Docker deployments the owner is the image's bootstrap user, which is a superuser and cannot be reassigned with `REASSIGN OWNED`. Moving ownership means altering every object one by one, re-issuing every `ALTER DEFAULT PRIVILEGES` for the new owner, and granting it admin on the existing roles, on live volumes. The first pull request shipped the half that removes the owner's credentials from the running process; the handover is decision 8 below.

### 7. Expand, then contract, for the session key

`iam.sessions.session_token` is renamed `token_hash` and existing rows are hashed in place. The old backend keeps serving while `init` runs, so for the seconds between the migration and the container swap it cannot resolve a session and answers 500 on authenticated requests. Deploys already have a comparable gap at the swap. Where `COOKIE_SECURE=1` the cookie is also renamed, so everyone signs in again once.

## Pull request breakdown

One branch, `fix/identity-hardening`, one pull request into `dev`, then `dev` into `main`. The parts depend on each other too closely to land separately: the bus's re-check needs the generation, the generation is moved by the account and policy commands, and the tests of all of them need the pool to behave as it will in production. Commits: this plan, the implementation with its tests, the registers and walkthrough.

## Out of scope, and who owns it

| Item | Owner |
| --- | --- |
| `waypoint_migrator` as a non-superuser owner | done in the second pass, decision 8 |
| Filtering reference reads by depot scope | done in the second pass, decision 9 |
| Admin console screens for devices, roles and policy | #22 |
| Driver row-level security using `app.actor_drives` | #12 |

## Second pass: the owner and reference scope (2026-10-02)

The first pull request (#62) left two lines of the issue open. This closes both, and one gap found on the way.

### Current state

Checked against `dev` at `6eec879`.

| Area | Already true | Still wrong |
| --- | --- | --- |
| Owner | The pool is `waypoint_app`; `migrate` has its own connection | Every schema, table and function belongs to the account `migrate` logs in as, the image's bootstrap superuser. Three `SECURITY DEFINER` functions therefore run as a superuser, and a migration can do anything to the cluster |
| Reference reads | `reference:Read` is checked on every endpoint, with a resource naming the depot, outlet or vehicle | Nothing checks scope. The data holds two depots, and a dispatcher of one reads the other's outlets and fleet |
| Store policy | The store screens read their outlet and the calendar | `WaypointStoreManager` has no `reference:Read`, so against a real backend both reads are refused |

### Which layer owns each dependency

| Concern | Layer | Class |
| --- | --- | --- |
| Moving ownership, default privileges, role admin | migration | `20261002T1900_platform_migrator_owner.sql` |
| Applying files as the owner | `platform/db` | `Migrator` |
| The scope half of a reference read | `referencedata/application` | `ReferenceScope`, asking `identity/contract/IdentityQuery` |
| Calling it | `referencedata/web` | `ReferenceController` |
| The store's permission | migration | `20261002T1910_iam_store_reads_reference.sql` |

### 8. The handover is a migration, and migrate acts as the owner from then on

One migration creates `waypoint_migrator` (no superuser, no `BYPASSRLS`, `CREATEROLE`) and moves every schema, table, partition, sequence, function and type the migrations made to it, by name, read from the catalog. It declares the same default privileges for the new owner, or the next table a migration adds would be readable by nobody, and gives it `ADMIN` (not `INHERIT`, not `SET`) on `waypoint_app` and the module roles. A last check raises if anything in those schemas has another owner. It is one transaction with the rest of the run: it moves everything or nothing. A 15 second `lock_timeout` stops a deploy from queueing requests behind a lock it cannot get.

The list is read from the catalog and not written out because branches are adding migrations: one with an earlier name that merges later is covered on a new database without editing this file, and on an existing database it runs after the handover, as the owner.

`Migrator` asks before each file whether `public.schema_migrations` belongs to `waypoint_migrator`, and if so applies the file under `SET LOCAL ROLE waypoint_migrator`. The marker is per database (roles are cluster-wide, so "the role exists" would be wrong for a second database on the same cluster) and it works on a new database, where the handover is one of the files in the run: the files before it need a superuser (`ALTER ROLE ... NOSUPERUSER` is refused to anyone else), the files after it do not get one.

Not chosen: making the Docker bootstrap account something other than a superuser. PostgreSQL's image always creates `POSTGRES_USER` as one, and existing volumes would not re-run an init script. The login `migrate` uses therefore stays what the deployment has. What changes is what a migration runs as. A deployment that wants `init` to hold no superuser credential gives `waypoint_migrator` a password and names it in `MIGRATION_DATABASE_URL`; that path is tested.

Row-level security now applies to migrations, which it never did under a superuser. The owner is subject to `FORCE ROW LEVEL SECURITY` and no policy names it, so a backfill would update nothing and report success. `Migrator` sets `row_security = off` beside the role, which makes PostgreSQL refuse such a statement instead of filtering it. A migration that must backfill a forced table lifts the force around the statement.

### 9. Reference scope is checked in the application, through Identity's contract

Reference data is one in-memory snapshot for every actor. There is no row for row-level security to hide, so the scope half is an explicit check, in `referencedata/application` (rule 3), before anything is returned.

It asks `IdentityQuery.scopeOf` and `driverVehicleOn`. The alternative was to call `app.actor_has_depot` as `waypoint_ref`, which needs the reference role to read the iam scope tables. Migration 004 revokes exactly that on purpose ("the arrow points one way"), and Identity is already the one module every application layer may call synchronously.

| Read | Allowed when the actor |
| --- | --- |
| `/outlets?depot=`, `/vehicles?depot=&date=` | is scoped to the depot |
| `/outlets/{id}` | is scoped to the outlet, or to its depot, or drives today a vehicle of its depot |
| `/vehicles/{id}` | is scoped to its depot, or drives it today |
| `/version`, `/calendar/{date}` | holds `reference:Read`: the same for every depot |

"Today" is the operating day by the server's clock, as for a delivery (EXE-13) and an issue (R-ISS-07). A driver is not given the depot's lists: their scope is a vehicle, and the run sheet needs single outlets. A refusal is 403 with an audit row and `waypoint_scope_denied_total`. An unknown outlet stays 404; outlet ids are dataset identifiers, not secrets.

`ReferenceQuery`, the contract other modules call inside their own commands, stays unscoped: those callers have already decided what the actor may reach.

### 10. The store manager gets `reference:Read`, on outlets and calendar days only

Without it the store header and the "this date rolls forward" notice (D-I) never appear against a real backend. It was unsafe while reads were unscoped, since the action on `*` would open every outlet and the fleet to every store. With decision 9 an outlet read stops at the manager's own outlet, and the policy statement names `wpt:ref:outlet:*` and `wpt:ref:calendar:*` only. A new default policy version, by migration, as the earlier role policy changes were.

### Pull request

One branch, `fix/identity-owner-and-reference-scope`, one pull request into `dev`, closing #5.

