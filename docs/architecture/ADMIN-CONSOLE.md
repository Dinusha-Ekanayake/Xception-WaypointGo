# Admin and super admin UI

This is the UI implementation and permission proposal requested on 2026-09-27. The challenge booklet defines four operational personas (pp. 6-7). The two administrative levels below are the user's product decision, not a booklet requirement.

## Hierarchy

| Role | Responsibility |
| --- | --- |
| Super admin | Full system access. Exclusively creates and manages admins. May also manage the four operational personas. |
| Admin | Creates and assigns operational accounts, sets their scope, changes role permission defaults and individual overrides. Cannot create admins, promote anyone to admin, or manage other admins. |
| Operational personas | Perform their assigned workflow. No account or policy administration. |

The backend currently has the original `admin` role and policy APIs. It does **not** yet implement this two-level hierarchy or the account directory and lifecycle API required by this UI. No existing administrator has been silently promoted. No database migration or backend authorization change is included.

## Permissions to grant

The canonical **UI proposal** is `frontend/src/roles/admin/permissions.ts`. Its identifiers are not backend action names and must not be sent directly to the policy API. Each permission carries its source and booklet pages. These defaults all start enabled in the demo.

| Persona | Booklet-derived permissions | Data scope |
| --- | --- | --- |
| Dispatcher | View confirmed orders; close the queue; build and adjust allocations; defer with a reason and review previous skips; monitor delivery progress and problems; view future capacity forecasts | Assigned depots |
| Loader | View the latest loading list and stop sequence; record loading checks; flag missing or damaged goods before departure | One assigned depot |
| Driver | View the assigned route and instructions; record delivery outcomes; capture proof; report delivery problems | Assigned vehicle and effective dates |
| Store manager | Place and confirm orders; track order status and expected arrival; view deferral notices; confirm receipt; report receipt issues | Assigned outlets |

Recommended product extensions are visibly labelled separately: dispatcher plan publication and vehicle availability; loader release and vehicle-change requests; store manager order amendments and cancellations. These are not represented as explicit booklet permissions.

An interchange permission requests a review; it never authorizes a loader to approve an invalid allocation. Driver offline recording and later synchronization are mandatory capabilities (pp. 5-7), not switches. Capacity, refrigeration, outlet access, delivery windows, cutoff and fuel constraints remain mandatory regardless of privileges, including super admin privileges. Full access does not bypass operational validation.

Role templates apply to a persona. Individual overrides can inherit, allow or deny a permission from that persona's set. Suspended demo accounts have no effective operational permissions. Revoking core permissions produces a workflow warning; a reason is required to save. Scope is separate from actions and can never be expanded by a permission toggle.

## Screens and routes

- `/super-admin/demo`: overview, administrator creation and management, operational team, role defaults, individual overrides, scope assignments and activity export.
- `/admin/demo`: the same operational management without administrator management or an admin role choice.
- `/super-admin` and `/admin`: server-session-backed entry screens. They show an explicit unavailable state after authentication because the necessary live APIs are missing. They never render fictional accounts as real accounts.
- `/`: links to sign in and both demos; authenticated administration roles route to the corresponding entry screen, with super admin taking precedence.

Demo mutations only affect synthetic data in `sessionStorage`, separately per role and browser tab. They survive reload, can be reset, never call mutation APIs, and create no login credentials or invitations. CSV exports contain only the displayed demo activity. The demo is not an authorization boundary or production audit record.

## Connecting production later

1. Add a forward-only migration for `super_admin` and its policy, with an explicit trusted bootstrap process. Decide global scope handling in the application/database without granting a PostgreSQL bypass role.
2. Implement account listing, creation, editing, suspension and assignment commands through the command bus. Check actor and target privilege inside the transaction, including rejecting admin-to-admin changes and self-elevation. Revoke sessions as required.
3. Map the UI proposals to catalogued backend actions and implemented handlers. Use versioned policies, explicit deny semantics, current scope, and revision checks; do not flatten overlapping policies into a misleading checkbox.
4. Add paginated reference options, accounts, effective permissions and activity reads. Use real reference contracts rather than the demo's generated outlet/vehicle options.
5. Replace the explicit unavailable screen with those adapters. Require command IDs, expected versions and reasons, handle conflicts and outages visibly, and add denied-scope integration tests. The browser's role selector must never authorize a request.

## Verification

Run from `frontend/`: `npm run typecheck`, `npm test`, `npm run build`, and `npx playwright test --config playwright.admin.config.ts`. Browser tests use installed Chrome. To test an already running local server, set `ADMIN_TEST_URL` to its origin.

The tests cover role separation, individual denies, duplicate identities, driver assignment dates and overlap, account creation and suspension, scope editing, permission persistence, activity records, blocked live routes, mobile navigation and overflow. Screenshots are generated under ignored `frontend/test-results/`.
