# Issue 22: Admin and super-admin console plan

Date: 2026-10-01. Branch inspected: `22-admin-console`.

Authorization implementation specification: [AWS IAM semantics and delivery plan](IAM-PLAN.md), with [all current and proposed permissions](PERMISSION-INVENTORY.md). These documents define the replacement permission model; the existing frontend preview is not evidence that it is implemented.

Status: frontend rebuild delivered for review. The GO admin/super-admin shell, account directory, member/driver creation flow, image picker, persona/member permission drafts, outlet/vehicle review forms and read-backed administration screens are implemented. Development preview: `http://127.0.0.1:3000/admin-preview`. Missing backend contracts display explicit unavailable messages; this does not mark backend-dependent issue 22 complete. See [WALKTHROUGH.md](WALKTHROUGH.md) for implementation and verification limits.

Rebuild decisions: retain the existing role/data/shared folder structure; use an isolated development-only sample adapter for preview; keep real session-based access unchanged. Permission decisions that have not been served are unknown, never rendered as a false denial. Individual member exceptions can be demonstrated while other permissions inherit. Account setup freezes the reviewed payload and reuses request IDs after failures. Directory filtering is explicitly limited to the current legacy API page until server-side persona filtering exists. No backend, migration, live policy change or real account creation was performed during the rebuild. Further checks were stopped at the user's request; the final discard-dialog and label refinements have not been revalidated.

## 1. Sources and evidence

- Original issue: `D:/Tech_Triatholon/issues/22-admin-console.md`.
- User requirements added in this session: preserve the supplied Figma theme, icons, fonts and interaction flow; show members grouped by persona; add members within each persona with an optional image; support persona permissions and member overrides with toggles; only super admins may add admins; add outlets and vehicles using actual schema fields; add drivers; fold former Auditor read/investigation functions into Admin without a separate Auditor persona.
- [Challenge Booklet](../../../Challenge%20Booklet.pdf), text inspected: pages 3-7 describe the network, constraints and four operational personas; pages 9-10 require consistent designs and documented departures; pages 12-13 require design fidelity and working responsive flows. Admin and super admin are project extensions, not personas mandated by the booklet. The 120-outlet competition dataset remains an unchanged baseline, not a permanent limit on the application.
- [Architecture](../../../SYSTEM-ARCHITECTURE.md), [module contracts](../../architecture/MODULES.md), [rules](../../architecture/RULES-AND-POLICIES.md), [foundation plan](../../architecture/FOUNDATION-PLAN.md), and current Java/SQL/TypeScript source. Source code determines whether an API exists; design documents sometimes describe future behavior.
- [Requested Figma file](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?node-id=0-1): opened and inspected in the browser. The user clarified that readable browser access is acceptable; `use_figma` is not mandatory. Page/layer names and the foundation/sidebar references in section 3 were inspected directly, including canvas screenshots. Screenshots were viewed during inspection, not saved as repository artifacts. The complete variable bindings, per-control measurements and prototype connections remain unverified.
- Booklet text extraction succeeded. PDF visual rendering was unavailable; installing a renderer failed on certificate verification. No claims about the booklet's visual layout are made.

Before implementing the visual layer, finish the remaining Figma evidence checklist in section 3. The backend and functional work below can be reviewed independently.

## 2. Scope and personas

Use one responsive, online-only admin workspace with capability-dependent controls. `admin` and proposed `super_admin` use the same components, navigation and visual theme; their authority differs. Admin includes all audit and investigation capabilities formerly assigned to Auditor. There is no separate Auditor persona, onboarding choice or workspace in the target design.

| Persona | Intended responsibility | Management boundary |
| --- | --- | --- |
| Admin | Manage operational members, permissions and scope; maintain outlets, reference data, calendar, fleet and integrations when authorized; read audit records and investigate operations across the former Auditor capability set | May create/manage operational members within delegated authority; investigation reads respect scope and privileged-account visibility; cannot create/promote to admin or super admin, manage privileged accounts, or expand its own authority |
| Super admin | Govern admin membership and administrative permissions, in addition to explicitly granted administrative functions | Only this level can create an admin or promote an existing member to admin; may manage admins and the admin persona's permissions |
| Dispatcher / Loader / Driver / Store manager | Existing operational workspaces | Their default capabilities are managed by persona; individual exceptions remain visible |

User decision: consolidate Auditor functionality into Admin. Add the former Auditor read-action set to the admin baseline: `order:Read`, `plan:Read`, `reference:Read`, `iam:ReadPolicy`, `audit:Read`, `loading:Read`, `delivery:Read`, `receipt:Read`, `issue:Read`, `sync:Read`, `warehouse:ReadCatalogue`, and `ml:Read`. Super admin includes these investigation capabilities too. Reads remain subject to applicable scope and protected-account rules. Preserve authorized admin writes: do not attach the existing `WaypointAuditor` policy or copy its `NeverWrite` Deny statement, because Deny wins and would block administrative commands (and its `iam:*` denial conflicts with policy reads).

Retire the legacy `auditor` role through a forward migration and coordinated session/type/router updates, not by editing applied migrations. Preserve historical role labels and audit attribution. Inventory existing assignments and retire old defaults/attachments without leaving `NeverWrite` attached to administrators. Existing Auditor accounts must not silently gain admin write authority: promotion to Admin requires the same super-admin-controlled process as any other account. Resolve remaining legacy assignments before removing the role from current-account contracts; invalidate affected sessions. This migration requirement does not introduce an Auditor option in the new UI.

Planning choices: first super admin is bootstrapped through a trusted host command. Do not automatically promote existing admins. Creating additional super admins is outside this issue's normal Add member UI; retain a controlled bootstrap/recovery path. Admin may view the persona directory, including a read-only admin summary; privileged account details and changes need dedicated authority. Counts and lists must be returned with server-side visibility filtering.

