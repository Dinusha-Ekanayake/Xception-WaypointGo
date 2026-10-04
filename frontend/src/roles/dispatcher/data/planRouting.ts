import type { PlanView } from "@shared/domain/types";
import { working, type Working } from "./plan.ts";

// The Plan screen is one plan for the depots in view: each depot's plan stays
// its own (one plan per depot and day, each command on one plan and version),
// and this reads them as one for the screen, and tells which depot's plan a
// command belongs to. Pure.

export type DepotWorking = { depot: string; state: Working };

export function depotStates(plans: Array<{ depot: string; published: PlanView | null; draft: PlanView | null }>): DepotWorking[] {
  return plans.map((p) => ({ depot: p.depot, state: working(p.published, p.draft) }));
}

/**
 * The depots' plans as one: their trips and orders together. A draft in any
 * depot makes the whole a draft (what is published stays read only on the
 * server); a revision is named only when one depot is in view.
 */
export function mergeWorking(items: DepotWorking[]): Working {
  const live = items.filter((i): i is { depot: string; state: Exclude<Working, { stage: "none" }> } => i.state.stage !== "none");
  if (live.length === 0) return { stage: "none" };
  if (live.length === 1) return live[0]!.state;
  const plans = live.map((i) => i.state.plan);
  const latest = (pick: (p: PlanView) => string | null) =>
    plans.map(pick).filter((v): v is string => v !== null).sort().at(-1) ?? null;
  const merged: PlanView = {
    ...plans[0]!,
    planId: plans.map((p) => p.planId).join("+"),
    depotCode: live.map((i) => i.depot).join(" + "),
    // Each order and trip belongs to one depot's plan; once each, whatever a read returns.
    trips: unique(plans.flatMap((p) => p.trips), (t) => t.tripId),
    allocations: unique(plans.flatMap((p) => p.allocations), (a) => a.orderId),
    savedAt: latest((p) => p.savedAt) ?? plans[0]!.savedAt,
    publishedAt: latest((p) => p.publishedAt),
    improvement: null,
    plannedWithoutPredictor: plans.some((p) => p.plannedWithoutPredictor),
  };
  const draft = live.some((i) => i.state.stage === "draft");
  return draft ? { stage: "draft", plan: merged, revises: null } : { stage: "published", plan: merged };
}

/** The depot whose plan holds an order or a trip, so a command goes to that plan with that plan's version. */
export function depotHolding(items: DepotWorking[], id: string): DepotWorking | null {
  return (
    items.find(
      (i) => i.state.stage !== "none" && (i.state.plan.allocations.some((a) => a.orderId === id) || i.state.plan.trips.some((t) => t.tripId === id)),
    ) ?? null
  );
}

/** The ids a command payload names, in the order they decide which plan it belongs to. */
export function idsIn(payload: Record<string, unknown>): string[] {
  const ids = [payload.orderId, payload.outOrderId, payload.inOrderId, payload.tripId, ...(Array.isArray(payload.orderIds) ? payload.orderIds : [])];
  return ids.filter((id): id is string => typeof id === "string");
}

function unique<T>(items: T[], id: (item: T) => string): T[] {
  const seen = new Set<string>();
  return items.filter((item) => (seen.has(id(item)) ? false : (seen.add(id(item)), true)));
}
