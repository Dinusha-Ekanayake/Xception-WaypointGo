# Waypoint permission system: AWS IAM semantics and implementation plan

Status: implementation in progress. The first permission editor slice now has a catalogue-backed access read, reasoned/versioned persona and member settings, an action grant limit, and a protected Super admin role. The later boundary, delegation, strict policy language, cross-instance revocation and policy-route migration stages below remain open. Prepared 2026-10-01 against this checkout. Parent issue: #22.

Read with [the complete permission inventory](PERMISSION-INVENTORY.md), [the admin console plan](PLAN.md), [MODULES](../../architecture/MODULES.md), [rules](../../architecture/RULES-AND-POLICIES.md), and [edge cases](../../architecture/EDGE-CASES.md). The Challenge Booklet pages 4-7 and 12 establish the four operational personas and their workflow. Admin and Super admin are application requirements beyond the booklet.

## 1. Decision and compatibility promise

Build an in-process identity-policy system with the documented AWS IAM behavior for the supported policy subset: default deny; identity-policy grants combine; an explicit deny overrides every allow; a permissions boundary limits grants and grants nothing itself. Persona policies, direct member policies and boundaries remain separate objects. PostgreSQL row scope and business invariants remain additional Waypoint requirements.

This is application authorization using AWS IAM semantics. AWS IAM itself manages AWS resources; Waypoint action names and `wpt:` resource identifiers do not become AWS actions or ARNs. Do not advertise full AWS policy-language or API compatibility. Unsupported constructs must fail validation, never silently disappear.

### AWS concepts mapped accurately

| AWS concept | Waypoint implementation |
| --- | --- |
| User | One `iam.users` account, authenticated through existing opaque server sessions |
| Group | A persona membership. Keep existing `iam.roles` / `iam.user_roles` table names for compatibility; document their group-like semantics |
| Identity policy attached to a group | Managed persona baseline inherited by every member |
| Policy attached directly to a user | Explicit member grant or restriction; does not replace inherited policies |
| Permissions boundary | One selected, versioned maximum-permission policy per account; evaluated separately from grants |
| Managed policy version/default | Immutable documents and one current default, with recorded revision and provenance |
| Resource / action / condition | `wpt:` identifier / catalogue action / trusted request attributes |
| Policy simulator | Server evaluation of an example member/action/resource without performing the business operation |
| Assumable role and trust policy | Deferred. The persona switcher changes the displayed workspace, not the authenticated principal or permission union |
| Resource policies, SCPs, RCPs, STS, AWS access keys | Outside this release; reject corresponding policy constructs rather than approximating their evaluation |

AWS groups and assumed roles differ. Members inherit group policies; assuming an AWS role creates a session using that role's permissions. Our existing multiple-persona membership model corresponds to the former. No nested persona groups. Common policies are explicitly attached to eligible personas; there is no magic implicit all-users group.

Sources checked 2026-10-01:

- [AWS evaluation logic](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_evaluation-logic.html): grant union, boundary intersection and explicit-deny priority.
- [AWS permissions boundaries](https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies_boundaries.html): boundaries cap authority; resource-policy exceptions are outside our supported model.
- [AWS groups](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_groups.html) and [roles](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles.html): persona/group versus assumed-role distinction.
- [Explicit versus implicit denial](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_evaluation-logic_AccessPolicyLanguage_Interplay.html).
- [Condition combination](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_condition-logic-multiple-context-keys-or-values.html) and [condition operators](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements_condition_operators.html).
- [Action matching](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements_action.html), [policy grammar](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_grammar.html), [managed versions](https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies_managed-versioning.html), [simulation](https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies_testing-policies.html).

## 2. Current state and concrete gaps

The inventory derives all catalogue rows, implemented flags and seeded effective persona grants from root migrations, including subsequent updates. It is repository evidence, not an inspection of a deployed database: administrators can already change policies at runtime.