The super-admin-only rule is enforced in the application transaction, not solely by hiding buttons. Apply it to create, promote, role assignment, policy attachment/default changes, member overrides and every alternative write path. Existing `iam:*` grants must not bypass the privileged-account boundary. Protect self-disable, the last active dispatcher, and the last active super admin, including simultaneous requests. Ordinary admins cannot reset a privileged member's password or edit credentials to take over that account.

## 3. Figma references and admin design specification

Extend the existing GO application. Do not introduce an unrelated admin template or a new icon library.

The following exact token values come from the current code. The Figma palette and typography were visually inspected for continuity, but every hex value and measurement has not been independently transcribed from Figma:

| Item | Existing implementation to reuse |
| --- | --- |
| Font | `Google Sans Flex Variable`, `font-go`; existing global font loading in `app/layout.tsx` |
| Canvas / surfaces | `go-canvas` #E7F3F2; white cards/sidebar; `go-surface` #F1F6F5 |
| Text / action / selected | `go-ink` #031B08; `go-teal` #0E766D; `go-mint` #B7F2ED; existing dark primary buttons |
| Status | Existing success, danger, warning and info tokens with text/icon labels |
| Shapes | Existing 12px input radius, 16-22px cards, 28px panels, pill controls and shared shadows |
| Desktop shell | Existing dispatcher pattern: white 260px sidebar, GO brand, persona badge, selected mint navigation, user controls at the bottom |
| Smaller screens | Existing compact navigation below `lg`; tables adapt to readable rows/cards; forms become full-width; do not claim these are existing Figma admin frames |
| Assets | `src/shared/ui/Icon.tsx` and `public/icons/go/*.svg`, preserving glyph dimensions and insets |
| Components | `Card`, `Pill`, `Notice`, `PrimaryButton`, `SecondaryButton`, `Segmented`, `ShellActions` |
| Detail flows | Existing list-to-drawer pattern, reason/confirmation step, explicit saving/error/success feedback |

The live Figma file contains 20 pages (00-19). Relevant verified pages are `05 · Dispatcher · Desktop`, `14 · Store Manager · Desktop`, `15 · Store Manager · Mobile`, and `17 · Style guide & components`. There is no admin or super-admin page in the page list, so the console must extend the existing GO design system rather than claim to reproduce an admin frame. The dispatcher desktop page groups sign-in/overview/orders/plan/live/vehicles/forecast, plan decision and review, order detail, live timeline/side panels, vehicles/notifications, and toast/call/audience-picker overlays. The style-guide page contains `Foundations`, `Shell / Sidebar`, `Shell / Rail`, `Page header`, `Status chip`, `Temp badge`, `Connection status`, `Notification card`, `Back link`, `Language toggle`, and components for dispatcher, store manager and driver. A selected style-guide heading identifies `Google Sans Flex` in Figma, consistent with the repository font.

Further design inspection through the browser or Figma connector should record visible frames, measurements and evidence limitations:

1. Record links and IDs for the specific reference frames within verified pages 05, 14, 15 and 17.
2. Inspect the foundations, desktop shell, list/table, detail drawer, form, dialog, empty/error, and any toggle/permissions examples at readable zoom.
3. Inspect actual text styles, sizes/weights, spacing, component variants, colors, shadows, icon sources and prototype reactions; capture representative frame screenshots.
4. Map each admin pattern to a source component/frame. Admin-specific flows are extensions where no matching persona exists; document their rationale rather than inventing existing admin designs.
5. Export any missing people, permissions, device, outlet or toggle assets from that design system. Until verified, prefer a text label over a misleading substituted icon.
6. Record findings in this issue folder and replace provisional measurements where the file differs. Compare implementation at matching frame sizes, then test 1440px desktop, 768px tablet and 393px phone layouts.

### 3.1. Direct Figma references

| Reference | Evidence from browser inspection | Use in admin |
| --- | --- | --- |
| [Style guide & components, page 17](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?node-id=6-57) | Foundations and reusable shell, status, connection, notification and navigation components | Shared visual vocabulary for every screen |
| [Foundations](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?node-id=218-2) | Contains Colour palette, Typography, and Radius & spacing boards | Use existing GO tokens; compare additions with these boards |
| [Colour palette](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?node-id=218-3) | Brand/action, light surfaces, dark surfaces, text and status groups; green/teal/mint family and white/light surfaces | Light admin workspace, mint navigation selection, semantic status feedback |
| [Typography](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?node-id=218-1080) | Google Sans Flex; named Display, KPI, Title XL/L/M, Headline, Subheading, Body L/Body/Body S, Caption, Label and Overline examples | Maintain hierarchy for page headings, section headings, row content, labels and help text; use code styles until exact frame text sizes are checked |
| [Shell / Sidebar](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?node-id=818-29781) | Seven `Active` variants: Overview, Orders, Plan, Live, Vehicles, Forecast, Issues. White sidebar, GO branding/persona badge, icon-plus-label rows, mint active row, lower scope/user blocks | Adapt active-state behavior to admin destinations. Preserve shell structure while changing destination labels and capability visibility |

The sidebar component set is 2060 x 900 and contains seven variants; that is the entire component-set size, not an admin sidebar width. Retain the implemented 260px shell width provisionally. Foundation-board padding, gaps and typography demonstration sizes are documentation layout measurements, not application spacing requirements.

### 3.2. Screen-to-component mapping

These are implementation decisions derived from the inspected design system and existing role implementations. Admin-specific forms and permission behavior are new product flows; they are not existing Figma admin frames.

