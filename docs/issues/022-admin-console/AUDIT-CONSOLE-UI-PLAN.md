# Waypoint audit console UI plan

Date: 2026-10-02. Status: first interactive mock implemented. Live integration and second-increment workflows remain planned.

Implementation: `frontend/src/roles/admin/access/audit/` keeps the audit mock inside the existing isolated access-demo feature, reusing its primitives without new cross-feature imports. Overview, Activity, Access & security, URL filters, sample feed states, event details and related-event navigation are available. Permission change history uses the same adapter. There are 26 operational fixtures plus current demo changes; Admin hides governance fixtures, Super admin sees them. This is a fixture visibility rule, not live SQL scope enforcement. Export, cases, alert rules, live scope and historical enrichment remain future work.

## Evidence and design references

Requested reference: [Xception Designathon](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?t=Yb17WAKxsO1Lkhgy-0).

A fresh inspection was attempted for this plan. Figma tools were unavailable, the web reader could not open the file, and the browser runtime failed to initialize. The visual recommendations below use the user's supplied GO screenshots, current access-demo code, and the earlier Figma inspection recorded in [PLAN.md](PLAN.md). They are not a claim that a dedicated audit frame exists in Figma or that prototype transitions were freshly verified.

Earlier recorded references: [Foundations](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?node-id=218-2), [Typography](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?node-id=218-1080), [Sidebar](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?node-id=818-29781). The issue plan records Google Sans Flex, white surfaces, mint selection, green/teal actions, status chips, and list-to-detail flows. It records no Admin or Super admin page among the pages inspected at that time.

Product and implementation sources: [system architecture](../../../SYSTEM-ARCHITECTURE.md), [module contracts](../../architecture/MODULES.md), [rules](../../architecture/RULES-AND-POLICIES.md), [capability UI plan](CAPABILITY-UI-PLAN.md), `platform/audit/AuditEntry.java`, `migrations/005_platform.sql`, `migrations/006_iam_policies.sql`, and the current `frontend/src/roles/admin/access/` mock.

## 1. Purpose and delivery boundary

An administrator should be able to find a decision, understand its actor and reason, see the affected business record, and follow related events without losing search context.

The first delivery is an interactive mock inside `/access-demo`. It covers representative access and operational events. A persistent Sample data label identifies the data; simulated activity is never presented as backend evidence. The existing audit page is currently a small overview plus a copy of permission Change history. Replace it with the experience specified here.

Keep the current Admin and Super admin workspace, login, Forecasts and Vehicles destinations. Do not introduce a separate Auditor persona. The Super admin persona remains hidden from the persona directory as requested. Audit attribution still needs to identify the actor who actually performed a visible event; hiding an account-management card must not rewrite historical attribution.

## 2. Navigation

Main sidebar destinations remain People & access, Audit console, Forecasts, Vehicles. People & access continues to expand its four existing subpages. Opening Audit console closes that group and lands on Overview.

Inside Audit console use a short horizontal navigation row:

| View | Primary question | Main contents |
| --- | --- | --- |
| Overview | What needs a closer look? | Selected-period totals, denied attempts, recorded failures, recent significant activity |
| Activity | What happened to this person or record? | Search, filters, chronological event list, event details |
| Access & security | Who changed access or tried a restricted action? | The same event explorer, limited to authentication, account, policy, scope and denied-access categories |

Do not add separate permanent sidebar items for every operational module. Module filters handle that distinction. People & access → Change history becomes a focused view of the same access-event collection, so edits are represented once and are consistent in both places.

Suggested mock routes: `#audit/overview`, `#audit/activity`, `#audit/security`. Keep `#audit` as an alias for Overview. Store selected event and filters in URL parameters when implementing deep links. Browser Back restores the prior list, filter state and scroll position. Links to related workspaces appear only when the destination exists and the viewer can read it.

## 3. GO theme and layout

Reuse the current shell and GO branding. Use Google Sans Flex with the existing fallback, mint canvas, white panels, muted borders, green primary controls and mint active navigation. Current code values such as canvas `#e7f3f2`, primary `#006b57`, border `#deebe6` and rounded 22px cards are implementation references, not newly verified Figma tokens.

Use a 32–36px page heading, 18–20px section titles, 14–16px row text and 12px supporting labels as provisional sizes. Maintain readable contrast and 44px control targets. Reuse existing typography and spacing classes before introducing new ones.

