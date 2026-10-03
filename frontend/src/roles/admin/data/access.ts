import { request, requestAll } from "@shared/api/client";
import type { Page } from "@shared/domain/common";
import type { ActionView, RoleView } from "@shared/domain/identity";

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
  const path = `/api/admin/roles${qs ? `?${qs}` : ""}`;
  return options.after ? request<Page<RoleView>>(path, { signal: options.signal })
    : { items: await requestAll<RoleView>(path, { signal: options.signal }), nextCursor: null };
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
  const path = `/api/admin/actions${qs ? `?${qs}` : ""}`;
  return options.after ? request<Page<ActionView>>(path, { signal: options.signal })
    : { items: await requestAll<ActionView>(path, { signal: options.signal }), nextCursor: null };
}