| Admin screen / flow | Figma reference or existing pattern | Planned composition |
| --- | --- | --- |
| Workspace navigation | Page 17 `Shell / Sidebar`, `Page header`; page 05 dispatcher desktop | GO sidebar with persona badge, selected mint item, signed-in user and Switch user at the bottom; content header with title, context and primary action; reuse `ShellActions` rather than adding another shell strip |
| People & access | Page 17 foundations/status components; existing `Card`, `Pill` | Persona cards with name, served counts and status, View members, Permissions and Add member; privileged personas display the actual management boundary |
| Persona members | Page 05 Orders/Vehicles list and detail grouping | Search/filter toolbar above a readable table; name/email, personas, account status, scope, access source; row opens member details; preserve filters and paging when returning |
| Member details | Page 05 side-panel grouping and existing list-to-drawer implementation | Header with member identity/status; Profile, Scope and Access sections; permitted actions in context; use a full-width detail view on small screens |
| Persona/member permissions | Page 17 typography, cards and status chips; new switch primitive | Group permissions by module; label, description, scope, effective result and switch on each row. Member rows show Inherited or Custom and a separate Use persona access switch. Preserve the semantics in section 5 |
| Add member / Add driver | Page 17 form typography/buttons via existing shared primitives; page 05 review/publish grouping as a flow reference | Details (including optional profile image) -> Scope/access -> Review. Keep the chosen persona visible; use inline validation and a clear final Create member action. Add driver preselects Driver; super-admin-only Add admin follows the same form structure |
| Outlet directory | Page 05 list/detail pattern; page 14 store context as a reference to inspect further | Searchable rows with outlet ID, brand, district/depot and delivery access; Add outlet in the header; outlet details show persisted fields |
| Add outlet | Foundation typography/surfaces; existing shared form controls | Identity and location, Delivery access and windows, Optional coordinates, Review. Brand/district selectors; derived depot read-only; conditional mall-window fields. Use the exact contract in section 6 |
| Vehicles & drivers | Page 05 Vehicles grouping and implemented vehicle detail pattern | Reuse fleet row/status vocabulary; add vehicle through a sectioned form and review; Add driver enters the shared member flow with Driver selected; dated assignment remains a separate action |
| Calendar / reference publication | Page 05 decide/review/publish grouping | Date or version context, editable fields, visible change summary, reason, explicit Save/Publish and result; no automatic publication when a toggle moves |
| Devices / integrations | Page 17 status/connection/notification components | List plus detail; textual state and supporting status chip; clear retry/replay results. Show unavailable dependencies with `Notice` instead of sample health data |
| Audit & investigation | Page 05 list/detail and timeline patterns; page 17 status chips | Filterable audit list, event detail and related operational records; actor, time, action, resource, decision and reason. Read-only investigation inside the admin workspace; no separate Auditor shell |
| Errors, conflicts and lost connection | Page 16 Degradation screens as a reference; page 17 Connection status | Persistent notice near affected controls, preserve draft, disable unavailable writes, offer retry or reload/review. Admin has no offline write queue |

### 3.3. Shared component and asset work

Reuse `frontend/src/shared/ui/theme.css`, `primitives.tsx`, `Icon.tsx` and the GO SVGs. Required reusable controls are `Card`, `Pill`, `Notice`, `PrimaryButton`, `SecondaryButton`, `Segmented`, and shell actions. Add shared controls only where missing: `Switch`, labeled form field/error treatment, accessible dialog/drawer, and list loading/empty/error presentation. Keep persona/member-specific permission rows inside the admin role; only the generic switch belongs in shared UI.

- Existing icons suitable for reuse include `search`, `chevron-down`, `chevron-right`, `arrow-left`, `close`, `check`, `lock`, `alert`, `truck`, `clock`, `loading`, and `switch-user`. Preserve their declared box/inset geometry. A lock communicates restricted access, not an invented people-directory icon.
- Dedicated people, permission-management, outlet, device and integration navigation glyphs have not been verified/exported. Resolve these from the Figma assets before final visual acceptance; use clear text labels until then. Do not introduce a second icon family.
- A `Language toggle` exists in Figma, but it is not evidence of a permission-switch variant. Build the permission switch as an explicit extension of GO colors, radius, typography and focus treatment. Show On/Off or Allowed/Blocked text and a visible label. Include checked, unchecked, keyboard-focus, disabled, pending and error states.
- No dedicated profile-image picker or Add vehicle frame was found in the page list. Compose both with the verified foundation typography, light card/form surfaces, GO buttons and status feedback. Use the existing role avatar treatment as the preview/fallback reference; do not claim it is an existing upload component.
- Keep account Active, attachment Enabled, capability Allowed and Use persona access as distinct labeled controls. Stage changes for review and show saved versus unsaved state; moving a switch does not itself claim that the backend saved access.
- Use existing status colors with a text label and, where suitable, an icon. Show inherited/custom access separately from allowed/blocked outcome; mint selection styling must not imply a permission grant.
- No new language selector, notification service or temperature badge is required merely because its component exists in the design library. Include only controls backed by an actual admin need and contract.

### 3.4. Responsive and interaction acceptance

Desktop follows the implemented dispatcher shell; below `lg`, adapt the existing compact navigation. Member/outlet tables become labeled rows or cards without losing actions or access provenance. Forms use one column on narrow screens; detail panels become full-width views. Permission labels wrap and their controls remain reachable without horizontal scrolling. Preserve focus, drafts, filters and navigation history across these layout changes.

Validate at 1440px desktop, 768px tablet and 393px phone, plus the exact reference frame sizes once inspected. These are planned test widths, not a claim that Figma contains admin frames at those dimensions. Check normal, hover/focus, selected, disabled, loading, empty, error, denied-access, conflict and saved states; verify keyboard navigation and readable status text. Prototype transitions and animation timings are still unverified, so do not invent a matching motion specification.

The GO theme and icons are a reuse requirement. A separate dark theme or new illustration set is not part of this scope. Before implementation acceptance, record representative screen captures and remaining per-control measurements, compare the resulting admin screens with these sources, and document deliberate admin extensions.

## 4. Navigation and screen flows

