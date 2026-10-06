import { request, requestAll } from "../../shared/api/client.ts";
import type { IssueHistoryView, IssueType, IssueView, WriteOffView } from "../../shared/domain/issues.ts";

// Roadmap FF-03: Operations' view of a depot's damaged-goods cases. Reads only
// issues: the role's policy holds issue:Read and the two inquiry commands, and
// nothing of trips, plans or messages.

/** The issue types an inquiry is about: goods damaged, short or disputed. */
export const INQUIRY_TYPES: IssueType[] = ["DAMAGED_GOODS", "LOADING_SHORTFALL", "RECEIPT_DISPUTE", "STOCK_DISCREPANCY"];

export const TYPE_LABEL: Partial<Record<IssueType, string>> = {
  DAMAGED_GOODS: "Damaged goods",
  LOADING_SHORTFALL: "Loading shortfall",
  RECEIPT_DISPUTE: "Receipt disputed",
  STOCK_DISCREPANCY: "Stock discrepancy",
};

export function inquiryIssues(issues: IssueView[]): IssueView[] {
  const seen = new Set<string>();
  return issues
    .filter((i) => INQUIRY_TYPES.includes(i.type) && !seen.has(i.issueId) && seen.add(i.issueId))
    .sort((a, b) => b.raisedAt.localeCompare(a.raisedAt));
}

export async function readInquiries(depots: string[], signal: AbortSignal): Promise<IssueView[]> {
  const q = encodeURIComponent;
  const lists = await Promise.all(
    depots.flatMap((depot) => [
      requestAll<IssueView>(`/api/issues?depot=${q(depot)}`, { signal }),
      request<IssueView[]>(`/api/issues/settled?depot=${q(depot)}`, { signal }),
    ]),
  );
  return inquiryIssues(lists.flat());
}

export type Case = { issue: IssueView; history: IssueHistoryView[]; writeOffs: WriteOffView[] };

export async function readCase(issueId: string, signal: AbortSignal): Promise<Case> {
  const path = `/api/issues/${encodeURIComponent(issueId)}`;
  const [issue, history, writeOffs] = await Promise.all([
    request<IssueView>(path, { signal }),
    request<IssueHistoryView[]>(`${path}/history`, { signal }),
    request<WriteOffView[]>(`${path}/write-offs`, { signal }),
  ]);
  return { issue, history, writeOffs };
}

export const photoUrl = (issueId: string, attachmentId: string): string =>
  `/api/issues/${encodeURIComponent(issueId)}/attachments/${encodeURIComponent(attachmentId)}/content`;

/** "LKR 30,000.00"; the value is rupees as the server sent it. */
export function rupees(value: string): string {
  const n = Number(value);
  return Number.isFinite(n) ? `LKR ${n.toLocaleString("en-LK", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` : `LKR ${value}`;
}

/**
 * Whether this person may decide a recorded write-off, as P-DG-1 reads; the
 * server decides again. Never the recorder; above the limit or a Tech line, only
 * the Head of Distribution.
 */
export function mayDecide(w: Pick<WriteOffView, "status" | "recordedBy" | "needsHead">, userId: string, isHead: boolean): boolean {
  return w.status === "recorded" && w.recordedBy !== userId && (!w.needsHead || isHead);
}

/** A write-off value as typed: rupees, at most two decimals, up to 10,000,000. Null when it is not one. */
export function parseRupees(text: string): string | null {
  const t = text.replace(/,/g, "").trim();
  if (!/^\d{1,8}(\.\d{1,2})?$/.test(t)) return null;
  return Number(t) <= 10_000_000 ? t : null;
}

/** History actions in words; unknown ones as they are, never a raw code with underscores. */
export function actionLabel(action: string): string {
  const words: Record<string, string> = {
    raised: "Raised",
    inquiry: "Inquiry recorded",
    write_off_approved: "Write-off approved",
    write_off_rejected: "Write-off rejected",
    resolved: "Resolved",
    assigned: "Assigned",
    closed: "Closed",
    cancelled: "Cancelled",
    escalated: "Escalated",
  };
  return words[action] ?? action.replace(/_/g, " ");
}
