import { request } from "@shared/api/client";
import { newCommand, send } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import {
  McpCommandKind,
  PolicyCommandKind,
  type McpClientView,
  type McpPrincipalAccessView,
  type McpSwitchPolicyName,
  type McpUsageView,
  type Page,
  type PolicySummaryView,
} from "@shared/domain/types";

// Reads and commands of the live AI assistants screen (issue #177). Usage comes
// from the audit log (audit:Read); the apps and blocking from Identity
// (mcp:ManageClients, mcp:BlockClient, mcp:UnblockClient). Per person and role,
// the switches are the existing policy attach and detach commands, and ending a
// person's connections is mcp:RevokeUserConnections (R-IAM-38).

export function loadClients(signal: AbortSignal): Promise<McpClientView[]> {
  return request<McpClientView[]>("/api/mcp/clients", { signal });
}

export function loadUsage(signal: AbortSignal): Promise<McpUsageView[]> {
  return request<McpUsageView[]>("/api/audit/mcp-usage", { signal });
}

export function blockClient(client: McpClientView, reason: string) {
  return send(newCommand(McpCommandKind.BlockClient, { clientId: client.clientId, reason }, client.rowVersion));
}

export function unblockClient(client: McpClientView) {
  return send(newCommand(McpCommandKind.UnblockClient, { clientId: client.clientId }, client.rowVersion));
}

export function loadRoles(signal: AbortSignal): Promise<McpPrincipalAccessView[]> {
  return request<McpPrincipalAccessView[]>("/api/mcp/access/roles", { signal });
}

/** Without a search: people with a switch or a live connection. With one: anyone whose name or email starts with it. */
export function loadPeople(search: string, signal: AbortSignal): Promise<Page<McpPrincipalAccessView>> {
  const query = search.trim() ? `?q=${encodeURIComponent(search.trim())}` : "";
  return request<Page<McpPrincipalAccessView>>(`/api/mcp/access/people${query}`, { signal });
}

/** The switch policies with the version an attach or detach must name. */
export async function loadSwitchVersions(signal: AbortSignal): Promise<Record<string, number>> {
  const page = await request<Page<PolicySummaryView>>("/api/policies?limit=200", { signal });
  return Object.fromEntries(page.items.map((policy) => [policy.name, policy.rowVersion]));
}

export function setSwitch(principal: McpPrincipalAccessView, policy: McpSwitchPolicyName, on: boolean, version: number) {
  const payload = { name: policy, principalType: principal.principalType, principalId: principal.principalId };
  return send(newCommand(on ? PolicyCommandKind.Attach : PolicyCommandKind.Detach, payload, version));
}

export function endConnections(userId: string, reason: string) {
  return send(newCommand(McpCommandKind.RevokeUserConnections, { userId, reason }));
}

/** Role codes are codes; a person reads the role (GLOSSARY: no raw codes on screen). */
export const ROLE_LABELS: Record<string, string> = {
  dispatcher: "Dispatchers",
  loader: "Loaders",
  driver: "Drivers",
  store_manager: "Store managers",
  auditor: "Auditors",
  admin: "Administrators",
};

/** Branches on the problem code, never on its title. */
export function refusal(error: unknown): string {
  if (!(error instanceof ApiError)) return "Waypoint could not be reached. Try again.";
  switch (error.problem.code) {
    case "FORBIDDEN": return "your account is not allowed to do this.";
    case "VERSION_CONFLICT": return "someone else changed this first. The list is refreshed; try again.";
    case "CONFLICT": return "this was already changed. The list is refreshed.";
    case "NOT_FOUND": return "this account no longer exists. The list is refreshed.";
    default: return "Waypoint could not do this. Try again.";
  }
}

/** Tool names are codes; a person reads what the tool does (GLOSSARY: no raw codes on screen). */
export const TOOL_LABELS: Record<string, string> = {
  my_context: "Own access",
  list_orders: "Orders",
  get_order: "One order",
  get_plan: "Plan summary",
  list_plan_allocations: "Plan decisions",
  get_manifest: "Manifest",
  list_ready_trips: "Ready trips",
  get_delivery: "One delivery",
  list_run_sheets: "Run sheets",
  get_receipt: "One receipt",
  list_pending_receipts: "Pending receipts",
  get_custody: "Custody",
  list_issues: "Issues",
  get_issue: "One issue",
  list_audit: "Audit log",
  get_command_decision: "Command decision",
  list_policies: "Policies",
  prepare_write: "Proposed changes",
  confirm_write: "Confirmed changes",
  disconnect: "Disconnect",
  other: "Outside the allowed reads",
};
