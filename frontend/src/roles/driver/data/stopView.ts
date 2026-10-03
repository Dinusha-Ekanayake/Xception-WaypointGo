import type { OutletView } from "@shared/domain/types";
import { clock, codeLabel, DOCK_TYPE, hhmm, units } from "../../../shared/wording/index.ts";
import { isFinished, lateMinutes, summarize, type Stop } from "./run.ts";

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
  /** One or more writes for this stop are still only on this phone. */
  onPhone: boolean;
  expectedUnits: number;
  deliveredItems: Array<{ code: string; category: string; qty: number }>;
};

function access(outlet: OutletView | undefined): string {
  if (!outlet) return "";
  const dock = codeLabel(DOCK_TYPE, outlet.dockType);
  if (outlet.parkingConstraint === "mall_dock") return "Mall dock. Deliveries are taken only inside the window.";
  if (outlet.parkingConstraint === "van_only") return `${dock} · vans only.`;
  return `${dock}.`;
}

function status(serviceDate: string, stop: Stop, now: Date): string {
  if (stop.outcome === "DELIVERED") return "Delivered";
  if (stop.outcome === "PARTIAL") return "Partly delivered";
  if (stop.outcome === "FAILED") return "Not delivered";
  if (stop.outcome === "SKIPPED") return "Replanned";
  const late = (stop.lateMinutes ?? 0) > 0 ? stop.lateMinutes! : lateMinutes(serviceDate, stop.windowClose, now);
  return late > 0 ? `${late} min late` : "On time";
}

export function toRouteStops(
  stops: Stop[],
  outlets: Record<string, OutletView>,
  serviceDate: string,
  now: Date,
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
      dockTag: outlet ? codeLabel(DOCK_TYPE, outlet.dockType) : "",
      eta: clock(stop.expectedArrival ?? stop.plannedArrival),
      etaDistanceTime: stop.expectedArrival ? `Planned ${hhmm(stop.plannedArrival)}` : "As planned",
      window: hhmm(stop.windowClose),
      windowStatus: status(serviceDate, stop, now),
      cargoText: units(stop.itemCount),
      instructions: access(outlet),
      mapButtonType: "split",
      finished: isFinished(stop),
      onPhone: stop.waiting,
      expectedUnits: stop.itemCount,
      // The catalogue is reconstructed from order totals: a product is never shown as a real SKU.
      deliveredItems: stop.lines.map((line) => ({ code: line.productId, category: "Inferred product", qty: line.orderedUnits })),
    };
  });
}

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
export function summaryRows(stops: Stop[], uploadsWaiting: number): Array<[string, string]> {
  const summary = summarize(stops);
  const proofs = stops.filter((stop) => stop.proofCaptured).length;
  const rows: Array<[string, string]> = [
    ["Stops delivered", `${summary.delivered + summary.partial} of ${summary.total}`],
    ["Partly delivered", String(summary.partial)],
    ["Not delivered", String(summary.failed)],
    ["Replanned by dispatch", String(summary.skipped)],
    ["Proof of delivery", `${proofs} saved${uploadsWaiting > 0 ? ` · ${uploadsWaiting} still sending` : ""}`],
  ];
  return rows.filter(([label, value]) => value !== "0" || label === "Stops delivered");
}

/** When the last stop was recorded, or null before any was. */
export function finishedAt(stops: Stop[]): string | null {
  return stops.map((stop) => stop.completedAt).filter((value): value is string => value !== null).sort().at(-1) ?? null;
}

/** The header pill: whether this phone is in step with the server, in words (EXE-01). */
export function syncLabel(online: boolean, uploads: number, syncedAt: Date | null, keptAt: Date | null): string {
  if (!online) return "Offline";
  if (uploads > 0) return `Sending ${uploads} proof ${uploads === 1 ? "file" : "files"}`;
  if (syncedAt) return `Synced ${clock(syncedAt)}`;
  return keptAt ? `Saved copy ${clock(keptAt)}` : "Connecting";
}
