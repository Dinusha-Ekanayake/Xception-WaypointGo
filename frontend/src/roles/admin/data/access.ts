import { request } from "@shared/api/client";
import { newCommand, send, type CommandAck } from "@shared/api/commands";
import type { Page } from "@shared/domain/common";
import type { ActionView, RoleView } from "@shared/domain/identity";
import { CAPABILITIES } from "../access/model";

/**
 * Fetch registered system roles from GET /api/admin/roles.
 * Returns role codes and catalogue descriptions.
 * Note: Neither response includes account membership or an effective access decision.
 */
export async function fetchRoles(options: {
  after?: string;
  limit?: number;
  signal?: AbortSignal;
} = {}): Promise<Page<RoleView>> {
  const params = new URLSearchParams();
  if (options.after) params.set("after", options.after);
  if (options.limit) params.set("limit", String(options.limit));
  const qs = params.toString();
  return request<Page<RoleView>>(`/api/admin/roles${qs ? `?${qs}` : ""}`, {
    signal: options.signal,
  });
}

/**
 * Fetch registered action verbs from GET /api/admin/actions.
 * Returns action names, owning modules, descriptions, and implementation readiness.
 * Note: Neither response includes account membership or an effective access decision.
 */
export async function fetchActions(options: {
  after?: string;
  limit?: number;
  signal?: AbortSignal;
} = {}): Promise<Page<ActionView>> {
  const params = new URLSearchParams();
  if (options.after) params.set("after", options.after);
  if (options.limit) params.set("limit", String(options.limit));
  const qs = params.toString();
  return request<Page<ActionView>>(`/api/admin/actions${qs ? `?${qs}` : ""}`, {
    signal: options.signal,
  });
}

export type PermissionChoice = {
  policyName: string;
  policyId: string | null;
  rowVersion: number;
  attached: boolean;
  document: Record<string, unknown> | null;
};

/**
 * Query current policy choice for a principal and action from GET /api/admin/access/choice.
 */
export async function fetchPermissionChoice(
  principalType: "user" | "role",
  principalId: string,
  action: string,
  options?: { signal?: AbortSignal }
): Promise<PermissionChoice> {
  const params = new URLSearchParams({
    principalType,
    principalId,
    action,
  });
  return request<PermissionChoice>(`/api/admin/access/choice?${params.toString()}`, {
    signal: options?.signal,
  });
}

export type ManagePermissionPayload = {
  principalType: "user" | "role";
  principalId: string;
  action: string;
  choice: "allow" | "deny" | "inherit";
  reason: string;
  place?: string | null;
  expires?: string | null;
};

/**
 * Manage permission choice via iam:ManagePermission command.
 */
export async function submitManagePermission(
  payload: ManagePermissionPayload,
  expectedVersion: number | null = null
): Promise<CommandAck<{ policyName: string; policyId: string | null; rowVersion: number; choice: string }>> {
  const cmd = newCommand("iam:ManagePermission", payload, expectedVersion);
  return send(cmd);
}

export const FALLBACK_ROLES: RoleView[] = [
  { roleCode: "dispatcher", description: "Plans and publishes daily allocation; resolves exceptions" },
  { roleCode: "loader", description: "Loads to the planned stop sequence and flags shortfalls" },
  { roleCode: "driver", description: "Executes stops and captures proof of delivery" },
  { roleCode: "store_manager", description: "Places orders and confirms receipt for an outlet" },
  { roleCode: "auditor", description: "Read-only across the operation, for investigating a dispute" },
  { roleCode: "admin", description: "Accounts, roles, scopes, reference import, calendar override" },
  { roleCode: "super_admin", description: "Protected root governance and privileged account management" },
];

export const FALLBACK_ACTIONS: ActionView[] = CAPABILITIES.map((c) => ({
  action: c.action,
  module: c.module,
  description: c.description,
  implemented: c.implemented,
}));

