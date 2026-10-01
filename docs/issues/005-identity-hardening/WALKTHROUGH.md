# Issue #5 walkthrough: identity and auth hardening

What was built, where it lives, and how to check it. The reasons for each choice are in [PLAN.md](PLAN.md); the rules are R-IAM-18 to R-IAM-24 in [RULES-AND-POLICIES.md](../../architecture/RULES-AND-POLICIES.md), the cases are SEC-02 to SEC-27 in [EDGE-CASES.md](../../architecture/EDGE-CASES.md), and the lockout numbers are P-13 in [ASSUMPTIONS.md](../../architecture/ASSUMPTIONS.md).

## What exists, layer by layer

All paths are under `backend/src/main/java/com/waypoint/dispatch/`.

| Layer | File | What it does |
| --- | --- | --- |
| `identity/domain` | `auth/LockoutPolicy` | Pure: given recent failures, a threshold, a window and now, is this a lockout and for how long |
| `identity/application` | `LoginHandler` | Sign-in. A refused attempt is a value until the transaction commits, so its record and audit row are kept |
| | `LoginThrottle` | The three counters, the attempt rows, marking failures cleared |
| | `SessionRegistry` | Sessions by token hash, expiry on the injected clock, a write at most once a minute, revocation with a reason |
| | `PolicyGeneration` | Moves the generation inside the change's transaction, clears the local cache after commit |
| | `PolicyDecisionPoint` | The decision before the transaction, the re-check inside it, and the `implemented` gate |
| | `PolicyAdminUseCase`, `PolicyCommandHandlers` | Policy administration as five command kinds |
| | `AccountAdminUseCase`, `IdentityCommandHandlers` | `iam:ChangeRole`; versions on scope and assignment commands; the last-dispatcher rule |
| | `DeviceRegistry`, `DeviceCommandHandlers` | `iam:RegisterDevice`, `iam:RetireDevice`, and the device list |
| | `IdentityDataQuery` | Implements the `IdentityQuery` contract |
| `identity/infrastructure` | `SessionTokens` | Mints tokens and hashes them |
| | `PolicyCache` | Statements per actor, each entry tagged with its generation |
| | `JdbcPolicyRepository` | Reads and moves the generation; reads the implemented actions |
| `identity/web` | `SessionCookie` | Name, flags and reading of the cookie |
| | `AuthController`, `PolicyAdminController`, `DeviceController` | Sign-in and out; read-only policy and device lists |
| `platform/web` | `OriginGuardFilter` | Refuses a cross-site state-changing request |
| `platform/messaging` | `CommandBus`, `CommandAuthorizer` | Unknown kind is 403 and audited; the second authorization inside the transaction |
| `platform/db` | `Database.afterCommit`, `Migrator` | Work after commit; migrations on the owner's own connection, and the runtime login's password |
| `platform/config` | `DataConfig`, `AppProperties` | The pool runs as `waypoint_app`; new settings |
| `referencedata/application` | `ReferenceBootstrap.refreshIfStale` | Picks up a reference version another process published |

Schema: `migrations/20261001T2200_iam_auth_hardening.sql`.

## Flows

### Sign-in

`POST /api/session` → `OriginGuardFilter` → `AuthController` → `LoginHandler.login`. One serializable transaction as `waypoint_iam`: check the three counters (429 with `Retry-After` if any tripped), look the account up, verify the password, check the device, record the attempt, mark this pair's earlier failures cleared, revoke the session the browser already held, issue the new one, write the audit row. A wrong password returns a null token from that transaction instead of throwing, so the failed attempt and its audit row commit; the 401 is thrown afterwards. Two concurrent attempts that both count and both insert cannot both commit under serializable isolation, which is what closes the check-then-insert gap.

The address is `request.getRemoteAddr()`. Tomcat rewrites it from `X-Forwarded-For` when the peer is a private-network proxy (`server.forward-headers-strategy=native`); nginx sets that header from the address Cloudflare reports, and the Next proxy passes it on.

### A command

`POST /api/commands` → `CommandBus.dispatch`:

1. No handler for the kind: audit, 403.
2. `PolicyDecisionPoint.denyReason`: the action must be marked implemented; then policy. A denial is audited here.
3. Open the transaction as the handler's module role.
4. `denyReasonInTransaction`: read `app.policy_generation()` on the command's own connection, evaluate against the statements cached for that generation, reloading on a mismatch. A denial throws, everything rolls back, and the bus audits it afterwards.
5. Receipt check, handler, receipt, audit, commit.

### A change to what someone may do

