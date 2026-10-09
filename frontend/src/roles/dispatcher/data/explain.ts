import type { AllocationView, FitView, PlacementView } from "@shared/domain/types";
import { checkLabel, ruleLabel } from "../../../shared/wording/index.ts";

// Why an order was not placed, as one explanation a dispatcher can read out to a
// store (issue #267). Nothing is worked out here: the rule, its reason, every
// check and every place the order could take come from the planning module, so
// there is still one definition of every rule. This only arranges those facts,
// in the same order every time, with no model and nothing stored.

export type Explanation = {
  headline: string;
  /** The deciding rule in words, and the server's reason for it. */
  rule: string;
  reason: string;
  /** The checks that failed, each with what the server said. */
  stopped: Array<{ label: string; detail: string }>;
  /** The checks that passed, in words. */
  met: string[];
  /** False when the order can no longer be placed on this plan (kept deferred, or the plan is final). */
  canPlace: boolean;
  /** Where it could still go; null while the places are still being checked. */
  fits: string[] | null;
  /** A few places it was tried and refused, with the rule that refused each. */
  refused: Array<{ where: string; why: string }>;
  /** Roadmap AI-2: the place it came closest to, and what stopped it there; null when it fits or before the solver answers. */
  closest: string | null;
  /** Roadmap AI-2: the smallest change the solver found, a swap it checked, or that none makes room. */
  change: string | null;
  next: string;
};

const REFUSED_SHOWN = 3;

/** The planner writes its reasons as clauses; read alone, one starts with a capital. */
const sentence = (text: string) => (text ? text.charAt(0).toUpperCase() + text.slice(1) : text);

const where = (place: PlacementView) => `${place.vehicleId}, trip ${place.tripNumber}`;

export function explainDeferral(input: {
  orderRef: string | null;
  outletId: string | null;
  day: string;
  allocation: Pick<AllocationView, "bindingRule" | "reason" | "checks">;
  /** Null while they load; undefined where the places are not looked up (the plan view's list). */
  places?: PlacementView[] | null;
  /** False for an order kept deferred, or on a plan that can no longer be edited. */
  canPlace?: boolean;
  /** Roadmap AI-2: the solver's answer, when asked. */
  fit?: FitView | null;
}): Explanation {
  const { allocation } = input;
  const unknown = input.places === undefined;
  const places = input.places ?? null;
  const order = input.orderRef ?? "This order";
  const canPlace = !unknown && (input.canPlace ?? true);
  const fits = places === null ? null : places.filter((place) => place.feasible);
  const refused = (places ?? []).filter((place) => !place.feasible).slice(0, REFUSED_SHOWN);

  return {
    headline: `${order}${input.outletId ? ` for ${input.outletId}` : ""} was not placed on the plan for ${input.day}.`,
    rule: ruleLabel(allocation.bindingRule),
    reason: sentence(allocation.reason),
    canPlace,
    stopped: allocation.checks.filter((check) => !check.passed).map((check) => ({ label: checkLabel(check.ruleId, false), detail: check.reason })),
    met: allocation.checks.filter((check) => check.passed).map((check) => checkLabel(check.ruleId, true)),
    fits: fits === null ? null : fits.map((place) => `${where(place)}: ${place.joins ? "joins the trip already planned" : "opens a new trip"}`),
    refused: refused.map((place) => ({ where: where(place), why: `${ruleLabel(place.bindingRule)}: ${place.reason}` })),
    ...fitWords(input.fit ?? null),
    next: unknown
      ? "Open the order in Decide to see where it could go, swap it with an order on a trip, or keep it deferred."
      : !canPlace
      ? "It stays deferred on this plan, with its reason on record, and is offered first on the next plan."
      : fits === null
        ? "Every vehicle is still being checked."
        : fits.length > 0
          ? `It can still go on ${fits.length === 1 ? "one trip" : `${fits.length} trips`}. Choose one and give a reason, and it is placed by hand.`
          : "It fits on no trip of any available vehicle. Swap it with an order on a trip, or keep it deferred: it is offered first on the next plan.",
  };
}