| Existing component | Keep | Required change |
| --- | --- | --- |
| `identity/domain/policy/PolicyEvaluator.java` | Pure default-deny and explicit-deny precedence | Separate identity and boundary evaluation; retain policy/version/Sid provenance |
| `Condition.java`, `ConditionOperator.java`, `Pattern.java` | Pure matching with supplied context | Correct supported AWS matching semantics; typed input validation and bounded document size |
| `identity/infrastructure/PolicyDocumentParser.java` | JSON parsing outside domain | Reject malformed conditions, missing Resource, unsupported keys, invalid values and duplicate JSON keys; current parser can accept missing Resource as all resources and skip malformed condition structures |
| `JdbcPolicyRepository`, `PolicyCache` | Published-version loading | Preserve policy identity; distinguish boundaries; eliminate stale authorization across instances |
| `PolicyDecisionPoint` | One decision point | Load trusted resource context and boundary; current command path supplies only actor/time context |
| `PolicyAdminController`, `PolicyAdminUseCase` | Existing policy concepts and compatibility paths | Route mutations through versioned, idempotent handlers; current create/version/default/attach/detach methods bypass CommandBus |
| `platform/messaging/CommandBus` | Receipt, audit, command registry | Recheck authorization inside each write transaction and before returning a replay; current policy check precedes `execute()` |
| `AccountAdminController` | Account lookup | Separate read permission: currently reads require `iam:UpdateUser`; assignments require `iam:AssignDriver` |
| Account mutations | Existing create/update/disable/reset/scope/assignment bodies | Protected-target checks, delegation controls, versioned memberships and scopes, privileged-account race guards |
| SQL scope tables and module roles | RLS, actor identity, module isolation | Explicit administrative scope; do not infer global scope from an admin label |
| `sync.operations` | Owner-only RLS | Keep owner-only behavior for this release. Existing dispatcher sync grants do not provide cross-user review; that requires a distinct later module design |
| Admin frontend | Existing people, persona and member screens | Server-backed catalogue, effective decisions and saving; replace ambiguous binary permission controls |

Specific mismatches: current `StringNotEquals` applies OR across excluded values; AWS negated matching uses NOR. Current missing-key behavior is always false, while AWS negated operators can match absence. Action matching is currently case-sensitive and only `*` is supported. Current `Version` values are revision-like dates rather than the AWS language identifier. Legacy Auditor allows `iam:ReadPolicy` but denies `iam:*`, so its effective policy read is denied. `iam:DetachPolicy` is catalogued but detach currently checks `iam:AttachPolicy`. `iam:EndDriverAssignment` is a command kind guarded by `iam:AssignDriver`, not a separate catalogue action. See inventory for these distinctions.

## 3. Policy categories and persona defaults

Use explicit action arrays in shipped baseline policies; an added catalogue action should not silently expand those baselines. Advanced wildcard semantics still follow the supported language. Proposed defaults and optional grants appear for every action in the inventory.

| Managed policy family | Recipients | Content and ownership |
| --- | --- | --- |
| CommonSelf | All active personas | Own notifications/subscriptions and own effective-access lookup; profile/session lifecycle only where an actual endpoint/action exists |
| FieldSync | Loader, Driver, Store manager | Submit, acknowledge and read own queued operations; each inner command still needs its own authorization |
| DispatcherPlanning | Dispatcher | All existing planning actions, order reads/cutoff, vehicle day status, issue resolution, progress and forecast reads |
| LoaderDock | Loader | Loading reads/start/check/shortfall/interchange request/release, plan/reference reads, own-scope issues |
| DriverRoad | Driver | All existing delivery actions, plan/reference reads, own-scope issues and FieldSync |
| StoreOrderingAndReceipt | Store manager | Order place/amend/cancel/read, receipt actions, delivery/catalogue reads, own-scope issues |
| AdminPeople | Admin, Super admin | Operational account, membership, scope and device administration within delegated limits |
| AdminInvestigation | Admin, Super admin | Applicable operational, catalogue, model, policy and audit reads; no automatic operational write grants |
| AdminReference | Admin, Super admin | Reference maintenance, calendar and vehicle administration, subject to publication and scope rules |
| ProtectedAccessGovernance | Super admin | Privileged-member governance, boundary/delegation policy management, advanced policy publication |
| MemberException | One account | Exact actions/resources/conditions; reason and optional expiry; explicit Allow or Deny |
| AccountBoundary | One account | Maximum permitted actions/resources; does not grant any permission |

