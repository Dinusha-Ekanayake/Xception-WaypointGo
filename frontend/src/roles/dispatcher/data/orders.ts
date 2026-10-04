import type { OrderStatus, OrderView } from "@shared/domain/types";
import type { Tone } from "@shared/ui";

// The dispatcher's reading of a day's orders. Pure.

export const STATUS: Record<OrderStatus, { label: string; tone: Tone }> = {
  STOCK_UNKNOWN: { label: "Stock not confirmed", tone: "warning" },
  PARTIALLY_RESERVED: { label: "Short stock · store manager deciding", tone: "warning" },
  CONFIRMED: { label: "Confirmed · not planned", tone: "neutral" },
  ALLOCATED: { label: "Planned", tone: "info" },
  DEFERRED: { label: "Deferred", tone: "warning" },
  UNSERVABLE: { label: "Cannot be served", tone: "danger" },
  LOADING: { label: "Loading", tone: "info" },
  IN_TRANSIT: { label: "On the road", tone: "mint" },
  DELIVERED: { label: "Delivered · awaiting the store manager", tone: "neutral" },
  PARTIALLY_DELIVERED: { label: "Partly delivered", tone: "warning" },
  FAILED: { label: "Not delivered", tone: "danger" },
  RECEIVED: { label: "Confirmed by the store manager", tone: "success" },
  UNCONFIRMED: { label: "Store manager did not confirm", tone: "warning" },
  CANCELLED: { label: "Cancelled", tone: "muted" },
};

const PLANNED: OrderStatus[] = ["ALLOCATED", "LOADING", "IN_TRANSIT", "DELIVERED", "PARTIALLY_DELIVERED", "FAILED", "RECEIVED", "UNCONFIRMED"];
const LEFT_DOCK: OrderStatus[] = ["IN_TRANSIT", "DELIVERED", "PARTIALLY_DELIVERED", "FAILED", "RECEIVED", "UNCONFIRMED"];
const DELIVERED: OrderStatus[] = ["DELIVERED", "PARTIALLY_DELIVERED", "RECEIVED", "UNCONFIRMED"];
/** A person has to do something about these. */
const ATTENTION: OrderStatus[] = ["STOCK_UNKNOWN", "PARTIALLY_RESERVED", "DEFERRED", "UNSERVABLE", "PARTIALLY_DELIVERED", "FAILED", "UNCONFIRMED"];

export type Flow = {
  /** Due that day and not cancelled. */
  due: number;
  planned: number;
  leftDock: number;
  delivered: number;
  confirmedByStore: number;
  onTheRoad: number;
  awaitingStore: number;
  attention: number;
  /** Stock unknown: the warehouse did not answer, so these are not demand yet (R-STK-05). */
  stockUnknown: number;
};

export function flow(orders: OrderView[]): Flow {
  const live = orders.filter((order) => order.status !== "CANCELLED");
  const count = (statuses: OrderStatus[]) => live.filter((order) => statuses.includes(order.status)).length;
  return {
    due: live.length,
    planned: count(PLANNED),
    leftDock: count(LEFT_DOCK),
    delivered: count(DELIVERED),
    confirmedByStore: count(["RECEIVED"]),
    onTheRoad: count(["IN_TRANSIT"]),
    awaitingStore: count(["DELIVERED"]),
    attention: count(ATTENTION),
    stockUnknown: count(["STOCK_UNKNOWN"]),
  };
}

export type StatusFilter = "all" | "attention" | "to-plan" | "planned" | "road" | "done";

const FILTER: Record<Exclude<StatusFilter, "all">, OrderStatus[]> = {
  attention: ATTENTION,
  "to-plan": ["CONFIRMED", "DEFERRED", "STOCK_UNKNOWN", "PARTIALLY_RESERVED"],
  planned: ["ALLOCATED", "LOADING"],
  road: ["IN_TRANSIT"],
  done: ["DELIVERED", "PARTIALLY_DELIVERED", "FAILED", "RECEIVED", "UNCONFIRMED"],
};

export function matches(order: OrderView, filter: { status: StatusFilter; brand: string; text: string }): boolean {
  if (filter.status !== "all" && !FILTER[filter.status].includes(order.status)) return false;
  if (filter.brand !== "all" && order.brandCode !== filter.brand) return false;
  const text = filter.text.trim().toLowerCase();
  if (!text) return true;
  return [order.orderRef, order.outletId, order.districtName].some((value) => value.toLowerCase().includes(text));
}

const one = new Intl.NumberFormat("en-US", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const whole = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });

/** "7.9 m³ · 520 kg", display only. */
export function size(order: Pick<OrderView, "volumeM3" | "weightKg">): string {
  return `${one.format(Number(order.volumeM3))} m³ · ${whole.format(Number(order.weightKg))} kg`;
}
