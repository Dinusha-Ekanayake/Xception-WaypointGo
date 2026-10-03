import assert from "node:assert/strict";
import test from "node:test";
import type { OutletView } from "../src/shared/domain/referencedata.ts";
import type { Stop } from "../src/roles/driver/data/run.ts";
import { currentIndex, toRouteStops, tripStatus } from "../src/roles/driver/data/routeStops.ts";

// Issue #114: the Figma driver screens draw the live run sheet.

const stop = (sequence: number, extra: Partial<Stop> = {}): Stop => ({
  deliveryId: `d${sequence}`, tripId: "t", sequence, orderId: `o${sequence}`, outletId: `OUT00${sequence}`, itemCount: 12, mallOutlet: false,
  plannedArrival: "05:10:00", windowOpen: "05:00:00", windowClose: "07:30:00", expectedArrival: null, startedAt: null, arrivedAt: null,
  completedAt: null, waitMinutes: null, lateMinutes: null, outcome: "PENDING", proofCaptured: false, rowVersion: 1,
  lines: [{ productId: "SEED-Fresh-ambient", orderedUnits: 12, deliveredUnits: null }], waiting: false, ...extra,
});

const outlet = { outletId: "OUT001", brandCode: "Fresh", districtName: "Colombo", dockType: "rear_dock", parkingConstraint: "none" } as OutletView;

test("each stop reads as the design draws it, in sequence, with its products marked inferred", () => {
  const [first, second] = toRouteStops([stop(2), stop(1)], { OUT001: outlet });
  assert.equal(first!.deliveryId, "d1");
  assert.equal(first!.stopNumber, "01");
  assert.equal(first!.totalStops, 2);
  assert.equal(first!.name, "Colombo");
  assert.equal(first!.address, "OUT001 • Fresh");
  assert.equal(first!.window, "05:00-07:30");
  assert.equal(first!.eta, "05:10");
  assert.equal(first!.expectedUnits, 12);
  assert.deepEqual(first!.deliveredItems, [{ code: "SEED-Fresh-ambient", category: "Inferred product", qty: 12 }]);
  assert.doesNotMatch(first!.dockTag, /_/, "no raw code on screen");
  assert.equal(second!.name, "OUT002", "an outlet not read yet falls back to its id");
});

test("lateness and outcomes reach the stop's status", () => {
  const [late, done] = toRouteStops([stop(1, { lateMinutes: 12 }), stop(2, { outcome: "PARTIAL" })], {});
  assert.equal(late!.windowStatus, "Late 12 min");
  assert.equal(done!.windowStatus, "Partly delivered");
});

test("the run is at its first unfinished stop and says how far it is", () => {
  const stops = [stop(1, { outcome: "DELIVERED", startedAt: "x" }), stop(2), stop(3)];
  assert.equal(currentIndex(stops), 1);
  assert.equal(tripStatus(stops), "in-progress");
  assert.equal(tripStatus([stop(1), stop(2)]), "not-started");
  assert.equal(tripStatus([stop(1, { outcome: "DELIVERED" })]), "completed");
  assert.equal(currentIndex([stop(1, { outcome: "FAILED" })]), 0, "a finished run stays on its last stop");
});