The common baseline does not grant `reference:Read` to everyone: Store manager currently lacks it and the broad read contract needs scope review. Nor does it grant generic account/profile editing or conflict resolution. Sign-in, own-session GET and sign-out are authentication lifecycle operations, not new configurable IAM grants; do not let removal of a persona prevent safe sign-out.

### Three states, not an ambiguous Off

| Persona control | Meaning |
| --- | --- |
| Allow | Add a managed identity-policy Allow |
| Not granted | No statement for this action in this managed policy; an authorized direct policy or another persona may grant it |
| Explicit deny | Add Deny, which blocks direct and inherited Allows |

Member choices are Inherit (no member statement), Allow, Explicit deny. Inherit does not guarantee an Allow; show the combined result. Removing an Allow from one attachment does not remove grants from other attachments. Deny cannot be defeated by a more specific member Allow. Default denied and explicitly denied must be visibly different.

This supersedes the binary persona-Off-as-Deny proposal in PLAN.md section 5 for the planned replacement. Existing stored denies are never silently removed or reinterpreted. Multiple personas contribute grants together; the workspace selection cannot conceal which grants are active.

### Boundaries and delegation are different

An account boundary caps what the target can receive. A delegation policy caps what an administrator can grant or revoke for others. Possessing an action does not automatically confer permission to delegate it. Admins may administer operational permissions without having permission to publish plans themselves.

Select exactly one effective boundary for each account at provisioning. Operational boundary templates include that persona's defaults plus the inventory's eligible member exceptions. For a multi-persona account, a protected operation creates/selects a reviewed combined boundary; membership changes never silently widen it. Boundary absence in AWS is unrestricted by that layer, but Waypoint provisioning requires a boundary: a missing mandatory assignment is a configuration failure that denies authorization. An empty boundary grants nothing. Only ProtectedAccessGovernance can widen/change/remove a boundary; normal UI cannot remove the required assignment.

Admin boundary excludes privileged governance and operational execution writes; optional warehouse/model/replay administration requires named delegation. Super admin has protected management authority but is still evaluated against explicit policy, scope and business invariants. There is no root-user bypass.

Examples: a dock lead may receive `loading:Handover`; a senior dispatcher may receive `calendar:Override`. A second outlet is a versioned scope grant, not a permission-policy edit. A replacement vehicle is a dated driver assignment, not `delivery:*` on every vehicle. Temporary scope requires validity fields on scope grants; a date condition on a member Allow cannot expire an unrelated inherited grant.

## 4. Supported evaluation specification

1. Authenticate and establish current active actor/session/device from server records. Reject disabled, expired or revoked sessions.
2. Resolve the canonical catalogue action, declared resource types and trusted resource attributes. Reject unregistered actions and invalid action/resource pairs. An unimplemented operation cannot execute even when a future grant exists.
3. Load active persona and direct policy attachments, exact default versions, mandatory boundary, scope and authorization revision. Evaluate attachment validity with server time, not the device timestamp.
4. Evaluate identity statements: matching explicit Deny wins; otherwise any matching Allow is needed.
5. Evaluate the boundary independently: matching Deny wins and a matching boundary Allow is required. Boundaries never add grants.
6. Require applicable SQL scope and application business invariants. A malformed required context or inaccessible resource fails closed; an out-of-scope resource does not become a general collection wildcard.
7. Record the decision, reason, action/resource, policy/version/Sid provenance and authorization revision. Mutations additionally record actor, command ID and business reason.

Formula for the supported identity-only model: `(common + all persona + direct grants) INTERSECT boundary INTERSECT scope`, minus all matching explicit denies. Plus authentication, endpoint availability and business validation. This does not describe AWS resource-policy exceptions because resource policies are unsupported here.

### Language contract and migration

Introduce a policy-language profile in persistence, distinct from both the JSON `Version` and the policy's numerical version. Keep `legacy-v1` only during migration; new documents use `waypoint-iam-v2` and JSON `Version: 2012-10-17` for the supported AWS syntax. A published v2 document is never evaluated with v1 rules.

