# Capability management UI plan

Status: interactive mock UI implemented; live IAM integration remains separate. Date: 2026-10-01.

This specifies the mock UI requested after the IAM plan. It supersedes the old all-actions permission screen design, not backend authorization rules. Read [IAM-PLAN.md](IAM-PLAN.md) for evaluation semantics and [PERMISSION-INVENTORY.md](PERMISSION-INVENTORY.md) for the action inventory.

## 1. Outcome and delivery boundary

An administrator can answer three questions quickly: what can this persona do, what is different for this member, and where does the member have access?

The interactive mock workspace has realistic fixtures, simulated saves and history. Every mock screen has a persistent “Demo data” label and a Reset demo action. Refresh resets the session to its starting fixtures; switching screens preserves saved demo changes. Mock saves never call the live command endpoint. Backend integration is a later, separately verified step.

At the start of this work, `frontend/src/roles/admin/` was absent, although earlier admin planning documents existed. The mock now lives under that directory and at `/access-demo`. It uses the current app tokens because the previous shared admin components are absent in this checkout. No fresh Figma inspection was performed for this plan. GO visual references are recorded in the issue PLAN.md.

## 2. Navigation and visual hierarchy

Keep one People & access destination in the admin shell. Inside it use four tabs:

| Tab | Primary job | Main action |
| --- | --- | --- |
| People | Find a member and understand their access | View member |
| Personas | Manage shared defaults | Manage capabilities |
| Permission catalogue | Search all capabilities and understand availability | View details |
| History | Explain who changed access and why | View change |

Enter on People; preserve tab, search, module and persona in navigation so Back returns to the same context. Use breadcrumbs for Persona > Module or People > Member. Catalogue and History are secondary destinations, not steps required to grant access.

Use existing GO font, colors, icons and shared primitives where present. White surfaces, a quiet mint selection, dark primary action, generous spacing and short labels. Use text and icons with color for status. One prominent primary action per context. Avoid dashboards full of metrics.

Desktop: sidebar plus content, persona cards in a responsive grid; persona detail uses a narrow module list beside its capability rows. Tablet: module selector above rows. Phone: single column, module dropdown, full-screen exception editor, sticky review button that does not cover content.

## 3. People and member details

Directory columns: Name, Persona, Assigned places, Access exceptions, Status. Filters: persona, status and “Has exceptions”; search by name/email. Show precise totals only from complete mock fixtures or authoritative server counts, never from one API page.

Member detail has Profile, Access and Scope tabs. Access shows:

1. Effective capability summary and the member's assigned persona(s).
2. Member exceptions first, with Allow/Block, reason and expiry where applicable.
3. Inherited capabilities grouped by module, initially showing available allowed capabilities.
4. “Show unavailable access” reveals relevant optional and blocked capabilities.
5. Scope summary linking to the separate Scope tab.

Each effective row includes its source: “From Dispatcher”, “Allowed for this member”, “Blocked for this member”, or “Blocked by persona”. A “Why?” drawer explains contributing sources, missing scope or unavailable feature. A member Allow cannot override a matching persona Deny. Multiple personas contribute together; a selected workspace does not hide the other grants.

## 4. Persona overview

Four operational persona cards are primary: Dispatcher, Loader, Driver, Store manager. Admin and Super admin appear under a quieter Administration section. Protected cards clearly indicate who may manage them; Super admin governance is read-only in the first mock release except for inspecting details.

Each card contains description, assigned member count, Active / Optional / Restricted counts and one “Manage capabilities” action. Members count links to the filtered directory. Coming later is a separate small count, excluded from active/optional/restricted totals.

Counts are calculated, never hard-coded:

- Active: implemented, relevant capability allowed by the persona policy. Row scope still applies.
- Optional: implemented, relevant, not currently allowed, and eligible for an authorized grant.
- Restricted: implemented, relevant, explicitly blocked or outside the permitted grant limit.
- Coming later: relevant capability whose operation is unavailable.

Initial historical examples are Dispatcher 5, Loader 4, Driver 4, Store manager 7 and Admin 17 active action-level capabilities. These are fixture expectations, not universal totals or proof of working UI flows. The fixture must label backend support and UI support separately.

## 5. Persona detail and module behavior

The header identifies “Changes apply to every member with this persona” and shows the assigned-member count. The module list summarizes active/available capabilities. Selecting a module updates the adjacent list without opening another page.

```text
Personas / Dispatcher                       6 assigned members
Shared defaults apply across assigned members' existing scope.

Modules                  Orders                 [Search capabilities]
Orders        2 active   View orders             Active        [Edit]
Vehicles      1 active   Close ordering          Active        [Edit]
Reference     1 active
Sync          1 active   [Show related unavailable actions]
Calendar      Optional

Coming later (collapsed): Planning, Issues, Delivery monitoring
```

