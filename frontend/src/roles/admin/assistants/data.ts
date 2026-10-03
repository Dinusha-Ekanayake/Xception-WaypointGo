import { request } from "@shared/api/client";
import { newCommand, send } from "@shared/api/commands";
import { McpCommandKind, type McpClientView, type McpUsageView } from "@shared/domain/types";

// Reads and commands of the live AI assistants screen (issue #177). Usage comes
// from the audit log (audit:Read); the apps and blocking from Identity
// (mcp:ManageClients, mcp:BlockClient, mcp:UnblockClient).

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
