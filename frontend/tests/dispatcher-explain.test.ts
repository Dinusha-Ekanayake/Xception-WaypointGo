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
