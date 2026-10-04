import assert from "node:assert/strict";
import { test } from "node:test";
import type { OrderStatus, OrderView } from "../src/shared/domain/ordering.ts";
import type { AllocationSource, AllocationView, PlanView, StopView, TripView } from "../src/shared/domain/planning.ts";
import type { PlanPredictionsView } from "../src/shared/domain/intelligence.ts";
import type { VehicleView } from "../src/shared/domain/referencedata.ts";
import { board } from "../src/roles/dispatcher/data/plan.ts";
import {
  NO_FILTER,
  addedByHand,
  daysBetween,
  decidedCount,
  decisionRows,
  filterBoard,
  freeLabel,
  largestVehicleM3,
  lastServedText,
  lateRisk,
  riskLabel,
  riskTone,
  scoredByEstimate,
  stopRisks,
  tripRisks,
  lowLoad,
  publishBlocker,
  stopShare,
  tightLine,
  tripMatches,
  unservable,
} from "../src/roles/dispatcher/data/planViews.ts";
import { RULE_LABEL, ruleLabel } from "../src/shared/wording/rules.ts";

// What the Plan screen derives from a plan beyond the board: which orders still
// need a decision, how the day compares to low-load and the filter, and the small
// sums a trip's timeline shows. The server decides every rule; these only count
// and order what it said.

function order(id: string, extra: Partial<OrderView> = {}, status: OrderStatus = "CONFIRMED"): OrderView {
  return {
    orderId: id, orderRef: `ORD-${id}`, outletId: `OUT-${id}`, depotCode: "KDY", brandCode: "Fresh", districtName: "Kandy",
    requestedDate: "2027-03-01", deliveryDate: "2027-03-01", dateRolled: false, temperature: "chilled", itemCount: 10,
    weightKg: "100", volumeM3: "2", status, warehouseOrderRef: "W-1", redeliveryOf: null, deferralCount: 0,
    placedAt: "2027-02-28T04:00:00Z", lines: [], rowVersion: 1, ...extra,
  };
}

function stop(orderId: string, outletId: string, sequence: number): StopView {
  return { sequence, orderId, outletId, plannedArrival: "04:10:00", windowOpen: "03:00:00", windowClose: "08:00:00", serviceMinutes: "15" };
}

function trip(id: string, vehicleId: string, tripNumber: 1 | 2, volumeM3: string, extra: Partial<TripView> = {}): TripView {
  return {
    tripId: id, vehicleId, tripNumber, brandCode: "Fresh", districtName: "Kandy", temperature: "chilled",
    weightKg: "200", volumeM3, plannedMinutes: "157", plannedDeparture: "03:30:00", stops: [], ...extra,
  };
}

function alloc(
  orderId: string,
  decision: AllocationView["decision"],
  source: AllocationSource = "ENGINE",
  extra: Partial<AllocationView> = {},
): AllocationView {
  return {
    orderId, decision, tripId: decision === "SERVED" ? "t1" : null, bindingRule: decision === "SERVED" ? null : "R-PLN-06",
    reason: "no room", checks: [], source, locked: false, decidedBy: source === "ENGINE" ? null : "u1", decidedAt: null,
    lastServedOn: null, ...extra,
  };
}

function plan(trips: TripView[], allocations: AllocationView[]): PlanView {
  return {
    planId: "plan-1", depotCode: "KDY", serviceDate: "2027-03-01", planVersion: 1, status: "DRAFT", referenceVersionId: "r",
    ruleSetVersionId: "s", priorityPolicyVersionId: "p", supersedes: null, publishedAt: null, savedAt: "2027-02-28T16:41:00Z",
    plannedWithoutPredictor: true, trips, allocations, rowVersion: 1, engine: "priority-insertion-v1", improvement: null,
  };
}

const vehicle = (vehicleId: string, refrigerated: boolean, volumeCapM3 = 20): VehicleView => ({
  vehicleId, vehicleType: "truck", temperatureCapability: refrigerated ? "reefer" : "ambient", weightCapKg: 1000, volumeCapM3,
  kmPerL: 5, weeklyFuelQuotaL: 100, depotCode: "KDY", refrigerated, van: false,
});

const orders = (...list: OrderView[]) => new Map(list.map((o) => [o.orderId, o]));

test("open orders come first with the longest deferred on top, then kept, then placed, and unservable are not here", () => {
  const p = plan(
    [trip("t1", "V1", 1, "10")],
    [
      alloc("a", "DEFERRED"),
      alloc("b", "DEFERRED"),
      alloc("c", "DEFERRED", "KEPT"),
      alloc("d", "SERVED", "OVERRIDE"),
      alloc("e", "SERVED", "ENGINE"),
      alloc("f", "UNSERVABLE"),
      alloc("g", "SERVED", "SWAP"),
    ],
  );
  const rows = decisionRows(p, orders(order("a"), order("b", { deferralCount: 2 }), order("c"), order("d"), order("e"), order("f"), order("g")));

  assert.deepEqual(rows.map((r) => [r.allocation.orderId, r.state]), [
    ["b", "open"], ["a", "open"], ["c", "kept"], ["d", "placed"], ["g", "placed"],
  ]);
  assert.equal(rows.find((r) => r.allocation.orderId === "d")?.placedOn, "V1 Trip 1");
});

