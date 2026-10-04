# Enterprise MCP plan

Goal: grow the read-only MCP server into an enterprise interface: more useful reads, per-client access, a data policy for personal fields, a small set of confirmed write tools and an admin view. Authority stays in the backend (policy AND scope, `CommandBus`); the adapter in `mcp/` only translates.

Spec: [issue #177](https://github.com/kavindamihiran/Xception-WaypointGo/issues/177). It replaces the earlier MCP issues; what they built is in the [#87 walkthrough](../087-readonly-mcp/WALKTHROUGH.md).

## Current state

- 16 read tools over curated GET paths (`McpReadPolicy`), stdio and remote Streamable HTTP with OAuth 2.1 and PKCE (R-IAM-30, R-IAM-31).
- Every call is `mcp:Connect` AND user policy AND SQL scope, rechecked per request.
- Rate limits per credential and OAuth client (R-IAM-33), per-tool metrics, outcome audit rows and adapter call logs (SEC-38, SEC-39).
- One OAuth scope, `waypoint.read`. No write path, no per-client restriction, personal fields removed by fixed allowlists.
- Plans and manifests above 256 KiB fail with `RESPONSE_TOO_LARGE`.

## Ownership

| Need | Owner | Why there |
| --- | --- | --- |
| Tool and prompt catalogue, output allowlists, composition of reads | `mcp/` adapter | Protocol translation only; it decides no rule |
| Paged plan and manifest reads | `planning`, `loading` web and query | The owning module's SQL applies scope and keyset paging |
| Client scopes, client block, connected clients, confirmation tokens | `identity` application and domain | Identity already owns MCP sessions, OAuth and the read gate |
| Write tools | owning module handlers through `CommandBus` | One command, one transaction; the adapter never reaches around the bus |
| Field classes | `mcp/` outputs, with the grant checked by `identity` | Classes are a property of each output field; the grant is policy |
| Admin view | `frontend/src/roles/admin/`, reads from `identity` | Built on the existing metrics and audit rows, no new store |

## Decisions

1. **`day_summary` is composed in the adapter, not a new backend read.** It calls the plan, loading trips and open issues reads the caller could already make; each is authorized on its own, so a refused or absent part is named in `unavailable` with its code, never counted as zero. A published plan wins; with none, the open draft is used and labelled `draft`. Open issues are counted over at most two pages of 50 and marked `complete: false` beyond. Chosen over a backend summary endpoint because it adds no table, no query and no new authorization path. Cost: up to six backend requests per call against the rate limit (P-30).
2. **Prompts grant nothing.** A prompt is a starting message naming tools; it is offered only when the caller holds every read action it needs, checked again when fetched, and its arguments must match the same code and date patterns as tool inputs, so an argument cannot carry instructions.
3. **Client scopes narrow, never widen.** Effective access becomes client scope AND user policy AND row scope. `waypoint.read` keeps meaning every read the user has, so existing connections keep working.
4. **Writes need step-up and a confirmation token.** A write tool without `waypoint.write` answers `insufficient_scope`. With it, the first call returns a preview and a two-minute single-use token bound to the session, tool, payload fingerprint and `expectedVersion`; the second call with the token submits the command through `CommandBus` under the user's own policy. Only `raise_issue` and `assign_issue` (decision 2026-10-03; generating a plan cancels the open draft). Annotations are set but never trusted.
5. **Personal fields need an explicit grant** (`mcp:ReadPersonal`, a new catalogue action). Without it they stay removed and the response lists which classes were withheld.

## Review focus

1. A composed or prompted call must never read more than the same tools called one by one.
2. A refused part of a summary must be distinguishable from an empty one.
3. A client scope must not widen a user's reach; a confirmation token must not be reusable or transferable.
4. No write may bypass `CommandBus`, its idempotency receipt or `expectedVersion`.

## PR breakdown

### 1. More useful reads (#178, merged)

- [x] `day_summary`, three prompts, an example question per tool.

### 2. The rest, in one PR (decision 2026-10-03: finish at an acceptable level, no further slicing)

- [x] Paged plans: summary plus keyset allocation pages (SEC-46). Manifests are one vehicle's orders and are not paged.
- [x] Client scopes (R-IAM-34), consent page lists them, `insufficient_scope` for remote writes.
- [x] Confirmed safe writes `raise_issue` and `assign_issue` only (R-IAM-35). `generate_draft_plan` dropped: it cancels the open draft. On by default for the field roles; hourly write limit (P-32).
- [x] Personal fields withheld without `mcp:ReadPersonal` (R-IAM-36).
- [x] Block and unblock a client by command; MCP or writes off per person or role by policy (R-IAM-37).
- [x] Own connections with Disconnect in the Connect AI sheet; admin AI assistants screen with usage per tool and app.

### Left, owned elsewhere

- `get_thread`: needs the messaging module, #136.
- Production enablement and hosted-client checks: a deployment step, in the [#87 walkthrough](../087-readonly-mcp/WALKTHROUGH.md) checklist.

## Verification

Each PR: `npm test` in `mcp/`, and for backend changes `mvn verify` with the integration tests run, not skipped. Every new command gets a denied-scope test; every new edge case gets its EDGE-CASES row and test together.