The page header has Audit console, a short explanation, Sample data, a selected date range and an explicitly labeled timezone. Default to Asia/Colombo; retain UTC timestamps in the data. A future live connection status belongs beside the date controls and must show stale/unavailable states accurately.

Keep the visual hierarchy quiet: at most four summary cards, one main list and one detail drawer. Pair status color with words and icons. Red represents denied access or a recorded error; it does not automatically mean an incident or malicious activity.

Desktop: readable table and a 480–560px detail drawer. Tablet: hide secondary columns in the list and show them in details. Phone: event cards and a full-screen detail panel; filters open in a labeled sheet. Keyboard focus moves into the drawer, Escape closes it, and focus returns to its originating row.

## 4. Overview

Default to the last seven days in live mode. Mock fixtures use an explicit fixed period that contains their sample timestamps; do not label old fixtures Today.

Four cards show events in the selected period, denied access attempts, permission/account changes, and recorded execution failures. Every card links to the matching Activity filters. All totals use the same scope and date filters. Unavailable execution data displays Not available, never zero.

Below the cards, show up to five items worth reviewing, such as an admin account created, a member scope changed, or repeated denied requests. In the first mock this is a labeled sample selection, not a claim that an anomaly detector is running. Clicking an item opens its evidence. A recent activity list provides a quick entry to the full explorer.

Do not show unresolved alerts or a security score until there is an actual alert lifecycle and a defined calculation.

## 5. Activity explorer

Default columns: Time, Actor, Activity, Target, Place, Result, Details. Use short business descriptions, such as “Changed vehicle day status,” with technical action names available in details. Sort newest first with a stable secondary event ID.

Always-visible filters: date range, search, module, outcome. More filters adds actor, persona at event time when recorded, depot/outlet, device and correlation ID. Show active filters as removable chips and provide Clear filters. Search covers recorded identifiers and labels, including order, trip, vehicle, member and event IDs. Do not infer historical persona from the person's current role.

Separate authorization decision from execution outcome. The detail may say Authorization: Allowed and Execution: Not recorded. A denied request can say Authorization: Denied and Execution: Not performed only when that fact is established. Never convert an ALLOW record into a Success badge automatically.

Proposed outcomes: Completed, Denied, Failed, Pending sync, Not recorded. Each requires an explicit source. Filter help explains that Failed means a recorded execution failure; it does not include every denied authorization decision.

Use cursor pagination for live data, with a visible loading state and stable ordering. Mock data may filter locally. Do not derive global totals from the currently loaded live page. No automatic list reordering while a reviewer is reading; a future live feed offers a New events button.

## 6. Event-detail drawer

Top section: business title, result, timestamp, event ID, actor and target. Then show:

1. What happened: concise recorded description and recorded reason, or Reason not recorded.
2. What changed: labeled Before and After values where captured; otherwise Before/after values were not captured. Show changed fields only, with sensitive values redacted.
3. Access decision: Allow/Deny, decision reason, relevant scope and policy version if recorded. Never evaluate current policy to invent a past decision.
4. Context: actor identity, device, depot/outlet, server-recorded time and client occurrence time when available. For offline events show both and explain delayed synchronization.
5. Related activity: events linked by a recorded correlation/command/operation ID. Merely similar times do not prove causation.
6. Technical details: collapsible action, resource, identifiers and source metadata. No raw unrestricted request bodies.

Offer Copy event ID and View related activity with visible success/error feedback. View member/order/vehicle is conditional on an existing authorized destination. There are no edit, delete or blind Undo controls on audit records.

## 7. Waypoint event coverage

| Category | Representative sample events | Main investigation question |
| --- | --- | --- |
| People and permissions | Member created, persona grant changed, member exception added/expired, scope granted/revoked | Who changed access, and what was the prior decision? |
| Authentication/security | Login failed, account disabled, session ended, authorization denied | Which actor/device attempted which action? |
| Orders | Order placed/amended/cancelled, ordering closed | Who changed an outlet order and when? |
| Planning | Plan published, constraint override, trip deferred/replanned | Which decision and recorded reason led to this plan? |
| Loading | Check completed, shortfall, release, interchange request | What happened before the trip left the depot? |
| Delivery and receipt | Delivery outcome, missing proof reason, partial receipt, dispute | What evidence supports the outcome? |
| Vehicles/calendar | Vehicle day status changed, calendar override | Who changed operational availability? |
| Sync/integrations | Conflict, rejection, retry, warehouse failure | Was work received, rejected or still pending? |
| Forecast administration | Model activation/retirement | Which recorded model change affected forecasts? |