test("decided counts what a dispatcher acted on against every order that needed a decision", () => {
  const p = plan([trip("t1", "V1", 1, "10")], [alloc("a", "DEFERRED"), alloc("b", "DEFERRED", "KEPT"), alloc("c", "SERVED", "OVERRIDE"), alloc("d", "DEFERRED", "MANUAL_DEFER")]);
  const rows = decisionRows(p, orders(order("a"), order("b"), order("c"), order("d")));

  assert.deepEqual(decidedCount(rows), { decided: 3, total: 4, open: 1 });
  assert.deepEqual(decidedCount([]), { decided: 0, total: 0, open: 0 });
});

test("publishing waits while any order has no decision, and names the first", () => {
  const p = plan([trip("t1", "V1", 1, "10")], [alloc("a", "DEFERRED"), alloc("b", "DEFERRED", "KEPT")]);
  const blocker = publishBlocker(decisionRows(p, orders(order("a"), order("b"))));
  assert.equal(blocker.open, 1);
  assert.equal(blocker.first?.allocation.orderId, "a");

  const decided = publishBlocker(decisionRows(plan([], [alloc("b", "DEFERRED", "KEPT")]), orders(order("b"))));
  assert.equal(decided.open, 0, "a kept order is a decision");
  assert.equal(decided.first, null);
});

test("an order no vehicle can carry is listed apart, with the biggest vehicle for comparison", () => {
  const p = plan([], [alloc("a", "UNSERVABLE"), alloc("b", "DEFERRED")]);
  assert.deepEqual(unservable(p, orders(order("a"))).map((u) => u.allocation.orderId), ["a"]);
  assert.equal(largestVehicleM3([vehicle("V1", false, 12), vehicle("V2", true, 38)]), 38);
  assert.equal(largestVehicleM3([]), null);
});

test("last served reads in days, yesterday, or never", () => {
  assert.equal(daysBetween("2027-02-27", "2027-03-01"), 2);
  assert.equal(daysBetween("2027-03-05", "2027-03-01"), 0, "never negative");
  assert.equal(lastServedText("2027-02-27", "2027-03-01"), "Last served 2 days ago");
  assert.equal(lastServedText("2027-02-28", "2027-03-01"), "Last served yesterday");
  assert.equal(lastServedText(null, "2027-03-01"), "Never served");
});

test("a trip under 70% on both measures is low-load and the room it leaves is counted", () => {
  const p = plan([trip("t1", "V1", 1, "6"), trip("t2", "V1", 2, "16"), trip("t3", "V2", 1, "5", { weightKg: "900" })], [alloc("a", "SERVED")]);
  const rows = board(p, [vehicle("V1", true, 20), vehicle("V2", true, 20)]);

  assert.deepEqual(lowLoad(rows), { trips: 1, spareM3: 14 }, "6 of 20 m3 and 200 of 1000 kg; V1 trip 2 is 80% full, V2 is 90% by weight");
});

test("the board filter keeps vehicles with a matching trip and dims the others", () => {
  const p = plan(
    [
      trip("t1", "V1", 1, "10", { stops: [stop("a", "OUT-A", 1)] }),
      trip("t2", "V1", 2, "10", { brandCode: "Style", temperature: "ambient", districtName: "Galle" }),
      trip("t3", "V2", 1, "10", { brandCode: "Style", temperature: "ambient", districtName: "Galle" }),
    ],
    [],
  );
  const rows = board(p, [vehicle("V1", true), vehicle("V2", false)]);

  assert.equal(filterBoard(rows, NO_FILTER).length, 2);
  assert.deepEqual(filterBoard(rows, { ...NO_FILTER, brands: ["Fresh"] }).map((r) => r.vehicleId), ["V1"]);
  assert.deepEqual(filterBoard(rows, { ...NO_FILTER, temperatures: ["ambient"] }).map((r) => r.vehicleId), ["V1", "V2"]);
  assert.deepEqual(filterBoard(rows, { brands: ["Style"], temperatures: ["ambient"], text: "" }).map((r) => r.vehicleId), ["V1", "V2"]);
  assert.deepEqual(filterBoard(rows, { ...NO_FILTER, text: "out-a" }).map((r) => r.vehicleId), ["V1"], "an outlet on a trip");
  assert.deepEqual(filterBoard(rows, { ...NO_FILTER, text: "v2" }).map((r) => r.vehicleId), ["V2"], "a vehicle");
  assert.equal(tripMatches(p.trips[1]!, { ...NO_FILTER, brands: ["Fresh"] }), false);
});

