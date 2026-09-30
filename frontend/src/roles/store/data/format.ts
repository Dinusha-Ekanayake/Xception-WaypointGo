import type { IsoDate, OrderStatus } from "@shared/domain/types";

// Dates and labels for the store screens. The depot clock is Asia/Colombo; the
// cutoff shown here is a countdown for the manager, and the server's clock
// decides whether an order made it (R-ORD-07).

const TZ = "Asia/Colombo";
export const CUTOFF_HOUR = 16;

/** Today's date in the depot's timezone, as YYYY-MM-DD. */
export function depotToday(now = new Date()): IsoDate {
  return new Intl.DateTimeFormat("en-CA", { timeZone: TZ }).format(now);
}

export function addDays(date: IsoDate, days: number): IsoDate {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** "Tue 29 Sep". */
export function dayLabel(date: IsoDate): string {
  const parts = new Intl.DateTimeFormat("en-US", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" }).formatToParts(new Date(`${date}T00:00:00Z`));
  const part = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
  return `${part("weekday")} ${part("day")} ${part("month")}`;
}

/** "3 cases", "1 case". */
export const cases = (n: number) => `${n} ${n === 1 ? "case" : "cases"}`;

/** "Mon 28 September". */
export function longDay(date: IsoDate): string {
  return new Intl.DateTimeFormat("en-GB", { weekday: "short", day: "numeric", month: "long", timeZone: "UTC" }).format(new Date(`${date}T00:00:00Z`));
}

/** "05:44" in the depot's clock. */
export function clock(instant: string | Date): string {
  return new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hour12: false, timeZone: TZ }).format(new Date(instant));
}

/** "05:00" from "05:00:00". */
export const hhmm = (time: string) => time.slice(0, 5);

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

export function greeting(now = new Date()): string {
  const h = Number(new Intl.DateTimeFormat("en-GB", { hour: "numeric", hour12: false, timeZone: TZ }).format(now));
  return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
}

export type StatusTone = "mint" | "ink" | "warn" | "danger" | "muted" | "ok";

export const ORDER_STATUS: Record<OrderStatus, { label: string; tone: StatusTone }> = {
  STOCK_UNKNOWN: { label: "Stock not checked", tone: "warn" },
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
