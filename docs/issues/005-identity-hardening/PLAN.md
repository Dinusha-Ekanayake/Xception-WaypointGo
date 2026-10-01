# Issue #5: Identity and auth hardening — plan

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

**Not done: a non-superuser `waypoint_migrator` that owns the tables.** In the Docker deployments the owner is the image's bootstrap user, which is a superuser and cannot be reassigned with `REASSIGN OWNED`. Moving ownership means altering every object one by one, re-issuing every `ALTER DEFAULT PRIVILEGES` for the new owner, and granting it admin on thirteen existing roles, on live volumes, with other branches adding migrations this week. A mistake there stops every future deploy. It stays open on issue #5; what ships is the half that removes the owner's credentials from the running process.

### 7. Expand, then contract, for the session key

`iam.sessions.session_token` is renamed `token_hash` and existing rows are hashed in place. The old backend keeps serving while `init` runs, so for the seconds between the migration and the container swap it cannot resolve a session and answers 500 on authenticated requests. Deploys already have a comparable gap at the swap. Where `COOKIE_SECURE=1` the cookie is also renamed, so everyone signs in again once.

## Pull request breakdown

One branch, `fix/identity-hardening`, one pull request into `dev`, commits in this order so each compiles and passes:

1. Plan (this file).
2. Authentication: lockout, throttle, cookie, session hashing, Origin.
3. Authorization: generation, re-check, after-commit, unknown kind, `implemented`, policy and version-guarded commands.
4. Privilege changes and devices.
5. Database roles and deployment.
6. Contract implementation and `app.actor_drives`.
7. Registers, walkthrough, log.

## Out of scope, and who owns it

| Item | Owner |
| --- | --- |
| `waypoint_migrator` as a non-superuser owner | stays on #5, decision 6 |
| Filtering reference reads by depot scope | #5 follow-up: reference is served from one in-memory snapshot shared by every actor, and the consumers that need a scoped list (planning, ordering) already filter in SQL in their own schema |
| Admin console screens for devices, roles and policy | #22 |
| Driver row-level security using `app.actor_drives` | #12 |