Primary navigation: People & access, Outlets, Vehicles & drivers, Calendar, Reference data, Devices, Integrations, Audit & investigation. Advanced policy documents live within People & access. Do not add dashboard metrics without a served data source. Selected member/persona state should survive reload and browser back through namespaced hashes or the existing navigation convention; this remains within the existing app-shell route.

| Screen | Information and actions | Main flow |
| --- | --- | --- |
| People & access | Persona cards/rows with server totals and active/disabled counts; each has View members, Permissions and Add member where allowed | Persona -> Members or Persona permissions |
| Persona members | Search, status/depot/outlet filters, cursor paging; name, email, account status, scope and individual-access badge | Select row -> Member details; Add member -> create flow with persona selected |
| Member details | Profile, all assigned personas, scope, effective access and direct policy attachments; edit, disable and reset password as permitted | Edit -> review -> save -> refetch; never clear a failed draft |
| Persona permissions | Capabilities grouped by module with human labels, On/Off toggles, implementation status, scope explanation and affected-member count | Change toggles -> Review changes -> confirm publication of policy revision |
| Member access | Inheritance and custom permission controls; effective result plus policy provenance and blocking reasons | Use persona access or customize -> review diff -> save -> server recomputation |
| Add member | Name, email, initial password, selected persona, scope and optional profile image for every persona, including Driver and Admin | Details/image -> Scope/access -> Review -> Create; image preview/remove, password rules and success feedback |
| Outlets | Filtered/paged directory, brand, district, derived depot, receiving window, dock/access indicators | Add outlet -> form -> validate/review -> publish reference version -> view outlet |
| Vehicles & drivers | All vehicle statuses/details, Add vehicle, Add driver, dated driver assignments | Add vehicle -> specification/depot -> review -> publish version; Add driver -> shared Add member form -> optional dated assignment; set day status or assign/end driver -> reason/review -> save |
| Calendar | Day details, generated marker, holiday/context indicators and effective operating status | Select date -> operating toggle + reason -> save |
| Reference data | Current snapshot, version history, source and import result/errors | Re-import configured server data -> review impact -> publish or show failures |
| Devices | Label, kind, depot, active/retired, last seen | Register or retire -> confirm -> refresh |
| Integrations | Catalogue age/staleness, circuit state; inbound quarantine and outbox dead letters | Open item -> inspect permitted details -> replay or discard with reason |
| Audit & investigation | Audit records filtered by time, actor, action, resource and decision; related orders, plans, loading, delivery, receipt/disputes, issues, sync, catalogue and intelligence records where served | Search/filter -> audit detail -> related operational record/history; inspect policy versions where permitted. Read-only investigation does not remove existing admin management actions |

Add member must handle partial onboarding honestly. Existing create and scope commands are separate transactions: after account creation succeeds, retain the user ID, apply scope steps, and retry only failed steps. Do not create a second account on retry or label the account fully configured before all selected scope operations succeed. A later atomic onboarding command is optional, not assumed to exist.

Every Add member entry point uses the same optional image picker: persona cards, Admin (super admin only), and Add driver from Vehicles & drivers. Show a square/circular preview, Replace and Remove before save, accepted format/size guidance, upload progress and a clear fallback avatar. Image failure must preserve the rest of the form and permit retry. Driver creation makes an `iam.users` account with the `driver` role; assigning that person to a vehicle for a date range is a separate optional step, never an automatic permanent scope. A newly created vehicle does not automatically create a driver account or assignment.

## 5. Permission toggles and inheritance

Extend IAM's existing policy evaluator according to [IAM-PLAN.md](IAM-PLAN.md). Common, persona and direct member grants combine; a member boundary limits the result; matching explicit Deny wins. Row scope and business invariants remain additional constraints. Persona memberships have AWS group semantics, not assumed-role semantics. Do not implement a competing authorization evaluator in React.

| Control | Behavior |
| --- | --- |
| Persona: Allow | Managed persona policy allows this action in its permitted resource scope |
| Persona: Not granted | No statement for this action in this managed policy; another persona or authorized member policy may grant it |
| Persona: Explicit deny | Managed persona policy denies the action and blocks all member or persona Allow grants |
| Member: Use persona access On | No managed member override for that action; display inherited outcome read-only |
| Member: Use persona access Off | Reveal a custom Allowed/Blocked toggle; store an explicit member Allow or Deny |
| Member custom Allow blocked by another policy | Do not show access as granted. Show the Deny source and disable ineffective elevation with an explanation |
| Policy assignment Enabled toggle | Activate/deactivate that attachment through a versioned command; distinct from a capability Allow/Block and from account active status |
| Reset to persona access | Remove the managed member override, preserve unrelated direct policies, and recompute effective access |

The replacement editor must distinguish Not granted from Explicit deny rather than label both Off. This supersedes the earlier binary Off-as-Deny UI proposal; it does not weaken Deny precedence or reinterpret any existing stored Deny. Member exceptions remain limited by the member's boundary and the administrator's delegation envelope. The current preview requires a follow-up implementation to represent these states.

Multiple personas may contribute to one member's result. Show all sources, not just the currently selected persona. Resource/condition-dependent actions must display 'Depends on scope/conditions' with concrete depot/outlet/resource context, not an unconditional green Allowed badge. A policy allowing an action does not grant access to every depot or outlet.

Policy documents remain immutable. Saving toggles produces a new managed document/version and atomically updates its default/attachment revision. Preserve advanced statements and other attachments; simple toggles must not replace an entire custom JSON policy. Restrict advanced JSON authoring to protected governance; ordinary Admin edits use the constrained delegation-safe form defined in IAM-PLAN.md. Validate the supported language, action catalogue and resource/condition contracts on the backend.

Changes are staged locally. A review shows affected persona/member, before/after capabilities, affected member count, scope and reason. Only the server acknowledgment changes the saved status; retain pending edits on errors. Disabling a Deny policy can expand access, so preview effective access for both enable and disable. Do not confuse the UI toggle with an unimplemented `enabled` column: attachment activation and its revision need a backend extension.

