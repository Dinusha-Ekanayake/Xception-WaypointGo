import type { AllocationView, ConstraintResultView, OrderView, PlanPredictionsView, PlanView, TripView, VehicleView } from "@shared/domain/types";
import type { VehicleRow } from "./plan.ts";
import { TIGHT_PERCENT, percent } from "./plan.ts";

// What the Plan screen reads out of a plan beyond the board itself: which orders
// still need a decision, how the day compares to the design's low-load and
// filter ideas, and the small sums a trip's timeline shows. Pure: the plan, the
// orders and the fleet go in, rows and counts come out.
//
// Load percentages are for the eye. Whether a load fits is decided by the
// server's constraint registry on order-level totals (AGENTS.md, External
// Product Catalogue).

/** A trip that is less full than this on both volume and weight is low-load (Figma 2, "under 70% full"). */
export const LOW_LOAD_PERCENT = 70;

export type DecisionState =
  /** Deferred by the engine; nobody has decided it yet. */
  | "open"
  /** A dispatcher decided it stays deferred. */
  | "kept"
  /** A dispatcher placed it on a trip. */
  | "placed";

export type DecisionRow = {
  allocation: AllocationView;
  /** Missing when the order list has not caught up with the plan. */
  order: OrderView | undefined;
  state: DecisionState;
  /** "VEH007 Trip 2" for a placed order. */
  placedOn: string | null;
};

const HAND_PLACED = new Set(["OVERRIDE", "SWAP"]);
const HAND_DEFERRED = new Set(["KEPT", "MANUAL_DEFER"]);

/** The vehicle and trip an allocation rides, from the plan's trips. */
export function placeOf(plan: PlanView, allocation: AllocationView): string | null {
  const trip = plan.trips.find((t) => t.tripId === allocation.tripId);
  return trip ? `${trip.vehicleId} Trip ${trip.tripNumber}` : null;
}

/**
 * The orders a dispatcher has to decide, and the ones already decided, in the
 * order the screen shows them: open first (the ones waiting longest on top, as
 * R-PLN-20 asks), then kept, then placed. An order that cannot be served by any
 * vehicle is not here: nobody can place it, so the screen draws it apart.
 */
export function decisionRows(plan: PlanView, orders: Map<string, OrderView>): DecisionRow[] {
  const rows: DecisionRow[] = [];
  for (const allocation of plan.allocations) {
    const order = orders.get(allocation.orderId);
    if (allocation.decision === "DEFERRED") {
      rows.push({ allocation, order, state: HAND_DEFERRED.has(allocation.source) ? "kept" : "open", placedOn: null });
    } else if (allocation.decision === "SERVED" && HAND_PLACED.has(allocation.source)) {
      rows.push({ allocation, order, state: "placed", placedOn: placeOf(plan, allocation) });
    }
  }
  const rank: Record<DecisionState, number> = { open: 0, kept: 1, placed: 2 };
  return rows.sort((a, b) => {
    if (a.state !== b.state) return rank[a.state] - rank[b.state];
    const waited = (b.order?.deferralCount ?? 0) - (a.order?.deferralCount ?? 0);
    if (waited !== 0) return waited;
    return (a.order?.orderRef ?? a.allocation.orderId).localeCompare(b.order?.orderRef ?? b.allocation.orderId);
  });
}

/** "3 of 8 decided": what a dispatcher has acted on, against every order that needed a decision. */
export function decidedCount(rows: DecisionRow[]): { decided: number; total: number; open: number } {
  const open = rows.filter((row) => row.state === "open").length;
  return { decided: rows.length - open, total: rows.length, open };
}

/** Orders too big for any vehicle: the plan names them, a person has to contact the store. */
export function unservable(plan: PlanView, orders: Map<string, OrderView>): Array<{ allocation: AllocationView; order: OrderView | undefined }> {
  return plan.allocations
    .filter((allocation) => allocation.decision === "UNSERVABLE")
    .map((allocation) => ({ allocation, order: orders.get(allocation.orderId) }));
}

/** The largest volume any vehicle of the fleet can carry, for "largest vehicle 38 m³". */
export function largestVehicleM3(fleet: VehicleView[]): number | null {
  const caps = fleet.map((vehicle) => Number(vehicle.volumeCapM3)).filter((n) => Number.isFinite(n) && n > 0);
  return caps.length === 0 ? null : Math.max(...caps);
}

