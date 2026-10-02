import type { IssueSeverity, IssueStatus, IssueType, IssueView, SubjectRef } from "@shared/domain/types";
import type { Tone } from "@shared/ui";

// The dispatcher's reading of the issue inbox. Pure; the clock is a parameter.
// The server decides every rule: these only offer what the command would accept
// and count what it served. A refusal still comes back with the rule (R-ISS-*).

export const TYPE: Record<IssueType, string> = {
  LOADING_SHORTFALL: "Loading shortfall",
  DAMAGED_GOODS: "Damaged goods",
  FAILED_DELIVERY: "Not delivered",
  LATE_DELIVERY: "Late delivery",
  VEHICLE_FAULT: "Vehicle fault",
  ROAD_DISRUPTION: "Road disruption",
  RECEIPT_DISPUTE: "Receipt disputed",
  STOCK_DISCREPANCY: "Stock discrepancy",
  OTHER: "Other",
};

export const SEVERITY: Record<IssueSeverity, { label: string; tone: Tone; rank: number }> = {
  CRITICAL: { label: "Critical", tone: "danger", rank: 4 },
  HIGH: { label: "High", tone: "warning", rank: 3 },
  MEDIUM: { label: "Medium", tone: "info", rank: 2 },
  LOW: { label: "Low", tone: "muted", rank: 1 },
};

export const STATUS: Record<IssueStatus, { label: string; tone: Tone }> = {
  OPEN: { label: "Unassigned", tone: "warning" },
  ASSIGNED: { label: "Assigned", tone: "info" },
  RESOLVED: { label: "Resolved", tone: "success" },
  CLOSED: { label: "Closed", tone: "muted" },
  CANCELLED: { label: "Cancelled", tone: "muted" },
};

/** Nothing reached the outlet, so the whole order can go again (A-24, R-ISS-04). */
export const REDELIVERABLE: IssueType[] = ["FAILED_DELIVERY", "STOCK_DISCREPANCY"];

/** The outcomes `issue:Resolve` takes; a replacement and a redelivery have commands of their own. */
export const RESOLUTIONS: Array<{ value: "write_off" | "no_fault_found" | "other"; label: string }> = [
  { value: "write_off", label: "Write off" },
  { value: "no_fault_found", label: "No fault found" },
  { value: "other", label: "Other" },
];

export type IssueAction = "take" | "resolve" | "replacement" | "redelivery" | "close" | "cancel";

export function subject(issue: Pick<IssueView, "subjects">, type: SubjectRef["type"]): string | null {
  return issue.subjects.find((ref) => ref.type === type)?.id ?? null;
}

export function isActive(issue: Pick<IssueView, "status">): boolean {
  return issue.status === "OPEN" || issue.status === "ASSIGNED";
}

/** What the dispatcher can do to this issue now, in the order the screen offers it. */
export function actionsFor(issue: IssueView, userId: string): IssueAction[] {
  if (issue.status === "RESOLVED") return ["close"];
  if (!isActive(issue)) return [];
  const actions: IssueAction[] = [];
  if (issue.assignee !== userId) actions.push("take");
  if (issue.type === "LOADING_SHORTFALL" && subject(issue, "trip") && subject(issue, "order")) actions.push("replacement");
  if (REDELIVERABLE.includes(issue.type) && subject(issue, "order")) actions.push("redelivery");
  actions.push("resolve", "cancel");
  return actions;
}

/** Most severe first, then oldest first: the order the server pages in. */
export function byUrgency(issues: IssueView[]): IssueView[] {
  return [...issues].sort(
    (a, b) =>
      SEVERITY[b.severity].rank - SEVERITY[a.severity].rank ||
      a.raisedAt.localeCompare(b.raisedAt) ||
      a.issueId.localeCompare(b.issueId),
  );
}

export type IssueFilter = "all" | "unassigned" | "mine" | "urgent";

export function matchesIssue(issue: IssueView, filter: IssueFilter, userId: string): boolean {
  if (filter === "unassigned") return issue.assignee === null;
  if (filter === "mine") return issue.assignee === userId;
  if (filter === "urgent") return issue.severity === "CRITICAL" || issue.severity === "HIGH";
  return true;
}

export type IssueCounts = { open: number; urgent: number; unassigned: number; mine: number };

export function issueCounts(issues: IssueView[], userId: string): IssueCounts {
  const active = issues.filter(isActive);
  return {
    open: active.length,
    urgent: active.filter((issue) => matchesIssue(issue, "urgent", userId)).length,
    unassigned: active.filter((issue) => issue.assignee === null).length,
    mine: active.filter((issue) => issue.assignee === userId).length,
  };
}

/** "8 min", "3 h", "2 d" since the issue was raised. */
export function age(raisedAt: string, now: Date): string {
  const minutes = Math.max(0, Math.floor((now.getTime() - new Date(raisedAt).getTime()) / 60_000));
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours} h` : `${Math.floor(hours / 24)} d`;
}

/** The day after a yyyy-mm-dd date: a redelivery's earliest sensible day. */
export function nextDay(isoDate: string): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(y!, m! - 1, d! + 1)).toISOString().slice(0, 10);
}

/** A short form of an id for a row: the dataset's own code, or the tail of a UUID. */
export function shortId(id: string): string {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-/i.test(id) ? `…${id.slice(-6)}` : id;
}
