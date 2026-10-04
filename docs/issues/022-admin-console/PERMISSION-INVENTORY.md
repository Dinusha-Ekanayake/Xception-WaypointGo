# Complete permission inventory for the IAM plan

Snapshot: 2026-10-01. Derived from root migrations and latest seeded policies; deployed custom policies were not queried. Read [IAM-PLAN.md](IAM-PLAN.md) for semantics and rollout.

## 1. Current catalogue and seeded effective grants

The original **75 catalogue actions** and new `iam:ManagePermission` action are listed below. **23** are marked implemented after the permission-settings migration; remaining entries are planned. Implemented means a handler or endpoint enforces that action, not complete UI or production verification.

A = Allow; D = explicit Deny; - = no grant. Results precede SQL scope. DS Dispatcher; LD Loader; DR Driver; ST Store manager; AD Admin; AU legacy Auditor. The Super admin policy is seeded by the new migration; its grants are described in IAM-PLAN.md and are omitted from this legacy comparison table.

| Action | Purpose | Availability | DS | LD | DR | ST | AD | AU |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `audit:Read` | Read the audit log | Planned | - | - | - | - | A | A |
| `calendar:Override` | Override a calendar day | Implemented | - | - | - | - | A | D |
| `delivery:CaptureProof` | Capture proof of delivery | Planned | - | - | A | - | - | D |
| `delivery:Read` | Read run sheets and delivery records | Planned | A | - | A | A | A | A |
| `delivery:Record` | Record a delivery outcome | Planned | - | - | A | - | - | D |
| `delivery:RecordArrival` | Record arrival at a stop | Planned | - | - | A | - | - | D |
| `delivery:ReportFault` | Report a vehicle or road fault | Planned | - | - | A | - | - | D |
| `delivery:ReportVehicleStatus` | Report the vehicle's status from the road | Planned | - | - | A | - | - | D |
| `delivery:Start` | Start a stop | Planned | - | - | A | - | - | D |
| `iam:AssignDriver` | Assign a driver to a vehicle for a period | Implemented | - | - | - | - | A | D |
| `iam:AttachPolicy` | Attach or detach a policy | Implemented | D | - | - | - | A | D |
| `iam:ChangeRole` | Change an account's role and rotate its sessions | Planned | D | - | - | - | A | D |
| `iam:CreatePolicy` | Author a policy | Implemented | D | - | - | - | A | D |
| `iam:CreateUser` | Create an account | Implemented | D | - | - | - | A | D |
| `iam:DetachPolicy` | Detach a policy from a user or role | Planned | D | - | - | - | A | D |
| `iam:DisableUser` | Disable an account and revoke its sessions | Implemented | D | - | - | - | A | D |
| `iam:GrantScope` | Grant depot or outlet access | Implemented | - | - | - | - | A | D |
| `iam:ManagePermission` | Set a persona or member permission decision | Implemented | - | - | - | - | A | D |
| `iam:ReadPolicy` | Read policies and attachments | Implemented | - | - | - | - | A | D |
| `iam:RegisterDevice` | Register a device | Planned | - | - | - | - | A | D |
| `iam:ResetPassword` | Set a new password and revoke every session | Implemented | - | - | - | - | A | D |
| `iam:RetireDevice` | Retire a device | Planned | - | - | - | - | A | D |
| `iam:RevokeScope` | Revoke depot or outlet access | Implemented | - | - | - | - | A | D |
| `iam:UpdateUser` | Change an account | Implemented | - | - | - | - | A | D |
| `issue:Assign` | Assign an issue to a person | Planned | A | - | - | - | - | D |
| `issue:Cancel` | Cancel an issue raised in error | Planned | A | - | - | - | - | D |
| `issue:Close` | Close a resolved issue | Planned | A | - | - | - | - | D |
| `issue:Raise` | Raise an operational issue | Planned | A | A | A | A | - | D |
| `issue:Read` | Read issues | Planned | A | A | A | A | A | A |
| `issue:RecordReplacement` | Record a replacement for a shortfall | Planned | A | - | - | - | - | D |
| `issue:Resolve` | Resolve an issue with an action and a reason | Planned | A | - | - | - | - | D |
| `issue:ScheduleRedelivery` | Request a redelivery of an order | Planned | A | - | - | - | - | D |
| `loading:Check` | Record a loading check | Planned | - | A | - | - | - | D |
| `loading:Handover` | Hand a loading session to another loader | Planned | - | A | - | - | - | D |
| `loading:Read` | Read manifests, ready trips and shortfalls | Planned | A | A | - | - | A | A |
| `loading:Release` | Release a trip for departure | Planned | - | A | - | - | - | D |
| `loading:RequestInterchange` | Request a substitute vehicle for a trip | Planned | - | A | - | - | - | D |
| `loading:Shortfall` | Flag missing or damaged goods | Planned | - | A | - | - | - | D |
| `loading:Start` | Start a loading session | Planned | - | A | - | - | - | D |
| `ml:ActivateModel` | Activate a model version | Planned | - | - | - | - | A | D |
| `ml:Read` | Read models, predictions and forecasts | Planned | A | - | - | - | A | A |
| `ml:RegisterModel` | Register a model version | Planned | - | - | - | - | A | D |
| `ml:RetireModel` | Retire a model version | Planned | - | - | - | - | A | D |
| `notification:MarkRead` | Mark notifications read | Planned | A | A | A | A | - | - |
| `notification:Read` | Read one's own inbox | Planned | A | A | A | A | - | - |
| `notification:Subscribe` | Subscribe a device to web push | Planned | A | A | A | A | - | - |
| `notification:Unsubscribe` | Unsubscribe a device from web push | Planned | A | A | A | A | - | - |
| `order:Amend` | Amend an order before allocation | Implemented | - | - | - | A | - | D |
| `order:Cancel` | Cancel an order | Implemented | - | - | - | A | - | D |
| `order:CloseForDay` | Close ordering for a depot and service day | Implemented | A | - | - | - | - | D |
| `order:Place` | Place an order for an outlet | Implemented | - | - | - | A | - | D |
| `order:Read` | Read orders | Implemented | A | - | - | A | A | A |
| `plan:Defer` | Defer an order from a draft plan with a reason | Planned | A | - | - | - | - | D |
| `plan:Generate` | Generate a draft allocation | Planned | A | - | - | - | - | D |
| `plan:Override` | Override an allocation decision | Planned | A | - | - | - | - | D |
| `plan:Publish` | Publish a plan | Planned | A | - | - | - | - | D |
| `plan:Read` | Read plans and deferrals | Planned | A | A | A | - | A | A |
| `plan:Replan` | Replan affected trips after a vehicle change | Planned | A | - | - | - | - | D |
| `plan:Revise` | Create a new version from a published plan | Planned | A | - | - | - | - | D |
| `platform:ReplayEvent` | Replay a dead-lettered event | Planned | - | - | - | - | A | D |
| `receipt:Confirm` | Confirm receipt at the outlet | Planned | - | - | - | A | - | D |
| `receipt:ConfirmPartial` | Confirm a partial receipt with received quantities | Planned | - | - | - | A | - | D |
| `receipt:Dispute` | Dispute a delivery | Planned | - | - | - | A | - | D |
| `receipt:Read` | Read receipts and pending confirmations | Planned | A | - | - | A | A | A |
| `reference:Import` | Stage, validate and publish a reference version | Implemented | - | - | - | - | A | D |
| `reference:Read` | Read outlets, vehicles, travel and calendar | Implemented | A | A | A | - | A | A |
| `sync:Acknowledge` | Acknowledge a synced operation | Implemented | - | A | A | A | - | D |
| `sync:Discard` | Discard a conflicting operation with a reason | Planned | A | - | - | - | - | D |
| `sync:Read` | Read pending operations and conflicts | Implemented | A | A | A | A | A | A |
| `sync:Resolve` | Reapply a conflicting operation on the current version | Planned | A | - | - | - | - | D |
| `sync:Submit` | Submit queued offline operations | Implemented | - | A | A | A | - | D |
| `vehicle:SetDayStatus` | Mark a vehicle available, in workshop or unavailable | Implemented | A | - | - | - | A | D |
| `warehouse:DiscardInbound` | Discard a quarantined inbound warehouse event | Planned | - | - | - | - | A | D |
| `warehouse:ReadCatalogue` | Read the cached product catalogue | Planned | A | - | - | A | A | A |
| `warehouse:Reconcile` | Reconcile orders with the warehouse | Planned | - | - | - | - | A | D |
| `warehouse:ReplayInbound` | Replay a quarantined inbound warehouse event | Planned | - | - | - | - | A | D |

