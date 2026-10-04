import assert from "node:assert/strict";
import { test } from "node:test";
import type { AllocationView, PlanView, TripView } from "../src/shared/domain/planning.ts";
import { depotHolding, depotStates, idsIn, mergeWorking } from "../src/roles/dispatcher/data/planRouting.ts";

// One plan view for several depots: each depot keeps its own plan, the screen
// reads them as one, and a command goes to the plan that holds what it names.

function trip(tripId: string): TripView {
  return {
    tripId, vehicleId: "VEH1", tripNumber: 1, brandCode: "Fresh", districtName: "Kandy", temperature: "chilled",
    weightKg: "100", volumeM3: "1", plannedMinutes: "60", plannedDeparture: "03:30:00", stops: [],
  };
}

function allocation(orderId: string, tripId: string | null): AllocationView {
  return {
    orderId, decision: tripId ? "SERVED" : "DEFERRED", tripId, bindingRule: null, reason: "", checks: [],
    source: "ENGINE", locked: false, decidedBy: null, decidedAt: null, lastServedOn: null,
  };
}

function plan(planId: string, depot: string, status: PlanView["status"], orders: Array<[string, string | null]>, savedAt: string): PlanView {
  return {
    planId, depotCode: depot, serviceDate: "2026-10-05", planVersion: 1, status, referenceVersionId: "r", ruleSetVersionId: "s",
    priorityPolicyVersionId: "p", supersedes: null, publishedAt: status === "PUBLISHED" ? savedAt : null, savedAt, plannedWithoutPredictor: false,
    trips: [...new Set(orders.map(([, t]) => t).filter((t): t is string => t !== null))].map(trip),
    allocations: orders.map(([o, t]) => allocation(o, t)), rowVersion: 1, engine: "e", improvement: null,
  };
}

const kandy = plan("k1", "Kandy", "DRAFT", [["o1", "t1"], ["o2", null]], "2026-10-04T10:00:00Z");
const peliyagoda = plan("p1", "Peliyagoda", "PUBLISHED", [["o3", "t3"]], "2026-10-04T11:00:00Z");

test("the depots' plans read as one: every trip and order once, a draft anywhere makes it a draft", () => {
  const items = depotStates([
    { depot: "Kandy", published: null, draft: kandy },
    { depot: "Peliyagoda", published: peliyagoda, draft: null },
  ]);
  const merged = mergeWorking(items);
  if (merged.stage !== "draft") return assert.fail(`expected a draft, got ${merged.stage}`);
  assert.deepEqual(merged.plan.trips.map((t) => t.tripId), ["t1", "t3"]);
  assert.deepEqual(merged.plan.allocations.map((a) => a.orderId), ["o1", "o2", "o3"]);
  assert.equal(merged.plan.depotCode, "Kandy + Peliyagoda");
  assert.equal(merged.plan.savedAt, "2026-10-04T11:00:00Z");
});

test("one depot in view is that depot's plan unchanged, and no plan anywhere is no plan", () => {
  const one = mergeWorking(depotStates([{ depot: "Kandy", published: null, draft: kandy }]));
  assert.equal(one.stage === "none" ? null : one.plan, kandy);
  assert.equal(mergeWorking(depotStates([{ depot: "Kandy", published: null, draft: null }])).stage, "none");
});

test("a command goes to the depot whose plan holds the order or the trip it names", () => {
  const items = depotStates([
    { depot: "Kandy", published: null, draft: kandy },
    { depot: "Peliyagoda", published: peliyagoda, draft: null },
  ]);
  assert.equal(depotHolding(items, "o3")?.depot, "Peliyagoda");
  assert.equal(depotHolding(items, "t1")?.depot, "Kandy");
  assert.equal(depotHolding(items, "nowhere"), null);
  assert.deepEqual(idsIn({ outOrderId: "o1", inOrderId: "o2", reason: "x" }), ["o1", "o2"]);
  assert.deepEqual(idsIn({ tripId: "t1", orderIds: ["o1"] }), ["t1", "o1"]);
});