Backend must return editable capabilities, implemented flags, effective decisions/provenance, locked reasons and delegation limits. Admins may grant only the authority they are permitted to delegate; wildcard policies and arbitrary JSON cannot escape this limit. Administrative privilege controls are nondelegable to ordinary admins.

## 6. Add outlet: exact fields and storage implications

Source of truth: [live migration 002_reference.sql](../../../migrations/002_reference.sql), `ref.outlet_registry`, `ref.outlets`, `ref.brands`, `ref.districts` and `ref.depots`. Do not implement from the unapplied schema-design directory.

| Form field | SQL column / derivation | Requirement |
| --- | --- | --- |
| Outlet ID | `outlet_id` | Required unique stable text identifier; validate availability server-side; do not require an invented code format |
| Brand | `brand_code` | Required lookup from current reference version |
| District | `district_name` | Required lookup from current reference version |
| Depot | District's `depot_code` | Derived read-only; not a separately editable outlet field |
| Dock type | `dock_type` | Required: `rear_dock`, `street`, `mall_bay` with readable labels |
| Parking/access | `parking_constraint` | Required: `normal`, `van_only`, `mall_dock` |
| Receiving window opens/closes | `window_open_time`, `window_close_time` | Required local times; open must precede close |
| Mall access opens/closes | `mall_window_open`, `mall_window_close` | Required when parking/access is `mall_dock`; paired inputs and coherent window |
| Latitude | `latitude numeric(9,6)` | Optional; range -90 to 90 |
| Longitude | `longitude numeric(9,6)` | Optional; range -180 to 180 |
| Reference version | `reference_version_id` | Server-controlled; never an editable form field |

No current outlet-name, street-address, contact-phone or active-status column exists. Do not label those fields required or imply they persist. If later desired, they need an explicit contract and migration.

Form sections: Identity and location; Delivery access and windows; Optional coordinates; Review. Show the derived depot, van-only restriction and effective window (intersection of receiving and mall windows). Validate overlap, travel-profile coverage and brand/dock service allowance using the existing backend rules; capacity and planning decisions remain on the server. Local-time labels use Asia/Colombo. Validate paired coordinates at the application level if that input is offered, and distinguish this usability decision from current SQL, which allows independent null values.

Required backend work:

1. Add versioned `reference:CreateOutlet` (proposed name) with a catalogue entry, payload contract, authorization, idempotency, audit and scope checks; there is no existing outlet-create endpoint.
2. Add lookup reads for brands, districts and depots, an admin outlet list/search and a full outlet-detail DTO. Current `OutletView` omits raw mall windows and coordinates; `Outlet` domain and snapshot writer also omit coordinates. Carry supplied coordinates through write, reload and future snapshot copy; do not silently discard them.
3. Treat creation as a reference-snapshot publication: retain stable outlet identity, copy the current structural data, add the outlet, validate and atomically publish a fresh version. Published snapshots and historical plans stay unchanged.
4. Introduce a versioned reference-head revision for command concurrency, and pass the base reference version as well. Import and outlet creation use the same serialization/version guard so simultaneous edits cannot lose an outlet. Refresh caches only after commit, across instances.
5. Retain administrator additions as managed reference-source data within `ref`, with provenance and revision; compose them into later CSV imports. Imports must preserve additions, and conflicting source IDs must be reported rather than silently overwriting or dropping them. Any current-version/cache pointer change must remain consistent on unchanged-content imports too.
6. Separate supplied-dataset completeness from runtime network validation. `ReferenceValidator.Expectations.waypoint()` currently requires exactly 120 outlets; the 121st valid outlet must pass the admin creation path while the original seed dataset remains intact. Reuse referential/window/coverage rules without weakening them.
7. Return the outlet and published version, then refetch the directory. Offer a separate follow-up to grant a store manager access using IAM commands; do not write IAM scope tables from the reference module.

## 7. Current backend readiness and gaps

### Add vehicle: schema fields and publication

Source: live `migrations/002_reference.sql`, `ref.vehicle_registry` and versioned `ref.vehicles`. The vehicle form must include:

| Form field | SQL column | Requirement |
| --- | --- | --- |
| Vehicle ID | `vehicle_id` | Required unique stable text ID; check uniqueness on the server |
| Type | `vehicle_type` | Required `truck` or `van` |
| Temperature capability | `temperature_capability` | Required `reefer` or `ambient`; describe reefer as capable of ambient loads too |
| Weight capacity (kg) | `weight_cap_kg numeric(12,2)` | Required positive decimal |
| Volume capacity (m³) | `volume_cap_m3 numeric(12,3)` | Required positive decimal |
| Fuel type | `fuel_type` | Required text; use a served lookup if the backend defines one, otherwise validated input without inventing an enum |
| Fuel efficiency (km/l) | `km_per_l numeric(10,3)` | Required positive decimal |
| Weekly fuel quota (l) | `weekly_fuel_quota_l numeric(12,3)` | Required nonnegative decimal |
| Home depot | `depot_code` | Required lookup from the current reference version |
| Reference version | `reference_version_id` | Server controlled, not a form input |

There is no vehicle registration number, make/model, photo, active flag or permanent driver column in this table. Do not promise those as persisted inputs. Present Specifications, Capacity and fuel, Depot, then Review. Validate decimal precision and server rules before publishing. Availability/workshop is a separate dated `vehicle:SetDayStatus` command; driver assignment lives in IAM and uses a half-open date range.

Add `reference:CreateVehicle` (proposed) with catalogue entry, command handler, privilege/scope check, idempotency, audit and reference-head version guard. Publish a new immutable reference snapshot containing the vehicle and registry identity; preserve previous versions and current plans. As with outlet creation, retain admin-created vehicles as managed reference-source data so a later CSV import neither drops nor silently overwrites them. The validator currently expects exactly 60 vehicles in the supplied dataset: preserve that seed check while allowing a 61st valid runtime vehicle. Import and admin create must share concurrency control and cache refresh. Return new vehicle/version and refetch the fleet. Add full admin fleet/list/detail and depot lookup reads; current `/api/reference/vehicles?depot=&date=` only reports available vehicles and cannot serve an administration directory.

