import type { IsoDate, OrderStatus } from "@shared/domain/types";
import { addDays, businessNow, dayLabel, hhmm } from "../../../shared/wording/index.ts";
import { translate, type Translate } from "./strings.ts";

/** These write English unless the screen passes its language (roadmap FF-00). */
const english: Translate = (text, vars) => translate("en", text, vars);
export { addDays, clock, dayLabel, depotToday, greeting, hhmm, longDay, productLines, units } from "../../../shared/wording/index.ts";

// Dates and labels for the store screens. The depot clock is Asia/Colombo; the
// cutoff shown here is a countdown for the manager, and the server's clock
// decides whether an order made it (R-ORD-07).

const TZ = "Asia/Colombo";
export const CUTOFF_HOUR = 16;


/** Milliseconds until today's 16:00 in Colombo; negative once it has passed. */
export function untilCutoff(now = businessNow()): number {
  const parts = new Intl.DateTimeFormat("en-GB", { hour: "numeric", minute: "numeric", second: "numeric", hour12: false, timeZone: TZ }).formatToParts(now);
  const n = (t: string) => Number(parts.find((p) => p.type === t)?.value ?? 0);
  const seconds = (n("hour") % 24) * 3600 + n("minute") * 60 + n("second");
  return (CUTOFF_HOUR * 3600 - seconds) * 1000;
}

/** "12 h 29 m left", or "closed". */
export function cutoffLabel(ms: number): string {
  if (ms <= 0) return "closed for today";
  const m = Math.floor(ms / 60_000);
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} m left` : `${m} m left`;
}

/**
 * When an order for `deliveryDate` stops being changeable: 4:00 PM the day
 * before, said as "Today 4:00 PM" while that is still today.
 */
export function changeDeadline(deliveryDate: IsoDate, today: IsoDate, t: Translate = english): string {
  const before = addDays(deliveryDate, -1);
  return `${before === today ? t("Today") : dayLabel(before)} ${CUTOFF_HOUR}:00`;
}

export type StatusTone = "mint" | "ink" | "warn" | "danger" | "muted" | "ok";

export const ORDER_STATUS: Record<OrderStatus, { label: string; tone: StatusTone }> = {
  STOCK_UNKNOWN: { label: "Stock not checked", tone: "warn" },
  PARTIALLY_RESERVED: { label: "Partly in stock", tone: "warn" },
  CONFIRMED: { label: "Confirmed", tone: "ok" },
  ALLOCATED: { label: "Planned", tone: "ok" },
  DEFERRED: { label: "Deferred", tone: "warn" },
  UNSERVABLE: { label: "Cannot be served", tone: "danger" },
  LOADING: { label: "Loading", tone: "mint" },
  IN_TRANSIT: { label: "On the way", tone: "mint" },
  DELIVERED: { label: "Delivered · to receive", tone: "ink" },
  PARTIALLY_DELIVERED: { label: "Part delivered", tone: "warn" },
  FAILED: { label: "Delivery failed", tone: "danger" },
  RECEIVED: { label: "Received", tone: "muted" },
  UNCONFIRMED: { label: "Closed unconfirmed", tone: "warn" },
  CANCELLED: { label: "Cancelled", tone: "muted" },
};

/** The store can still change these; later ones are planned or moving. */
/**
 * Issue #224: what the store is promised once the plan is published, "Planned
 * for Fri 9 Oct · stop 3 · planned arrival 06:10"; null while it is not on a plan.
 */
export function planNote(
  order: { status: OrderStatus; deliveryDate: string; plannedStop?: number | null; plannedArrival?: string | null },
  t: Translate = english,
): string | null {
  if (order.plannedStop == null || !(order.status === "ALLOCATED" || order.status === "LOADING" || order.status === "IN_TRANSIT")) return null;
  const vars = { day: dayLabel(order.deliveryDate), n: order.plannedStop };
  return order.plannedArrival
    ? t("Planned for {day} · stop {n} · planned arrival {time}", { ...vars, time: hhmm(order.plannedArrival) })
    : t("Planned for {day} · stop {n}", vars);
}

export const editable = (status: OrderStatus) => status === "CONFIRMED" || status === "STOCK_UNKNOWN";
export const onTheWay = (status: OrderStatus) => status === "LOADING" || status === "IN_TRANSIT";
export const temperatureLabel = (t: string) => (t === "chilled" ? "Chilled" : "Ambient");

/**
 * When the vehicle is expected at the outlet, as an instant. The observed delay
 * moves it (R-EXE-15); until one is observed it is the plan's time at this stop.
 * The plan's time is a wall-clock time on the delivery day in the depot's zone.
 */
export function expectedAt(stop: { expectedArrival: string | null; plannedArrival: string; serviceDate: IsoDate }): Date {
  if (stop.expectedArrival) return new Date(stop.expectedArrival);
  return new Date(`${stop.serviceDate}T${stop.plannedArrival.slice(0, 8)}+05:30`);
}

/** "13 min", "1 h 5 min"; "now" inside a minute, "late" once past. */
export function minutesLabel(target: Date, now = businessNow()): string {
  const m = Math.round((target.getTime() - now.getTime()) / 60_000);
  if (m < 0) return "late";
  if (m < 1) return "now";
  return m >= 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m} min`;
}

