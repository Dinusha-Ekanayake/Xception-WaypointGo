import type { OutletView } from "../../../shared/domain/referencedata.ts";
import { clock, codeLabel, DOCK_TYPE, hhmm, PARKING, units } from "../../../shared/wording/index.ts";
import type { RouteStop } from "../screens/routeData.ts";
import { isFinished, type Stop } from "./run.ts";

// The live run in the shape the Figma screens draw (issue #114): the screens
// were built on sample stops (routeData.ts), and this fills the same fields
// from the run sheet the loader's release produced. Pure, so it is unit tested.

const pad = (n: number) => String(n).padStart(2, "0");

function windowStatus(stop: Stop): string {
  if (isFinished(stop)) return stop.outcome === "DELIVERED" ? "Delivered" : stop.outcome === "PARTIAL" ? "Partly delivered" : "Not delivered";
  const late = stop.lateMinutes ?? 0;
  return late > 0 ? `Late ${late} min` : "On Time";
}

export function toRouteStop(stop: Stop, index: number, total: number, outlet: OutletView | undefined): RouteStop {
  const dock = outlet ? codeLabel(DOCK_TYPE, outlet.dockType) : "";
  const parking = outlet ? codeLabel(PARKING, outlet.parkingConstraint) : "";
  return {
    id: stop.deliveryId,
    deliveryId: stop.deliveryId,
    stopNumber: pad(stop.sequence),
    stopIndex: index,
    totalStops: total,
    name: outlet?.districtName ?? stop.outletId,
    address: [stop.outletId, outlet?.brandCode].filter(Boolean).join(" • "),
    dockTag: dock,
    eta: stop.expectedArrival ? clock(stop.expectedArrival) : hhmm(stop.plannedArrival),
    etaDistanceTime: stop.expectedArrival ? "Expected" : "Planned",
    window: `${hhmm(stop.windowOpen)}-${hhmm(stop.windowClose)}`,
    windowStatus: windowStatus(stop),
    cargoText: units(stop.itemCount),
    instructions: [dock, parking, stop.mallOutlet ? "Mall: goods only inside the window" : ""].filter(Boolean).join(" • "),
    mapButtonType: "wide",
    expectedUnits: stop.itemCount,
    deliveredItems: stop.lines.map((line) => ({
      code: line.productId,
      category: "Inferred product",
      qty: line.deliveredUnits ?? line.orderedUnits,
    })),
  };
}

/** Every stop of the run, in sequence, as the screens draw them. */
export function toRouteStops(stops: Stop[], outlets: Record<string, OutletView>): RouteStop[] {
  const ordered = [...stops].sort((a, b) => a.sequence - b.sequence);
  return ordered.map((stop, index) => toRouteStop(stop, index, ordered.length, outlets[stop.outletId]));
}

/** Where the run is: the first stop not finished, else the last. */
export function currentIndex(stops: Stop[]): number {
  const ordered = [...stops].sort((a, b) => a.sequence - b.sequence);
  const next = ordered.findIndex((stop) => !isFinished(stop));
  return next === -1 ? Math.max(0, ordered.length - 1) : next;
}

/** The run as the Home screen's trip card names it. */
export function tripStatus(stops: Stop[]): "not-started" | "in-progress" | "completed" {
  if (stops.length > 0 && stops.every(isFinished)) return "completed";
  return stops.some((stop) => stop.startedAt !== null || stop.arrivedAt !== null || isFinished(stop)) ? "in-progress" : "not-started";
}