/** Whole days from one date to another, both yyyy-mm-dd; never negative. */
export function daysBetween(from: string, to: string): number {
  const ms = new Date(`${to}T00:00:00Z`).getTime() - new Date(`${from}T00:00:00Z`).getTime();
  return Math.max(0, Math.round(ms / 86_400_000));
}

/** "Last served 2 days ago", "yesterday", or "never served" for an outlet with no earlier delivery. */
export function lastServedText(lastServedOn: string | null, serviceDate: string): string {
  if (!lastServedOn) return "Never served";
  const days = daysBetween(lastServedOn, serviceDate);
  if (days <= 1) return "Last served yesterday";
  return `Last served ${days} days ago`;
}

/** The plan can be published only when no order is waiting for a decision. */
export function publishBlocker(rows: DecisionRow[]): { open: number; first: DecisionRow | null } {
  const open = rows.filter((row) => row.state === "open");
  return { open: open.length, first: open[0] ?? null };
}

// ---- the board ---------------------------------------------------------------

export type LowLoad = { trips: number; spareM3: number };

/** Trips under {@link LOW_LOAD_PERCENT} full on both volume and weight, and the room they leave. */
export function lowLoad(rows: VehicleRow[]): LowLoad {
  let trips = 0;
  let spare = 0;
  for (const row of rows) {
    for (const load of row.trips) {
      if (!load || load.volumePercent === null || load.weightPercent === null) continue;
      if (load.volumePercent < LOW_LOAD_PERCENT && load.weightPercent < LOW_LOAD_PERCENT) {
        trips += 1;
        spare += Math.max(0, Number(row.vehicle?.volumeCapM3 ?? 0) - Number(load.trip.volumeM3));
      }
    }
  }
  return { trips, spareM3: Math.round(spare * 10) / 10 };
}

export type BoardFilter = {
  /** Brand codes to show; empty shows every brand. */
  brands: string[];
  /** Temperature classes to show; empty shows both. */
  temperatures: Array<"chilled" | "ambient">;
  /** Matches a vehicle, an outlet on a trip or a district. */
  text: string;
};

export const NO_FILTER: BoardFilter = { brands: [], temperatures: [], text: "" };

export function filterActive(filter: BoardFilter): boolean {
  return filter.brands.length > 0 || filter.temperatures.length > 0 || filter.text.trim() !== "";
}

/** Whether one trip matches the brand and temperature choices and the search text. */
export function tripMatches(trip: TripView, filter: BoardFilter): boolean {
  if (filter.brands.length > 0 && !filter.brands.includes(trip.brandCode)) return false;
  if (filter.temperatures.length > 0 && !filter.temperatures.includes(trip.temperature)) return false;
  const text = filter.text.trim().toLowerCase();
  if (!text) return true;
  return [trip.vehicleId, trip.districtName, ...trip.stops.map((stop) => stop.outletId)].some((value) => value.toLowerCase().includes(text));
}

/** The vehicles with at least one matching trip; a row with none is left out, a trip that does not match stays dim. */
export function filterBoard(rows: VehicleRow[], filter: BoardFilter): VehicleRow[] {
  if (!filterActive(filter)) return rows;
  return rows.filter((row) =>
    row.trips.some((load) => load !== null && tripMatches(load.trip, filter)) ||
    (filter.text.trim() !== "" && row.vehicleId.toLowerCase().includes(filter.text.trim().toLowerCase())),
  );
}

/** The brands in the plan, for the filter's choices. */
export function brandsOf(plan: PlanView): string[] {
  return [...new Set(plan.trips.map((trip) => trip.brandCode))].sort();
}

/** What an empty cell on the board says: the vehicle's kind, so a dispatcher sees what could go there. */
export function freeLabel(vehicle: VehicleView | undefined): string {
  if (!vehicle) return "Free";
  return vehicle.refrigerated ? "Free · refrigerated" : "Free · ambient only";
}

/** An order's share of the vehicle's volume, for a stop's percent in the timeline; display only. */
export function stopShare(order: Pick<OrderView, "volumeM3"> | undefined, vehicle: VehicleView | undefined): number | null {
  if (!order) return null;
  return percent(order.volumeM3, vehicle?.volumeCapM3);
}