test("an empty cell says what kind of vehicle could take a trip there", () => {
  assert.equal(freeLabel(vehicle("V1", true)), "Free · refrigerated");
  assert.equal(freeLabel(vehicle("V1", false)), "Free · ambient only");
  assert.equal(freeLabel(undefined), "Free");
});

test("a stop's share is its order against the vehicle, and the tight line names the measure that is over", () => {
  assert.equal(stopShare(order("a", { volumeM3: "2" }), vehicle("V1", true, 40)), 5);
  assert.equal(stopShare(undefined, vehicle("V1", true)), null);
  assert.equal(tightLine(94, 92), "Volume 94%");
  assert.equal(tightLine(60, 93), "Weight 93%");
  assert.equal(tightLine(60, 70), null);
  assert.equal(tightLine(null, null), null);
});

test("a trip is added when a dispatcher placed or swapped an order onto it", () => {
  const t = trip("t1", "V1", 1, "10", { stops: [stop("a", "OUT-A", 1), stop("b", "OUT-B", 2)] });
  assert.equal(addedByHand(plan([t], [alloc("a", "SERVED"), alloc("b", "SERVED", "OVERRIDE")]), t), true);
  assert.equal(addedByHand(plan([t], [alloc("a", "SERVED"), alloc("b", "SERVED", "RESTORED")]), t), false, "restored is not a decision about this trip");
  assert.equal(addedByHand(plan([t], [alloc("a", "SERVED"), alloc("b", "SERVED")]), t), false);
});

test("late risk counts trips with a stop at least 35% likely to run late", () => {
  const p = plan([trip("t1", "V1", 1, "10"), trip("t2", "V2", 1, "10"), trip("t3", "V3", 1, "10")], []);
  const predictions = {
    scoring: { status: "SCORED" },
    stops: [
      { tripId: "t1", lateProbability: "0.10" },
      { tripId: "t1", lateProbability: "0.42" },
      { tripId: "t2", lateProbability: "0.34" },
    ],
  } as unknown as PlanPredictionsView;

  assert.deepEqual(lateRisk(p, predictions), { high: 1, low: 2 });
});

test("each stop and trip reads its own late risk; a trip is as late as its latest stop (#119)", () => {
  const predictions = {
    scoring: { status: "SCORED" },
    stops: [
      { orderId: "o1", tripId: "t1", lateProbability: "0.05", degraded: false },
      { orderId: "o2", tripId: "t1", lateProbability: "0.414", degraded: false },
      { orderId: "o3", tripId: "t2", lateProbability: "0.2", degraded: true },
    ],
  } as unknown as PlanPredictionsView;

  assert.deepEqual(stopRisks(predictions).get("o2"), { percent: 41, estimate: false });
  assert.deepEqual(tripRisks(predictions).get("t1"), { percent: 41, estimate: false });
  assert.deepEqual(tripRisks(predictions).get("t2"), { percent: 20, estimate: true });
  assert.equal(riskTone(19), "low");
  assert.equal(riskTone(20), "watch");
  assert.equal(riskTone(35), "high");
  assert.equal(riskLabel({ percent: 41, estimate: false }), "Late 41%");
  assert.equal(riskLabel({ percent: 0, estimate: true }), "Late <1% · estimate", "a tiny chance is not shown as none");
  assert.equal(riskLabel({ percent: 20, estimate: true }, "Late risk"), "Late risk 20% · estimate");
  assert.equal(scoredByEstimate(predictions), true, "one estimated stop makes the scoring an estimate");
  assert.equal(scoredByEstimate({ scoring: { status: "SCORED" }, stops: [] } as unknown as PlanPredictionsView), false);
});

test("every rule the engine names reads as words, never as a code", () => {
  const engineRules = ["R-FLT-03", "R-PLN-01", "R-PLN-02", "R-PLN-03", "R-PLN-04", "R-PLN-05", "R-PLN-06", "R-PLN-07", "R-PLN-09", "R-PLN-10", "R-PLN-12", "R-PLN-13", "R-PLN-16", "R-PLN-19", "R-PLN-22", "R-PLN-29", "R-PLN-30", "R-PLN-31", "R-LOD-09", "PLN-07", "R-PLN-ENGINE-TIMEOUT"];
  for (const id of engineRules) {
    assert.ok(RULE_LABEL[id], `${id} has a label`);
    assert.doesNotMatch(ruleLabel(id), /\bR-[A-Z]+-\d+\b|\bPLN-\d+\b/, `${id} reads as words`);
  }
  assert.equal(ruleLabel("R-NOPE-99"), "Another rule", "an unknown rule is not shown raw");
  assert.equal(ruleLabel(null), "");
});
