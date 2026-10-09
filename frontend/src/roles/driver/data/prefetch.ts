import type { OutletView } from "../../../shared/domain/referencedata.ts";
import type { PlanView } from "../../../shared/domain/planning.ts";
import { num, type LatLon } from "../../../shared/ui/map/geo.ts";

// Roadmap F-06: the evening before, the phone keeps the map of tomorrow's
// planned stops, so it draws in a valley with no signal. A trip is released to
// the phone only after loading, often at night, but the plan that names its
// stops is published from 16:00, so that is when the map can be fetched.

/** From this depot hour the next day's plan is published and worth reading ahead. */
export const EVENING_FROM_HOUR = 16;

export const isEvening = (hour: number): boolean => hour >= EVENING_FROM_HOUR;

/** The outlets on the published plan's trips of this driver's vehicles, in stop order. */
export function plannedOutletIds(vehicleIds: string[], plan: Pick<PlanView, "trips"> | null): string[] {
  const mine = new Set(vehicleIds);
  const trips = (plan?.trips ?? []).filter((t) => mine.has(t.vehicleId));
  const ordered = trips.flatMap((t) => [...t.stops].sort((a, b) => a.sequence - b.sequence).map((s) => s.outletId));
  return [...new Set(ordered)];
}

/** Where each outlet is, when its point is known. */
export function outletPoints(outlets: Array<Pick<OutletView, "location">>): LatLon[] {
  const out: LatLon[] = [];
  for (const outlet of outlets) {
    const lat = num(outlet.location?.latitude);
    const lon = num(outlet.location?.longitude);
    if (lat !== null && lon !== null) out.push({ lat, lon });
  }
  return out;
}
