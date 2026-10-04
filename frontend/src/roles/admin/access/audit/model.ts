import { CAPABILITIES } from "../model";

export type Outcome = "Completed" | "Denied" | "Failed" | "Pending sync" | "Not recorded";
export type AuditEvent = {
  id: string; at: string; occurred?: string; actor: string; persona?: string; title: string;
  module: string; target: string; place: string; outcome: Outcome;
  authorization: "Allowed" | "Denied" | "Not recorded"; reason?: string;
  before?: string; after?: string; device?: string; correlation?: string; action: string;
  access: boolean; governance?: boolean; expires?: string;
};

export type AuditRow = {
  auditId: number; occurredAt: string; actorId: string | null; deviceId: string | null;
  action: string; resource: string | null; decision: "ALLOW" | "DENY"; reason: string | null;
  correlationId: string | null; targetType: string | null; targetId: string | null;
  before: unknown; after: unknown;
};

export function auditRowToEvent(row: AuditRow): AuditEvent {
  const access = row.action.startsWith("iam:") || row.action.startsWith("audit:");
  return {
    id: String(row.auditId), at: row.occurredAt, actor: row.actorId ?? "System",
    title: CAPABILITIES.find((item) => item.action === row.action)?.label ?? row.action,
    module: CAPABILITIES.find((item) => item.action === row.action)?.module ?? row.action.split(":")[0],
    target: row.targetId ?? row.resource ?? "Unavailable", place: row.resource ?? "Unavailable",
    outcome: row.decision === "DENY" ? "Denied" : "Completed",
    authorization: row.decision === "DENY" ? "Denied" : "Allowed",
    reason: row.reason ?? undefined, before: row.before == null ? undefined : JSON.stringify(row.before),
    after: row.after == null ? undefined : JSON.stringify(row.after),
    device: row.deviceId ?? undefined, correlation: row.correlationId ?? undefined,
    action: row.action, access, governance: access,
  };
}

export const localDay = (value: string) => new Date(value).toLocaleDateString("sv-SE", { timeZone: "Asia/Colombo" });
export const eventTime = (value: string) => new Date(value).toLocaleString("en-LK", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Colombo" });
