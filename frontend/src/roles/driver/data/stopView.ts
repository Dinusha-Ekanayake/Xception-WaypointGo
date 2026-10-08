import type { OutletView } from "@shared/domain/types";
import { clock, codeLabel, DOCK_TYPE, hhmm } from "../../../shared/wording/index.ts";
import { isFinished, lateMinutes, summarize, unitsHandedOver, type Stop } from "./run.ts";
import { translate, type Translate } from "./strings.ts";

/** The functions below write English unless the screen passes its language (roadmap FF-00). */
const english: Translate = (text, vars) => translate("en", text, vars);

// The Figma driver screens (#174) draw a stop in this shape. It is filled from
// the run sheet Execution serves, never from sample data (issue #117). Pure; the
// clock is a parameter.

export type RouteStop = {
  /** The delivery id: what every command for this stop names. */
  id: string;
  stopNumber: string;
  stopIndex: number;
  totalStops: number;
  name: string;
  address: string;
  /** The outlet's place (its district, from Reference); null when the outlet is not known yet. */
  place: string | null;
  /** The brand the trip carries (Fresh, Style or Tech), from Reference; null when not known yet. */
  brand: string | null;
  dockTag: string;
  /** Expected arrival in depot time: planned, moved by any delay already seen. */
  eta: string;
  /** A second line under the time; the data holds no road distance, so it is the plan. */
  etaDistanceTime: string;
  window: string;
  windowStatus: string;
  cargoText: string;
  instructions: string;
  mapButtonType: "split" | "wide";
  /** Recorded or replanned: nothing left to do here. */
  finished: boolean;
  /** Recorded as delivered in full; read this, never the status text, which is in the driver's language. */
  delivered: boolean;
  /** One or more writes for this stop are still only on this phone. */
  onPhone: boolean;
  /** When the stop was recorded, in depot time; null until it is. */
  doneAt: string | null;
  expectedUnits: number;
  deliveredItems: Array<{ code: string; category: string; qty: number }>;
};

function access(outlet: OutletView | undefined, t: Translate): string {
  if (!outlet) return "";
  const dock = t(codeLabel(DOCK_TYPE, outlet.dockType));
  if (outlet.parkingConstraint === "mall_dock") return t("Mall dock. Deliveries are taken only inside the window.");
  if (outlet.parkingConstraint === "van_only") return t("{dock} · vans only.", { dock });
  return `${dock}.`;
}

function status(serviceDate: string, stop: Stop, now: Date, t: Translate): string {
  if (stop.outcome === "DELIVERED") return t("Delivered");
  if (stop.outcome === "PARTIAL") return t("Partly delivered");
  if (stop.outcome === "FAILED") return t("Not delivered");
  if (stop.outcome === "SKIPPED") return t("Replanned");
  const late = (stop.lateMinutes ?? 0) > 0 ? stop.lateMinutes! : lateMinutes(serviceDate, stop.windowClose, now);
  return late > 0 ? t("{n} min late", { n: late }) : t("On time");
}

export function toRouteStops(
  stops: Stop[],
  outlets: Record<string, OutletView>,
  serviceDate: string,
  now: Date,
  t: Translate = english,
): RouteStop[] {
  return stops.map((stop, index) => {
    const outlet = outlets[stop.outletId];
    return {
      id: stop.deliveryId,
      stopNumber: String(stop.sequence).padStart(2, "0"),
      stopIndex: index,
      totalStops: stops.length,
      name: stop.outletId,
      address: outlet ? `${outlet.districtName} · ${outlet.brandCode}` : "",
      place: outlet?.districtName ?? null,
      brand: outlet?.brandCode ?? null,
      dockTag: outlet ? t(codeLabel(DOCK_TYPE, outlet.dockType)) : "",
      eta: clock(stop.expectedArrival ?? stop.plannedArrival),
      etaDistanceTime: stop.expectedArrival ? t("Planned {time}", { time: hhmm(stop.plannedArrival) }) : t("As planned"),
      window: hhmm(stop.windowClose),
      windowStatus: status(serviceDate, stop, now, t),
      // Roadmap T1-04: how long the stop is expected to take, from the plan's prediction.
      cargoText: (stop.itemCount === 1 ? t("1 unit") : t("{n} units", { n: stop.itemCount })) + unloadText(stop.expectedServiceMinutes, stop.expectedServiceLow, stop.expectedServiceHigh, t),
      instructions: access(outlet, t),
      mapButtonType: "split",
      finished: isFinished(stop),
      delivered: stop.outcome === "DELIVERED",
      onPhone: stop.waiting,
      doneAt: stop.completedAt ? clock(stop.completedAt) : null,
      expectedUnits: stop.itemCount,
      // The catalogue is reconstructed from order totals: a product is never shown as a real SKU.
      deliveredItems: stop.lines.map((line) => ({ code: line.productId, category: t("Inferred product"), qty: line.orderedUnits })),
    };
  });
}