const DOCKS: Record<string, { label: string; where: string }> = {
  rear_dock: { label: "Rear dock", where: "at your rear dock" },
  street: { label: "Street", where: "outside your store" },
  mall_bay: { label: "Mall bay", where: "at the mall bay" },
};

/** "Rear dock", from the outlet's dock code ("rear_dock"). */
export const dockLabel = (code: string) => DOCKS[code]?.label ?? code.replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase());

/** "at your rear dock", for a sentence about where a vehicle stands. */
export const dockWhere = (code: string | null | undefined) => (code ? (DOCKS[code]?.where ?? `at your ${code.replace(/_/g, " ")}`) : "at your store");

export { productLabel } from "../../../shared/wording/index.ts";

/**
 * Why an order's delivery day moved, in the store's words, or null when it did
 * not move. Orders for a day close at 16:00 the day before (R-ORD-01): an order
 * placed at or after that time arrived after the cutoff, for example one saved
 * on the phone with no signal and sent later. Otherwise the asked-for day had no
 * deliveries (R-ORD-08). Depot time is Asia/Colombo, UTC+05:30 all year.
 */
export function movedWhy(order: MovedOrder, t: Translate = english): string | null {
  const reason = movedReason(order);
  if (!reason) return null;
  const day = dayLabel(order.requestedDate);
  return reason === "cutoff"
    ? t("Moved from {day}: your order arrived after the 16:00 cutoff.", { day })
    : t("Moved from {day}: no deliveries that day.", { day });
}

type MovedOrder = { dateRolled: boolean; requestedDate: IsoDate; placedAt?: string | null; rolledBecause?: string[] };

/** "cutoff" or "closed-day" for a moved order: the order's own reason when it has one, else from when it was placed. */
export function movedReason(order: MovedOrder): "cutoff" | "closed-day" | null {
  if (!order.dateRolled) return null;
  if (order.rolledBecause) return order.rolledBecause.includes("cutoff") ? "cutoff" : "closed-day";
  const closed = Date.parse(`${addDays(order.requestedDate, -1)}T16:00:00+05:30`);
  const placed = order.placedAt ? Date.parse(order.placedAt) : NaN;
  return placed >= closed ? "cutoff" : "closed-day";
}

/**
 * Roadmap T1-04 and MF-06: how long to hold someone at the dock. A range when
 * the prediction has one ("unloading 15 to 24 min"), else the minutes alone,
 * else nothing. Returned as a string key and its values, for the store's t().
 */
export function unloadingLabel(stop: {
  expectedServiceMinutes?: number | string | null;
  expectedServiceLow?: number | string | null;
  expectedServiceHigh?: number | string | null;
}): { key: string; values: Record<string, number> } | null {
  const n = Math.round(Number(stop.expectedServiceMinutes ?? 0));
  if (!(n > 0)) return null;
  const low = Math.round(Number(stop.expectedServiceLow ?? NaN));
  const high = Math.round(Number(stop.expectedServiceHigh ?? NaN));
  if (Number.isFinite(low) && Number.isFinite(high) && high > low) {
    return { key: "unloading {low} to {high} min", values: { low: Math.max(1, low), high } };
  }
  return { key: "unloading about {n} min", values: { n } };
}
