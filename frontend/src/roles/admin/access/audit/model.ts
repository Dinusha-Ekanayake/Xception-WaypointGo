import { CAPABILITIES, type Change } from "../model";

export type Outcome = "Completed" | "Denied" | "Failed" | "Pending sync" | "Not recorded";
export type AuditEvent = {
  id: string; at: string; occurred?: string; actor: string; persona?: string; title: string;
  module: string; target: string; place: string; outcome: Outcome;
  authorization: "Allowed" | "Denied" | "Not recorded"; reason?: string;
  before?: string; after?: string; device?: string; correlation?: string; action: string;
  access: boolean; governance?: boolean; expires?: string;
};

const scenarios: [string, string, string, string, Outcome, string][] = [
  ["Orders", "Placed an order", "Ayesha Hassan", "ORD-1041", "Completed", "Outlet replenishment submitted"],
  ["Orders", "Amended an order", "Ayesha Hassan", "ORD-1041", "Completed", "Updated quantities before allocation"],
  ["Planning", "Published plan", "Nimali Perera", "PLAN-210", "Completed", "All publication checks passed"],
  ["Planning", "Deferred trip", "Nimali Perera", "TRIP-310", "Completed", "Vehicle capacity constraint"],
  ["Loading", "Completed loading check", "Ravi Fernando", "TRIP-310", "Completed", "Manifest checked at the dock"],
  ["Loading", "Recorded shortfall", "Ravi Fernando", "TRIP-310", "Completed", "Two cartons missing"],
  ["Loading", "Released trip", "Ravi Fernando", "TRIP-310", "Completed", "Shortfall recorded before release"],
  ["Delivery", "Recorded delivery outcome", "Amal Silva", "STOP-401", "Completed", "Recipient accepted delivery"],
  ["Delivery", "Completed without photo", "Amal Silva", "STOP-402", "Completed", "Camera unavailable; lower evidence recorded"],
  ["Receipts", "Confirmed partial receipt", "Ayesha Hassan", "ORD-1041", "Completed", "Two cartons were not received"],
  ["Receipts", "Opened delivery dispute", "Chamari Silva", "ORD-1042", "Completed", "Received quantity differs from manifest"],
  ["Vehicles", "Changed vehicle day status", "Nimali Perera", "WP-1042", "Completed", "Scheduled maintenance"],
  ["Calendar", "Overrode operating day", "Devika Senanayake", "CAL-210", "Completed", "Approved additional dispatch day"],
  ["Sync", "Rejected stale offline command", "Amal Silva", "STOP-402", "Failed", "Expected version differs from server version"],
  ["Sync", "Received offline receipt", "Ayesha Hassan", "ORD-1041", "Completed", "Work synchronized after connection returned"],
  ["Sync", "Queued offline work", "Amal Silva", "STOP-403", "Pending sync", "Waiting for connectivity in this sample scenario"],
  ["Integrations", "Warehouse update failed", "Warehouse connector", "ORD-1041", "Failed", "Warehouse request timed out"],
  ["Integrations", "Retried warehouse update", "Warehouse connector", "ORD-1041", "Completed", "Retry acknowledged by warehouse"],
  ["Forecasts", "Activated forecast model", "Devika Senanayake", "MODEL-07", "Completed", "Reviewed sample model version"],
  ["Access", "Denied order cancellation", "Chamari Silva", "ORD-1042", "Denied", "Explicit member block"],
  ["Access", "Denied cross-depot request", "Ravi Fernando", "TRIP-900", "Denied", "Target is outside assigned depot scope"],
  ["Security", "Sign-in failed", "Unknown actor", "LOGIN-201", "Failed", "Invalid credentials; submitted secrets are not recorded"],
  ["People", "Disabled member account", "Devika Senanayake", "Former sample member", "Completed", "Employment ended"],
  ["Access", "Recorded authorization decision", "Former sample member (now disabled)", "ORD-1040", "Not recorded", "Historical role and execution result not captured"],
  ["People", "Created admin account", "Local Super Admin", "Admin sample account", "Completed", "Protected governance sample"],
  ["Access", "Granted member exception", "Devika Senanayake", "Nimali Perera", "Completed", "Persona deny still prevents effective access"],
];

export const SAMPLES: AuditEvent[] = scenarios.map(([module, title, actor, target, outcome, reason], index) => ({
  id: `SAMPLE-${String(index + 1).padStart(3, "0")}`, at: new Date(Date.UTC(2026, 9, 1, 2, index * 12)).toISOString(),
  actor, title, module, target, outcome, reason,
  persona: actor === "Nimali Perera" ? "Dispatcher" : actor === "Ravi Fernando" ? "Loader" : actor === "Amal Silva" ? "Driver" : ["Ayesha Hassan", "Chamari Silva"].includes(actor) ? "Store manager" : actor === "Devika Senanayake" ? "Admin" : actor === "Local Super Admin" ? "Super admin" : undefined,
  place: index === 20 ? "KANDY" : index === 10 || index === 19 ? "OUT-SAMPLE-02" : index === 24 ? "Governance" : "PELIYAGODA",
  authorization: outcome === "Denied" ? "Denied" : [15, 21, 23].includes(index) ? "Not recorded" : "Allowed",
  access: ["Access", "Security", "People"].includes(module), governance: index === 24,
  action: `sample:${module.replaceAll(" ", "")}`, device: index % 4 === 0 ? undefined : `DEMO-DEVICE-${index % 4 + 1}`,
  correlation: target.startsWith("ORD-") ? `FLOW-${target}` : target.startsWith("TRIP-") ? `FLOW-${target}` : undefined,
  occurred: index === 14 ? "2026-09-30T08:15:00+05:30" : undefined,
  before: index === 11 ? "Available" : index === 25 ? "No member exception" : undefined,
  after: index === 11 ? "Workshop" : index === 25 ? "Member allow; effective access remains denied by persona" : undefined,
}));

export function auditEvents(changes: Change[], viewer: "admin" | "super_admin"): AuditEvent[] {
  const dynamic: AuditEvent[] = changes.map((change) => ({
    id: `CHANGE-${change.id}`, at: change.at, actor: change.actor, title: CAPABILITIES.find((item) => item.action === change.action)?.label ?? change.action,
    module: "People & access", target: change.target, place: change.place ?? "Existing assigned places",
    outcome: "Completed", authorization: "Not recorded", reason: change.reason, before: change.before, after: change.after,
    action: change.action, access: true, expires: change.expires ?? undefined,
    governance: ["Admin", "Devika Senanayake", "Mahesh de Alwis", "Local Super Admin"].includes(change.target) || change.after.startsWith("Admin sample"),
  }));
  return [...SAMPLES, ...dynamic].filter((event) => viewer === "super_admin" || !event.governance)
    .sort((a, b) => b.at.localeCompare(a.at) || b.id.localeCompare(a.id));
}

export const localDay = (value: string) => new Date(value).toLocaleDateString("sv-SE", { timeZone: "Asia/Colombo" });
export const eventTime = (value: string) => new Date(value).toLocaleString("en-LK", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Colombo" });
