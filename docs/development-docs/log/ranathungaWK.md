# Development log: @ranathungaWK

@ranathungaWK's entries, newest first. Only @ranathungaWK adds to this file; how to write an entry is in the [log's index](../development-log.md).

---

## 2026-10-04 - fix: render admin depots while loading

`dev` · @ranathungaWK

Guard depot-specific calculations until the scoped depot list arrives, and show a loading or unavailable state when it is empty.
Why: the preview Depots page dereferenced `currentDepot.id` on its first render and fell into the app error boundary.
Verified: frontend typecheck and Next.js compilation on the matching local fix; preview VPS backend and frontend containers healthy.

---

## 2026-10-04 - fix: grant reference reads of actor scope

`codex/admin-live-wiring` · @ranathungaWK

Give the reference module read access to only the depot and outlet scope tables, with policies limited to the current actor.
Why: the admin reference directory calls the shared scope predicates in SQL; CI showed waypoint_ref lacked IAM schema usage and returned 500.
Verified: CI failure traced to the scoped depot query; the forward migration awaits a fresh integration run.
Open: confirm the admin integration test on CI and keep policy editing as separate issue 22 work.

---

## 2026-10-03 - fix: align admin reads with backend and SQL scope

`dev` · @ranathungaWK

Add scoped admin directories for current reference data, orders and plans; connect the console to supported account, audit and forecast APIs and remove invented operational values.
Why: VPS inspection found missing admin routes and an admin account with no depot grants; the console was showing stale mock access decisions and empty or failed data.
Verified: frontend typecheck, 161 Node tests, production build, backend test compilation. Database integration test could not run because Maven Surefire dependency retrieval failed certificate validation.
Open: deploy the local routes and grant intended production depot scope; implement versioned IAM policy editing and effective access reads before enabling those controls.

---

## 2026-10-03 - refactor: purge admin mock data and wire directly to live backend

`dev` · @ranathungaWK

Remove all mock, fallback and fixture datasets from admin frontend screens (Orders, Trips, Outlets, Vehicles, Forecasts, and Audit). All views now query live backend endpoints directly with clean empty states.
Why: replaces sample datasets with authentic operational data and dynamic resolution across the admin console.
Verified: typecheck (0 errors), boundaries tests (6/6), unit and wording tests (144/144 passed), and Next.js production build with service worker compilation passed.
Open: nothing.

---

## 2026-10-03 - feat: wire admin frontend and backend with live reference and access APIs

`dev` · @ranathungaWK

Wire admin console screens (Depots, Outlets, Vehicles, Orders, Trips, People, Actions) to live backend endpoints with offline resilient fallbacks, typed identity and reference contracts, and glossary compliant wording.
Why: provides live management operations and data inspection for the admin workspace across depots, retail outlets, fleet vehicles, and IAM permissions.
Verified: boundaries test (6/6 passed), typecheck (0 errors), npm test (144/144 passed), and production build with service worker compilation passed.
Open: nothing.

---

## 2026-10-02 - fix: simplify admin preview sidebar

`dev` · @ranathungaWK

Remove the extra Waypoint label and Demo workspace card from the admin sidebar.
Why: they cluttered the navigation and repeated the preview context already shown elsewhere.
Verified: frontend typecheck and production build passed.
Open: nothing.

---

## 2026-10-02 - fix: keep admin demo depots to Peliyagoda and Kandy

`dev` · @ranathungaWK

Remove Galle from admin member assignments, vehicle samples, forecasts and depot selectors; share one demo depot list.
Why: Galle is a delivery district served by Peliyagoda, not a depot.
Verified: frontend typecheck and production build passed.
Open: nothing.

---

## 2026-10-02 - fix: use clear admin navigation icons

`dev` · @ranathungaWK

Add people, permission list, history and audit icons to the admin sidebar while retaining the GO icon style.
Why: the former catalogue asset was white on a light background and several navigation symbols did not describe their destinations clearly.
Verified: frontend typecheck and production build passed.
Open: nothing.

---

## 2026-10-02 - fix: align admin people list columns

`dev` · @ranathungaWK

Give the member identity, persona and place, exception badge, and action stable desktop columns.
Why: variable badge presence shifted values and actions between rows.
Verified: frontend typecheck and production build passed.
Open: nothing.

---

## 2026-10-02 - fix: place admin preview sign out under the sidebar profile

`dev` · @ranathungaWK

Move the demo sign out action below the sidebar account summary and keep it available on smaller screens.
Why: the account control belongs beside the profile shown in the sidebar.
Verified: frontend typecheck and production build passed.
Open: nothing.

---

## 2026-10-02 - fix: align admin preview sidebar with page top

