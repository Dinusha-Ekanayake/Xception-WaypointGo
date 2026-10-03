import type { DeliveryRecordView, IssueType, IssueView, OrderView, ReceiptView, StatusChangeView } from "@shared/domain/types";
import { addDays, clock, dayLabel, depotToday, dockWhere, expectedAt, type StatusTone } from "./format.ts";
import type { StoreGateway } from "./gateway.ts";
import { isOpenIssue, loaderShortUnits } from "./issues.ts";

// The rows of "05a Deliveries" and the steps of "05b make-up delivery". A row is
// one vehicle's visit: the outlet's orders on one trip. Cases are the orders'
// own totals. A chilled order rides only on a refrigerated vehicle (R-PLN-02),
// which is all the screen can say about the vehicle.

export type Status = { label: string; tone: StatusTone };

export type Run = {
  tripId: string;
  vehicleId: string;
  serviceDate: string;
  /** This outlet's stops on the trip, in stop order. */
  records: DeliveryRecordView[];
  orders: OrderView[];
  refrigerated: boolean;
  /** Every order on it resends one that went wrong. */
  makeUp: boolean;
  units: number;
  eta: Date;
  arrivedAt: string | null;
  /** When the last of its orders was handed over; null until all were. */
  completedAt: string | null;
  /** A photo or signature was taken at handover. */
  signed: boolean;
  stop: { sequence: number; of: number | null };
  /** Units the loader kept back from these orders, while that is open. */
  short: number;
};

export function runsOf(records: DeliveryRecordView[], orders: OrderView[], issues: IssueView[]): Run[] {
  const trips = new Map<string, DeliveryRecordView[]>();
  for (const r of records) {
    const key = `${r.serviceDate}|${r.tripId}`;
    trips.set(key, [...(trips.get(key) ?? []), r]);
  }
  const orderOf = (r: DeliveryRecordView) => orders.find((o) => o.orderId === r.orderId);
  return [...trips.values()]
    .map((rs): Run => {
      const sorted = [...rs].sort((a, b) => a.stopSequence - b.stopSequence);
      const first = sorted[0]!;
      const own = sorted.map(orderOf).filter((o): o is OrderView => o !== undefined);
      const ids = new Set(sorted.map((r) => r.orderId));
      const done = sorted.every((r) => r.completedAt !== null);
      return {
        tripId: first.tripId,
        vehicleId: first.vehicleId,
        serviceDate: first.serviceDate,
        records: sorted,
        orders: own,
        refrigerated: own.some((o) => o.temperature === "chilled"),
        makeUp: own.length > 0 && own.every((o) => o.redeliveryOf !== null),
        units: sorted.reduce((s, r) => s + (orderOf(r)?.itemCount ?? r.lines.reduce((n, l) => n + l.orderedUnits, 0)), 0),
        eta: expectedAt(first),
        arrivedAt: sorted.find((r) => r.arrivedAt)?.arrivedAt ?? null,
        completedAt: done ? sorted.map((r) => r.completedAt!).sort().at(-1)! : null,
        signed: sorted.some((r) => r.proofId !== null),
        stop: { sequence: first.stopSequence, of: first.tripStopCount },
        short: issues
          .filter((i) => i.type === "LOADING_SHORTFALL" && isOpenIssue(i) && i.subjects.some((s) => s.type === "order" && ids.has(s.id)))
          .reduce((s, i) => s + loaderShortUnits(i), 0),
      };
    })
    .sort((a, b) => a.eta.getTime() - b.eta.getTime());
}

/** Where a run stands today: on the way, at the dock, delivered, or waiting for the store's count. */
export function runStatus(run: Run, waiting: ReadonlySet<string>, windowClose: string | null): Status {
  if (run.records.some((r) => r.outcome === "FAILED")) return { label: "Delivery failed", tone: "danger" };
  if (run.records.some((r) => waiting.has(r.orderId))) return { label: "Delivered · to receive", tone: "ink" };
  if (run.completedAt) return { label: "Delivered", tone: "ok" };
  if (run.arrivedAt) return { label: "At your dock", tone: "ink" };
  const close = windowClose ? new Date(`${run.serviceDate}T${windowClose.slice(0, 8)}+05:30`) : null;
  return close && run.eta > close ? { label: "On the way · running late", tone: "warn" } : { label: "On the way · on time", tone: "ok" };
}

/** What the store's count said about a run's orders: "Confirmed", "Confirmed · 1 short". */
export function countStatus(receipts: (ReceiptView | null)[]): Status & { short: number; known: boolean } {
  const known = receipts.filter((r): r is ReceiptView => r !== null);
  const short = known.reduce((s, r) => s + r.lines.reduce((n, l) => n + Math.max(0, l.expectedQuantity - (l.receivedQuantity ?? l.expectedQuantity)), 0), 0);
  const of = (label: string, tone: StatusTone) => ({ label, tone, short, known: known.length > 0 });
  if (known.length === 0) return of("Count not read", "muted");
  if (known.some((r) => r.status === "DISPUTED")) return of("Disputed", "danger");
  if (known.some((r) => r.status === "PENDING")) return of("To receive", "ink");
  if (known.some((r) => r.status === "AUTO_CLOSED")) return of("Closed unconfirmed", "warn");
  return short > 0 ? of(`Confirmed · ${short} short`, "danger") : of("Confirmed", "ok");
}