Sources: [initial actions](../../../migrations/006_iam_policies.sql), [command path corrections](../../../migrations/008_command_path.sql), [identity commands](../../../migrations/009_identity_commands_and_calendar_override.sql), [module actions and policy v2](../../../migrations/20260930T1201_iam_module_actions.sql), [ordering](../../../migrations/20261001T0300_ordering_actions_implemented.sql), [sync](../../../migrations/20261001T0900_sync_operations.sql), [permission settings](../../../migrations/20261001T1400_iam_permission_settings.sql).

## 2. Proposed persona defaults and member exceptions

Super = proposed Super admin. All six = the four operational personas plus Admin/Super. Defaults activate only with an implemented endpoint and scope contract. An eligible extra grant still requires target boundary and actor delegation. Any default may have a member Explicit deny; Inherit removes only the managed member statement. Absent actions require protected boundary review, not an ordinary toggle.

| Action | Default recipients | Optional member grant | Mandatory scope |
| --- | --- | --- | --- |
| `audit:Read` | Admin, Super | - | Explicit administrative scope |
| `calendar:Override` | Admin, Super | Dispatcher | Reference entity/depot or explicit global authority |
| `delivery:CaptureProof` | Driver | - | Dated vehicle assignment; outlet-only store reads |
| `delivery:Read` | Driver, Store, Dispatcher, Admin, Super | - | Dated vehicle assignment; outlet-only store reads |
| `delivery:Record` | Driver | - | Dated vehicle assignment; outlet-only store reads |
| `delivery:RecordArrival` | Driver | - | Dated vehicle assignment; outlet-only store reads |
| `delivery:ReportFault` | Driver | - | Dated vehicle assignment; outlet-only store reads |
| `delivery:ReportVehicleStatus` | Driver | - | Dated vehicle assignment; outlet-only store reads |
| `delivery:Start` | Driver | - | Dated vehicle assignment; outlet-only store reads |
| `iam:AssignDriver` | Admin, Super | - | Visible target; delegation and protected account checks |
| `iam:AttachPolicy` | Admin, Super | - | Visible target; delegation and protected account checks; Admin constrained templates only; Super for advanced/protected policies |
| `iam:ChangeRole` | Admin, Super | - | Visible target; delegation and protected account checks |
| `iam:CreatePolicy` | Admin, Super | - | Visible target; delegation and protected account checks; Admin constrained templates only; Super for advanced/protected policies |
| `iam:CreateUser` | Admin, Super | - | Visible target; delegation and protected account checks |
| `iam:DetachPolicy` | Admin, Super | - | Visible target; delegation and protected account checks; Admin constrained templates only; Super for advanced/protected policies |
| `iam:DisableUser` | Admin, Super | - | Visible target; delegation and protected account checks |
| `iam:GrantScope` | Admin, Super | - | Visible target; delegation and protected account checks |
| `iam:ManagePermission` | Admin, Super | - | Operational persona/member action limits; protected actions require later governance commands |
| `iam:ReadPolicy` | Admin, Super | - | Visible target; delegation and protected account checks |
| `iam:RegisterDevice` | Admin, Super | - | Visible target; delegation and protected account checks |
| `iam:ResetPassword` | Admin, Super | - | Visible target; delegation and protected account checks |
| `iam:RetireDevice` | Admin, Super | - | Visible target; delegation and protected account checks |
| `iam:RevokeScope` | Admin, Super | - | Visible target; delegation and protected account checks |
| `iam:UpdateUser` | Admin, Super | - | Visible target; delegation and protected account checks |
| `issue:Assign` | Dispatcher | - | Linked authorized operation |
| `issue:Cancel` | Dispatcher | - | Linked authorized operation |
| `issue:Close` | Dispatcher | - | Linked authorized operation |
| `issue:Raise` | Dispatcher, Loader, Driver, Store | - | Linked authorized operation |
| `issue:Read` | Dispatcher, Loader, Driver, Store, Admin, Super | - | Linked authorized operation |
| `issue:RecordReplacement` | Dispatcher | - | Linked authorized operation |
| `issue:Resolve` | Dispatcher | - | Linked authorized operation |
| `issue:ScheduleRedelivery` | Dispatcher | - | Linked authorized operation |
| `loading:Check` | Loader | - | Depot/trip/session ownership |
| `loading:Handover` | - | Loader | Depot/trip/session ownership |
| `loading:Read` | Loader, Dispatcher, Admin, Super | - | Depot/trip/session ownership |
| `loading:Release` | Loader | - | Depot/trip/session ownership |
| `loading:RequestInterchange` | Loader | - | Depot/trip/session ownership |
| `loading:Shortfall` | Loader | - | Depot/trip/session ownership |
| `loading:Start` | Loader | - | Depot/trip/session ownership |
| `ml:ActivateModel` | Super | Admin | Scoped forecast/model administration |
| `ml:Read` | Dispatcher, Admin, Super | - | Scoped forecast/model administration |
| `ml:RegisterModel` | Super | Admin | Scoped forecast/model administration |
| `ml:RetireModel` | Super | Admin | Scoped forecast/model administration |
| `notification:MarkRead` | All six | - | Own recipient/device |
| `notification:Read` | All six | - | Own recipient/device |
| `notification:Subscribe` | All six | - | Own recipient/device |
| `notification:Unsubscribe` | All six | - | Own recipient/device |
| `order:Amend` | Store | - | Outlet/depot and order state |
| `order:Cancel` | Store | - | Outlet/depot and order state |
| `order:CloseForDay` | Dispatcher | - | Outlet/depot and order state |
| `order:Place` | Store | - | Outlet/depot and order state |
| `order:Read` | Store, Dispatcher, Admin, Super | - | Outlet/depot and order state |
| `plan:Defer` | Dispatcher | - | Depot/plan and publication invariants |
| `plan:Generate` | Dispatcher | - | Depot/plan and publication invariants |
| `plan:Override` | Dispatcher | - | Depot/plan and publication invariants |
| `plan:Publish` | Dispatcher | - | Depot/plan and publication invariants |
| `plan:Read` | Dispatcher, Loader, Driver, Admin, Super | - | Depot/plan and publication invariants |
| `plan:Replan` | Dispatcher | - | Depot/plan and publication invariants |
| `plan:Revise` | Dispatcher | - | Depot/plan and publication invariants |
| `platform:ReplayEvent` | Super | Admin | Explicit administrative scope |
| `receipt:Confirm` | Store | - | Outlet/delivery |
| `receipt:ConfirmPartial` | Store | - | Outlet/delivery |
| `receipt:Dispute` | Store | - | Outlet/delivery |
| `receipt:Read` | Store, Dispatcher, Admin, Super | - | Outlet/delivery |
| `reference:Import` | Admin, Super | - | Reference entity/depot or explicit global authority |
| `reference:Read` | Dispatcher, Loader, Driver, Admin, Super | - | Reference entity/depot or explicit global authority |
| `sync:Acknowledge` | Loader, Driver, Store | - | Own operation; no cross-user review |
| `sync:Discard` | - | Dispatcher: own records only, future handler | Own operation; no cross-user review |
| `sync:Read` | Dispatcher, Loader, Driver, Store, Admin, Super | - | Own operation; no cross-user review |
| `sync:Resolve` | - | Dispatcher: own records only, future handler | Own operation; no cross-user review |
| `sync:Submit` | Loader, Driver, Store | - | Own operation; no cross-user review |
| `vehicle:SetDayStatus` | Dispatcher, Admin, Super | - | Reference entity/depot or explicit global authority |
| `warehouse:DiscardInbound` | Super | Admin | Scoped catalogue or delegated integration |
| `warehouse:ReadCatalogue` | Dispatcher, Store, Admin, Super | - | Scoped catalogue or delegated integration |
| `warehouse:Reconcile` | Super | Admin | Scoped catalogue or delegated integration |
| `warehouse:ReplayInbound` | Super | Admin | Scoped catalogue or delegated integration |