`dev` · @ranathungaWK

Overlay the shell session controls on the admin preview at desktop widths so its sidebar begins at the top of the page.
Why: the separate shell control row left a visible gap above the sidebar.
Verified: frontend typecheck and production build passed.
Open: nothing.

---

## 2026-10-02 - chore: refresh legacy CI actions

`dev` · @ranathungaWK

Move the legacy CI workflow to current checkout, Java, Node and Python actions and pin Ubuntu 24.04.
Why: the failed run reports deprecation warnings for the older actions and a pending `ubuntu-latest` migration.
Verified: `git diff --check` passed. Backend tests could not run locally because required Maven artifacts were unavailable and the Maven repository TLS certificate was rejected.
Open: the backend job's exit-code annotation does not identify its failing test; the private job log is needed to isolate it.

---

## 2026-10-02 - fix: show admin mock on preview role address

`dev` · @ranathungaWK

Route the signed-in admin role on `admin-preview.waypointgo.live` to the existing interactive sample console instead of the placeholder. Keep the normal session and role gate.
Why: nginx already serves the hostname, but the frontend role router still showed "not built yet".
Verified: frontend typecheck and production build passed.
Open: the admin console uses sample data; live backend wiring remains in issue #22.

---

## 2026-10-02 - fix: match admin preview branding

`dev` · @ranathungaWK

Set the admin font token to the bundled Google Sans Flex Variable family and pair the black GO mark with a white Preview pill in the login and workspace headers. Use a plain mint login background and remove the remote font import.
Why: match the supplied references and use the actual font family registered by the bundled font files.
Verified: browser font inspection confirmed the custom Google Sans Flex face on the admin heading; frontend typecheck and production build passed.
Open: nothing.

---

## 2026-10-02 - fix: reconcile audit console with dev

`23-auditor-console` · @ranathungaWK

Retain AuditConsole for both audit and focused permission history routes when merging dev. Preserve the current session flow and both branches' documentation.
Why: the admin placeholder must not replace the completed mock audit workspace.
Verified: frontend typecheck and production build passed.
Open: audit UI still uses mock data.

---

## 2026-10-02 - feat: build the mock audit console

`23-auditor-console` · @ranathungaWK

Add Overview, Activity and Access & security views, 26 sample operational events, URL filters, event drawer, related activity and visible feed states. Permission Change history now reads the same event adapter. See [audit plan](../issues/022-admin-console/AUDIT-CONSOLE-UI-PLAN.md).
Why: provide a consistent investigation flow while keeping authorization decisions distinct from execution outcomes.
Verified: non-incremental TypeScript check and production build passed; `/access-demo` returned HTTP 200. Browser interaction remains unverified under the current browser-tool limitation.
Open: live audit reads, server scope, exports and investigation cases remain future work. Mock Admin visibility excludes governance fixtures; no backend authorization was added.

---

## 2026-10-02 - docs: plan the GO audit console

`23-auditor-console` · @ranathungaWK

Define the mock audit views, event detail flow, visibility rules and live data gaps in [AUDIT-CONSOLE-UI-PLAN.md](../issues/022-admin-console/AUDIT-CONSOLE-UI-PLAN.md).
Why: extend the established GO theme with a contextual audit workflow and keep authorization decisions distinct from execution outcomes.
Verified: checked current mock, audit write contract and schema; no application code changed.
Open: fresh Figma access was unavailable; design references use supplied screenshots and the earlier recorded inspection.

---

## 2026-10-02 - fix: resolve admin session merge conflict

`22-admin-console` · @ranathungaWK

Merge current dev and retain its session state, sign-in, role routing and pending offline write protection. Keep the standalone `/access-demo` route and both branches' documentation.
Why: the older checked/session rendering conflicts with the newer state-based shell.
Verified: frontend typecheck and production build passed.
Open: the admin UI remains a mock demo; live integration is unchanged.

---

## 2026-10-02 - feat: add mock forecasts and vehicles tabs

`22-admin-console` · @ranathungaWK

Add main Forecasts and Vehicles tabs beside Audit console. Show clearly labeled sample demand, fleet summaries, filters and vehicle details without calling the backend.
Why: admins need these workspaces visible in the demo navigation before the backend forecast capability is available.
Verified: non-incremental TypeScript check and production build passed; `/access-demo` returned HTTP 200 on port 43000.
Open: forecast values and vehicle records are mock data; live actions need backend integration.

---

## 2026-10-02 - refine: GO login and expandable access navigation

`22-admin-console` · @ranathungaWK

