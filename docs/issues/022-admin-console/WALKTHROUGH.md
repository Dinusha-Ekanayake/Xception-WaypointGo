# Admin console frontend rebuild

## Managed reference creation (2026-10-04)

The Add depot, Add outlet and Add vehicle dialogs now send `reference:CreateDepot`, `reference:CreateOutlet` and `reference:CreateVehicle` through the command bus. Each handler validates the full candidate snapshot, stores the managed source row in `ref.managed_additions`, publishes a new immutable version, and refreshes the local cache after commit. A later CSV import overlays the managed rows under the same publication lock. Vehicle and outlet commands require scope for the existing depot; the outlet's depot is derived from its published district. A new depot needs a supplied approximate point and starts empty. Its creator still needs an IAM depot grant to see it in scoped admin reads.

The UI collects only fields represented in the reference schema and reloads from the API after saving. After depot creation it submits a separate, versioned `iam:GrantScope` for the creator and reports if that grant fails. People and Trips sidebar parents have collapse chevrons; the Trips content no longer repeats the Planned/Live choice. Persona and action details do not expose internal role resource names. Verify with frontend typecheck, tests and build, plus `ReferenceValidatorTest`, `ModuleBoundaryTest` and `ReferenceCreationIntegrationTest` against a dedicated PostgreSQL test database.

## Capability UI mock, 2026-10-01

The current checkout adds an isolated interactive mock at `http://127.0.0.1:43000/access-demo` when the frontend is started on port 43000. The route does not require a session and never sends permission writes. The signed-out root page links to it. It is labeled Mock data in the page chrome; its state resets on reload. The shell follows the supplied Figma file's style guide and dispatcher desktop language; that file has no separate Admin screen. The existing walkthrough below describes an earlier admin rebuild whose source files are absent from this checkout.

`frontend/src/roles/admin/access/model.ts` defines the 76 documented catalogue actions, persona baselines, decision precedence and derived counts. `fixtures.ts` provides 24 fictional operational members, two admins, one super admin, a dual-persona member, an expired exception and a direct deny. `screens.tsx` contains People, Personas, Catalogue and History. `components.tsx` contains the shared rows, badges and modal. `AccessDemo.tsx` owns browser-memory drafts, review, simulated save and navigation; `frontend/app/access-demo/page.tsx` exposes the route.

Walkthrough: choose Personas > Dispatcher to see five active actions and one optional calendar override. Choose a module to narrow the rows. Select Edit, choose a decision, enter a reason, review affected members and save the demo change. People > Nimali Perera > Access shows inherited capabilities and the member exception editor. Catalogue exposes the complete demo action inventory; History records simulated changes. Reset demo restores the fixture. Planned operations remain read-only.

Verified with `npx tsc --noEmit --incremental false`, `npm run build` and browser inspection of persona navigation, editor review and a simulated save. The usual `npm run typecheck` could not write its incremental cache in the sandbox; the non-incremental equivalent passed. The browser inspector confirmed the demo route and state update. Scope/expiry, impact and history have no live backend contract here. The mock does not prove current database state or grant access to any real user.

---

## Open the frontend

The development server started for this handoff is available at:

http://127.0.0.1:3000/admin-preview

Use **Preview as** to switch between Admin and Super admin. All records in this route are explicitly labeled samples. It makes no backend requests and cannot save accounts, reference records or permission changes. The route returns not-found in production; it does not bypass the normal session gate.

To restart locally, from `frontend/`:

```powershell
node node_modules/next/dist/bin/next dev --hostname 127.0.0.1 --port 3000
```

The normal application at `/` continues to use real server sessions. An actual super-admin session requires backend support; no role was bootstrapped or promoted.

## What changed

- `src/roles/admin/index.tsx`, `Sidebar.tsx`, and `navigation.ts`: responsive GO shell, existing SVG icons, hash navigation, nested persona/member routes, online notice and unsaved-draft protection.
- `src/roles/admin/screens/`: rebuilt people directory, member detail, three-step member/driver onboarding, optional image selection, persona/member permissions, outlet and vehicle directories/forms, calendar, reference publication layout, devices, integrations and audit/investigation screens.
- `src/roles/admin/data/`: typed read gateway, cancellation-aware resource hook, schema-based form validation, frozen/idempotent member setup, route parsing, leave-dialog state and isolated sample adapter.
- `src/shared/ui/Switch.tsx`: labeled keyboard-operable boolean switches; unavailable decisions are visually distinct and do not claim a grant or denial.
- `src/shared/domain/identity.ts`: current account and assignment read types.
- `src/app-shell/`: real admin role routing and the development preview composition. `app/admin-preview/page.tsx` delegates to the shell and excludes production access.
- `src/shared/offline/tiers.ts`: both administrative roles explicitly use the online-only tier.