Deliberate proposed changes: Handover becomes an optional dock-lead grant; notification self-actions include Admin/Super; ordinary Admin warehouse/model/replay writes become optional; shipped wildcard baselines become explicit action lists. Dispatcher sync conflict writes remain unavailable until handlers exist and remain own-record only here. Review live before/after access before changing any baseline.

## 3. New actions required by the IAM plan

These names are proposed, not existing AWS or Waypoint runtime actions. Each needs a catalogue row, handler/query, contract mirror and denied-scope test.

| New action | Default recipients | Contract |
| --- | --- | --- |
| `iam:ReadActionCatalogue` | All six | Public capability metadata; server supplies actor-specific editable state |
| `iam:ReadOwnAccess` | All six | Own effective-access explanation |
| `iam:ReadEffectiveAccess` | Admin, Super | Scoped member/persona access and impact preview |
| `iam:SimulatePolicy` | Admin, Super | Hypothetical scoped evaluation; never an authorization token |
| `iam:ReadUser` | Admin, Super | Account reads split from UpdateUser |
| `iam:ReadDriverAssignment` | Admin, Super; Dispatcher optional | Assignment reads split from AssignDriver |
| `iam:ReadDevice` | Admin, Super | Visible device details without secrets |
| `iam:CreatePolicyVersion` | Admin constrained, Super | New immutable policy version |
| `iam:SetDefaultPolicyVersion` | Admin constrained, Super | Versioned head change checking all affected accounts |
| `iam:SetMemberPolicy` | Admin constrained, Super | Managed member exceptions without replacing other policies |
| `iam:SetBoundary` | Super | Reviewed maximum permissions; required assignment cannot be removed in normal UI |
| `iam:ManageDelegation` | Super | Versioned administrator delegation envelope |
| `iam:ManagePrivilegedAccounts` | Super | Additional gate for every operation targeting Admin/Super |
| `reference:CreateOutlet` | Admin, Super | Reference-owned snapshot publication as specified in issue 22 |
| `reference:CreateVehicle` | Admin, Super | Reference-owned snapshot publication; assignment separate |

