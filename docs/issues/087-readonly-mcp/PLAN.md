# Read-only MCP implementation plan

Goal: let the six beneficial roles inspect existing operational facts through an optional MCP client, with the same live policies and row scope as the application.

Spec: [issue #87](https://github.com/kavindamihiran/Xception-WaypointGo/issues/87).

## Current state and ownership

The backend has authorized reads for orders, plans, manifests, delivery records, receipts, issues, audit history and policies. AuditController and AuditApiIntegrationTest already exist; the status page's missing audit API line is stale. Identity owns passwords, throttling, opaque sessions and policy evaluation. Existing browser sessions may write, so copying one into an adapter is insufficient.

The adapter lives in root `mcp/`, separate from frontend routing and the backend modules. It owns MCP schemas, a fixed GET catalogue, response field selection and stdio transport. Identity application code owns connection authorization, credential issuance/revocation and the read-only request gate. The existing business application queries retain SQL scope filtering. Platform owns a typed `app.mcp.enabled` setting, disabled by default. No business module or table is added.

## Chosen decisions

- Transport: local stdio, using official TypeScript SDK 1.31.0 and its supported stable protocol negotiation. Target: desktop/process-spawning MCP clients and the official SDK client smoke test. Remote HTTP/OAuth and mobile connector hosting are separate work.
- Credentials: an interactive local `connect` command submits credentials directly to Identity's new `/api/mcp/session` authentication endpoint. Identity issues a separate `mcp.` opaque session stored only as a hash, marked read-only in SQL. No browser cookie is copied. A private file outside the repository holds the token. The assistant never receives a password or token.
- Identity: each call re-resolves the session and checks `mcp:Connect`. Policy or account revocation affects the next call. Existing session revocation paths apply to both browser and MCP sessions. Standard browser session resolution rejects MCP sessions. Bearer browser tokens are refused.
- Read-only enforcement: only exact approved GET paths and own-session revocation are admitted by the backend gate, even if an MCP token is supplied as a browser cookie. Commands, uploads, account/device administration, redirects and arbitrary endpoints are excluded.
- Loader: use a personal credential with no shared device identity. The adapter cannot claim an operator or inherit the browser's PIN-switched supervisor session. Shared-device MCP connections are unsupported.
- Tool discovery: refresh safe context and permitted read actions on each list/call, with conservative discovery for resource-restricted policies. Backend policy and SQL scope still decide every actual target. No role-name privilege bypass.
- Data: explicit output schemas discard unknown fields. Audit snapshots, proof URLs/images, credentials, contact details and PIN material never enter a result. Text fields remain untrusted data. Cap response bytes and fail visibly rather than truncate. List tools use existing keyset pagination; the first slice excludes unbounded cross-record list APIs.

## Tool and action matrix

| Tool | Backend read | Action/resource |
| --- | --- | --- |
| `my_context` | `/api/mcp/context` | `mcp:Connect`, own identity |
| `list_orders` | `/api/orders?outlet=...` | `order:Read`, `wpt:order:outlet:<id>` |
| `get_order` | `/api/orders/<id>` | `order:Read`, order |
| `get_plan` | `/api/plans/draft` or `/published` | `plan:Read`, depot |
| `get_manifest` | `/api/loading/trips/<id>/manifest` | `loading:Read`, trip |
| `list_ready_trips` | `/api/loading/trips?depot=&date=` | `loading:Read`, depot |
| `get_delivery` | `/api/execution/deliveries/<id>` | `delivery:Read`, delivery |
| `list_run_sheets` | `/api/execution/run-sheets?date=&depot?` | `delivery:Read`, vehicle or depot |
| `get_receipt` | `/api/receipts/<orderId>` | `receipt:Read`, order |
| `list_pending_receipts` | `/api/receipts/pending?outlet=` | `receipt:Read`, outlet |
| `get_custody` | `/api/receipts/<orderId>/custody` | `receipt:Read`, order |
| `list_issues` | `/api/issues?depot=...` | `issue:Read`, depot |
| `get_issue` | `/api/issues/<id>` | `issue:Read`, issue |
| `list_audit` | `/api/audit` | `audit:Read`, platform audit |
| `get_command_decision` | `/api/audit/decisions/<id>` | `audit:Read`, platform audit |
| `list_policies` | `/api/policies` | `iam:ReadPolicy`, policy |

The 16 tools reuse the existing owning-module reads and their SQL scope. Lists outside scope answer 403 with audit; details outside scope answer 404. No new backend reads were added for this.

Single-record tools retain IDs and versions. Plans/manifests can exceed the byte limit and then return an explicit size error, with no partial data. Run-sheet, ready-trip and pending-receipt lists are bounded by depot/date/outlet. Custody composes the existing receipt, delivery-facts, loading-check and proof reads; neighbours from undeployed modules are named in `unavailable`, and personal fields (notes, confirmed-by, recorded-by, holders, proof links) are discarded before model context.

## Review focus

1. A token placed in a cookie or sent to a command/upload must remain read-only.
2. Revocation/policy deny on an already-running connection must affect the next call.
3. A resource ID or depot/outlet from another person's scope must be refused in SQL.
4. Unknown response fields, redirects, oversized data and backend exception messages must not expose secrets.
5. Loader personal identity must stay distinct from a PIN-switched browser; temporal driver scope must still apply.

## PR breakdown and verification

### 1. Connection boundary and role read tools (this branch)

- [x] Add failing PostgreSQL HTTP tests for issuing a dedicated session, browser/bearer separation, denied commands/uploads, revocation, scope and audit behavior. Add a pure route-policy test and run the boundary baseline before cross-module work.
- [x] Add an additive IAM migration for session purpose and an opt-in connection policy for the six existing roles. Default the feature off. Implement Identity application handlers, the servlet credential adapter and the context endpoint. Preserve normal sign-in and its throttling.
- [x] Add adapter tests with an official SDK client and an HTTP fixture for discovery, role reads, errors, input schemas, field redaction, pagination, redirects and size limits; verify failure before adapter implementation.
- [x] Implement strict TypeScript catalogue, bounded backend client, stdio entry point and interactive credential-file setup/revocation. Add CI checks for the independent package and forward Authorization through the existing Next proxy.
- [x] Run adapter build/test, backend verify on isolated PostgreSQL, frontend tests/typecheck/build and relevant shell smoke tests. Review skips and diff; record actual verification in WALKTHROUGH.md and development log. Open a PR into dev; do not close #87 while its acceptance gaps remain.

### 2. Remaining reads and user validation

Bound the owning modules' run-sheet, loading-work and pending-receipt reads; add tools and cross-user client demonstrations. Validate an installed desktop client and decide whether users actually need remote/mobile access. These are follow-up PRs on #87, without write tools.

### 3. Remote transport for clients that cannot start a local process

Requirement (2026-10-02): users connect whichever assistant they already use. There is still no chatbot in Waypoint. ChatGPT and the hosted Claude connectors accept only a remote HTTPS MCP server that they authorize with OAuth, so the stdio adapter alone leaves them out, and it also asks every other user to clone and build the repository.

#### Current state

- `mcp/` owns the tool catalogue and speaks stdio only. `BackendClient` refuses any origin that is not HTTPS or loopback.
- Identity issues the `mcp.` read-only session from `POST /api/mcp/session`, which takes a password in a request body. A hosted client must never see that password.
- On the VPS every path reaches the Next.js container (`deploy/vps/nginx/snippets/site.conf`, `location /`), and `/api/*` is proxied on to Spring. So new public paths need no edge change to be reachable; the edge changes only with a production deploy.

#### Which layer owns what

| Concern | Owner |
| --- | --- |
| OAuth authorization server: client registration, authorization codes, token issue and revocation | Identity (`identity/domain/oauth`, `application`, `web`), tables in `iam` |
| The credential itself | unchanged: the opaque `mcp.` session in `iam.sessions`, read-only by purpose (R-IAM-30) |
| MCP protocol over HTTP, the one tool catalogue | `mcp/` (`src/http.ts`), a container of its own with no database access |
| Sign-in and consent page | frontend `app/oauth/authorize` with the view in `src/app-shell/` |
| Public routing of `/mcp` and the two `/.well-known/` documents | frontend route handlers proxying to the adapter and the backend |
| Feature switch | the existing `MCP_ENABLED`; off answers 404 for discovery and 403 elsewhere |

#### Decisions

1. **Transport: Streamable HTTP, stateless, JSON responses.** One `POST /mcp` per message, no server-sent stream and no MCP session id, so the adapter keeps nothing between requests and any replica can answer. `GET` and `DELETE` answer 405. Alternative rejected: implementing MCP in the backend, which would be a second tool catalogue (rule 5).
2. **The adapter is a resource server, the backend is the authorization server.** The adapter forwards the bearer to the same REST reads as today and turns a backend 401 into `401` with `WWW-Authenticate: Bearer resource_metadata=...`, which is what starts the client's OAuth flow. It validates nothing itself and stores nothing. Alternative rejected: the SDK's in-memory OAuth provider in the adapter, which loses every registration at a deploy and puts credentials outside Identity.
3. **OAuth 2.1 authorization code with PKCE (S256 only), public clients only.** No client secret exists, so there is none to leak. Dynamic client registration (RFC 7591) is open, because that is how ChatGPT and Claude obtain a client id; it is bounded per address per hour and a registration grants nothing by itself.
4. **The access token is the existing `mcp.` session.** Opaque, server-side, revocable on the next request, never a JWT. It is created at the token exchange, not at sign-in, and the grant `mcp:Connect` is checked at both.
5. **No refresh token.** A refresh token would be a second, longer-lived credential held by a third party. The session lifetimes apply unchanged (12 h absolute, 2 h idle by default); when one ends the client sends the user through sign-in again. Revisit only if users report it.
6. **Sign-in is always explicit.** The authorize page asks for email and password every time and never adopts the browser's existing session, for the same reason the local adapter does not: a shared loader device's cookie is the supervisor's (R-IAM-30). The page names the client and the host it will return to before the password is typed.
7. **Redirect URIs: HTTPS, or HTTP on a loopback address.** Compared exactly, except that a loopback port may differ (RFC 8252). No custom schemes: the page navigates to the redirect, so a scheme allowlist is the difference between a redirect and script execution. A request with an unknown client or redirect is shown as an error on our page and is never redirected.
8. **Authorization codes are single use, short lived and stored as a hash**, bound to the client, the redirect URI, the PKCE challenge and the resource. A second use is refused and revokes the session the first use created.
9. **OAuth protocol endpoints answer in the OAuth error shape** (`{"error": ...}`), not RFC 9457, because the clients are third-party OAuth libraries that branch on that field. The two endpoints our own page calls stay `application/problem+json`. Recorded as an exception in R-IAM-31.
10. **The public origin is configured through `MCP_PUBLIC_URL`**, identical in the backend and adapter. Request and forwarded headers never choose the OAuth issuer or resource.

#### Review focus

1. A code cannot be exchanged twice, by another client, with another redirect URI or without the PKCE verifier.
2. A wrong password, a locked-out address or a denied `mcp:Connect` never produces a code or a redirect.
3. An unregistered client or an unregistered redirect URI is never redirected to.
4. The token issued is read-only by purpose: it is the same `mcp.` session, so SEC-30 and SEC-31 already cover what it can reach and how it is revoked.
5. With `MCP_ENABLED=false` nothing is discoverable and nothing can be issued.
6. The adapter never returns a token, a password or a backend exception message, and one user's request cannot see another's context.

#### PR breakdown

One PR into `dev` (this branch), in this order: this plan; the IAM migration and pure domain rules with unit tests; application handlers and controllers with PostgreSQL integration tests, including one denied-scope case; the HTTP adapter with SDK client tests; the frontend routes and the authorize page; Compose, the deploy script and CI (the adapter image is built in the checks, because a broken image stops a deploy); then rules, edge cases, walkthrough, status and log. Not in this PR: enabling it in production or hosted-client account validation.

#### Continuation on `feat/complete-remote-mcp`

The three commits from the unfinished `feat/remote-mcp` worktree and its uncommitted domain tests are preserved on this new branch. The original checkout is unchanged. Remote access continues to use the existing 16 read tools, with no chatbot UI.

Before enabling HTTP, complete the missing audience binding: configure one canonical `MCP_PUBLIC_URL` ending in `/mcp`, bind authorization codes and opaque sessions to that resource and client, require matching resource at exchange and each remote read, and reject local stdio credentials at the remote listener. The HTTP adapter and backend together implement the same Waypoint protected resource, not a third-party API token proxy. Keep the public URL independent of untrusted Host/forwarded headers. An empty URL leaves remote OAuth unavailable while local stdio still works.

Finish in checkpoints: OAuth tests and binding/revocation; stateless Streamable HTTP with SDK tests; authorization/consent page and public proxy routes with browser tests; container/deployment/CI wiring and guide. Test the browser authorization flow as part of this PR, superseding the earlier exclusion. Use public-client DCR and PKCE S256; do not advertise client metadata-document support. No refresh token in this version. Test using an official SDK OAuth client through the public routes, and report hosted-client validation separately.

Compatibility references checked during continuation: [MCP authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization), [OpenAI authentication](https://developers.openai.com/plugins/build/auth), [Claude custom connectors](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp). Claude's automatic registration option fits this DCR implementation; arbitrary MCP clients are supported only when they implement the advertised transport and OAuth flow.