Supported: Effect, optional Sid, Action, Resource, Condition; Statement object or array; action/resource string or array; `*` and `?`; case-insensitive action matching with canonical catalogue spelling; case-sensitive resource and ordinary StringEquals matching. Generate an internal statement index for provenance if Sid is absent; reject duplicate provided Sids as a Waypoint validation restriction. Require explicit Resource and nonempty valid values. Policy documents are identity policies, so reject Principal, NotPrincipal, NotAction, NotResource, variables and unsupported operators in this release. Reject unknown elements and non-string action/resource values.

Supported conditions: StringEquals, StringNotEquals, StringLike, StringNotLike, DateLessThan, DateGreaterThan, DateLessThanEquals, DateGreaterThanEquals, Bool and Null. Scalar request attributes only initially; reject set operators and IfExists until implemented with AWS conformance fixtures. Distinct operators and keys combine with AND; positive policy values combine with OR; negated string values use NOR. Missing keys follow the documented operator semantics (positive comparisons false; negated string comparisons true; Null tests presence). Date/boolean literals are validated when published. Conditions are reevaluated per request even when immutable parsed policies are cached.

The existing R-IAM-07 blanket missing-key rule conflicts with v2. PR1 must amend it to operator-specific AWS semantics plus required-context validation before activation. For security-critical attributes, the action schema requires the context provider to supply them; failure is a context error, not an opportunity to satisfy a negative condition. Negated Allow policies that rely on attribute presence require an explicit Null:false check. This validation requirement is an additional Waypoint restriction.

Trusted context keys: `wpt:actorId`, `wpt:now`, `wpt:resourceDepot`, `wpt:resourceOutlet`, `wpt:resourceOwnerId`, `wpt:serviceDate`, `wpt:deviceId`, `wpt:targetPrivilege`. Global values come from authentication/server clock; resource values come from the owning module's authorized query or command context provider. Membership/target privilege never comes from an unchecked request body. Persist schema/type/provider information per supported condition key. Do not import another module's domain or query its tables from IAM.

