import assert from "node:assert/strict";
import test from "node:test";
import type { PlacementView } from "../src/shared/domain/planning.ts";
import { explainDeferral } from "../src/roles/dispatcher/data/explain.ts";

// Issue #267: one deferral, explained from the planning module's own facts.

const allocation = {
  bindingRule: "R-PLN-06",
  reason: "The order is 320 kg over what the vehicle can still take",
  checks: [
    { ruleId: "R-PLN-06", passed: false, reason: "320 kg over", slack: "-320.00" },
    { ruleId: "R-PLN-02", passed: true, reason: "", slack: null },
  ],
};
const place = (vehicleId: string, feasible: boolean, joins = true): PlacementView =>
  ({ vehicleId, tripNumber: 1, joins, tripId: null, feasible, bindingRule: feasible ? null : "R-PLN-13", reason: feasible ? "" : "Arrives after the window closes", checks: [] }) as PlacementView;
const input = { orderRef: "ORD-1", outletId: "OUT001", day: "Mon 5 Oct", allocation };

test("the explanation names the order, the rule in words and the server's reason", () => {
  const e = explainDeferral({ ...input, places: [] });
  assert.equal(e.headline, "ORD-1 for OUT001 was not placed on the plan for Mon 5 Oct.");
  assert.ok(e.rule.length > 0 && !e.rule.startsWith("R-"), `rule shown as "${e.rule}"`);
  assert.equal(e.reason, allocation.reason);
  assert.equal(e.stopped.length, 1);
  assert.deepEqual(e.met, ["Refrigerated where needed"]);
});

test("it says where the order can still go, and what refused the other places", () => {
  const e = explainDeferral({ ...input, places: [place("VEH001", true), place("VEH002", true, false), place("VEH003", false)] });
  assert.deepEqual(e.fits, ["VEH001, trip 1: joins the trip already planned", "VEH002, trip 1: opens a new trip"]);
  assert.equal(e.refused[0]?.where, "VEH003, trip 1");
  assert.match(e.next, /2 trips/);
});

test("with nowhere to go it says to swap or keep deferred; while loading it says so", () => {
  assert.match(explainDeferral({ ...input, places: [place("VEH003", false)] }).next, /Swap it/);
  const loading = explainDeferral({ ...input, places: null });
  assert.equal(loading.fits, null);
  assert.match(loading.next, /still being checked/);
});

test("the same facts always give the same explanation", () => {
  const places = [place("VEH001", true)];
  assert.deepEqual(explainDeferral({ ...input, places }), explainDeferral({ ...input, places }));
});

// The whole plan, explained from its own allocations.
import { explainPlan } from "../src/roles/dispatcher/data/explain.ts";

const a = (decision: string, bindingRule: string | null, source = "ENGINE") => ({ decision, bindingRule, reason: "the planner's reason", source }) as never;
const plan = (status: string, allocations: never[]) => ({ depotCode: "PELIYAGODA", status, trips: [{ vehicleId: "VEH001" }, { vehicleId: "VEH001" }, { vehicleId: "VEH002" }], allocations });

test("a plan says what it carries and groups the orders left off by reason, most first", () => {
  const e = explainPlan({
    plan: plan("DRAFT", [a("SERVED", null), a("SERVED", null), a("DEFERRED", "R-PLN-06"), a("DEFERRED", "R-PLN-06"), a("DEFERRED", "R-PLN-13"), a("UNSERVABLE", "R-PLN-22"), a("SERVED", null, "OVERRIDE")]),
    day: "Mon 5 Oct",
    notes: ["The second pass placed two more orders."],
  });
  assert.equal(e.carries, "3 orders are placed on 3 trips across 2 vehicles.");
  assert.equal(e.leftOff[0]?.count, 2);
  assert.ok(!e.leftOff[0]!.label.startsWith("R-"));
  assert.equal(e.cannotBeServed, 1);
  assert.match(e.byHand ?? "", /1 order was decided by hand/);
  assert.deepEqual(e.notes, ["The second pass placed two more orders."]);
  assert.match(e.next, /3 orders still need a decision/);
});

test("a plan with nothing left off can be published, and a published one says who works from it", () => {
  assert.match(explainPlan({ plan: plan("DRAFT", [a("SERVED", null)]), day: "Mon 5 Oct", notes: [] }).next, /can be published/);
  const published = explainPlan({ plan: plan("PUBLISHED", [a("SERVED", null)]), day: "Mon 5 Oct", notes: [] });
  assert.match(published.headline, /published plan/);
  assert.match(published.next, /Loaders and drivers/);
});

test("an order that can no longer be placed says it stays deferred, and never 'still checking'", () => {
  const e = explainDeferral({ ...input, allocation: { ...allocation, reason: "arrived after the plan was published" }, places: null, canPlace: false });
  assert.equal(e.canPlace, false);
  assert.match(e.next, /stays deferred/);
  assert.equal(e.reason, "Arrived after the plan was published");
});