Replace the mock identity picker with a GO-style staff ID and password screen based on the supplied reference. Make People & access a main sidebar item that reveals its four subpages; keep Audit console beside it.
Why: the demo login and navigation should match the requested admin workspace hierarchy.
Verified: non-incremental TypeScript check and production build passed; `/access-demo` returned HTTP 200 on port 43000.
Open: credentials are demo-only and no server session is created.

---

## 2026-10-02 - refine: role-specific member setup and navigation

`22-admin-console` · @ranathungaWK

Make the mock Add member form conditional by persona, remove its reason field, and keep Admin creation inside that form for Super admin. Hide the Super admin persona and directory entry; group access subpages and add a separate mock Audit console navigation item.
Why: member setup and navigation should match each role's actual choices and the requested admin hierarchy.
Verified: non-incremental TypeScript check and production build passed; the updated demo returned HTTP 200 on port 43000.
Open: assignments and audit events remain frontend-only sample data.

---

## 2026-10-02 - feat: add persona-based mock members

`22-admin-console` · @ranathungaWK

Replace the People page's Add admin control with Add member for both administrative previews. Collect persona and matching depot, warehouse/depot or outlet assignment; reserve Admin creation for Super admin. Persona pages can start with their role selected.
Why: operators need one member-creation flow for all operational personas while preserving privileged account boundaries.
Verified: non-incremental TypeScript check and production build passed; the updated demo server started on port 43000.
Open: this remains frontend-only sample data and does not create real accounts.

---

## 2026-10-02 - fix: keep access demo dialogs open

`22-admin-console` · @ranathungaWK

Remove the native dialog close event handler that cleared modal state during React development effect cleanup. Add exception, permission details/edit, and Add admin share this dialog.
Why: these controls appeared inert because their dialog closed immediately after opening.
Verified: non-incremental TypeScript check passed.
Open: browser interaction could not be verified under the current browser security policy.

---

## 2026-10-02 - fix: complete access demo controls

`22-admin-console` · @ranathungaWK

Fix scoped member exception evaluation, protected edit controls, unchanged saves, history details, catalogue availability labels, Add admin entry points and reset navigation in the frontend-only permission demo.
Why: Admin and Super admin preview controls produced misleading results or incomplete flows.
Verified: non-incremental TypeScript check and production build passed; `/access-demo` returned HTTP 200 on port 43000. Browser interaction was unavailable under the current browser security policy.
Open: live authorization and account creation remain separate backend work.

---

## 2026-10-01 - feat: align access demo with GO visual language

`22-admin-console` · @ranathungaWK

Refine the isolated permission demo with the Figma style guide and dispatcher desktop shell: Google Sans Flex preference, pale mint canvas, GO navigation, white surfaces and clearer persona cards. Add a demo link to the signed-out root page.
Why: the mock route was hard to discover from `/` and its styling did not read as part of the GO product.
Verified: non-incremental TypeScript check passed; the development server returned HTTP 200 for `/access-demo` after launching with the required local filesystem access.
Open: production build and visual browser inspection remain unverified in this run.

---

## 2026-10-01 - feat: align access demo with GO visual language

`22-admin-console` · @ranathungaWK

Refine the isolated permission demo with the Figma style guide and dispatcher desktop shell: Google Sans Flex preference, pale mint canvas, GO navigation, white surfaces and clearer persona cards. Add a demo link to the signed-out root page.
Why: the mock route was hard to discover from `/` and its styling did not read as part of the GO product.
Verified: non-incremental TypeScript check passed. Browser verification and build remain open because the sandbox denied Next.js writes to `.next`; the elevated build request was rejected.
Open: rerun the production build and inspect the refreshed route when filesystem access permits.

---

## 2026-10-01 - docs: plan capability management screens

`22-admin-console` · @ranathungaWK

Define persona modules, member exceptions, review and history in [the capability UI plan](../issues/022-admin-console/CAPABILITY-UI-PLAN.md), with isolated mock data and reusable components.
Why: the full action catalogue obscures relevant persona access; scoped and expiring mock exceptions must not imply backend support.
Verified: compared existing IAM plans and repository structure; documentation only, no application tests run.
Open: build the mock UI next; current checkout lacks the earlier admin source directory.

---

## 2026-10-01 - feat: build access capability mock

`22-admin-console` · @ranathungaWK

Add the isolated `/access-demo` route with persona modules, member access, all 76 inventory actions, review, simulated saves and mock history. See [capability UI plan](../issues/022-admin-console/CAPABILITY-UI-PLAN.md).
Why: operators need relevant business capabilities and clear member exceptions instead of an unfiltered technical list.
Verified: non-incremental TypeScript check, production build and browser walkthrough of persona edit/review/save.
Open: live permissions, scope/expiry, impact and history require backend contracts; mock saves have no server effect.