const WAITING = new Set(["STOCK_UNKNOWN", "PARTIALLY_RESERVED", "CONFIRMED", "DEFERRED"]);

export type Day = { date: string; orders: OrderView[]; status: Status };

/** Orders after today, a row per delivery day: planned, or waiting for the plan made after the cutoff. */
export function upcomingDays(orders: OrderView[], today: string): Day[] {
  const days = new Map<string, OrderView[]>();
  for (const o of orders) if (o.deliveryDate > today && o.status !== "CANCELLED") days.set(o.deliveryDate, [...(days.get(o.deliveryDate) ?? []), o]);
  return [...days.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, own]) => ({
      date,
      orders: own,
      status: own.some((o) => WAITING.has(o.status)) ? { label: "Waiting for plan", tone: "muted" } : { label: "Planned", tone: "ok" },
    }));
}

/**
 * The outlet's deliveries of the last seven days and the store's count of each
 * order. A day whose read fails is left out; when every read fails the failure
 * is thrown, so the screen says so rather than show an empty week.
 */
export async function pastWeek(
  gateway: Pick<StoreGateway, "deliveries" | "receipt">,
  outletId: string,
  today: string,
  signal: AbortSignal,
): Promise<{ records: DeliveryRecordView[]; receipts: Map<string, ReceiptView | null> }> {
  const days = Array.from({ length: 7 }, (_, i) => addDays(today, -(i + 1)));
  const reads = await Promise.allSettled(days.map((d) => gateway.deliveries(outletId, d, signal)));
  const failed = reads.filter((r): r is PromiseRejectedResult => r.status === "rejected");
  if (failed.length === reads.length) throw failed[0]!.reason;
  const records = reads.flatMap((r) => (r.status === "fulfilled" ? r.value : []));
  const ids = [...new Set(records.map((r) => r.orderId))];
  const counts = await Promise.allSettled(ids.map((id) => gateway.receipt(id, signal)));
  const receipts = new Map<string, ReceiptView | null>();
  ids.forEach((id, i) => {
    const count = counts[i]!;
    receipts.set(id, count.status === "fulfilled" ? count.value : null);
  });
  return { records, receipts };
}

// ---- "05b make-up delivery" ---------------------------------------------------

export type Step = { title: string; detail: string; done: boolean };

const REPORTED: Partial<Record<IssueType, string>> = {
  LOADING_SHORTFALL: "Short at loading",
  RECEIPT_DISPUTE: "Short reported",
  DAMAGED_GOODS: "Damage reported",
  FAILED_DELIVERY: "Delivery failed",
};

/** The issue the make-up answers: the one on the original order resolved as a redelivery, else its first. */
export function issueBehind(order: OrderView, issues: IssueView[]): IssueView | null {
  const on = issues
    .filter((i) => i.subjects.some((s) => s.type === "order" && s.id === order.redeliveryOf))
    .sort((a, b) => a.raisedAt.localeCompare(b.raisedAt));
  return on.find((i) => i.resolutionAction === "REDELIVERY") ?? on[0] ?? null;
}

/**
 * A make-up delivery's way to the outlet: the problem on the original order,
 * the make-up booked when the dispatcher resolved it as a redelivery, loading,
 * arriving, and the store's count. A step is done only on a record that says
 * so; the others say what comes next.
 */
export function makeUpSteps(input: {
  order: OrderView;
  issue: IssueView | null;
  timeline: StatusChangeView[];
  record: DeliveryRecordView | null;
  dock: string | null;
  window: string | null;
  today: string;
}): Step[] {
  const { order, issue, timeline, record, dock, window, today } = input;
  const day = (date: string) => (date === today ? "Today" : dayLabel(date));
  const at = (instant: string) => `${day(depotToday(new Date(instant)))} · ${clock(instant)}`;
  const loaded = timeline.find((h) => h.to === "LOADING" || h.to === "IN_TRANSIT");
  return [
    { title: (issue && REPORTED[issue.type]) ?? "Problem reported", detail: issue ? at(issue.raisedAt) : "On the original order", done: true },
    { title: "Make-up booked", detail: at(issue?.resolvedAt ?? order.placedAt), done: true },
    {
      title: `Loading at depot ${order.depotCode}`,
      detail: loaded ? at(loaded.at) : record ? at(record.releasedAt) : `${day(order.deliveryDate)} · before the run`,
      done: loaded !== undefined || record !== null,
    },
    {
      title: `Arriving ${dockWhere(dock)}`,
      detail: record?.arrivedAt ? `Arrived ${clock(record.arrivedAt)}` : record ? `Expected ${clock(expectedAt(record))}` : window ? `Window ${window}` : "Time set when it leaves",
      done: Boolean(record?.arrivedAt),
    },
    { title: "You confirm what arrived", detail: order.status === "RECEIVED" ? "Counted" : "Count and confirm with PIN", done: order.status === "RECEIVED" },
  ];
}