Example additional member grant (illustrative trip identifier; not an executed policy):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "TemporaryDockLead",
    "Effect": "Allow",
    "Action": "loading:Handover",
    "Resource": "wpt:loading:trip:example-trip-id",
    "Condition": {"DateLessThan": {"wpt:now": "2026-10-10T00:00:00Z"}}
  }]
}
```

The boundary must also allow Handover, and the trip must remain in the member's authorized depot. Once this grant expires, another applicable grant could still allow Handover; the simulator must explain all contributing sources.

## 5. Resource and SQL scope contracts

| Family | Required authorization target and scope |
| --- | --- |
| Orders | Existing `wpt:order:order:<id>`, `wpt:order:outlet:<id>`, `wpt:order:depot:<code>`; outlet/depot SQL scope; placement checks the requested outlet inside transaction |
| Reference | Preserve `wpt:ref:version:*`, `depot:<code>`, `outlet:<id>`, `vehicle:<id>`, `calendar:<date>`; do not rename ref to reference in stored resources |
| Planning | Plan ID for existing plans, depot/day context for generation; owning module enforces depot scope and immutable publication rules |
| Loading | Trip/session target; depot and loading ownership checks; list results projected from authorized trips |
| Delivery | Stop/trip target; dated driver assignment for writes; store-manager reads projected to own outlet only |
| Receipt | Receipt/delivery target; outlet scope and receipt state checks |
| Issues | Issue target; linked depot/outlet/assignment scope; creation validates target references before assigning scope |
| Notifications | Own recipient inbox and own device subscription; no ability to select another actor in payload |
| Sync | Own operation/device; cross-account triage deferred even for admins until a dedicated scope design exists |
| IAM | User/policy/attachment/boundary/delegation target; SQL target visibility and protected-account checks |
| Warehouse/model/audit/replay | Explicit depot or global administrative scope; ownership module applies read filtering and payload redaction |

Future resource spellings must be finalized in each catalogue row before its handler ships. Do not imply those handlers already exist. Expand catalogue metadata to include supported resource patterns, collection resource, required/optional condition keys, read/write classification, implementation status, delegation class, module owner and dependency actions. Each endpoint checks its own actions; there are no hidden inherited action dependencies in a UI toggle.

Collection authorization is distinct from individual resource authorization. A concrete resource grant must not authorize an unscoped collection read. List queries apply SQL scope and any supported policy resource restrictions, with keyset pagination and accurate counts. For the first release, only structured scope predicates and validated exact-resource restrictions may reach list queries; reject arbitrary conditions for list actions if they cannot be translated/enforced in SQL. Do not fetch a broad page and filter it afterward.

## 6. Administration and escalation controls

Every access mutation checks: actor's requested administration action; target visibility; actor's delegation envelope; target's resulting boundary and scope; protected target invariant; expected revisions. The check uses the complete proposed result, including all attachments and affected members of a persona policy.

Ordinary Admin can create/manage operational accounts within delegated scope, grant approved operational actions, and edit scoped managed persona/member policies. Changes to shared persona policies need authority over every affected account; a depot admin cannot edit a global Loader policy. Supply depot-scoped attachments/templates for local changes. Admin cannot modify their own authority, protected policy/boundary/delegation records, admin credentials, privileged memberships, or grant access outside their delegation envelope. Removing a Deny, disabling an attachment, narrowing an exclusion and moving an old version to default all receive the same escalation check.

Avoid attempting general JSON-policy implication. The ordinary admin editor produces a constrained form: explicit catalogue actions, structured allowed scope, supported presence/expiry conditions and approved templates. The server proves subset relationships for that form and refuses unprovable edits. Only Super admin can author advanced policies; mandatory boundaries and protected-target invariants still apply. Wildcard attachment and new action registration cannot bypass delegation: ordinary-admin templates use explicit actions and versioned envelopes.

Add `iam:ManagePrivilegedAccounts` as an extra required capability for any create/promote/change-role/update/reset/disable/scope operation targeting admin or super_admin. `iam:ManageDelegation` and `iam:SetBoundary` govern protected delegation/boundary changes. Never authorize these merely by finding the string super_admin in a frontend role list. A protected governance invariant ensures these grants cannot be delegated by ordinary admins, even through arbitrary iam:*.

Bootstrap the first Super admin through a documented host operation with operator identity, reason and audit. Never auto-promote existing admins. Lock and validate the active privileged-account set for last-super-admin checks, covering simultaneous disable, demotion, session/credential changes and removal of usable governance access. Preserve existing self-disable prohibition; add the existing plan's last-dispatcher guard with a defined eligible dispatcher count. Require fresh server-authenticated credentials for privileged governance; MFA can be a later authentication feature but must not be presented as already built.

## 7. Persistence and module ownership

All new authorization tables belong to IAM; new surrogate IDs use UUIDv7. Policy heads, assignments and scope aggregates use row_version; immutable versions never mutate. Retire attachments instead of deleting evidence. Persist snapshots/provenance sufficient to explain historical decisions without storing passwords, tokens or payload personal data.

| Schema change | Purpose |
| --- | --- |
| Extend `iam.policies` | Policy kind/purpose, managed ownership, protected marker, row_version, lifecycle state |
| Extend `iam.policy_versions` | Language profile, content fingerprint; keep old documents immutable |
| Extend policy attachments | Active/retired state, validity start/end, actor/reason, row_version; unique active relationship |
| `iam.account_boundaries` | One active boundary policy per account with revision and history |
| `iam.delegation_grants` | Explicit administrator grantable actions, target classes, permitted scope, validity, revision |
| Extend membership/scope state | Versioned active/retired membership; expiry-capable scope grants and scope aggregate revisions |
| `iam.authorization_revision` | Transactional revision for all changes affecting access; no TTL-only revocation |
| Extend action/context metadata | Resource/condition contracts, implementation flags and UI labels |
| Decision provenance | Extend platform-owned audit schema through a platform migration; IAM publishes value-only evidence, not direct audit-table access |

Use expand/backfill/validate/contract migrations. Existing active attachment and scope queries must understand lifecycle/validity before retirement is enabled. Add indexes on every new foreign key and lookup path. Keep SQL role grants minimal; do not grant operational modules blanket access to IAM policy tables.

Pure domain additions: `EffectivePermissionPolicy`, `BoundaryPolicy`, `DelegationPolicy`, policy-language validation values and decision provenance. Application: versioned policy/attachment/boundary/delegation handlers; capability and simulation queries; trusted context orchestration. Infrastructure: parser, repositories and immutable-version cache. Web: request validation and delegation to application handlers. Publish authorization DTO/query contracts through `identity/contract`; platform depends on its existing authorization seam, and business modules never import IAM domain classes. Mirror all DTOs in `frontend/src/shared/domain/identity.ts`.

## 8. Transactions, revocation and cache correctness

Do not simply move the current PDP invocation inside `Database.asModule`: its IAM transaction can join the caller and switch `SET LOCAL ROLE`, violating module isolation. Implement a platform-owned authorization consistency guard plus an IAM contract query using the existing separate read transaction pattern.

For this small deployment choose a single transaction-scoped shared/exclusive PostgreSQL advisory lock for access consistency. Authorized writes acquire the shared guard, load current session/policy/boundary/scope revision through authorized seams, then execute and commit. Every access-changing transaction acquires the exclusive guard BEFORE reading anything it will use for authorization or mutation. This includes policy/default/attachment/delegation/boundary, role/scope, disable/reset, and vehicle assignment changes. No shared-to-exclusive upgrade in the same command. Classify such commands in trusted handler metadata, not client payload. The global lock is an intentionally simple initial trade-off; measure wait time.

Acquire the guard before transaction snapshots used for authorization are established; verify this against PostgreSQL SERIALIZABLE snapshot behavior. If a snapshot was acquired while waiting, restart before evaluating or use a guard coordinator outside the command transaction that establishes the correct order. PR3 must demonstrate this using two real connections; an advisory-lock call alone is not proof of freshness. IAM reads in a separate transaction do not acquire the guard again and must not deadlock with the suspended caller. Define lock ordering for reference/driver-assignment and sync paths before implementation.

No access revocation that has committed may be ignored by a newly authorized command. In-flight operations holding the shared guard may finish before the revocation commits; document this ordering. Reauthorize idempotent receipt reads under current access to avoid replaying a sensitive previous result after revocation. Authorization denial is audited after rollback through the existing standalone path.

Initially remove mutable per-actor policy caches from security decisions. Cache only immutable parsed versions by ID/hash, load current attachments/boundary/revision from the database, and clear the whole local cache on observed revision changes after commit. This obeys whole-cache invalidation without depending on an asynchronous event to revoke access on another replica. Any later optimization needs cross-instance and stale-load race tests. Expiry uses request-time server clock; do not cache final decisions. Read requests also obtain a consistent current authorization snapshot before their scoped read.

Offline queues retain evidence but convey no old authority. Every replay checks current account, assignment and permission. Revoked writes become a visible rejected outcome; preserve the local record for authorized resolution. Admin and Dispatcher remain online-only; no queued IAM administration.

## 9. APIs and UI delivery

New read/query contracts (proposed paths):

| Contract | Required authority/result |
| --- | --- |
| `GET /api/access/catalogue` | `iam:ReadActionCatalogue`; labels, action/resource/condition schema, implemented/editable state |
| `GET /api/access/me` | Own authenticated account only; effective capability summaries with contextual/unknown state |
| `GET /api/accounts/{id}/access` | `iam:ReadEffectiveAccess`; scoped target, source policies, boundary, revisions and locked reasons |
| `POST /api/access/simulate` | `iam:SimulatePolicy`; no mutation; validates target visibility and supplied hypothetical context, clearly labels simulation |
| Policy version/detail and attachment reads | `iam:ReadPolicy`; keyset pages, content, revisions, attachment history and visibility |
| Persona access preview | Scoped `iam:ReadEffectiveAccess`; before/after decisions and server-computed affected counts |

Simulation is advisory, never an authorization token. It uses the same evaluator; context supplied for hypothetical testing is never reused as trusted context on real commands. Do not return another person's sensitive resources or policy details to an ordinary member.

All writes use `/api/commands` with command ID, expected_version and reason: policy create/new-version/default, attach/detach, member overrides, membership, boundary and delegation, plus existing account/scope commands. New versions/default heads and target access state are separate revisioned aggregates where appropriate; require the relevant expected revisions for each mutation. A single persona-editor save may create a version and move its head atomically in one policy command. Independent member creation and scope assignment remain explicit resumable steps; never silently mix multiple aggregates into one broad transaction.

Maintain old API routes during deprecation by adapting to the same handlers and required command envelope. Existing clients lacking command IDs/revisions must be upgraded through an explicit version/deprecation window; do not keep a permissive old mutation path. Split CreatePolicyVersion and SetDefaultPolicyVersion actions from current CreatePolicy checks; use DetachPolicy for detach. Account reads get their own action. Compatibility mappings are listed in the inventory.

UI tabs: General access, Persona policies, Member exceptions, Scope, Boundary, Effective access/history. Show Inherit/Allow/Explicit deny, source policy/version, expiry, concrete scope and blocked reason. Planned actions are visible as unavailable and cannot be saved as operational permissions. Permission values depend on server results, not role strings. For a broad capability with mixed resource outcomes show Conditional, not a green global Allow. Persona selection is navigation only. Preserve advanced statements when changing a managed toggle; review before/after and affected count; keep drafts after conflict; acknowledge saves only after server success.

## 10. Migration and rollout

1. Inventory live policies/attachments/persona memberships and compare them with repository defaults using an authorized deployment operation. Do not assume migrations describe live grants. Produce before/after decisions for representative scoped resources.
2. Add schema and strict v2 evaluator alongside the legacy language profile. Parse and flag legacy malformed/missing-Resource/unknown-condition policies; require reviewed explicit replacements. Do not silently rewrite them.
3. Seed v2 baseline and boundary templates as new versions with explicit actions. Shadow-evaluate; old enforcement remains until coverage and privilege checks pass. Block cutover on unexplained expanded grants.
4. Bootstrap Super admin and boundaries; introduce protected-target checks BEFORE exposing privileged account creation or policy controls. Remove broad iam:* from ordinary Admin through reviewed policy versions.
5. Migrate every write route, CLI and sync path to the same guard and required revision checks. The trusted bootstrap path is explicitly separate and audited; generic host account-create must not become an undocumented privileged bypass.
6. Migrate each account/attachment atomically to the new profile. Avoid mixed-language effective statements on one account: convert its complete effective set together or keep it in legacy until ready. No user is automatically promoted during migration.
7. Retire Auditor only after existing assignments are resolved. Preserve investigative read access through an approved replacement; promotion to Admin requires Super admin authorization. Preserve original audit identities and old policy versions. Never copy Auditor's NeverWrite policy onto Admin.
8. Enable read-only effective-access UI, then editor saves. Complete the four-role workflow and administrative acceptance checks; deprecate old APIs and legacy evaluator after a documented window.

Recovery uses forward policy versions/default movement and reviewed attachments; never undo SQL migrations or automatically restore broader legacy access. Keep a tested host recovery procedure if governance access is accidentally lost. New permissions introduced after this inventory require a catalogue row, metadata, actual handler/read endpoint, updated inventory, boundary consideration and denied-scope test before marking implemented.

## 11. PR breakdown and acceptance gates

| PR | Deliverable | Gate |
| --- | --- | --- |
| 1 | Language contract, R-IAM rule amendments, action/resource/context metadata, strict parser and pure evaluator | AWS-derived fixture suite; reject unsupported constructs; tests for negative multi-values, missing keys, wildcard/case and boundary intersection |
| 2 | IAM migrations, versioned policy/attachment/boundary/delegation repositories and catalogue extensions | Fresh and upgrade database integration; immutable history; stale version and idempotency tests |
| 3 | Transaction guard, current-session/PDP reevaluation, immutable cache, old mutation adapters | Two-connection revocation/snapshot races, multi-instance cache tests, no module-role leakage |
| 4 | Protected governance, constrained delegation, ordinary account/scope and privileged handlers | Self-escalation, direct JSON, old routes, reset takeover, boundary removal, concurrent last-admin/dispatcher checks |
| 5 | Capability queries, scoped simulator, persona/member editor integration | Action-by-action coverage, SQL list scope, counts, read visibility, conditional decisions and conflict UX |
| 6 | Baseline migration, Auditor transition, controlled activation and walkthrough | No unexplained grant expansion; real four-role workflow; documented host recovery and release evidence |

Business module handlers absent today remain owned by their modules. IAM completion does not mark planning/loading/execution/receipt/issues/notifications/intelligence features implemented. The action inventory provides contracts for those teams.

Required cases to add to EDGE-CASES alongside actual tests (planned names below are not claims of existing coverage):

| Case | Expected behavior | Planned test / detection |
| --- | --- | --- |
| Persona has no grant; direct member grants | Allow only inside boundary and scope | `EffectivePermissionPolicyTest` / decision reason |
| Persona/member explicit deny competes with allow | Deny, with source | `EffectivePermissionPolicyTest` / explicit-deny counter |
| Boundary allows with no identity grant | Deny | `BoundaryPolicyTest` / default-deny counter |
| Required boundary missing | Fail closed | `BoundaryPolicyTest` / configuration alert |
| Negative condition contains two values or absent key | Match AWS semantics; required-context failure still denies | `PolicyLanguageConformanceTest` / invalid-context counter |
| Unsupported or malformed JSON narrows/omits condition | Reject publication | `PolicyDocumentParserTest` / rejected-policy counter |
| Admin attaches wildcard, removes deny, edits shared head or resets admin password | Reject privilege expansion or protected target | `DelegationAuthorizationIntegrationTest` / denied-governance audit |
| Revocation versus write or receipt replay on another instance | Correct serialization and current authorization | `AuthorizationRaceIntegrationTest` / guard wait and revision metrics |
| Two actors demote/disable final privileged accounts | At least one eligible account remains | `PrivilegedAccountConcurrencyTest` / rejected-last-account counter |
| Cross-depot list, direct ID, count or simulation | SQL scope/visibility denies without disclosure | `AccessScopeIntegrationTest` / denied-scope audit |
| Temporary grant expires while offline | Replayed command rejected if no other valid grant exists | `OfflineAuthorization.spec.ts` / rejected sync outcome |
| Switch workspace on a multi-persona account | Display changes; permission union remains explained | `PermissionEditor.spec.ts` / browser assertion |
| Disable/retire restriction attachment | Preview and enforce resulting expansion through delegation guard | `PolicyAttachmentIntegrationTest` / governance audit |
| Applied schema lacks one default policy version | Reject activation; unique index alone only enforces at most one | `PolicyHeadIntegrationTest` / policy integrity alert |

Before each cross-module import run ModuleBoundaryTest and frontend boundaries. For command/database PRs use a dedicated TEST_DATABASE_URL distinct from DATABASE_URL; unset-database skips are not verification. Run Maven tests/package with that database, frontend typecheck and production build, plus affected browser/role flows. Record exact results in the development log and write the implementation walkthrough before issue closure.

## 12. Decisions recorded by this plan

- Personas are group-like memberships; common + persona + member policies combine, boundaries intersect, explicit deny wins.
- Ordinary persona Not granted is absence of a grant. Hard prohibitions use Explicit deny. Member Allow never overrides Deny.
- Keep application sessions, module ownership, RLS and command envelopes; implement no AWS credentials/STS service.
- Retain catalogue names/resources; distinguish command kinds from authorization actions; strict supported AWS semantics require migration of legacy language.
- Boundaries and delegation are mandatory application controls; arbitrary JSON implication is not attempted for ordinary admins.
- Own-only sync remains until its separate cross-user authorization design is delivered.
- Existing R-IAM-07 and plan section 5 require the explicitly identified updates during implementation. This planning change does not claim those runtime semantics already exist.
- Exact deployment policy inventory, unknown GitHub owner handle, and real database migration verification remain implementation preparation items. These do not prevent reviewing this repository-based plan.