Show active and grantable relevant actions by default. Related actions outside the persona limit are collapsed and read-only. Never hide an existing out-of-template grant or deny: show an “Additional configured access” group so operators can review it. The catalogue retains every action for inspection.

| Persona | Primary capability groups | Example optional capability |
| --- | --- | --- |
| Dispatcher | Orders, Vehicles, Reference, Sync status; planned Planning, Issues, Forecasts and Monitoring | Calendar override |
| Loader | Reference, Offline work; planned Dock board, Loading checks, Shortfalls and Release | Handover, once its handler exists and the approved default is changed |
| Driver | Reference, Offline work; planned Assigned trips, Stops, Delivery proof and Fault reporting | Only approved actions within driver scope; no invented cross-role grants |
| Store manager | Orders, Offline work; planned Incoming deliveries, Receipts and Product catalogue | Only approved actions within outlet scope |

Use business labels such as “View orders”, “Close ordering”, “Record loading checks”. One capability initially maps to one catalogue action. A module is only a visual group. Avoid module-wide toggles that could silently grant future actions. Offline work is a collapsible supporting group with explanatory text; sync permission never grants the queued business action.

## 6. Labels and editing semantics

Separate availability, effective decision and configured choice. An Active chip is not an editable switch. “Coming later” describes availability, not a denial. Never render a failed read as blocked access.

| Context | Editable choices | Meaning |
| --- | --- | --- |
| Persona | Use published default / Allow / Do not grant / Block | Inherit baseline; add Allow; suppress this managed persona grant; explicit Deny |
| Member | Use persona access / Allow for this member / Block for this member | Remove member exception; direct Allow; explicit Deny |

“Do not grant” must explain that another persona or direct policy can still allow the action. “Block” must explain that it defeats other matching Allows. Keep these choices in an Edit drawer with a result preview, rather than four controls on every row. Show a read-only reason when actor authority, target protection or grant limits prevent a choice.

## 7. Member exception editor

“Add exception” opens a side drawer with two choices: Give access or Block access. Search only applicable capabilities, with available actions first. Choosing a blocked capability explains why a direct Allow cannot unblock it.

Fields: capability, target member, current result, proposed choice, applicable scope, duration and required reason. Scope defaults to the member's existing assignments. Narrowing an exception cannot add a depot/outlet. Changing assignments is a separate Scope workflow. Dates show Asia/Colombo explicitly; require a future expiry. Allow permanent duration only where permitted.

Mock example: give Nimali, a Dispatcher, Calendar override for PELIYAGODA until a selected future date, with “Covering the depot lead” as the reason. The editor clearly identifies scoped/expiring exceptions as a demo of the intended future workflow. The previously described single-action backend command has no expiry or exception-scope fields; do not connect these controls to it and imply enforcement.

An expiring Allow does not remove a separate inherited Allow. Show the resulting source after expiry. A blocked or expired exception stays explainable in history. Provide “Use persona access” to remove the member exception, with a before/after review.

## 8. Review and save

Use a two-stage interaction: edit in place, then Review changes. A sticky draft bar shows the change count, Discard and Review. Review lists target, capability, previous choice/result, new choice/result, scope and reason.

For shared persona changes show both “Assigned members” and “Members whose effective access changes”. These counts differ when direct grants or denies exist. Preview warnings explain those cases and link to the affected member list. In a live integration, exact impact requires a server simulation; show “Impact unavailable” and prevent a save that requires that preview when the service cannot calculate it.

Persona changes apply across existing assignments. Do not offer a depot selector that falsely makes the shared role policy depot-specific. Member exception narrowing belongs to the member flow. Depot-specific persona templates require a later backend contract.

Mock Save applies the draft atomically, updates derived counts and effective views, and adds a history entry. Success reads “Demo changes saved”. For live wiring, the existing single-action command is insufficient for an atomic batch: implement a versioned batch contract first or deliberately keep one-change saves. No unannounced partial success.

## 9. Catalogue and history

Catalogue: search plus module, persona relevance and availability filters. Each row shows friendly name, module, purpose, availability and assigned persona defaults. Technical action ID is tucked into Details. Full IAM JSON remains outside the ordinary editor. Planned actions may be read but not granted.

History: timestamp, actor, target, change summary and reason. A detail drawer shows before/after and scope/expiry where supported. Filter by persona/member. Do not offer blind Undo; restoring old access is a new reviewed change. Mock history is clearly labeled and driven by the same fixture store.

## 10. Component and data ownership

Proposed folder: `frontend/src/roles/admin/access/`, with screens/, components/, data/ and model/. Keep UI components small and compose them through screen containers. Reuse existing shared primitives; do not create a second shared design system.

