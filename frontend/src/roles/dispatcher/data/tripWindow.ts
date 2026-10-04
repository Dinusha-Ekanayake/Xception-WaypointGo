import type { ConstraintResultView, OrderView, PlanView, StopView, VehicleView } from "@shared/domain/types";
import { checkLabel, hhmm } from "../../../shared/wording/index.ts";
import { size } from "./orders.ts";
import { lastServedText, stopShare } from "./planViews.ts";

// What the trip window shows for a trip as it is, or as a swap or a new stop
// order would leave it: the load against the vehicle, each stop's line, and the
// checks as chips. Pure; the server's preview supplies the times and checks.
// Load here is a reading for the dispatcher, from order-level totals; whether a
// load fits is decided by the server's capacity rule, never by this sum.

type Tile = { label: string; value: string; note: string; percent: number | null; warn?: boolean };
type StopRow = { orderId: string; title: string; sub: string; note?: string; tag?: string; window: string; eta: string; share: number | null };
type Chip = { ok: boolean | "warn"; text: string; title?: string };

const TIGHT = 90;

function sum(orderIds: string[], orders: Map<string, OrderView>, field: "volumeM3" | "weightKg"): number {
  return orderIds.reduce((total, id) => total + Number(orders.get(id)?.[field] ?? 0), 0);
}

function pct(used: number, cap: number | string | undefined): number | null {
  const c = Number(cap);
  return Number.isFinite(c) && c > 0 ? Math.round((used / c) * 100) : null;
}

function late(stop: StopView): boolean {
  return Boolean(stop.windowClose) && Boolean(stop.plannedArrival) && stop.plannedArrival.slice(0, 5) > stop.windowClose.slice(0, 5);
}

/**
 * The trip's load and windows. `checked` false means the trip changed and the
 * server has not timed it yet: the windows tile says so rather than judging
 * times from before the change. A stop with no window, or no time yet, is
 * counted apart, never as met.
 */
export function tripTiles(stops: StopView[], orders: Map<string, OrderView>, vehicle: VehicleView | undefined, checked = true): Tile[] {
  const ids = stops.map((stop) => stop.orderId);
  const volume = sum(ids, orders, "volumeM3");
  const weight = sum(ids, orders, "weightKg");
  const v = pct(volume, vehicle?.volumeCapM3);
  const w = pct(weight, vehicle?.weightCapKg);
  const lateCount = stops.filter(late).length;
  const unknown = stops.filter((stop) => !stop.windowClose || !stop.plannedArrival).length;
  const judged = stops.length - unknown;
  return [
    {
      label: "Volume",
      value: v === null ? "n/a" : `${v}%`,
      note: vehicle ? `${volume.toFixed(1)} of ${Number(vehicle.volumeCapM3).toFixed(1)} m³` : "vehicle capacity unknown",
      percent: v,
      warn: (v ?? 0) >= TIGHT,
    },
    {
      label: "Weight",
      value: w === null ? "n/a" : `${w}%`,
      note: vehicle ? `${Math.round(weight).toLocaleString("en-GB")} of ${Number(vehicle.weightCapKg).toLocaleString("en-GB")} kg` : "vehicle capacity unknown",
      percent: w,
      warn: (w ?? 0) >= TIGHT,
    },
    { label: "Stops", value: `${stops.length}`, note: "on this trip", percent: null },
    !checked
      ? { label: "Delivery windows", value: "Not checked yet", note: "the server is timing the trip", percent: null, warn: true }
      : {
          label: "Delivery windows",
          value: stops.length === 0 ? "No stops" : lateCount > 0 ? `${lateCount} late` : unknown > 0 ? `${judged} of ${stops.length} met` : "All met",
          note: unknown > 0 ? `${unknown} without a window or a time` : stops.length ? `last stop ${hhmm(stops[stops.length - 1]!.plannedArrival)}` : "no stop",
          percent: judged ? Math.round(((judged - lateCount) / stops.length) * 100) : null,
          warn: lateCount > 0 || unknown > 0,
        },
  ];
}

export function stopRows(
  plan: PlanView,
  stops: StopView[],
  orders: Map<string, OrderView>,
  vehicle: VehicleView | undefined,
  tagged: string | null = null,
): StopRow[] {
  return stops.map((stop) => {
    const order = orders.get(stop.orderId);
    const allocation = plan.allocations.find((a) => a.orderId === stop.orderId);
    const served = allocation ? lastServedText(allocation.lastServedOn, plan.serviceDate) : null;
    return {
      orderId: stop.orderId,
      title: `${order ? `${order.brandCode} ` : ""}${stop.outletId}${order ? ` ${order.districtName}` : ""}`,
      sub: [order?.orderRef, order ? size(order) : null, served].filter(Boolean).join(" · "),
      note: late(stop) ? `Arrives after its window closes at ${hhmm(stop.windowClose)}` : undefined,
      tag: stop.orderId === tagged ? "NEW · added by you" : undefined,
      window: stop.windowOpen && stop.windowClose ? `${hhmm(stop.windowOpen)}-${hhmm(stop.windowClose)}` : "No window",
      eta: stop.plannedArrival ? hhmm(stop.plannedArrival) : "…",
      share: stopShare(order, vehicle),
    };
  });
}

/** Each rule once, in words; a failed rule is red, a rule with little room left is amber. */
export function checkChips(checks: ConstraintResultView[]): Chip[] {
  const seen = new Set<string>();
  const chips: Chip[] = [];
  for (const check of checks) {
    if (seen.has(check.ruleId)) continue;
    seen.add(check.ruleId);
    chips.push({ ok: check.passed, text: checkLabel(check.ruleId, check.passed), title: check.reason });
  }
  return chips;
}
