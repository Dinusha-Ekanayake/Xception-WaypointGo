# Enterprise MCP walkthrough

Issue #177, built in #178 (reads) and the follow-up PR (everything else). The [plan](PLAN.md) has the decisions; the rules are R-IAM-34 to R-IAM-37 in [RULES-AND-POLICIES](../../architecture/RULES-AND-POLICIES.md), the cases SEC-40 to SEC-46 in [EDGE-CASES](../../architecture/EDGE-CASES.md), the write limit P-32 in [ASSUMPTIONS](../../architecture/ASSUMPTIONS.md). How to connect and use it is the [connect guide](../../../mcp/README.md).

## What was built

- **Reads** (`mcp/src/catalogue.ts`, `summary.ts`, `prompts.ts`): `day_summary`, three prompts, example questions. Plans are read as `get_plan` (summary) plus `list_plan_allocations` (pages), backed by `GET /api/plans/{published|draft}/summary` and `GET /api/plans/{planId}/allocations` in `planning/web/PlanController` and `PlanDataQuery`.
- **Scopes** (`identity/domain/McpScopes`): stored on `iam.sessions.mcp_scopes` and on the OAuth code; checked in `SessionRequestAuthorizer` through `McpAccessHandler.requireReadScope`; returned by `/api/mcp/context` with the write tools and the personal grant.
- **Writes** (`identity/application/McpWriteHandler`, `web/McpWriteController`, `mcp/src/writes.ts`): `POST /api/mcp/writes` stores the command in `iam.mcp_write_confirmations` and returns a preview; `POST /api/mcp/writes/confirm` spends it once and dispatches through `CommandBus`. Hourly limit in `McpRateLimiter.requireWrite`.
- **Personal fields**: `withholdPersonal` and `fieldClasses` in `mcp/src/outputs.ts`.
- **Blocking and visibility**: `McpClientHandlers` (`mcp:BlockClient`, `mcp:UnblockClient`), `McpConnectionsQuery` and `McpConnectionsController` (`/api/mcp/connections`, `/api/mcp/clients`), `AuditQuery.mcpUsage` (`/api/audit/mcp-usage`). Screens: connected assistants in `frontend/src/shared/ui/McpConnect.tsx`, the consent page lists scopes, and `frontend/src/roles/admin/assistants/` is the live admin screen.
- **Migration** `migrations/20261003T2100_iam_mcp_enterprise.sql`: the columns, the confirmations table, five catalogue actions and five policies (writer and client admin attached by default; personal reader, no writes and blocked attached to no one).

## A write end to end

The assistant calls `raise_issue`; the adapter refreshes the context, checks the tool is offered and posts the prepare. Identity checks the scope, `mcp:Write` and the payload, stores the command with a fixed id and returns the preview. The person agrees; the assistant calls `confirm_write` with only the confirmation. Identity spends it (same connection, unexpired, unused), checks scope and grant again, and the bus authorizes `issue:Raise` under the person's own policy and scope, checks idempotency and version, runs the handler and audits it.

## Verify locally

`npm test` in `mcp/`; from `backend/`, `mvn test -Dtest='McpEnterpriseIntegrationTest,McpOAuthIntegrationTest,PlanPagingIntegrationTest,McpScopesTest'` with a test database; from `frontend/`, `npx playwright test tests/e2e/mcp-oauth.spec.ts tests/e2e/mcp-admin.spec.ts` after `npm run build`.

## Known gaps

`get_thread` waits for messaging (#136). Production enablement is a deployment step. The admin can block apps on screen, but attaching the personal, no-writes or blocked policies still goes through the policy API until the admin console is live (#22).
