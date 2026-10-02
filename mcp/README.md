# Waypoint read-only MCP server

Connects the AI assistant you already use to Waypoint, so it can answer questions from the records you are allowed to read. It supports local stdio and remote Streamable HTTP. Waypoint contains no chatbot and calls no model.

How it is built, its rules and its tests are in the [walkthrough](../docs/issues/087-readonly-mcp/WALKTHROUGH.md). This page is only how to connect.

## Which clients work

For local stdio, the client starts a process on your machine. For remote access, use the HTTPS endpoint described below.

| Client | Works | How |
| --- | --- | --- |
| Claude Code | yes | command below |
| Claude Desktop | yes | config file below |
| Codex CLI | yes | command below |
| opencode | yes | config file below |
| Cursor | yes | config file below |
| Remote clients with Streamable HTTP, OAuth code + S256 PKCE and public-client registration | protocol implemented | HTTPS URL below; hosted ChatGPT/Claude connection still requires deployment validation |

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

It asks for the Waypoint URL (HTTPS, or `http://localhost` for local development), your email and your password. It stores a dedicated read-only credential in `~/.config/waypoint/mcp.json`, readable only by you. Your password is not stored and the token is never printed.

The credential follows the normal session lifetimes (12 hours, or 2 hours unused, unless the environment sets others). When it expires, run `npm run disconnect` and then `npm run connect` again. `npm run disconnect` also revokes it on the server.

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
| `get_plan` | The draft or published plan for a depot and day, with recorded constraint reasons | `plan:Read` |
| `get_manifest` | A trip manifest in loading order | `loading:Read` |
| `get_delivery` | A recorded delivery outcome | `delivery:Read` |
| `get_receipt` | Receipt status and item quantities for an order | `receipt:Read` |
| `list_issues` | One page of open operational issues for a depot | `issue:Read` |
| `get_issue` | One issue: type, severity, subjects, resolution | `issue:Read` |
| `list_audit` | Audit activity in a bounded time range | `audit:Read` |
| `get_command_decision` | The recorded decisions for one command | `audit:Read` |
| `list_policies` | Policy names and versions | `iam:ReadPolicy` |

The definitions are in [`src/catalogue.ts`](src/catalogue.ts). Product identifiers in order, manifest and receipt results are inferred catalogue identifiers, not verified SKUs, and are labelled so in the output.

## When it does not work

| What you see | Cause |
| --- | --- |
| `Connection setup failed` on connect | `MCP_ENABLED` is off on that environment, the URL or password is wrong, or a credential file already exists (disconnect first) |
| The client shows the server as failed to start | No credential yet, or it has expired: run connect again |
| A tool answers `FORBIDDEN` (403) | Your policy or scope does not cover that record. This is a refusal, not an empty result |
| A tool answers `UNAUTHENTICATED` (401) | The credential was revoked or expired, or the account was disabled |
| A tool answers `RESPONSE_TOO_LARGE` | The record is over the 256 KB limit. Large plans and manifests fail on purpose until a paged read exists |
| A tool answers `DEPENDENCY_UNAVAILABLE` | Waypoint could not be reached |

## Remote connection

The deployment operator applies the migrations explicitly, sets `MCP_ENABLED=true` and `MCP_PUBLIC_URL=https://YOUR-WAYPOINT-HOST/mcp`, then deploys the backend, `mcp` adapter and frontend together. Both Compose stacks wire the internal services; for native development run `MCP_BACKEND_URL=http://127.0.0.1:8080 MCP_PUBLIC_URL=http://localhost:3000/mcp MCP_ENABLED=true npm run start:http` after building this package. Export the same public URL and feature flag for the backend. The frontend defaults to the adapter on port 8081.

In an assistant that supports the advertised OAuth flow, add a remote MCP connection with URL `https://YOUR-WAYPOINT-HOST/mcp`, OAuth authentication and automatic client registration (no client secret). Follow the redirect to Waypoint, check the client's callback hostname, and approve with your personal Waypoint account. The assistant gets an opaque, read-only credential; it never gets your password. Your account still needs `mcp:Connect`, the business read permission and the relevant scope. A shared loader PIN cannot authorize this connection.

This version supports stateless JSON Streamable HTTP, DCR public clients, authorization code + PKCE S256, and the single `waypoint.read` scope. There are no refresh tokens: reconnect after expiry. Revocation is `POST /api/oauth/revoke` with form fields `token` and `client_id`; account/session revocation also takes effect on subsequent reads. The same 12 tools and output limits apply to both transports. Local stdio credentials cannot be reused at the remote endpoint.

Discovery is published at `/.well-known/oauth-protected-resource/mcp` (also the root resource document) and `/.well-known/oauth-authorization-server`. The public URL is configuration, never derived from request headers. A blank `MCP_PUBLIC_URL` disables remote authorization; `MCP_ENABLED=false` disables MCP access. No production environment is enabled by this change.

Automated checks cover the official SDK transport and local OAuth flow. Hosted ChatGPT and Claude account/UI compatibility is not yet verified; see their current [authentication documentation](https://developers.openai.com/plugins/build/auth) and [custom connector guide](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp).
