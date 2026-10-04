import { request, requestAll } from "@shared/api/client";
import { newCommand, send, type CommandAck } from "@shared/api/commands";
import type { Page } from "@shared/domain/common";
import type { AccountView } from "@shared/domain/identity";
import type { Member, Persona } from "../access/model";

export async function fetchOwnProfile(): Promise<{ userId: string; rowVersion: number }> {
  return request<{ userId: string; rowVersion: number }>("/api/profile");
}

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
  const path = `/api/accounts${qs ? `?${qs}` : ""}`;
  return options.after ? request<Page<AccountView>>(path, { signal: options.signal })
    : { items: await requestAll<AccountView>(path, { signal: options.signal }), nextCursor: null };
}

/**
 * Fetch a single visible account by its UUID.
 */
export async function fetchAccount(id: string, signal?: AbortSignal): Promise<AccountView> {
  return request<AccountView>(`/api/accounts/${encodeURIComponent(id)}`, {
    signal,
  });
}

export type CreateUserPayload = {
  email: string;
  displayName: string;
  password: string;
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
    password: payload.password,
    roleCode: payload.roleCode,
  });
  return send(cmd);
}

/** Grant the selected place after creation, guarded by the account revision. */
export async function submitGrantScope(userId: string, place: string, kind: "depot" | "outlet", expectedVersion: number): Promise<void> {
  await send(newCommand("iam:GrantScope", {
    userId,
    ...(kind === "depot" ? { depotCode: place } : { outletId: place }),
  }, expectedVersion));
}

export async function submitUpdateUser(payload: {
  userId: string; displayName: string; email: string; expectedVersion: number;
}): Promise<void> {
  await send(newCommand("iam:UpdateUser", {
    userId: payload.userId,
    displayName: payload.displayName,
    email: payload.email,
  }, payload.expectedVersion));
}

export async function submitDisableUser(
  userId: string,
  expectedVersion: number
): Promise<CommandAck<{ userId: string; sessionsRevoked: number }>> {
  return send(
    newCommand(
      "iam:DisableUser",
      { userId },
      expectedVersion
    )
  );
}

export type DriverAssignmentView = {
  assignmentId: string;
  vehicleId: string;
  driverUserId: string;
  driverName: string;
  from: string;
  until: string | null;
  rowVersion: number;
};

export async function fetchDriverAssignments(options: {
  on?: string;
  after?: string;
  limit?: number;
  signal?: AbortSignal;
} = {}): Promise<DriverAssignmentView[]> {
  const params = new URLSearchParams();
  if (options.on) params.set("on", options.on);
  if (options.after) params.set("after", options.after);
  if (options.limit) params.set("limit", String(options.limit));
  const qs = params.toString();
  const path = `/api/accounts/driver-assignments${qs ? `?${qs}` : ""}`;
  return requestAll<DriverAssignmentView>(path, { signal: options.signal });
}

export async function submitAssignDriver(payload: {
  vehicleId: string;
  driverUserId: string;
  from: string;
  until?: string | null;
  expectedVersion: number;
}): Promise<CommandAck<{ assignmentId: string; from: string }>> {
  return send(
    newCommand(
      "iam:AssignDriver",
      {
        vehicleId: payload.vehicleId,
        driverUserId: payload.driverUserId,
        from: payload.from,
        until: payload.until || null,
      },
      payload.expectedVersion
    )
  );
}

export async function submitEndDriverAssignment(payload: {
  assignmentId: string;
  on: string;
  expectedVersion: number;
}): Promise<CommandAck<{ assignmentId: string; endedOn: string }>> {
  return send(
    newCommand(
      "iam:EndDriverAssignment",
      {
        assignmentId: payload.assignmentId,
        on: payload.on,
      },
      payload.expectedVersion
    )
  );
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