Figma was inspected through the browser using the supplied file and the [GO sidebar component set](https://www.figma.com/design/WSYlQF5chNlrln207gOEWr/Xception_Designathon?node-id=818-29781). The implementation reuses Google Sans Flex, the existing green/teal/mint palette, white surfaces, rounded controls and exported GO icons. There are no supplied admin frames; administrative forms and permission controls are extensions of that system. Exact pixel/prototype equivalence is not claimed.

## Member setup

Details, including the optional local image, lead to scope and optional dated driver assignment, then a review without the password. Operational member creation uses the existing command bus: `iam:CreateUser`, followed by optional `iam:GrantScope` commands and `iam:AssignDriver`.

The first submission freezes the reviewed payload. Retry reuses each command ID and resumes unfinished steps after account creation; simultaneous submits are ignored. Offline saves are blocked and never queued. After an uncertain result, edits remain locked to avoid replaying one identity while displaying another. Leaving an incomplete workflow does not roll back an already created account. The operator must resolve rejected scope/assignment data through an available administrative path; the current backend lacks the complete repair UI contract.

Every persona's Add member flow includes an image picker. JPEG, PNG and WebP files up to 5 MB are decoded locally and previewed; object URLs are released. Image storage is missing, so a selected image blocks live creation until removed. Nothing is silently uploaded or discarded.

## Access and backend boundaries

Only Super admin displays Add admin. Live admin creation remains disabled because the backend does not enforce the proposed super-admin boundary. Existing privileged-account mutation paths were not expanded.

The preview demonstrates persona defaults, an overall inheritance choice, per-permission inheritance and individual overrides, with an explicit review. Live decisions, sources and scopes remain unavailable until served. Existing policy summaries can be read, but a definition is not treated as proof of attachment or effective access.

The directory uses the existing cursor-paged account API. Search and persona filters apply to the loaded page and are labeled accordingly; no global persona count is inferred from one page. Privileged details are restricted in the frontend, which does not substitute for backend authorization.

| Feature | Existing connection / remaining dependency |
| --- | --- |
| Account directory/detail and driver assignments | Existing reads |
| Operational member/driver creation | Existing commands; not executed during this work |
| Admin creation | Missing protected super-admin command and role/session support |
| Images | Missing upload, persistence and image read contract |
| Persona/member permissions | Missing effective decisions, inheritance, attachments and versioned save |
| Outlets | Existing depot read; schema-complete draft/review; creation and reference publication missing |
| Fleet | Existing available-vehicles-by-depot/date read; complete fleet/status revision read missing |
| Vehicle creation | Schema-complete draft/review; create/publication command missing |
| Calendar | Existing date read; override history/revision missing |
| Reference | Existing current-version read; import preview/history/reviewed publication missing |
| Devices, integrations, audit/investigation | Named unavailable states and feature layouts; required admin APIs missing |

Audit and investigation live inside Admin; no separate Auditor persona was added. Legacy Auditor sessions are not silently promoted. No API, SQL migration, seed data or policy was changed.

## Verification and limits

- Production build passed before final UI refinements; the missing font dependency was restored locally without changing manifests or disabling TLS verification.
- Typecheck passed before final UI refinements.
- All 12 current frontend tests passed: schema limits, mall-window overlap, route state, immutable command retries, partial setup, double-submit/offline handling, and architecture boundaries.
- Browser inspection covered desktop rendering, driver directory/member details, an individual access override while another permission remained inherited, empty outlet validation and outlet review with native keyboard time input.
- The user requested no further checks. Final label improvements, per-permission presentation and the new discard dialog have not all been revalidated together. Planned tablet/phone acceptance checks and full live backend integration remain outstanding.

No real account, scope grant, driver assignment, policy change or publication was submitted while testing. The full issue remains dependent on the backend work listed above.