/**
 * Roadmap AI-2, from the solver's own answer: where the order came closest and
 * what stopped it there, and the smallest change. Nothing is worked out here.
 */
export function fitWords(fit: FitView | null): { closest: string | null; change: string | null } {
  if (!fit || fit.fits || !fit.closest) return { closest: null, change: null };
  const c = fit.closest;
  const place = `${c.vehicleId}, trip ${c.tripNumber}, ${c.joins ? "joining the trip already planned" : "as a new trip"}`;
  const why = c.failed.map((check) => `${checkLabel(check.ruleId, false)}: ${check.reason}`).join("; ");
  return {
    closest: `${place}. ${sentence(why)}`,
    change: fit.swap
      ? `Swapping it with ${fit.swap.orderRef} for ${fit.swap.outletId}, which ranks lower, makes room on ${fit.swap.vehicleId}, trip ${fit.swap.tripNumber}: every rule passes with that swap.`
      : "No single swap makes room there: it needs more room on a vehicle, another vehicle, or a later day.",
  };
}

// The whole plan in a few sentences (issue #267, extended): what it carries,
// why orders were left off, grouped by the rule that decided each, and what
// was decided by hand. Counted from the plan itself; the planner's own notes
// (its second pass, the cost stage, estimated times) are passed in as written.

export type PlanExplanation = {
  headline: string;
  carries: string;
  /** Why orders were not placed: the rule in words and how many it stopped, most first. */
  leftOff: Array<{ label: string; count: number; example: string }>;
  cannotBeServed: number;
  byHand: string | null;
  notes: string[];
  next: string;
};

const many = (n: number, one: string, more: string) => `${n} ${n === 1 ? one : more}`;
const BY_HAND = new Set(["OVERRIDE", "SWAP", "KEPT", "MANUAL_DEFER"]);

export function explainPlan(input: {
  plan: { depotCode: string; status: string; trips: Array<{ vehicleId: string }>; allocations: Array<Pick<AllocationView, "decision" | "bindingRule" | "reason" | "source">> };
  day: string;
  notes: string[];
}): PlanExplanation {
  const { plan } = input;
  const served = plan.allocations.filter((a) => a.decision === "SERVED").length;
  const deferred = plan.allocations.filter((a) => a.decision === "DEFERRED");
  const unservable = plan.allocations.filter((a) => a.decision === "UNSERVABLE").length;
  const vehicles = new Set(plan.trips.map((trip) => trip.vehicleId)).size;

  const groups = new Map<string, { label: string; count: number; example: string }>();
  for (const a of deferred) {
    const label = ruleLabel(a.bindingRule) || "Another reason";
    const group = groups.get(label) ?? { label, count: 0, example: sentence(a.reason) };
    group.count += 1;
    groups.set(label, group);
  }
  const byHand = plan.allocations.filter((a) => BY_HAND.has(a.source)).length;
  const published = plan.status === "PUBLISHED";

  return {
    headline: `The ${published ? "published plan" : "draft"} for ${plan.depotCode} on ${input.day}.`,
    carries: `${many(served, "order is", "orders are")} placed on ${many(plan.trips.length, "trip", "trips")} across ${many(vehicles, "vehicle", "vehicles")}.`,
    leftOff: [...groups.values()].sort((a, b) => b.count - a.count || a.label.localeCompare(b.label)),
    cannotBeServed: unservable,
    byHand: byHand > 0 ? `${many(byHand, "order was", "orders were")} decided by hand, each with its reason on record.` : null,
    notes: input.notes,
    next: published
      ? "Loaders and drivers work from this plan. A change needs a revision, which tells the people it affects."
      : deferred.length > 0
        ? `${many(deferred.length, "order still needs", "orders still need")} a decision in Decide before the plan can be published: place it by hand, swap it, or keep it deferred.`
        : "Every order has its decision. The plan can be published.",
  };
}