### Add driver and member images

Add driver is `iam:CreateUser` with role `driver`, followed by allowed depot scope and, if chosen, `iam:AssignDriver` with vehicle and `from`/`until`. Use the same staged, resumable onboarding behavior as Add member. Require a driver role before assignment; show assignment conflicts and the date range. Only the super admin can use the same flow with role `admin`.

`iam.users` and `AccountQuery.AccountView` currently have no image column; there is no account-image upload/read API. Add an optional profile image contract for all created personas, including a forward IAM migration for image metadata/reference, authorized upload/remove/read operations, and a serving/storage mechanism. Store the binary through a dedicated bounded media endpoint or storage service, not in a command JSON payload or public frontend directory. Validate actual content type (allow JPEG, PNG and WebP), byte size, pixel dimensions and image decoding on the server; normalize the image, strip metadata, and serve it through a stable account image URL with appropriate access control. Define a size limit in the contract and show it in the picker. The frontend uses `accept`, preview and client size feedback for usability, but server validation is authoritative. Account reads return image metadata/URL or null.

Chosen flow: create the account first, then upload and attach the image through an authorized versioned follow-up operation. Keep the created user ID and stable command ID; if image upload fails, show that the member exists and offer Retry image without creating another account. Ensure failed/replaced uploads are cleaned up, access to another member's image follows profile visibility, and image changes are audited without logging image bytes. No image is required to create a member; the fallback avatar remains available.

All existing mutations below use `POST /api/commands` except the legacy policy REST writes. Proposed endpoints/commands in section 8 are not currently callable.

| Feature | Current callable surface | Gaps to close |
| --- | --- | --- |
| Session | GET/POST `/api/session`, POST `/api/session/end` | Add `super_admin` across persisted roles, policies, session types/labels and routing; capability read for the console |
| Members | GET `/api/accounts?after=&limit=`, GET `/api/accounts/{id}`; `iam:CreateUser`, `iam:UpdateUser`, `iam:DisableUser`, `iam:ResetPassword` | Persona-filtered keyset paging, search/counts, privilege-target checks, last-dispatcher guard, optional account image storage/upload/read; current version guard covers edit/disable/reset only |
| Role changes | Role chosen at create; `iam:ChangeRole` catalogue row exists | Handler, version guard, session revocation, protected-role checks, documented add/remove semantics for multi-role accounts |
| Scope | `iam:GrantScope`, `iam:RevokeScope`; one depot or outlet per command | Version guards, explicit scope-delegation limits, complete picker lookups and authoritative refresh |
| Drivers | Driver accounts use `iam:CreateUser`; GET `/api/accounts/driver-assignments?on=`; `iam:AssignDriver`, `iam:EndDriverAssignment` | Dedicated Add driver UI reuses Add member with Driver selected; assignment revision and complete vehicle lookup. Date ranges are half-open; overlap on one vehicle is already refused |
| Policies | GET `/api/policies` summaries; REST create/version/default/attach/detach | Convert writes to commands; read document/history/attachments/catalogue; managed toggle updates, delegation validation, effective-access preview and versioned attachment activation |
| Devices | `iam.devices` table and register/retire action catalogue entries | List/details DTOs, commands, state revision, retire/session behavior |
| Reference | GET `/api/reference/version`; `reference:Import` from configured server directory | History, failed-import details, revision/impact preview, outlet and vehicle creation and import preservation |
| Calendar | GET `/api/reference/calendar/{date}`; `calendar:Override` | Versioned overrides and reads of reason/actor/history. Existing command edits operating status, not independent holiday metadata |
| Vehicles | GET `/api/reference/vehicles?depot=&date=` returns available only; individual lookup; `vehicle:SetDayStatus` | No CreateVehicle command exists; add versioned create/publish, managed source persistence, complete fleet/day-status/history reads and row versions |
| Integrations | Warehouse view/command contracts and outbox tables | Real health, inbound quarantine, dead-letter reads and replay/discard handlers from #6/#7 |
| Planning parameters | Target requirement only | Optional, after #9 serves versioned data |
| Audit & investigation | Audit recording exists; `audit:Read` is catalogued but marked unimplemented in migration 006; former Auditor read actions are listed in the policy migration | Deliver authorized paged audit reads/detail and module-owned investigation queries; include the full read set in Admin/Super admin, retire the Auditor persona and preserve historical attribution. Policy grants alone do not prove these views exist |

Backend reference files: `identity/web/AccountAdminController.java`, `identity/application/AccountQuery.java`, `AccountAdminUseCase.java`, `IdentityCommandHandlers.java`, `identity/web/PolicyAdminController.java`, `identity/application/PolicyAdminUseCase.java`, `identity/infrastructure/JdbcPolicyRepository.java`, `identity/domain/policy/PolicyEvaluator.java`, `referencedata/web/ReferenceController.java`, and its import/calendar/vehicle application handlers.

Account passwords require at least 12 characters. Self-disable is already refused. Reset and disable revoke sessions. The current `admin` policy broadly allows `iam:*`; introducing super admin requires changing the authorization behavior, not just adding a role string. Catalogue existence or `implemented` metadata alone is not proof of an HTTP read surface.

## 8. Proposed contract additions and ownership

Names here are proposed and should be settled with backend owners before client implementation. Every write is a command with `commandId`, `expectedVersion`, `clientRecordedAt`, payload and a result acknowledging the applied revision. New records use a documented creation convention; versioned updates never silently accept null.