Optional member images still need a storage/media lifecycle contract and a separately catalogued action when defined; enforce the same protected-target rules. Self-profile editing, integration-secret editing and planning-parameter editing are not silently included in existing actions.

## 4. Routes, aliases and operations outside the action catalogue

| Surface | Current protection | Planned treatment |
| --- | --- | --- |
| POST /api/session | Authentication credentials | Keep authentication outside persona toggles; iam:Login is an audit label, not a grantable action |
| GET /api/session; POST /api/session/end | Own session | Keep safe session lifecycle independent of persona membership |
| GET /api/accounts and /{id} | iam:UpdateUser | Split to ReadUser with compatibility window |
| GET /api/accounts/driver-assignments | iam:AssignDriver | Split to ReadDriverAssignment |
| Policy create/version/default | iam:CreatePolicy | Split version/default; route all through CommandBus |
| Attachment deletion | iam:AttachPolicy | Enforce existing iam:DetachPolicy; retire record, preserve history |
| iam:EndDriverAssignment command kind | iam:AssignDriver | Keep explicit alias; end by shortening validity |
| POST /api/sync | sync:Submit plus inner command authorization | Submission never grants the inner action |
| GET /api/sync / acknowledge | sync:Read / sync:Acknowledge | Owner-only RLS; cross-user review is deferred |
| Health/readiness/Prometheus | Infrastructure exposure controls | Exclude from persona permissions |
| Host migration/import/account operations | Trusted operator host access | Audit operator identity; dedicated bootstrap/recovery; no HTTP bootstrap |

The Auditor policy explicitly denies iam:* and therefore blocks its own iam:ReadPolicy Allow. Do not copy NeverWrite onto Admin. Policy administration being implemented does not mean it already uses idempotent/versioned CommandBus writes.

## 5. Coverage gate

This inventory expands seeded wildcards and applies deny precedence to every inserted catalogue row, then applies subsequent implemented-flag updates. Proposed additions are kept separate from current rows. Before activation, export live effective policies, attachments, memberships and scopes through an authorized deployment operation and compare with this baseline.

Add automated coverage tying each implemented action to a handler or guarded read endpoint, each command kind to its authorization action, every route to explicit authentication/action classification, and every action to resource/context/scope metadata. Update this inventory when a module introduces actions. No current application code, migration or live records were changed to generate this document.
