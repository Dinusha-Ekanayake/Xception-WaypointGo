import type { IsoDate, OrderStatus } from "@shared/domain/types";
import { addDays, dayLabel } from "../../../shared/wording/index.ts";
export { addDays, clock, dayLabel, depotToday, greeting, hhmm, longDay } from "../../../shared/wording/index.ts";

// Dates and labels for the store screens. The depot clock is Asia/Colombo; the
// cutoff shown here is a countdown for the manager, and the server's clock
// decides whether an order made it (R-ORD-07).

const TZ = "Asia/Colombo";
export const CUTOFF_HOUR = 16;

/** "3 cases", "1 case". */
export const cases = (n: number) => `${n} ${n === 1 ? "case" : "cases"}`;

/** Milliseconds until today's 16:00 in Colombo; negative once it has passed. */
export function untilCutoff(now = new Date()): number {
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
export function changeDeadline(deliveryDate: IsoDate, today: IsoDate): string {
  const before = addDays(deliveryDate, -1);
  return `${before === today ? "Today" : dayLabel(before)} ${CUTOFF_HOUR - 12}:00 PM`;
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
export function minutesLabel(target: Date, now = new Date()): string {
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