| Component | Responsibility |
| --- | --- |
| CapabilitySummary | Derived counts with precise labels |
| PersonaCapabilityMatrix | Persona/module overview and navigation |
| CapabilityGroup / CapabilityRow | Friendly grouped readout, source and Edit entry |
| MemberEffectiveAccess | Inherited results and exceptions |
| ExceptionEditor | Validated draft, scope and duration |
| ScopeSelector | Existing eligible places only; read-only when unsupported |
| ImpactPreview | Before/after, affected members and reason |
| PermissionHistory | Filtered history and change details |
| CapabilityDetailsDrawer | Description, action ID and explanation |

Data model: capability definitions (stable ID, action ID, module, friendly label, description, backend availability, UI availability, persona relevance); persona defaults; accounts and assignments; member exceptions; change history. Availability is separate from permission. Mock evaluation and derived counts are centralized once in a pure model, not reimplemented per screen. Real authorization remains exclusively server-owned.

Use an AccessData adapter with explicit mock/live implementations. Future server responses must provide authority to edit, limits, effective sources, scope, complete counts, versions, history and simulation results. Do not infer grantability solely from role names or catalogue availability. API contracts belong in shared/domain; screens never import backend module internals.

## 11. Mock dataset and walkthroughs

Use 24 fictional operational members: 6 dispatchers, 6 loaders, 8 drivers and 4 store managers; plus 2 admins and 1 super admin. Use example.test email addresses and clearly named sample depots/outlets. All counts derive from these records. One member has two operational personas to demonstrate combined access.

Fixtures cover inherited access, optional calendar grant, direct block on order cancellation, shared explicit deny, existing unusual grant, account with no assigned scope, expired exception and protected administrative target. The default dataset follows the recorded catalogue availability. Future capabilities stay Coming later; a separate future-workflow demonstration must never present them as deployed.

Review scenarios:

1. Open Dispatcher, find Orders and inspect the two active capabilities.
2. Add a sample calendar exception for one dispatcher; compare with another dispatcher who still inherits the default.
3. Block cancellation for one store manager; all other store managers retain access.
4. Review a persona change and explain why an existing member Deny remains effective.
5. Remove an exception and show the resulting inherited access.
6. Inspect a planned loading action without an enabled save.
7. Switch the demo viewer between Admin and Super admin and inspect protected controls.

## 12. Accessibility, failure states and acceptance

Support keyboard navigation, visible focus, properly labeled form fields, drawer focus trapping/restoration and escape/discard behavior. Use at least 44px touch targets. Status never depends on color alone. Announce save results. Long names, descriptions and translated labels wrap.

Provide explicit loading, empty, no search results, read failure, offline, stale version, denied edit and save failure states. Failed reads mean Unknown. Preserve drafts on failure; stale versions require refreshed review. Admin changes remain online-only for later live integration.

Acceptance: four operational personas are easy to find; default screens never dump all 76 actions; member exceptions are visible without searching a catalogue; source and scope are understandable; explicit denies cannot be overridden in mock evaluation; counts and history update consistently; mock saves never reach production APIs; mobile and keyboard paths remain usable.

## 13. Build sequence after this plan

1. Inventory current shared UI and establish the demo route, capability metadata, fixture adapter and pure mock evaluation.
2. Build tabs, People, persona cards and module navigation.
3. Build shared capability rows/details and member effective-access view.
4. Build exception editor, review, simulated save and history.
5. Check the seven walkthroughs at desktop, tablet and phone widths; run typecheck and production build. No new test files are requested for the mock UI phase.
6. Deliver the working mock route for user review. Integrate real APIs only after confirming missing scope/expiry, batch, impact and history contracts.

The mock contains all 76 actions from the documented inventory, with the requested four-persona subset surfaced in context. The mock “implemented” flag comes from that inventory; it is not a runtime check against the current checkout or database. Scope and expiry editing, impact and history are browser-memory demonstrations. Completion does not claim missing backend features are built.

## 14. Mock administration follow-up

The demo opens on a role chooser with sample Admin and Super admin identities. Choosing an identity is explicitly labeled a preview sign-in; it accepts no credentials, creates no server session and grants no real access. Signing out returns to the chooser without clearing browser-memory changes.

Only the Super admin preview shows Add admin. Its form collects name, email, an existing sample depot assignment and a reason. Review shows the proposed account and scope before a simulated save. The new admin appears in People and in the Admin assigned-member count, and the change appears in mock history. Duplicate emails and incomplete fields block review. Reset demo removes created accounts. The Admin preview cannot open or submit this flow.

Real Super admin authentication and privileged account creation require a protected backend command and server-side authorization; the mock must never be presented as that integration.