These are intended coverage categories, not a claim that every backend action or event producer exists. Preserve the current Coming later status of planning, forecast and other unimplemented capabilities. Each mock row visibly belongs to sample data.

Create roughly 25–30 coherent fixtures spanning the four operational personas and administrative activity. Include an actor who is later disabled, unavailable before/after values, a scoped denial, a permission change with zero effective impact, a delayed offline receipt, and a failed integration operation. Do not fabricate personal data from real accounts.

## 8. Admin access and privacy

Live visibility requires an implemented audit read capability plus scope filtering in SQL. A role name alone is insufficient. Admin sees authorized operational and delegated access records; privileged governance records require a separately defined visibility rule. Super admin also receives explicit policy and scope rather than an implicit bypass.

The current catalogue contains `audit:Read` marked unimplemented. Reading, exporting, investigating and managing retention are distinct capabilities; names and enforcement for additional actions must be designed and catalogued before enabling them. Do not add frontend-only security assumptions to the live application.

Hide secrets, passwords, tokens, session credentials and unrestricted payloads. Prefer field-level redaction and stable identifiers. Access to sensitive evidence and exports must itself be auditable. Deleted or disabled accounts retain historical attribution according to retention policy.

## 9. Current backend gaps and proposed read model

The inspected audit schema contains audit ID, occurred-at time, actor/device IDs, action, resource, decision, reason and correlation ID. `AuditEntry` mirrors those write fields. Its decision values are ALLOW/DENY. That schema does not establish a complete execution result, before/after diff, historical role, event scope, review status, or reliable client occurrence time.

The UI read model should expose available fields plus explicit nullable/unknown values for enrichment: event ID, recorded time, optional occurred time, actor snapshot, optional actor persona snapshot, module, business label, target, scope, authorization decision, execution outcome, reason, optional diff, correlation identifiers and source. Mock and live adapters should implement the same view contract while preserving provenance.

Before live integration, verify all applied migrations and audit producers, implement a scope-safe audit query through the proper platform/identity contracts, and define privileged-record visibility. Preserve append-only storage. Successful state changes and their audit facts commit together; failed/rolled-back attempts need a separate durable audit path that cannot accidentally disappear with the rejected transaction. The existing standalone denial writer is a foundation to inspect, not proof that all failures are covered.

Missing event enrichment must be addressed with forward migrations and producing-handler changes. No direct client reads of audit tables, no cross-module domain imports, and no RLS bypass to make global search work.

## 10. States and secondary workflows

Provide distinct states for no events in range, no filter matches, access denied, source unavailable, stale results and an event that is no longer accessible. Retain filters after errors. Display Not recorded for missing metadata rather than leaving ambiguous blank values. A missing feed cannot count as a healthy system.

Defer saved investigations, assignment, review notes, alerts and exports to a second increment. Review notes are separate append-only case activity; they never modify the original fact. Exports need scope enforcement, redaction, size limits, a stable time range and their own audit record. Retention settings belong to protected governance after requirements are settled; do not expose Delete logs.

## 11. Implementation sequence

1. Create typed mock audit fixtures and one adapter for existing demo permission/member changes. Derive People & access Change history from the same events to avoid duplicate or conflicting records.
2. Add Audit console's Overview, Activity and Access & security view routes and filters. Preserve the user-requested main sidebar hierarchy.
3. Implement the shared event list, detail drawer, related-event flow and unknown/error states. Verify every displayed control has a working mock outcome.
4. Check responsive layouts, keyboard operation, return navigation, consistent counts and changing filters while details are open. No backend actions are called in this increment.
5. Connect live reads only after audit capability, SQL scope, privilege visibility and source-field gaps are implemented and verified.

Suggested local components under `frontend/src/roles/admin/audit/`: AuditConsole, AuditOverview, AuditFilters, AuditEventList, AuditEventDetails, RelatedActivity, auditModel, fixtures and a read adapter. Reuse shared visual primitives; avoid importing private components across admin feature folders. If promoting existing primitives into a shared folder, run the required boundary check before introducing imports. Keep route composition in the shell and individual component files under roughly 300 lines.

Acceptance: a reviewer can find a sample event by target ID, follow its recorded related events, understand an access denial, distinguish permission from execution outcome, return to the same filtered list, and see the same access change in both histories. Admin/Super admin mock visibility follows an explicit fixture rule. Unavailable capabilities and metadata are honest. Existing login, member management, Vehicles and Forecasts flows remain usable.
