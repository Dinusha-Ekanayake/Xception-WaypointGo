import type { AllocationView, ImprovementView, OrderView, PlanView, TripView, VehicleView } from "@shared/domain/types";
export { hhmm } from "../../../shared/wording/index.ts";

// What a plan adds up to on the dispatcher's screen. Pure: the plan, the day's
// orders and the fleet go in, rows and counts come out.
//
// Load percentages here are for the eye only. Whether a load fits is decided by
// the server's constraint registry on order-level totals; nothing on this
// screen re-decides it (AGENTS.md, External Product Catalogue).

/** A trip this full is drawn as tight, so the dispatcher sees where there is no room left. */
export const TIGHT_PERCENT = 90;

export type Decision = {
  allocation: AllocationView;
  /** Missing when the order list has not caught up with the plan. */
  order: OrderView | undefined;
};

/**
 * The orders the plan did not place, the ones waiting longest first: an order
 * deferred before is the one a dispatcher most needs to see (R-PLN-20).
 */
export function openDecisions(plan: PlanView, orders: Map<string, OrderView>): Decision[] {
  return plan.allocations
    .filter((allocation) => allocation.decision !== "SERVED")
    .map((allocation) => ({ allocation, order: orders.get(allocation.orderId) }))
    .sort((a, b) => {
      // Unservable orders cannot be placed by anyone; they go last.
      const unservable = Number(a.allocation.decision === "UNSERVABLE") - Number(b.allocation.decision === "UNSERVABLE");
      if (unservable !== 0) return unservable;
      const waited = (b.order?.deferralCount ?? 0) - (a.order?.deferralCount ?? 0);
      return waited !== 0 ? waited : (a.order?.orderRef ?? a.allocation.orderId).localeCompare(b.order?.orderRef ?? b.allocation.orderId);
    });
}

/** Used against capacity as a whole percentage, or null when the capacity is unknown. */
export function percent(used: string | number, capacity: string | number | undefined): number | null {
  const cap = Number(capacity);
  if (!Number.isFinite(cap) || cap <= 0) return null;
  return Math.round((Number(used) / cap) * 100);
}

export type TripLoad = {
  trip: TripView;
  weightPercent: number | null;
  volumePercent: number | null;
  tight: boolean;
};

export type VehicleRow = {
  vehicleId: string;
  /** Missing when the vehicle left the available fleet after the plan was made. */
  vehicle: VehicleView | undefined;
  trips: [TripLoad | null, TripLoad | null];
};

function load(trip: TripView, vehicle: VehicleView | undefined): TripLoad {
  const weightPercent = percent(trip.weightKg, vehicle?.weightCapKg);
  const volumePercent = percent(trip.volumeM3, vehicle?.volumeCapM3);
  return { trip, weightPercent, volumePercent, tight: Math.max(weightPercent ?? 0, volumePercent ?? 0) >= TIGHT_PERCENT };
}

/** One row per vehicle the plan uses, by vehicle id, with trip 1 and trip 2 side by side. */
export function board(plan: PlanView, fleet: VehicleView[]): VehicleRow[] {
  const vehicles = new Map(fleet.map((vehicle) => [vehicle.vehicleId, vehicle]));
  const rows = new Map<string, VehicleRow>();
  for (const trip of plan.trips) {
    const vehicle = vehicles.get(trip.vehicleId);
    const row = rows.get(trip.vehicleId) ?? { vehicleId: trip.vehicleId, vehicle, trips: [null, null] };
    row.trips[trip.tripNumber - 1] = load(trip, vehicle);
    rows.set(trip.vehicleId, row);
  }
  return [...rows.values()].sort((a, b) => a.vehicleId.localeCompare(b.vehicleId));
}

export type PlanSummary = {
  orders: number;
  served: number;
  deferred: number;
  unservable: number;
  trips: number;
  tightTrips: number;
  vehiclesUsed: number;
  /** Available that day and not on the plan. */
  vehiclesIdle: number;
};

export function summarise(plan: PlanView, fleet: VehicleView[]): PlanSummary {
  const count = (decision: AllocationView["decision"]) => plan.allocations.filter((a) => a.decision === decision).length;
  const rows = board(plan, fleet);
  const used = new Set(plan.trips.map((trip) => trip.vehicleId));
  return {
    orders: plan.allocations.length,
    served: count("SERVED"),
    deferred: count("DEFERRED"),
    unservable: count("UNSERVABLE"),
    trips: plan.trips.length,
    tightTrips: rows.flatMap((row) => row.trips).filter((trip) => trip?.tight).length,
    vehiclesUsed: used.size,
    vehiclesIdle: fleet.filter((vehicle) => !used.has(vehicle.vehicleId)).length,
  };
}

/** "HH:mm" a number of minutes after a time of day, for when a trip is back. */
export function after(time: string, minutes: string | number): string {
  const [h, m] = time.split(":").map(Number);
  const total = Math.round((h ?? 0) * 60 + (m ?? 0) + Number(minutes));
  const wrapped = ((total % 1440) + 1440) % 1440;
  return `${String(Math.floor(wrapped / 60)).padStart(2, "0")}:${String(wrapped % 60).padStart(2, "0")}`;
}

/** The plan the screen works on: the open draft when there is one, else what is published. */
export type Working =
  | { stage: "none" }
  | { stage: "draft"; plan: PlanView; revises: PlanView | null }
  | { stage: "published"; plan: PlanView };

export function working(published: PlanView | null, draft: PlanView | null): Working {
  // A draft that names the plan it supersedes is a revision of it; any other draft is the day's first plan.
  if (draft) return { stage: "draft", plan: draft, revises: draft.supersedes !== null ? published : null };
  if (published) return { stage: "published", plan: published };
  return { stage: "none" };
}

/**
 * What the engine's second pass did, in the dispatcher's words (issue #92), or
 * null when there is nothing to say. A search cut short is always said (rule 9).
 */
export function improvementNote(improvement: ImprovementView | null): { title: string; detail: string } | null {
  if (!improvement) return null;
  const early =
    (improvement.stoppedBy !== "NONE" ? " The search stopped before it finished, so this is the best it found." : "") +
    (improvement.chilledSearched < improvement.chilledCandidates
      ? ` It ranked the top ${improvement.chilledSearched} of ${improvement.chilledCandidates} chilled orders; the rest were placed one at a time.`
      : "");
  if (!improvement.improved) {
    return early ? { title: "The first plan stands", detail: `The second pass found nothing better by priority.${early}` } : null;
  }
  const more = improvement.served - improvement.firstPassServed;
  const volume = Number(improvement.chilledVolumeGainedM3);
  return {
    title: `Reefers planned again: ${more} more ${more === 1 ? "order" : "orders"} served`,
    detail:
      `Deferred went from ${improvement.firstPassDeferred} to ${improvement.deferred}` +
      (volume > 0 ? `, with ${volume.toFixed(1)} m³ more chilled delivered` : "") +
      `. An order is never dropped for a lower priority one.${early}`,
  };
}