/** "Volume 94%" when a trip is over the tight line on either measure; null when it is not. */
export function tightLine(volumePercent: number | null, weightPercent: number | null): string | null {
  const volume = volumePercent ?? 0;
  const weight = weightPercent ?? 0;
  if (Math.max(volume, weight) < TIGHT_PERCENT) return null;
  return volume >= weight ? `Volume ${volumePercent}%` : `Weight ${weightPercent}%`;
}

/** Whether a trip was put there by a dispatcher: any of its orders placed by hand. */
export function addedByHand(plan: PlanView, trip: TripView): boolean {
  const onTrip = new Set(trip.stops.map((stop) => stop.orderId));
  return plan.allocations.some((a) => onTrip.has(a.orderId) && (a.source === "OVERRIDE" || a.source === "SWAP"));
}

/** The first rule a check list fails, in words; empty when every check passed. */
export function firstFailureLabel(checks: ConstraintResultView[], label: (ruleId: string) => string): string {
  const failed = checks.find((check) => !check.passed);
  return failed ? label(failed.ruleId) : "";
}

// ---- late risk ---------------------------------------------------------------

/** A trip with a stop at least this likely to be late is high risk (Figma 2, "over 35% chance"). */
export const LATE_RISK_PERCENT = 35;

export type LateRisk = { high: number; low: number };

/**
 * Trips by how likely a stop is to run late, from the predictions of a published
 * plan. A draft is never scored, so there is nothing to count for it: the screen
 * says so rather than show zero.
 */
export function lateRisk(plan: PlanView, predictions: PlanPredictionsView): LateRisk {
  const worst = new Map<string, number>();
  for (const stop of predictions.stops) {
    worst.set(stop.tripId, Math.max(worst.get(stop.tripId) ?? 0, Number(stop.lateProbability) * 100));
  }
  const high = plan.trips.filter((trip) => (worst.get(trip.tripId) ?? 0) >= LATE_RISK_PERCENT).length;
  return { high, low: plan.trips.length - high };
}

// ---- late risk per stop and trip (issue #119) -----------------------------------

/** From this chance a stop or trip is worth a look: amber. At LATE_RISK_PERCENT it is red. */
export const LATE_WATCH_PERCENT = 20;

/** One stop's chance of arriving after its window, and whether a model or the estimate said so. */
export type StopRisk = { percent: number; estimate: boolean };

const asPercent = (p: string | number): number => Math.round(Number(p) * 100);

/** Each order's late risk on the published plan, keyed by order id. */
export function stopRisks(predictions: PlanPredictionsView): Map<string, StopRisk> {
  return new Map(predictions.stops.map((stop) => [stop.orderId, { percent: asPercent(stop.lateProbability), estimate: stop.degraded }]));
}

/** Each trip's worst stop, keyed by trip id: a trip is as late as its latest stop. */
export function tripRisks(predictions: PlanPredictionsView): Map<string, StopRisk> {
  const worst = new Map<string, StopRisk>();
  for (const stop of predictions.stops) {
    const seen = worst.get(stop.tripId);
    worst.set(stop.tripId, {
      percent: Math.max(seen?.percent ?? 0, asPercent(stop.lateProbability)),
      // Any stop from the estimate makes the trip's figure an estimate.
      estimate: (seen?.estimate ?? false) || stop.degraded,
    });
  }
  return worst;
}

export type RiskTone = "low" | "watch" | "high";

export function riskTone(percent: number): RiskTone {
  return percent >= LATE_RISK_PERCENT ? "high" : percent >= LATE_WATCH_PERCENT ? "watch" : "low";
}

/** "Late 41%" ("Late <1%" below one), with "· estimate" when the time predictor was not running (degrade visibly). */
export function riskLabel(risk: StopRisk, lead = "Late"): string {
  return `${lead} ${risk.percent < 1 ? "<1" : risk.percent}%${risk.estimate ? " · estimate" : ""}`;
}

/** True when any stop was scored by the deterministic estimate rather than the model. */
export const scoredByEstimate = (predictions: PlanPredictionsView): boolean =>
  predictions.scoring.status === "DEGRADED" || predictions.stops.some((stop) => stop.degraded);
