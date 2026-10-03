import { request } from "@shared/api/client";
import { newCommand, send, type CommandAck } from "@shared/api/commands";
import type { Page } from "@shared/domain/common";
import type { AccountView } from "@shared/domain/identity";
import type { Member, Persona } from "../access/model";

/**
 * Fetch a paged list of visible accounts from the backend Identity module.
 * Admin callers receive operational accounts; privileged accounts are excluded by the backend SQL.
 */
export async function fetchAccounts(options: {
  after?: string;
  limit?: number;
  role?: string;
  depot?: string;
  outlet?: string;
  active?: boolean;
  search?: string;
  signal?: AbortSignal;
} = {}): Promise<Page<AccountView>> {
  const params = new URLSearchParams();
  if (options.after) params.set("after", options.after);
  if (options.limit) params.set("limit", String(options.limit));
  if (options.role && options.role !== "all") params.set("role", options.role);
  if (options.depot && options.depot !== "all") params.set("depot", options.depot);
  if (options.outlet && options.outlet !== "all") params.set("outlet", options.outlet);
  if (options.active !== undefined) params.set("active", String(options.active));
  if (options.search) params.set("search", options.search);
  const qs = params.toString();
  return request<Page<AccountView>>(`/api/admin/accounts${qs ? `?${qs}` : ""}`, {
    signal: options.signal,
  });
}

/**
 * Fetch a single visible account by its UUID.
 */
export async function fetchAccount(id: string, signal?: AbortSignal): Promise<AccountView> {
  return request<AccountView>(`/api/admin/accounts/${encodeURIComponent(id)}`, {
    signal,
  });
}

export type AccountAccessSource = {
  name: string;
  description: string;
  version: number;
  principalType: string;
  principalId: string;
  policyId: string;
  rowVersion: number;
};

export type AccountAccessView = {
  userId: string;
  policies: AccountAccessSource[];
};

/**
 * Fetch policy sources attached to an account from GET /api/admin/accounts/{id}/access.
 */
export async function fetchAccountAccess(id: string, signal?: AbortSignal): Promise<AccountAccessView> {
  return request<AccountAccessView>(`/api/admin/accounts/${encodeURIComponent(id)}/access`, {
    signal,
  });
}

export type PermissionExplanation = {
  userId: string;
  action: string;
  resource: string;
  active: boolean;
  implemented: boolean;
  policyGeneration: number;
  current: "allow" | "deny" | "omit";
  proposed: "allow" | "deny" | "omit";
  scopeEvaluation: string;
};

/**
 * Fetch permission explanation for an account and action from GET /api/admin/accounts/{id}/permissions.
 */
export async function fetchAccountPermissions(
  id: string,
  action: string,
  resource: string = "*",
  signal?: AbortSignal
): Promise<PermissionExplanation> {
  const params = new URLSearchParams({ action, resource });
  return request<PermissionExplanation>(
    `/api/admin/accounts/${encodeURIComponent(id)}/permissions?${params.toString()}`,
    { signal }
  );
}

export type CreateUserPayload = {
  email: string;
  displayName: string;
  password?: string;
  roleCode: string;
};

/**
 * Submit iam:CreateUser command.
 */
export async function submitCreateUser(
  payload: CreateUserPayload
): Promise<CommandAck<{ userId: string }>> {
  const cmd = newCommand("iam:CreateUser", {
    email: payload.email,
    displayName: payload.displayName,
    password: payload.password || "TemporaryPass123!",
    roleCode: payload.roleCode,
  });
  return send(cmd);
}

/**
 * Convert an Identity AccountView to the Member model used in Admin People screens.
 */
export function accountToMember(account: AccountView): Member {
  const knownPersonas = new Set<Persona>([
    "dispatcher",
    "loader",
    "driver",
    "store_manager",
    "admin",
    "super_admin",
  ]);
  const personas = account.roles.filter((r): r is Persona => knownPersonas.has(r as Persona));
  const fallbackPersonas: Persona[] = personas.length > 0 ? personas : ["dispatcher"];

  const depots = account.depots ?? [];
  const outlets = account.outlets ?? [];
  const places = [...depots, ...outlets];

  return {
    id: account.userId,
    name: account.displayName || account.email.split("@")[0] || "Unnamed Member",
    email: account.email,
    personas: fallbackPersonas,
    places,
    active: account.active,
    rowVersion: account.rowVersion,
    depots,
    outlets,
    source: "live",
  };
}

