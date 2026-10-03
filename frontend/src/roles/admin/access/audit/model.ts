import { CAPABILITIES, type Change } from "../model";

export type Outcome = "Completed" | "Denied" | "Failed" | "Pending sync" | "Not recorded";
export type AuditEvent = {
  id: string; at: string; occurred?: string; actor: string; persona?: string; title: string;
  module: string; target: string; place: string; outcome: Outcome;
  authorization: "Allowed" | "Denied" | "Not recorded"; reason?: string;
  before?: string; after?: string; device?: string; correlation?: string; action: string;
  access: boolean; governance?: boolean; expires?: string;
};

export const SAMPLES: AuditEvent[] = [];

export function auditEvents(changes: Change[], viewer: "admin" | "super_admin"): AuditEvent[] {
  const dynamic: AuditEvent[] = changes.map((change) => ({
    id: `CHANGE-${change.id}`, at: change.at, actor: change.actor, title: CAPABILITIES.find((item) => item.action === change.action)?.label ?? change.action,
    module: "People & access", target: change.target, place: change.place ?? "Existing assigned places",
    outcome: "Completed", authorization: "Not recorded", reason: change.reason, before: change.before, after: change.after,
    action: change.action, access: true, expires: change.expires ?? undefined,
    governance: ["Admin", "Devika Senanayake", "Mahesh de Alwis", "Local Super Admin"].includes(change.target) || change.after.startsWith("Admin sample"),
  }));
  return dynamic.filter((event) => viewer === "super_admin" || !event.governance)
    .sort((a, b) => b.at.localeCompare(a.at) || b.id.localeCompare(a.id));
}

export const localDay = (value: string) => new Date(value).toLocaleDateString("sv-SE", { timeZone: "Asia/Colombo" });
export const eventTime = (value: string) => new Date(value).toLocaleString("en-LK", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Colombo" });
