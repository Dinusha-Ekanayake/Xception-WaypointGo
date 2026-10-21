# The full rule editor for permissions

Owner: @Oxshadha. Item 8 of @jv-ransika's plan of 2026-10-21. The folder takes its issue number once the issue is opened.

## Current state

- Writes existed as commands: `iam:CreatePolicy`, `iam:CreatePolicyVersion`, `iam:SetDefaultPolicyVersion`, `iam:AttachPolicy`, `iam:DetachPolicy`. The only read was the list, `GET /api/policies`. The admin console's access screens were partly sample data.

## Decision (owner, 2026-10-21)

A full editor (Effect, Action, Resource, Condition). Before saving, show what changes: which people gain or lose which actions. Save as a new default version; old versions can be restored.

## How

| Piece | Layer |
| --- | --- |
| `PolicyChange.compare` (pure): a person's other statements plus the current version, then plus the draft, decided by the decision point's own evaluator for every catalogue action against each resource the statements name; gained, lost, reach changed. A condition is taken to hold, since it depends on the request | `identity/domain/policy` |
| `PolicyLint.check` (pure): per statement, actions in the catalogue, resources `*` or `wpt:<module>[:<type>[:<id>]]` | `identity/domain/policy` |
| Reads: `GET /api/policies/{name}/versions`, `/versions/{n}`, `/attachments` (`iam:ReadPolicy`); `POST /api/policies/{name}/preview-change` (read only, `iam:CreatePolicy`). People with the same policies and roles are one group, worked out once | `identity/application/PolicyAdminUseCase`, `identity/web/PolicyAdminController` |
| Save refuses what the preview refuses; the editor's reason goes into the audit row | `PolicyAdminUseCase.validateDocument`, `applyCreateVersion`, `applySetDefaultVersion` |
| The Policies tab: list, rule rows with an action picker from the catalogue, raw JSON, "What changes", save with a reason (enabled only after a clean check of that exact draft), version history with Restore | `roles/admin/access/PoliciesScreen.tsx`, `PolicyEditor.tsx`, `roles/admin/data/policies.ts` |

Saving is two commands, each guarded by the policy's `rowVersion`: a new version, then that version made the default, which clears the whole policy cache (R-IAM rules), so the change applies on the next request.

## Tests

`PolicyChangeTest` (gained, lost, reach; another policy still granting or a Deny elsewhere is no change; lint per statement); `IdentityHardeningIntegrationTest.thePermissionEditorPreviewsAChangeThatTheSaveThenMakesAndARestoreUndoes` (the preview names the person and the actions, problems per statement, the same refused on save, a non-admin refused the preview, the decision point answers as previewed after save, versions and attachments read, restore brings the old access back).