/**
 * " · 15 to 24 min to unload" when the prediction has a range (roadmap MF-06),
 * " · about 18 min to unload" when it has only the minutes, or nothing.
 */
export function unloadText(
  minutes: number | string | null | undefined,
  low?: number | string | null,
  high?: number | string | null,
  t: Translate = english,
): string {
  const m = minutes == null ? NaN : Math.round(Number(minutes));
  if (!(Number.isFinite(m) && m > 0)) return "";
  const lo = low == null ? NaN : Math.round(Number(low));
  const hi = high == null ? NaN : Math.round(Number(high));
  return Number.isFinite(lo) && Number.isFinite(hi) && hi > lo
    ? t(" · {low} to {high} min to unload", { low: Math.max(1, lo), high: hi })
    : t(" · about {n} min to unload", { n: m });
}

export { dialStyle } from "../../../shared/ui/dial.ts";

/** Where the route screen opens: the first stop still to do, else the last one. */
export function activeIndex(stops: Stop[]): number {
  const next = stops.findIndex((stop) => !isFinished(stop));
  return next >= 0 ? next : Math.max(0, stops.length - 1);
}

export type TripStatus = "not-started" | "in-progress" | "completed";

export function tripStatus(stops: Stop[]): TripStatus {
  if (stops.length > 0 && stops.every(isFinished)) return "completed";
  const begun = stops.some((stop) => stop.startedAt !== null || stop.arrivedAt !== null || isFinished(stop));
  return begun ? "in-progress" : "not-started";
}

/** The run complete card: label and value, the empty counts left out. */
/** The run summary's labels, translated where they are drawn (tests/driver-strings.test.ts reads this). */
export const SUMMARY_LABELS = [
  "Stops delivered",
  "Units delivered",
  "Partly delivered",
  "Not delivered",
  "Replanned by dispatch",
  "Proof of delivery",
] as const;

export function summaryRows(stops: Stop[], uploadsWaiting: number, t: Translate = english): Array<[string, string]> {
  const summary = summarize(stops);
  const proofs = stops.filter((stop) => stop.proofCaptured).length;
  const units = unitsHandedOver(stops);
  const rows: Array<[string, string]> = [
    ["Stops delivered", t("{done} of {total}", { done: summary.delivered + summary.partial, total: summary.total })],
    ["Units delivered", t("{done} of {total}", { done: units.handed, total: units.ordered })],
    ["Partly delivered", String(summary.partial)],
    ["Not delivered", String(summary.failed)],
    ["Replanned by dispatch", String(summary.skipped)],
    [
      "Proof of delivery",
      uploadsWaiting > 0 ? t("{n} saved · {waiting} still sending", { n: proofs, waiting: uploadsWaiting }) : t("{n} saved", { n: proofs }),
    ],
  ];
  return rows
    .filter(([label, value]) => value !== "0" || label === "Stops delivered" || label === "Units delivered")
    .map(([label, value]) => [t(label), value]);
}

export function finishedAt(stops: Stop[]): string | null {
  return stops.map((stop) => stop.completedAt).filter((value): value is string => value !== null).sort().at(-1) ?? null;
}

/** The header pill: whether this phone is in step with the server, in words (EXE-01). */
export function syncLabel(
  online: boolean,
  uploads: number,
  syncedAt: Date | null,
  keptAt: Date | null,
  t: Translate = english,
): string {
  if (!online) return t("Offline");
  if (uploads > 0) return uploads === 1 ? t("Sending 1 proof file") : t("Sending {n} proof files", { n: uploads });
  if (syncedAt) return t("Synced {time}", { time: clock(syncedAt) });
  return keptAt ? t("Saved copy {time}", { time: clock(keptAt) }) : t("Connecting");
}
