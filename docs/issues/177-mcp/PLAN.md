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
4. **Writes need step-up and a confirmation token.** A write tool without `waypoint.write` answers `insufficient_scope`. With it, the first call returns a preview and a two-minute single-use token bound to the session, tool, payload fingerprint and `expectedVersion`; the second call with the token submits the command through `CommandBus` under the user's own policy. Only `raise_issue`, `assign_issue` and `generate_draft_plan` (draft only). Annotations are set but never trusted.
5. **Personal fields need an explicit grant** (`mcp:ReadPersonal`, a new catalogue action). Without it they stay removed and the response lists which classes were withheld.

## Review focus

1. A composed or prompted call must never read more than the same tools called one by one.
2. A refused part of a summary must be distinguishable from an empty one.
3. A client scope must not widen a user's reach; a confirmation token must not be reusable or transferable.
4. No write may bypass `CommandBus`, its idempotency receipt or `expectedVersion`.

## PR breakdown

### 1. More useful reads (this PR)

- [x] `day_summary` composed from plan, loading trips and open issues, with `unavailable` per part (SEC-40).
- [x] Prompts `morning_briefing`, `what_to_load_next`, `pending_receipts`, filtered by read actions.
- [x] One example question in every tool description.
- [x] Tests in `mcp/tests/summary.test.ts`.

### 2. Paged plans and manifests

- [ ] Keyset paged allocations and manifest lines in `planning` and `loading`, added to `McpReadPolicy`.
- [ ] `get_plan` and `get_manifest` take a cursor; `RESPONSE_TOO_LARGE` stays only as a guard.
- [ ] `get_thread` once the messaging module (#136) exists.

### 3. Per-client authorization

- [ ] Scopes per area, client scope AND user policy AND row scope, `insufficient_scope` challenge.
- [ ] Connected clients listed and revoked by their owner; admin block of a client, and MCP off per user or role, as catalogue actions and R-IAM rows.

### 4. Data policy

- [ ] Field class on every output field; personal fields only with `mcp:ReadPersonal`; withheld classes listed.

### 5. Confirmed write tools

- [ ] `waypoint.write`, confirmation tokens in Identity, three tools through `CommandBus`, audit with client and reason.

### 6. Admin view and production

- [ ] Admin screen of calls, denials, errors and latency per tool and client; alert on repeated denials or rate limits.
- [ ] Preview validation with Claude and ChatGPT, `oauth-smoke.mjs`, then production.

## Verification

Each PR: `npm test` in `mcp/`, and for backend changes `mvn verify` with the integration tests run, not skipped. Every new command gets a denied-scope test; every new edge case gets its EDGE-CASES row and test together.