Any of `iam:CreatePolicy`, `iam:SetDefaultPolicyVersion`, `iam:AttachPolicy`, `iam:DetachPolicy`, `iam:ChangeRole`, `iam:GrantScope`, `iam:RevokeScope`, `iam:DisableUser` calls `PolicyGeneration.advance()` inside its transaction. That increments `iam.policy_generation` and registers a clear of this process's cache for after the commit. Every other replica notices on its next decision, because each decision starts by reading the generation. Role change, scope revoke, disable, password reset and device retirement also delete the affected sessions in the same transaction, counted in `waypoint_session_revoked_total{reason}`.

### Database logins

`migrate` opens its own connection from `MIGRATION_DATABASE_URL` (or `DATABASE_URL` when that is unset), applies the files, and, when the two URLs name different logins, runs `ALTER ROLE waypoint_app LOGIN PASSWORD ...` with the password from `DATABASE_URL`. The pool then logs in as `waypoint_app`. Every pooled connection also opens with `SET ROLE waypoint_app`, which is what makes a login as the owner behave the same way in development and CI.

## Running and verifying it locally

```sh
cd backend
TEST_DATABASE_URL=postgresql://waypoint:local-testing-only@127.0.0.1:5432/waypoint_test mvn verify
```

338 tests. The ones written for this issue: `IdentityHardeningIntegrationTest` (21), `LockoutPolicyTest`, `PolicyCacheTest`, `SessionCookieTest`, `OriginGuardFilterTest`, and two cases in `CommandBusTest`.

The deployment path was rehearsed on a local PostgreSQL 16: a database built and signed in to with the `dev` build, then the new build's `migrate import-reference demo-accounts` with separate owner and runtime URLs, then the new backend started as `waypoint_app` alone. A session opened before the migration still resolved afterwards; the forwarded address was the one recorded; the ninth wrong password got 429.

To try it by hand against a running backend:

```sh
# nine wrong passwords: eight 401, then 429 with Retry-After
for i in 1 2 3 4 5 6 7 8 9; do
  curl -s -o /dev/null -w '%{http_code} ' -X POST localhost:8080/api/session \
    -H 'content-type: application/json' -d '{"email":"driver@waypoint.local","password":"nope"}'
done
# a cross-site POST: 403
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/session \
  -H 'Origin: https://evil.example' -H 'content-type: application/json' -d '{}'
```

## What changes for callers

- **Cookie.** Behind HTTPS it is now `__Host-waypoint_session`, so everyone signs in once more after the deploy. Over plain HTTP it is still `waypoint_session`, and the backend must be told `COOKIE_SECURE=0`.
- **Unknown command kind** is 403, not 404.
- **`iam:GrantScope`, `iam:RevokeScope`, `iam:AssignDriver`** need `expectedVersion`, the account's `rowVersion`. **`iam:EndDriverAssignment`** needs the assignment's, which the assignment list now carries.
- **Policy writes** moved from `POST/PUT/DELETE /api/policies/...` to command kinds. `GET /api/policies` stays and carries `rowVersion`.
- **`deviceId` at sign-in** must be a registered device or be left out.

No screen on `dev` used any of the changed calls.

## Decisions and where they are recorded

| Decision | Recorded in |
| --- | --- |
| Unknown device is refused, not auto-registered | PLAN decision 1, R-IAM-22, SEC-21 |
| Generation counter, not `LISTEN/NOTIFY` | PLAN decision 2, R-IAM-21, SEC-12, SEC-26 |
| Lockout numbers | PLAN decision 3, P-13, R-IAM-20 |
| The re-check re-evaluates | PLAN decision 4, SEC-03 |
| What each command's version guards | PLAN decision 5, R-IAM-23 |
| The pool is always `waypoint_app` | PLAN decision 6, SEC-15, AGENTS.md |

## Known gaps and who owns them

| Gap | Owner |
| --- | --- |
| The owner of the tables is still the image's superuser. A non-superuser `waypoint_migrator` needs every object's ownership and every default privilege moved on live volumes (PLAN decision 6) | #5, left open |
| Reference reads are not filtered by the actor's depot. Reference is one in-memory snapshot for all actors, and there is one depot in the data | #5 follow-up |
| For the seconds between `init` and the container swap on the first deploy of this change, the old backend answers 500 on authenticated requests (the session column was renamed under it) | one-off, PLAN decision 7 |
| A calendar override made on one replica is not seen by another until it restarts; only a new reference version is picked up | reference data |
| No screens for devices, role change or policy commands | #22 |
| Driver row-level security using `app.actor_drives` | #12 |
