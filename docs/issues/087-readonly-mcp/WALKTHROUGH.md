# Read-only MCP: local and remote connections

Issue [#87](https://github.com/kavindamihiran/Xception-WaypointGo/issues/87), local slice `feat/readonly-mcp` and remote continuation `feat/complete-remote-mcp`, targeting `dev`. The 16-tool catalogue is complete: work discovery and custody composition reuse the existing owning-module reads. Left before close: merge this branch, then hosted-client and Docker deploy validation in a deployment environment. The [plan](PLAN.md) records the scope and tool/action matrix.

## What is built

`mcp/` is an independent Node package. It uses the official MCP TypeScript SDK over local stdio and stateless Streamable HTTP. The pinned SDK negotiates its supported protocol, including `2025-11-25`; it does not claim support for the newer date mentioned in the issue. Remote OAuth, HTTP transport and personal consent are described in the continuation section below.

The 16 tools are `my_context`, `list_orders`, `get_order`, `get_plan`, `get_manifest`, `list_ready_trips`, `get_delivery`, `list_run_sheets`, `get_receipt`, `list_pending_receipts`, `get_custody`, `list_issues`, `get_issue`, `list_audit`, `get_command_decision` and `list_policies`. Discovery asks Identity for current permitted actions instead of selecting by role name. Resource-restricted policies can conservatively hide a tool from discovery; the target read is always separately authorized. All six current roles can connect when enabled and their connection policy allows it; that grant adds no business read permissions or row scope.

| Layer | Files and responsibility |
| --- | --- |
| Identity contract | `backend/.../identity/contract/McpContextView.java`, mirrored in `frontend/src/shared/domain/identity.ts`: own UUID, roles, coarse scope and eligible read actions |
| Identity domain | `identity/domain/McpReadPolicy.java`: exact curated GET paths, plus ending one's own connection. No commands, account/device administration, proof downloads or uploads |
| Identity application | `McpSessionHandler`, `McpAccessHandler`, `McpContextQuery`: enabled flag, independent `mcp:Connect` grant, login, revoke, current context and read audit. `LoginHandler` reuses password verification and shared throttling. `SessionRegistry` fixes credential purpose at issuance |
| Identity web | `McpSessionController`, `McpCredentialFilter`: thin credential endpoints and request adaptation. `SessionActorResolver` distinguishes personal MCP from PIN-switched loader browsers. `SessionRequestAuthorizer` retains the existing business authorization |
| Platform config | `platform/config/McpProperties.java`: typed `app.mcp.enabled`, mapped from `MCP_ENABLED`, false by default. Both Compose paths forward it |
| Persistence | `migrations/20261002T2300_iam_mcp_readonly_sessions.sql`: additive `iam.sessions.mcp_read_only`, catalogue action and managed connection policy attached to the six existing roles. Existing sessions retain browser purpose |
| MCP adapter | `mcp/src/catalogue.ts`, `outputs.ts`, `client.ts`, `server.ts`: strict inputs, selected output fields, fixed GET routes, bounded HTTP, current authorization and SDK handlers |
| Local setup | `mcp/src/connect.ts`, `credentials.ts`, `stdio.ts`: interactive sign-in, private credential file, disconnect and protocol-only stdout |
| Proxy and CI | `frontend/app/api/[...path]/route.ts` forwards Authorization. `.github/workflows/checks.yml` independently builds/tests `mcp/` |

`backend/.../` above means `backend/src/main/java/com/waypoint/dispatch/`. Business reads still execute in their owning module and use its SQL scope/RLS policies. The adapter never reads the database or imports another module's implementation. The status page now recognizes the already-built audit and recorded decision APIs instead of treating them as prerequisites.

## Connection and read flows

1. A user runs the local connect CLI in a trusted terminal and enters the Waypoint URL, email and hidden password. Credentials go directly to `POST /api/mcp/session`, using the existing sign-in throttle. The feature switch and `mcp:Connect` must both allow connection.
2. Identity mints a dedicated opaque `mcp.` token, stores only its hash with `mcp_read_only=true`, and returns it with `Cache-Control: no-store`, without a browser cookie. The dot prefix cannot occur in normal base64url browser tokens. The CLI writes a new credential file with mode `0600` outside the repository; it refuses overwrite and revokes the new session if saving fails.
3. The stdio process loads that private file. It rejects a symlink, an oversized file and, on POSIX, a file owned by someone else or readable by other users. An explicit HTTPS origin is required, except loopback HTTP for development. No token appears in tool inputs, outputs or configuration examples.
4. Discovery fetches `/api/mcp/context`. Calls validate strict input and refresh context before their fixed read. The backend checks credential purpose, enabled state, connection policy, business action and row scope on every request. Normal sessions used as bearer credentials are refused. MCP credentials used as cookies remain subject to the same read-only gate.
5. The owning query returns a view. The adapter discards unknown fields, audit snapshots, proof links and contact material. Product-bearing results carry `productIdentifierProvenance: "inferred_unverified_sku"`; descriptions preserve order-level capacity and temperature authority. `retrievedAt` is retrieval time, not an invented record version. IDs and applicable record/plan versions remain in the view.
6. A failed call returns a visible error code, HTTP status, correlation ID and violation identifiers. Backend exception details and raw bodies stay outside model context. Reads have a 10-second timeout, reject redirects and fail explicitly above 256 KiB; no partial response is returned. Lists use keyset cursors with at most 50 rows. Non-list collections have a schema bound as well.
7. Disconnect revokes only this credential and removes its local file. Ending one's own connection remains possible after the feature is disabled or the connection policy is denied. Existing absolute/idle expiry and account/scope/role revocation apply; there is no automatic password storage or refresh token.

MCP read audit rows record authorization; since #140 an authorized read also writes an outcome row (status, outcome, duration) under the same correlation id, because an allowed read can still fail, including a SQL-hidden detail returning `404`. Per-tool metrics and the adapter's call log are described in the [connect guide](../../../mcp/README.md#observing-it). Lists targeting an explicitly unauthorized depot/outlet return the existing `403` plus audit. Detail reads preserve existing non-disclosure behavior, returning `404` for absent or inaccessible IDs.

There are no business commands, new events or scheduled jobs. Authentication bookkeeping and read audit are the same intentional writes that normal authenticated reads require. Rules are in [R-IAM-30](../../architecture/RULES-AND-POLICIES.md), cases in [SEC-30 to SEC-33](../../architecture/EDGE-CASES.md), and implementation status in [STATUS.md](../../development-docs/STATUS.md).

## Run locally

Connecting a client (Claude Code, Claude Desktop, Codex, opencode, Cursor), the tool list and the common failures are in the connect guide, [mcp/README.md](../../../mcp/README.md). This section covers the backend side.

Initialize the local backend using [development.md](../../development-docs/development.md). Run migrations explicitly, then import reference data. Use a real personal account with its normal role and depot/outlet/vehicle assignment. Do not use a shared loader PIN as a credential.

Start the backend with `MCP_ENABLED=true` exported alongside its normal settings. For Compose, opt in with `MCP_ENABLED=true` in the root environment after applying the migration through the explicit deployment step. The Next proxy can be the configured origin when its `BACKEND_URL` targets that backend. No requests, adapter startup or builds migrate or seed.

From the repository root:

```sh
cd mcp
npm ci
npm run build
npm run connect
```

In an MCP desktop client supporting local stdio, use the absolute path to Node and the built server. Replace the paths below with this installation. The client launches the process; do not separately start it as a web server.

```json
{
  "mcpServers": {
    "waypoint": {
      "command": "/absolute/path/to/node",
      "args": ["/absolute/path/to/Waypoint/mcp/dist/stdio.js"],
      "env": {
        "WAYPOINT_MCP_CREDENTIAL_FILE": "/home/your-user/.config/waypoint/mcp.json"
      }
    }
  }
}
```

The file defaults to `~/.config/waypoint/mcp.json`. A different private file allows a separate personal connection; disconnect before replacing it. Call `my_context`, then use tools advertised for that account. For example, a store manager lists orders for their own outlet, a dispatcher reads a depot/day plan, a loader reads a trip ID from the loading application, and a driver reads a delivery ID from their run sheet. Auditors can query bounded audit intervals; admins can inspect policy names/versions. Authority still comes from policy and scope, not these examples.

```sh
npm run disconnect
```

Re-run connect after expiry/revocation. Disabling `MCP_ENABLED` blocks further business reads for every connection. On Windows, private-file ACL setup requires host validation; the POSIX ownership/mode checks do not apply there.

## Verification and remaining work

Backend reads are owned by their modules; MCP adds no new queries. `list_ready_trips`, `list_run_sheets`, `list_pending_receipts` and `get_custody` call `GET /api/loading/trips`, `GET /api/execution/run-sheets`, `GET /api/receipts/pending` and `GET /api/receipts/<id>/custody` through the same `McpReadPolicy` gate and the same SQL scope. Lists outside scope answer 403 with audit; custody outside scope answers 404. Notes, confirmed-by, recorded-by, holders and proof links are discarded before model context; product identifiers stay labelled inferred.

- `mcp/`: `npm test` builds and passes 15 tests. An official SDK client connects through in-memory, real stdio child and Streamable HTTP transports; tests cover discovery per read action, cross-user isolation, cursor preservation, provenance, sensitive-field removal, revoked access, malformed input, redirects and response limits. New tests cover the four discovery and custody tools.
- `backend/`: `McpConnectionIntegrationTest` (9 tests) exercises real HTTP policy/scope decisions on PostgreSQL, forbidden commands/uploads in bearer and cookie forms, ordinary bearer rejection, own revocation, policy deny, disabled account, all six roles, loader personal identity, driver assignment dates, and the new discovery scope cases. `McpReadPolicyTest` holds the exact route boundary including the four new paths. `McpOAuthIntegrationTest`, `OAuthRulesTest`, `ModuleBoundaryTest` and `EventCatalogueTest` pass.
- `frontend/`: Node tests pass (54). No role UI was changed.

Close checklist: merge this branch into `dev` with CI green; deploy preview with `MCP_ENABLED=true` and `MCP_PUBLIC_URL=https://preview.waypointgo.live/mcp` in `/opt/waypoint/preview/.env`; connect Claude and ChatGPT to `https://preview.waypointgo.live/mcp` and read `my_context` plus one scoped tool each; run `mcp/scripts/oauth-smoke.mjs` against preview; build the `mcp` image and confirm Compose starts backend, adapter and frontend together. Then set the same two lines with `https://waypointgo.live/mcp` in `/opt/waypoint/app/.env` and deploy production. Windows ACL review remains a deployment check, not a code gap. Oversized plans/manifests intentionally fail with `RESPONSE_TOO_LARGE` until owning-module paging exists.

## Remote OAuth and HTTP continuation

`feat/complete-remote-mcp` preserves the earlier unfinished OAuth work and completes the external connection. It uses the existing sixteen-tool catalogue; Waypoint does not call a model.

- Identity domain: `identity/domain/oauth/` checks redirects, S256 challenges and one-time exchange rules without infrastructure.
- Identity application: `McpOAuthHandler` registers public clients, verifies personal credentials with the existing throttle, checks `mcp:Connect`, issues hashed two-minute codes and exchanges them in a serialized transaction. Replay revokes the session. `SessionRegistry` binds OAuth sessions to resource and client, and `SessionRetentionJob` prunes expired bookkeeping in bounded batches. The two `20261002T2330/2340` IAM migrations are applied explicitly.
- Identity web: `McpOAuthController` publishes discovery, registration, token exchange and revocation with OAuth error responses. `McpAuthorizationController` provides the consent-page API with the normal problem contract. Existing business read handlers and SQL scopes remain authoritative.
- Adapter: `mcp/src/http.ts` creates a new official SDK transport and server per POST, authenticates each request against Identity, validates Origin, bounds input to 64 KiB and sends the canonical resource header on backend reads. It accepts no cookie session and stores no transport sessions. Responses retain the existing output filtering and 256 KiB backend response cap.
- Frontend: `app/.well-known/` and `app/mcp/` delegate to `src/app-shell/mcpProxy.ts`. `app/oauth/authorize/` renders `OAuthAuthorize.tsx`: client name and callback host, explicit personal sign-in, approve/cancel and visible failures. Passwords are sent only to Waypoint and cleared after a submission. OAuth pages bypass the offline shell fallback.
- Deployment: `mcp/Dockerfile`, both Compose files and `deploy/vps/deploy.sh` include the adapter. `MCP_ENABLED` and `MCP_PUBLIC_URL` must agree between backend and adapter. The frontend only needs the internal adapter address. CI checks the adapter package and builds its image. No deployment or feature enablement is performed by this branch.

Flow: assistant discovers the protected resource and authorization server, registers its public callback, generates PKCE, opens Waypoint consent, receives a code at its registered callback, and exchanges code + verifier + resource. Every later MCP request carries the resulting opaque credential, which is constrained by purpose, current account/policy and SQL scope. The fixed issuer/resource never comes from Host headers. `POST /api/oauth/revoke` invalidates the client's token idempotently. There are no refresh tokens; reconnect on expiry.

Rules are recorded in R-IAM-31 and edge cases SEC-34/35. See the [connect guide](../../../mcp/README.md#remote-connection) for configuration and the consent [mobile screenshot](consent-mobile.png).

Local verification: isolated PostgreSQL 16, `mvn verify`; `npm test` in `mcp` and `frontend`; frontend typecheck/build and shell Playwright suite. `mcp/scripts/oauth-smoke.mjs` additionally drives official SDK discovery, DCR, PKCE approval/exchange, a scoped read and revocation through the running public frontend routes. Use a disposable environment and dedicated test account, supply `MCP_SMOKE_URL`, `MCP_SMOKE_EMAIL` and `MCP_SMOKE_PASSWORD`, and run `node scripts/oauth-smoke.mjs` from `mcp/`. Do not use operational credentials in a test fixture.

Known gaps: none planned on #87. Generic assistants that omit the resource or send extra scopes around `waypoint.read` are accepted and bound to the configured endpoint (R-IAM-31); the grant stays read-only with no refresh token. After the preview check above, production enablement is two `.env` lines and a deploy.
