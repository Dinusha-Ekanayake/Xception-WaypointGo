# Waypoint MCP server

Connects the AI assistant you already use to Waypoint, so it can answer questions from the records you are allowed to read, and raise or assign an issue once you confirm. It supports local stdio and remote Streamable HTTP. Waypoint contains no chatbot and calls no model.

How it is built, its rules and its tests are in the walkthroughs of [#87](../docs/issues/087-readonly-mcp/WALKTHROUGH.md) (reads, OAuth) and [#177](../docs/issues/177-mcp/WALKTHROUGH.md) (scopes, writes, personal fields, blocking). This page is how to connect and use it.

## Which clients work

For local stdio, the client starts a process on your machine. For remote access, use the HTTPS endpoint described below.

| Client | Works | How |
| --- | --- | --- |
| Claude Code | yes | command below |
| Claude Desktop | yes | config file below |
| Codex CLI | yes | command below |
| opencode | yes | config file below |
| Cursor | yes | config file below |
| Remote Claude, ChatGPT, or any Streamable HTTP + OAuth assistant | yes, once its checkout is deployed with the two MCP lines | shared-host HTTPS URL below |

## Before you start

- Node.js 22.13 or newer.
- A Waypoint environment with `MCP_ENABLED=true` and its migrations applied. It is off by default; see [deployment.md](../docs/deployment.md).
- Your own Waypoint account. A shared loader PIN is not a credential.

## 1. Build

Build in a folder that stays put. Client configurations hold the absolute path, so a temporary checkout breaks them when it is removed.

```sh
cd mcp
npm ci
npm run build
```

The server is `mcp/dist/stdio.js`. Below, `<node>` is the absolute path to Node (`which node`) and `<stdio.js>` is the absolute path to that file.

## 2. Sign in

Run this in your own terminal. It needs an interactive terminal because the password is read hidden, so an assistant cannot run it for you.

```sh
npm run connect
```

It asks for the Waypoint URL (HTTPS, or `http://localhost` for local development), your email and your password. It stores a dedicated MCP credential in `~/.config/waypoint/mcp.json`, readable only by you. Your password is not stored and the token is never printed.

The credential follows the normal session lifetimes (12 hours, or 2 hours unused, unless the environment sets others). When it expires, run `npm run disconnect` and then `npm run connect` again. `npm run disconnect` also revokes it on the server.

To grant less than the default of every read plus the confirmed issue writes, name the scopes: `npm run connect -- --scope "orders.read receipts.read"` (the list is under [Scopes](#scopes)).

To keep a second connection (another account or environment), set `WAYPOINT_MCP_CREDENTIAL_FILE` to another private file for both `connect` and the client entry.

## 3. Add the server to your client

Every client below shares the one credential from step 2. Restart the client after changing its configuration.

**Claude Code**

```sh
claude mcp add waypoint --scope user -- <node> <stdio.js>
```

**Codex CLI**

```sh
codex mcp add waypoint -- <node> <stdio.js>
```

**Claude Desktop** (`claude_desktop_config.json`) and **Cursor** (`~/.cursor/mcp.json`)

```json
{
  "mcpServers": {
    "waypoint": {
      "command": "<node>",
      "args": ["<stdio.js>"]
    }
  }
}
```

**opencode** (`~/.config/opencode/opencode.json`)

```json
{
  "mcp": {
    "waypoint": {
      "type": "local",
      "command": ["<node>", "<stdio.js>"],
      "enabled": true
    }
  }
}
```

Any other client that launches a local stdio MCP server works the same way: the command is `<node>`, the one argument is `<stdio.js>`.

## 4. Try it

Ask: "Use Waypoint to show my current access." That calls `my_context` and returns your roles, your scope and the read actions you hold.

## Tools

A client is shown only the tools your account may use, and every call is authorized again on the server against your policy and your depot, outlet or vehicle scope.

| Tool | Reads | Action needed |
| --- | --- | --- |
| `my_context` | Your identity, scope and eligible read actions | `mcp:Connect` |
| `list_orders` | One page of orders for an outlet | `order:Read` |
| `get_order` | One order with its weight, volume and temperature | `order:Read` |
| `get_plan` | The draft or published plan for a depot and day: versions, served, deferred and unservable counts, trips with stop counts | `plan:Read` |
| `list_plan_allocations` | One page of a plan's per-order decisions, each with its trip, stop, binding rule, reason and constraint results | `plan:Read` |
| `get_manifest` | A trip manifest in loading order | `loading:Read` |
| `list_ready_trips` | Ready trips for a depot and day, with trip IDs | `loading:Read` |
| `get_delivery` | A recorded delivery outcome | `delivery:Read` |
| `list_run_sheets` | Run sheets for a day, yours or one depot, with delivery IDs | `delivery:Read` |
| `get_receipt` | Receipt status and item quantities for an order | `receipt:Read` |
| `list_pending_receipts` | Orders waiting for a store answer at one outlet | `receipt:Read` |
| `get_custody` | Loading check, delivery record and receipt side by side | `receipt:Read` |
| `list_issues` | One page of open operational issues for a depot | `issue:Read` |
| `get_issue` | One issue: type, severity, subjects, resolution | `issue:Read` |
| `list_audit` | Audit activity in a bounded time range | `audit:Read` |
| `get_command_decision` | The recorded decisions for one command | `audit:Read` |
| `list_policies` | Policy names and versions | `iam:ReadPolicy` |
| `day_summary` | One depot and day in counts: orders served and deferred, trips by loading status, open issues by severity. A part you may not read, or that does not exist, is listed in `unavailable` with its code, never shown as zero | `plan:Read` (and `loading:Read`, `issue:Read` for those parts) |

Every description ends with one example question, so an assistant picks the right tool.

## Changes, always confirmed

Two write tools exist, both about issues, and neither changes anything by itself:

| Tool | Proposes | Needs |
| --- | --- | --- |
| `raise_issue` | A new issue (a problem report) for a depot, optionally at an outlet, about one or more records | `issues.write` scope, `mcp:Write`, and your own permission to raise that type there |
| `assign_issue` | Assigning an open issue to someone who works its depot, at the issue's version | `issues.write` scope, `mcp:Write`, and your own `issue:Assign` |
| `confirm_write` | Carries out the change a write tool prepared | the confirmation from that preview |

A write tool returns a preview and a confirmation. The assistant must show you the preview, and only after you agree call `confirm_write`. The confirmation works once, from the same connection, within two minutes, and Waypoint then runs the stored command through its normal command path: your policy, your scope and the record version are checked again, and the change is audited as yours. The assistant cannot alter the command at the confirm step. Do not set your client to approve `confirm_write` automatically.

Nothing that cancels, publishes, releases, confirms a receipt or replaces someone's work is available, and generating a plan is left out because it cancels the open draft. Writes are on by default for dispatchers, store managers, loaders and drivers; an administrator turns them off for a person or a role by attaching the `WaypointMcpNoWrites` policy.

## Scopes

A connection is granted scopes when you connect, and they only narrow what your own permissions already allow.

| Scope | Lets the assistant |
| --- | --- |
| `waypoint.read` | read everything you can read |
| `orders.read`, `plans.read`, `loading.read`, `deliveries.read`, `receipts.read`, `issues.read`, `audit.read`, `policies.read` | read one area only |
| `issues.write` | propose issue changes for you to confirm |

Asking for nothing grants `waypoint.read issues.write`. The consent page lists what you are granting. A remote assistant that tries a write without `issues.write` gets an `insufficient_scope` challenge naming it, so it can ask you to approve more.

## Personal fields

Names of the people who raised or are assigned an issue, issue descriptions and resolution notes, and receipt notes and confirmers are left out unless an administrator grants you `mcp:ReadPersonal` (the `WaypointMcpPersonalReader` policy). A result that had any says `"withheld": ["personal"]`. `day_summary` is made of up to six backend reads, each authorized on its own, so it counts that many requests against the rate limit.

## Prompts

Clients that show MCP prompts (often as slash commands) offer these. A prompt only starts the conversation and names the tools to call; it grants nothing, and it is offered only when you hold every read it needs.

| Prompt | Arguments | Needs |
| --- | --- | --- |
| `morning_briefing` | `depot`, `date` | `plan:Read` |
| `what_to_load_next` | `depot`, `date` | `loading:Read` |
| `pending_receipts` | `outlet` | `receipt:Read` |

The definitions are in [`src/catalogue.ts`](src/catalogue.ts). Product identifiers in order, manifest and receipt results are inferred catalogue identifiers, not verified SKUs, and are labelled so in the output.

## When it does not work

| What you see | Cause |
| --- | --- |
| `Connection setup failed` on connect | `MCP_ENABLED` is off on that environment, the URL or password is wrong, or a credential file already exists (disconnect first) |
| The client shows the server as failed to start | No credential yet, or it has expired: run connect again |
| A tool answers `FORBIDDEN` (403) | Your policy or scope does not cover that record. This is a refusal, not an empty result |
| A tool answers `UNAUTHENTICATED` (401) | The credential was revoked or expired, or the account was disabled |
| A tool answers `RESPONSE_TOO_LARGE` | The record is over the 256 KB limit. Use `get_plan` and `list_plan_allocations` for a large plan |
| A tool answers `INSUFFICIENT_SCOPE` | The connection was not granted that scope; reconnect with it (`requiredScope` names it) |
| `confirm_write` answers `CONFLICT` (409) | The confirmation expired, was already used or came from another connection; prepare the change again |
| A tool answers `DEPENDENCY_UNAVAILABLE` | Waypoint could not be reached |

## Remote connection

The deployment operator applies the migrations explicitly, sets `MCP_ENABLED=true` and the checkout's canonical URL, then deploys the backend, `mcp` adapter and frontend together. Preview: `MCP_PUBLIC_URL=https://preview.waypointgo.live/mcp`. Production: `MCP_PUBLIC_URL=https://waypointgo.live/mcp`. Use the shared host, never a role address. Both Compose stacks wire the internal services; for native development run `MCP_BACKEND_URL=http://127.0.0.1:8080 MCP_PUBLIC_URL=http://localhost:3000/mcp MCP_ENABLED=true npm run start:http` after building this package. Export the same public URL and feature flag for the backend. The frontend defaults to the adapter on port 8081.

Any assistant that speaks stateless Streamable HTTP over JSON with OAuth code + S256 PKCE and public-client registration can connect. A missing resource is bound to this endpoint. Unknown scope words (such as `openid`) are ignored; the grant is the known scopes asked for, or the default.

Signed in to Waypoint, the **Connect AI** button in any role (header, top bar or Settings) shows this address with a copy button and these steps. It is hidden where MCP is off.

To see and end your connected assistants, open **Connect AI** in Waypoint: each one is listed with what it may do and a Disconnect button.

**Claude (Claude.ai):** Settings, Connectors, Add custom connector. URL `https://preview.waypointgo.live/mcp` for testing, `https://waypointgo.live/mcp` once enabled. Choose OAuth with automatic registration, follow the redirect to Waypoint, check the callback hostname shown, and approve with your personal Waypoint account.

**ChatGPT:** Settings, Apps or Connectors, Add a remote MCP server. Same URL. Choose OAuth, let it register, approve with your personal Waypoint account. Ask it to show your current access; that calls `my_context`.

**opencode and other local-first clients:** remote works the same way when the client supports OAuth; otherwise use the local stdio entry above, which needs no OAuth.

The assistant gets an opaque credential limited to its scopes; it never gets your password. Your account still needs `mcp:Connect`, the business read permission and the relevant scope. A shared loader PIN cannot authorize this connection. Ask first for `my_context`: if it answers `FORBIDDEN`, the account lacks scope; if `UNAUTHENTICATED`, reconnect.

This version supports stateless JSON Streamable HTTP, DCR public clients, authorization code + PKCE S256, and the scopes above. There are no refresh tokens: the credential follows the normal session lifetimes (12 hours, 2 hours idle) and reconnect means signing in again. Revocation is `POST /api/oauth/revoke` with form fields `token` and `client_id`; account/session revocation also takes effect on subsequent reads. The same tools, prompts and output limits apply to both transports. Local stdio credentials cannot be reused at the remote endpoint.

Discovery is published at `/.well-known/oauth-protected-resource/mcp` (also the root resource document) and `/.well-known/oauth-authorization-server`. The public URL is configuration, never derived from request headers. A blank `MCP_PUBLIC_URL` disables remote authorization; `MCP_ENABLED=false` disables MCP access. No production environment is enabled by this change.

## Observing it

What every MCP call leaves behind (issues #140 and #177), and what to do when a number moves. Administrators see the same per tool and per assistant app in Waypoint: **Admin · AI assistants**, which lists the apps (block or unblock each, with a reason) and the last 24 hours of calls, marking ten or more refused calls as needing attention.

**Metrics** (Prometheus, at the backend's `/prometheus`):

| Metric | Tags | Meaning |
| --- | --- | --- |
| `waypoint_mcp_calls_total` | `tool`, `outcome`, `transport` | One per request. `tool` is named by the backend from the path, never from the client; `other` is a path outside the read boundary. `outcome` is `ok`, `not_found`, `denied`, `unauthenticated`, `rate_limited`, `rejected` or `error`. `transport` is `local` (stdio) or `remote` (OAuth) |
| `waypoint_mcp_duration_seconds` | `tool`, `outcome` | Time inside the backend, with p95 |
| `waypoint_mcp_outcome_audit_failed_total` | `tool` | An outcome row could not be written; the read itself was answered |
| `waypoint_mcp_writes_total` | `tool`, `step` | A write `prepared`, `confirmed`, `rejected` by the command bus, or a confirmation `refused` |

```promql
sum by (tool, outcome) (rate(waypoint_mcp_calls_total[5m]))
histogram_quantile(0.95, sum by (tool, le) (rate(waypoint_mcp_duration_seconds_bucket[5m])))
sum(rate(waypoint_mcp_calls_total{tool="other"}[15m]))
```

Alert rules worth adding where Prometheus alerting runs (there is none in this repository yet):

```promql
# An assistant keeps being refused: misconfigured or probing.
sum(increase(waypoint_mcp_calls_total{outcome="denied"}[15m])) > 50
# Writes or requests are hitting their limits.
sum(increase(waypoint_mcp_rate_limited_total[15m])) > 0
# Confirmed writes rejected by the command bus (stale versions, scope).
sum(increase(waypoint_mcp_writes_total{step="rejected"}[1h])) > 10
```

**Audit.** An authorized read writes two rows under one correlation id: the business authorization (`order:Read` and so on, reason `read via MCP`) and the outcome (`mcp:Connect`, resource `wpt:mcp:tool:<tool>`, `after_state` with status, outcome, duration, transport and, for a remote connection, the OAuth client). Since #177 a refused call of a known connection gets an outcome row too, so the usage view counts refusals per app. A write leaves `mcp:Write` rows (`MCP write prepared`, `MCP write confirmed`) beside the command's own audit row, all under its command id; the payload is never in a reason.

```sql
SELECT occurred_at, action, decision, reason, after_state
FROM integration.audit_log
WHERE correlation_id = '<from the adapter log or the X-Correlation-Id header>'
ORDER BY audit_id;
```

**Adapter log.** One JSON line per tool call on stderr, collected with the container logs:

```json
{"event":"mcp.tool_call","tool":"list_orders","outcome":"ok","status":200,"durationMs":41,"correlationId":""}
```

Only catalogue tool names are logged (`unknown` otherwise); arguments, results and credentials never are. Outcomes only the adapter sees appear here: `RESPONSE_TOO_LARGE`, `VALIDATION_FAILED`, `UNKNOWN_TOOL`, `DEPENDENCY_UNAVAILABLE`.

**Write limit** (R-IAM-35, P-32). 30 write requests an hour per connection by default (`MCP_WRITES_PER_HOUR`); preparing and confirming each count. Over it is `429` with `Retry-After`; reads still work. Counted as `bucket="write"`.

**Rate limits** (R-IAM-33, P-30). 120 requests a minute per credential and 1200 per OAuth client by default (`MCP_RATE_PER_CREDENTIAL`, `MCP_RATE_PER_CLIENT` on the backend); one tool call is about two requests, because the adapter refreshes the context first. Over the limit the backend answers `429` with `Retry-After`, and the assistant gets a tool error with `retryAfterSeconds`. `waypoint_mcp_rate_limited_total{bucket="credential"|"client"}` counts refusals; the first refusal per window is in the audit log with reason `MCP rate limit`.

**When a number moves:**

| Signal | Likely cause | First step |
| --- | --- | --- |
| `outcome="denied"` rising for one person | a policy or scope change, or a client probing | Check `integration.audit_log` DENY rows for that actor; revoke the connection if unexpected |
| `tool="other"` above zero | a client calling paths outside the catalogue | Find the actor from the DENY rows; an old adapter version or a misbehaving client |
| `outcome="error"` or p95 rising for one tool | the owning module's read is slow or failing | Look at that module's own metrics and logs by correlation id |
| `RESPONSE_TOO_LARGE` in adapter logs | a record above 256 KiB | Plans are read in pages now; anything else this large is a bug to report |
| `waypoint_mcp_writes_total{step="refused"}` rising | confirmations reused, expired or from another connection | Check the person's `mcp:Write` audit rows; an assistant retrying old confirmations |
| One app marked as needing attention | an app refused again and again | Look at its rows in **AI assistants**; block it with a reason if it is probing |
| `waypoint_mcp_rate_limited_total` rising | one assistant looping, or many users of one client | DENY rows with reason `MCP rate limit` name the person; raise P-30 or P-32 only if the traffic is legitimate |
| `outcome_audit_failed` above zero | audit writes failing | Database health first; reads keep working, but their outcome rows are missing until fixed |