| Owner | Read contracts needed | Command work |
| --- | --- | --- |
| Identity (#5) | Roles/personas + visible counts; server-filtered accounts with optional image metadata; available/delegable actions; full policy/version/attachment details; member/persona effective access + provenance + scoped conditions; devices | Protected account creation/promotion including Driver; versioned image attachment, scope and assignments; policy create/version/default/attach/detach commands; managed persona/member permission update; attachment activation; device register/retire |
| Reference | Brands/depots/districts; full outlet list/detail and complete vehicle list/detail; reference-head revision/history; import preview/failures; day-status and calendar-override views | `reference:CreateOutlet`, `reference:CreateVehicle`; versioned import, calendar and vehicle commands; managed-addition persistence |
| Warehouse (#7) | Catalogue age/circuit state; paged quarantined inbound events | Existing proposed `warehouse:ReplayInbound`, `warehouse:DiscardInbound` contracts implemented with actor/reason/version rules |
| Platform (#6) | Paged dead letters with delivery attempts and safe error details | `platform:ReplayEvent` with confirmation/result and existing event identity preserved |
| Frontend (#22) | Mirrors in `shared/domain/identity.ts` and reference/platform contracts | Gateway, hooks, shared member form/image picker, Add driver and Add vehicle forms, controlled switches, permission provenance, concurrency and failure UI |

Use a backend decision/preview contract to compute policy impact and effective permissions; do not derive them from incomplete pages of users. Account paging currently returns `{ accounts, nextAfter }`; either extend compatibly or add a documented admin read, without breaking the existing shape. Role totals cannot be computed from one page of accounts. Cursor pagination, search and persona filters operate server-side.

Audit consolidation ownership: Identity owns the Admin/Super admin read grants and legacy Auditor role/policy transition. Platform owns the authorized cursor-paged audit query/detail contract, including actor, action, resource, decision, reason and timestamp filters/fields where stored. Each operational module owns its investigation record/history read; the admin frontend consumes those published contracts without cross-module table access. Add investigation query hooks and related-record views to the frontend scope. Deliver these alongside operational administration in implementation step 5, and verify that audit reads cannot edit or delete audit history.

## 9. Frontend layout and responsibilities

```text
src/roles/admin/
  index.tsx                    Workspace container and capability-aware navigation
  navigation.ts                Namespaced persona/member/view state
  Sidebar.tsx                  GO shell using shared primitives
  screens/
    People.tsx                 Persona directory and entry points
    PersonaMembers.tsx         Search/filter/paged member list
    MemberDetails.tsx          Profile, scope and access tabs
    AddMember.tsx              Shared persona-specific onboarding, including Add driver
    MemberImageField.tsx       Optional image picker, preview, replace/remove and progress
    PersonaPermissions.tsx     Default capability toggles
    MemberPermissions.tsx      Inheritance, custom toggles, effective access
    PolicyDetails.tsx          Versions, attachments, advanced editor
    Outlets.tsx / AddOutlet.tsx
    Vehicles.tsx / AddVehicle.tsx / DriverAssignments.tsx
    Calendar.tsx / ReferenceData.tsx
    Devices.tsx / Integrations.tsx
    AuditInvestigation.tsx       Audit filters/list/detail and related-record navigation
  data/
    gateway.ts                 Read API boundary; no sample gateway
    useAdminCommands.ts        Stable command IDs, send state, conflicts, refresh
    usePeople.ts               Query keys, pagination, filters
    useAccess.ts               Served permissions and impact preview
    useOutlets.ts              Lookups, listing and creation flow
    useVehicles.ts             Full fleet, create vehicle and dated assignments
    useMemberImage.ts          Authorized upload/attach and resumable retry
```

Keep each component around or below 300 lines by separating views, forms and hooks. Reuse `@shared/ui` and `@shared/api`. Roles must not import dispatcher/store internals; move genuinely shared primitives into `shared` and use the existing screens only as pattern references.

Keep `AddMember`, `MemberImageField` and `AddVehicle` under `src/roles/admin/screens/`; keep their server interactions in `src/roles/admin/data/`. Use `src/shared/domain/identity.ts` and `src/shared/domain/referencedata.ts` for backend contract mirrors, `src/shared/api/` for transport helpers, and `src/shared/ui/` only for genuinely reusable primitives. Add no parallel `components/` tree or imports from another role's internals. The image picker may become shared UI only if another role actually reuses it.

Update `RoleRouter` for both admin personas, `ShellRole`/labels, online-tier declarations and boundary tests. Remove Auditor from current persona selectors, onboarding and routing after the legacy-assignment transition in section 2. Keep authentication in the existing shell; no separate admin login. Add admin to the roles tested for cross-role imports, and apply the same tests to any new privileged role representation.

Shared switch semantics: native button with `role="switch"`, `aria-checked`, visible label, keyboard support, disabled/busy indication and text feedback. Do not communicate permission solely with color. Dialogs trap focus, restore it on close, support Escape and announce errors. Staged edits remain visible if a conflict or connection failure occurs.

Handle 401 as reauthentication, 403 as No access, 409 VERSION_CONFLICT as reload/review, other 409 as specific conflict, 422 as validation details, and 503/network as unavailable with explicit retry. Do not queue admin writes. Do not manufacture counts, last-seen times, health statuses or implementation flags while endpoints are absent.

## 10. Implementation sequence and PR boundaries

1. **Design evidence and contracts:** inspect Figma through an available connector/browser or supplied design export; record reusable frame/component mappings; agree the proposed DTOs, permission-toggle semantics, protected-role policy, reference versioning and managed-addition import behavior. Add rule/edge-case entries before implementation changes.
2. **Identity foundations:** add super-admin bootstrap and protection; complete role/policy/device commands and reads, scoped administration/delegation, effective-access previews and revision guards. Add targeted backend authorization/concurrency tests. Preserve an explicit migration/recovery path for current admins.
3. **People and access UI:** implement GO admin shell, personas, member list, Add member with optional image for every persona, profile/scope/actions, persona/member switches and advanced policy view. Add driver reuses this form and may continue to a separate dated assignment. Connect to real APIs. Verify ordinary admin versus super admin flows, partial image/scope retry and session refresh after changes.
4. **Outlet and vehicle foundations/UI:** add versioned create commands, lookups/full DTOs, outlet coordinate round-trip, managed additions and dynamic completeness validation; implement both directories and form/review flows. Test later imports and historical snapshots for both entity types.
5. **Operational administration:** complete calendar/fleet/reference reads and guards; implement dated driver assignment and override/import flows. Add devices and integration screens only against served dependencies from #5/#6/#7. Planning parameters remain optional.
6. **Acceptance and design comparison:** run required backend tests against a dedicated test database, frontend boundary tests, typecheck/build and Playwright flows. Compare responsive screenshots to inspected Figma frames and document admin extensions in README/design mapping. Add this issue's WALKTHROUGH.md and update development log, rules and edge-case records; do not close the issue with placeholder flows.

Work may be split across owners, but UI completion is gated by the corresponding real backend contract. This plan does not authorize sending messages to those owners or changing the Figma file.

## 11. Acceptance tests

- Admin sees accurate permitted persona member counts and paged lists; selecting a persona returns all matching members through cursor paging, not just client filtering of the first page.
- No separate Auditor persona, Add member choice or workspace exists. Admin and super admin can use every former Auditor read capability against served APIs within scope, including audit search/detail and related investigation records. Authorized admin writes still succeed because the legacy `NeverWrite` policy was not copied. Legacy role assignments and historical audit attribution survive a tested transition without automatic privilege escalation.
- Create an operational member, grant scope, sign in as them and prove access to the selected scope and denial outside it. A failed scope step can resume without duplicating the member.
- Every permitted Add member path offers an optional image picker with preview, replace/remove and progress. A valid image persists and renders after reload; invalid type, oversized or malformed files are refused; an image failure can retry against the already-created account. Creation without an image shows a fallback avatar. Add driver creates a Driver account and optional dated assignment without silently assigning a vehicle forever.
- Super admin can add/promote an admin; ordinary admin is denied through direct API create/promotion, edited payloads, policy attachment/default changes and wildcard grants. Ordinary admin cannot reset or modify a protected account to bypass this rule.
- Last dispatcher and last super admin cannot be removed/disabled, including concurrent changes. Self-disable remains refused.
- Persona changes affect inheriting members; explicit member blocks remain; reset-to-inherit removes only the intended override. Multiple-role Deny precedence and scope-dependent outcomes match the backend result.
- Unknown actions are rejected; unimplemented capabilities are visibly unavailable. Policy attachment disabling can neither falsely claim blocked access nor silently widen protected authority. Policy/cache changes are effective on the next authorized request.
- Stale member, scope, policy, assignment, calendar, vehicle or reference-head edits produce conflicts without overwriting another administrator's changes. Retry after a lost response does not duplicate the command.
- Add a 121st valid outlet, verify all fields including optional coordinates after reload, derived depot, mall effective windows and lookup availability; refuse duplicate IDs, missing mall times, impossible windows and invalid coordinates. Retain original 120 seed records.
- A new outlet survives a later server-data import. Simultaneous import/create cannot lose it. A prior reference version and published plan remain unchanged. Failed validation changes neither registry/current version nor cache.
- Add a 61st valid vehicle with all schema fields, then verify list/detail, depot, decimal capacities, type/temperature capability, historical reference snapshot and persistence after a later import. Reject duplicate IDs, invalid enum/depot, nonpositive capacity/efficiency and negative quota. Concurrent import/create cannot lose it. Creating a vehicle does not create a driver or assignment; dated availability remains separate.
- Assignment range boundaries and overlapping-driver rejection are understandable in the form. Calendar shows generated status and required reason. Fleet includes unavailable/workshop vehicles once the admin read is delivered.
- Devices and integration replay/discard are tested against real handlers, with appropriate denied-access and repeat-command cases.
- Direct unauthorized admin API requests return 403 and the UI shows No access. Offline admin forms show unavailable writes and preserve drafts, without entering the operational offline queue.
- Keyboard-only forms/switches/dialogs, focus restoration, status announcements, long names/emails, loading/error/empty states and desktop/tablet/phone screenshots pass.
- Run `npm test`, `npm run typecheck`, `npm run build`; add a functioning Playwright command/config for these flows (the current package does not define `test:e2e`). Database integration tests must use `TEST_DATABASE_URL` distinct from application data. Existing test source is evidence of coverage intent, not a fresh passing run.

## 12. Remaining decisions and blockers

- **Figma access resolved:** browser access works. Section 3 records direct foundation/sidebar links, screen mappings and component decisions. Remaining implementation checks are exact per-control typography/spacing, missing navigation assets, representative list/form/detail frames and prototype behavior. No permission-switch variant or dedicated admin frame has been verified; these are explicit design extensions.
- **Chosen for this proposal:** structured persona/member permission toggles first, advanced JSON editor secondary; inherit/custom state explicitly modeled; Deny precedence retained; reference import remains server-directory based; add-outlet and add-vehicle use immutable reference publication with persistent managed additions; profile image is optional and attaches after account creation with resumable retry.
- **Chosen authority decision:** ordinary admins manage operational membership within delegated authority and include all former Auditor investigation/read capabilities. There is no separate Auditor persona. Super admins alone manage admin membership and administrative privilege. Additional super-admin creation is a controlled host operation. These are project decisions derived from the user's requirements, not booklet claims.
- **Backend dependencies:** the gaps in sections 6-8 are part of delivering the requested behavior. A frontend-only change cannot safely implement super-admin protection, effective permission overrides, outlet/vehicle persistence or account-image storage.
- **Issue completion:** all required issue 22 flows and the user's additions must work against real APIs, with verified Figma continuity. Pending dependencies and unavailable tools are not a reason to mark the issue complete.
