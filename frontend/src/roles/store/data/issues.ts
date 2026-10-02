import type { IssueStatus, IssueType, IssueView, OrderView } from "@shared/domain/types";
import { addDays, depotToday, type StatusTone } from "./format.ts";
import type { StoreGateway } from "./gateway.ts";

// What the store sees of issues: the ones about its own orders. Issues are read
// per order (`/api/issues/by-subject`), because the list endpoint is per depot
// and a store manager has no depot scope. Recent orders only, so the number of
// reads stays small and an old closed issue does not weigh on the screen.

const LOOKBACK_DAYS = 14;

export const ISSUE_TYPE: Record<IssueType, string> = {
  LOADING_SHORTFALL: "Short at loading",
  DAMAGED_GOODS: "Damaged goods",
  FAILED_DELIVERY: "Delivery failed",
  LATE_DELIVERY: "Late delivery",
  VEHICLE_FAULT: "Vehicle fault",
  ROAD_DISRUPTION: "Road disruption",
  RECEIPT_DISPUTE: "Receipt disputed",
  STOCK_DISCREPANCY: "Stock discrepancy",
  OTHER: "Other",
};

export const ISSUE_STATUS: Record<IssueStatus, { label: string; tone: StatusTone }> = {
  OPEN: { label: "Open", tone: "danger" },
  ASSIGNED: { label: "With dispatch", tone: "warn" },
  RESOLVED: { label: "Resolved", tone: "ok" },
  CLOSED: { label: "Closed", tone: "muted" },
  CANCELLED: { label: "Cancelled", tone: "muted" },
};

export const isOpenIssue = (i: IssueView) => i.status === "OPEN" || i.status === "ASSIGNED";

/** The orders worth asking about: recent, and not cancelled. */
export function recentOrderIds(orders: OrderView[], today = depotToday()): string[] {
  const since = addDays(today, -LOOKBACK_DAYS);
  return orders.filter((o) => o.status !== "CANCELLED" && o.deliveryDate >= since).map((o) => o.orderId);
}

/**
 * Every issue about those orders, newest first, once each. A read that fails for
 * one order is skipped rather than hiding the rest; when every read fails the
 * failure is thrown so the screen says so instead of showing "no issues".
 */
export async function issuesForOrders(gateway: StoreGateway, orderIds: string[], signal: AbortSignal): Promise<IssueView[]> {
  const results = await Promise.allSettled(orderIds.map((id) => gateway.issuesFor(id, signal)));
  const failed = results.filter((r): r is PromiseRejectedResult => r.status === "rejected");
  if (failed.length > 0 && failed.length === results.length) throw failed[0]!.reason;
  const byId = new Map<string, IssueView>();
  for (const r of results) if (r.status === "fulfilled") for (const i of r.value) byId.set(i.issueId, i);
  return [...byId.values()].sort((a, b) => b.raisedAt.localeCompare(a.raisedAt));
}

// ---- how an issue reads on the store's screen (Figma "08 Issues") ------------

/** What the store can report after unpacking, and the type each is raised as. The store's policy
 * lets it raise damaged goods, late delivery and other (R-ISS-07); a dispute goes through the
 * receipt, so missing and wrong items are "other" with the words saying which. */
export const REPORT_KINDS = [
  { label: "Missing items", kind: "Missing", type: "OTHER" },
  { label: "Damaged items", kind: "Damaged", type: "DAMAGED_GOODS" },
  { label: "Wrong items", kind: "Wrong item", type: "OTHER" },
  { label: "Other", kind: "Other", type: "OTHER" },
] as const;

export type ReportKind = (typeof REPORT_KINDS)[number];

const WORDS: [string, string][] = [
  ["Damaged", "damaged"],
  ["Missing", "missing"],
  ["Wrong item", "wrong"],
  ["Other", "other"],
];

/** Units per problem from a store's note ("Damaged: Red lentils 1 kg x1, Biscuits x2. Missing: Soya meat x1.").
 * A part ends at a full stop before a space or the end, so "Milk 1.5 L x2" stays one item. */
export function problemsIn(text: string): { word: string; units: number }[] {
  const out: { word: string; units: number }[] = [];
  for (const [kind, word] of WORDS) {
    const m = text.match(new RegExp(`${kind}: (.*?)\\.(?: |$)`));
    if (!m) continue;
    const units = m[1]!.split(", ").reduce((s, item) => s + (Number(item.match(/ x(\d+)$/)?.[1]) || 1), 0);
    out.push({ word, units });
  }
  return out;
}

export type IssueCard = {
  /** "Damaged on arrival", "Short delivery". */
  label: string;
  tone: "danger" | "ok" | "muted";
  /** "3 packages of ORD0092335 (Ambient)". */
  title: string;
  /** "2 damaged · 1 missing · photos attached", "Reported by the loader at 03:10". */
  detail: string;
  /** "Sent 05:54", "Reported 03:10". */
  stamp: string;
};

const packages = (n: number) => `${n} ${n === 1 ? "package" : "packages"}`;

/** One issue as a card. `order` names it; without one the issue's own words stand. */
export function issueCard(issue: IssueView, order: OrderView | null, clock: (instant: string) => string): IssueCard {
  const of = order ? `${order.orderRef} (${order.temperature === "chilled" ? "Chilled" : "Ambient"})` : "an order";
  const photos = (issue.attachments?.length ?? 0) > 0 ? " · photos attached" : "";
  const closed = !isOpenIssue(issue);
  const at = clock(issue.raisedAt);

  if (issue.type === "LOADING_SHORTFALL") {
    const units = Number(issue.description.match(/(\d+) units?/)?.[1]) || 0;
    return {
      label: "Short delivery",
      tone: closed ? "muted" : "ok",
      title: units ? `${packages(units)} of ${of}` : `Short at loading: ${of}`,
      detail: `Reported by the loader at ${at}`,
      stamp: `Reported ${at}`,
    };
  }
  const problems = problemsIn(issue.description);
  const total = problems.reduce((s, p) => s + p.units, 0);
  const label =
    issue.type === "DAMAGED_GOODS" || problems.some((p) => p.word === "damaged")
      ? "Damaged on arrival"
      : problems.some((p) => p.word === "missing")
        ? "Missing on arrival"
        : problems.some((p) => p.word === "wrong")
          ? "Wrong item"
          : issue.type === "RECEIPT_DISPUTE"
            ? "Count differs"
            : ISSUE_TYPE[issue.type];
  return {
    label,
    tone: closed ? "muted" : "danger",
    title: total ? `${packages(total)} of ${of}` : `${ISSUE_TYPE[issue.type]}: ${of}`,
    detail: (problems.length ? problems.map((p) => `${p.units} ${p.word}`).join(" · ") : issue.description) + photos,
    stamp: closed ? `${ISSUE_STATUS[issue.status].label} ${clock(issue.resolvedAt ?? issue.raisedAt)}` : `Sent ${at}`,
  };
}
