import assert from "node:assert/strict";
import { test } from "node:test";
import type { OutletView } from "../src/shared/domain/referencedata.ts";
import type { RunSheetStopView } from "../src/shared/domain/execution.ts";
import type { Stop } from "../src/roles/driver/data/run.ts";
import { activeIndex, finishedAt, summaryRows, syncLabel, toRouteStops, tripStatus } from "../src/roles/driver/data/stopView.ts";

// The Figma driver screens draw the run sheet, never sample data (issue #117).

const DAY = "2027-03-01";

function stop(id: string, sequence: number, extra: Partial<RunSheetStopView> = {}): Stop {
  return {
    deliveryId: id,
    tripId: "trip-1",
    sequence,
    orderId: `order-${id}`,
    outletId: `OUT00${sequence}`,
    itemCount: 40,
    mallOutlet: false,
    plannedArrival: "05:20:00",
    windowOpen: "05:00:00",
    windowClose: "07:30:00",
    expectedArrival: null,
    startedAt: null,
    arrivedAt: null,
    completedAt: null,
    waitMinutes: null,
    lateMinutes: null,
    outcome: "PENDING",
    deliveredUnits: null, proofCaptured: false, storeAnswerWaived: null,
    rowVersion: 1,
    lines: [{ productId: "P-17", orderedUnits: 40, deliveredUnits: null }],
    waiting: false,
    ...extra,
  };
}

const outlet = (id: string, extra: Partial<OutletView> = {}) =>
  ({ outletId: id, districtName: "Kandy", brandCode: "KEELLS", dockType: "rear_dock", parkingConstraint: null, ...extra }) as unknown as OutletView;

test("a stop is drawn from the run sheet: number, outlet, units, window and access", () => {
  const [a, b] = toRouteStops(
    [stop("d1", 1), stop("d2", 2, { expectedArrival: "2027-03-01T00:15:00Z" })],
    { OUT001: outlet("OUT001"), OUT002: outlet("OUT002", { parkingConstraint: "mall_dock" }) },
    DAY,
    new Date("2027-03-01T00:00:00Z"),
  );
  assert.equal(a.id, "d1");
  assert.equal(a.stopNumber, "01");
  assert.equal(a.totalStops, 2);
  assert.equal(a.name, "OUT001");
  assert.equal(a.address, "Kandy · KEELLS");
  assert.equal(a.cargoText, "40 units");
  assert.equal(a.window, "07:30");
  assert.equal(a.windowStatus, "On time");
  assert.doesNotMatch(a.instructions, /rear_dock/, "a code from the data is never shown raw");
  assert.equal(b.eta, "05:45", "the expected arrival in depot time, moved by the delay");
  assert.equal(b.etaDistanceTime, "Planned 05:20");
  assert.equal(a.etaDistanceTime, "As planned", "no delay seen yet");
  assert.match(b.instructions, /^Mall dock/);
  assert.equal(a.deliveredItems[0]!.category, "Inferred product", "a product is never shown as a real SKU");
});

test("a finished stop says what was recorded; a stop past its window says how late", () => {
  const late = new Date("2027-03-01T02:30:00Z"); // 08:00 in Colombo, 30 min past 07:30
  const [delivered, partial, pending] = toRouteStops(
    [stop("d1", 1, { outcome: "DELIVERED" }), stop("d2", 2, { outcome: "PARTIAL" }), stop("d3", 3)],
    {},
    DAY,
    late,
  );
  assert.equal(delivered.windowStatus, "Delivered");
  assert.equal(partial.windowStatus, "Partly delivered");
  assert.equal(pending.windowStatus, "30 min late");
});

test("the trip is not started, in progress or completed by its stops", () => {
  assert.equal(tripStatus([stop("d1", 1), stop("d2", 2)]), "not-started");
  assert.equal(tripStatus([stop("d1", 1, { startedAt: "2027-03-01T00:00:00Z" }), stop("d2", 2)]), "in-progress");
  assert.equal(tripStatus([stop("d1", 1, { outcome: "DELIVERED" }), stop("d2", 2, { outcome: "SKIPPED" })]), "completed");
  assert.equal(tripStatus([]), "not-started");
});

test("the route opens on the first stop still to do", () => {
  assert.equal(activeIndex([stop("d1", 1, { outcome: "DELIVERED" }), stop("d2", 2), stop("d3", 3)]), 1);
  assert.equal(activeIndex([stop("d1", 1, { outcome: "DELIVERED" }), stop("d2", 2, { outcome: "FAILED" })]), 1);
});

test("run complete counts the real day and leaves the empty rows out", () => {
  const stops = [
    stop("d1", 1, { outcome: "DELIVERED", proofCaptured: true, completedAt: "2027-03-01T01:00:00Z" }),
    stop("d2", 2, { outcome: "FAILED", completedAt: "2027-03-01T02:10:00Z" }),
  ];
  assert.deepEqual(summaryRows(stops, 1), [
    ["Stops delivered", "1 of 2"],
    ["Units delivered", "40 of 80"],
    ["Not delivered", "1"],
    ["Proof of delivery", "1 saved · 1 still sending"],
  ]);
  assert.equal(finishedAt(stops), "2027-03-01T02:10:00Z");
  assert.equal(finishedAt([stop("d1", 1)]), null);
});

test("the header pill says whether the phone is in step with the server", () => {
  const at = new Date("2027-03-01T00:31:00Z");
  assert.equal(syncLabel(false, 0, at, null), "Offline");
  assert.equal(syncLabel(true, 2, at, null), "Sending 2 proof files");
  assert.equal(syncLabel(true, 0, at, null), "Synced 06:01");
  assert.equal(syncLabel(true, 0, null, at), "Saved copy 06:01");
  assert.equal(syncLabel(true, 0, null, null), "Connecting");
});

test("a partial stop counts the units it was recorded with, not its whole order", () => {
  const stops = [
    stop("d1", 1, { outcome: "DELIVERED", deliveredUnits: 40 }),
    stop("d2", 2, { outcome: "PARTIAL", deliveredUnits: 31 }),
    stop("d3", 3),
  ];
  assert.deepEqual(summaryRows(stops, 0).find(([label]) => label === "Units delivered"), ["Units delivered", "71 of 120"]);
});
