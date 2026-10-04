import assert from "node:assert/strict";
import { test } from "node:test";
import type { IssueView } from "../src/shared/domain/issues.ts";
import type { OrderView } from "../src/shared/domain/ordering.ts";
import type { ReadyTripView } from "../src/shared/domain/loading.ts";
import type { RunSheetView } from "../src/shared/domain/execution.ts";
import type { VehicleView } from "../src/shared/domain/referencedata.ts";
import { groupResults, kindLabel, search, type SearchSource } from "../src/roles/dispatcher/data/search.ts";

// The global search's ranking and grouping, over data a screen already holds.
// Pure; no network, no DOM.

function order(n: number, extra: Partial<OrderView> = {}): OrderView {
  return {
    orderId: `order-${n}`, orderRef: `ORD-${1000 + n}`, outletId: `OUT0${50 + n}`, depotCode: "KDY", brandCode: "Fresh",
    districtName: "Kandy", requestedDate: "2027-03-01", deliveryDate: "2027-03-01", dateRolled: false, temperature: "ambient",
    itemCount: 10, weightKg: "100", volumeM3: "1.5", status: "CONFIRMED", warehouseOrderRef: "W-1", redeliveryOf: null,
    deferralCount: 0, placedAt: "2027-02-28T04:00:00Z", lines: [], rowVersion: 1, ...extra,
  };
}

function vehicle(vehicleId: string, extra: Partial<VehicleView> = {}): VehicleView {
  return {
    vehicleId, vehicleType: "truck", temperatureCapability: "ambient", weightCapKg: 1000, volumeCapM3: 10, kmPerL: 5,
    weeklyFuelQuotaL: 100, depotCode: "KDY", refrigerated: false, van: false, ...extra,
  };
}

function sheet(vehicleId: string): RunSheetView {
  return { vehicleId, serviceDate: "2027-03-01", stops: [] };
}

function dockTrip(tripId: string, vehicleId: string, extra: Partial<ReadyTripView> = {}): ReadyTripView {
  return {
    tripId, vehicleId, tripNumber: 1, tripsForVehicle: 1, plannedDeparture: "03:30:00", status: "READY", brandCode: "Fresh",
    districtName: "Kandy", temperature: "ambient", dockCode: "D1", stopCount: 3, orderCount: 3, weightKg: "500", volumeM3: "5",
    holder: null, releasedAt: null, rowVersion: 1, ...extra,
  };
}

function issue(n: number, extra: Partial<IssueView> = {}): IssueView {
  return {
    issueId: `issue-${n}`, type: "OTHER", severity: "MEDIUM", status: "OPEN", depotCode: "KDY", outletId: `OUT0${50 + n}`,
    subjects: [], description: `Reported problem ${n}`, assignee: null, resolutionAction: null, resolutionNote: null,
    raisedBy: "loader-user", raisedAt: "2027-02-28T04:00:00Z", resolvedAt: null, rowVersion: 1, ...extra,
  };
}

const empty: SearchSource = { orders: [], vehicles: [], sheets: [], dock: [], issues: [], depots: [] };

test("an empty or whitespace query returns no results", () => {
  const source: SearchSource = { ...empty, orders: [order(1)] };
  assert.deepEqual(search(source, ""), []);
  assert.deepEqual(search(source, "   "), []);
});

test("a startsWith match outranks a contains match for the same field", () => {
  const source: SearchSource = { ...empty, vehicles: [vehicle("VEH100"), vehicle("XVEH20")] };
  const results = search(source, "VEH");
  // "VEH100" starts with "VEH"; "XVEH20" only contains it further in.
  assert.equal(results[0]!.id, "VEH100");
  assert.equal(results[1]!.id, "XVEH20");
});

test("a shorter match outranks a longer one at the same tier", () => {
  const source: SearchSource = { ...empty, vehicles: [vehicle("VEH0001"), vehicle("VEH02")] };
  const results = search(source, "VEH0");
  // Both start with "VEH0"; the shorter id ranks first.
  assert.equal(results[0]!.id, "VEH02");
  assert.equal(results[1]!.id, "VEH0001");
});

test("an exact match outranks a startsWith match", () => {
  const source: SearchSource = { ...empty, depots: ["KDY", "KDY2"] };
  const results = search(source, "KDY");
  assert.equal(results[0]!.id, "KDY");
  assert.equal(results[1]!.id, "KDY2");
});

test("matches reach across every kind: order ref, vehicle id, trip, issue text and depot code", () => {
  const source: SearchSource = {
    orders: [order(1, { orderRef: "ORD-5050" })],
    vehicles: [vehicle("VEH050")],
    sheets: [sheet("VEH501")],
    dock: [dockTrip("trip-50", "VEH502")],
    issues: [issue(1, { description: "Pallet 50 damaged" })],
    depots: ["DEP50"],
  };
  const byKind = Object.fromEntries(groupResults(search(source, "50")).map((g) => [g.kind, g.items]));
  assert.ok(byKind.order?.some((r) => r.id === "order-1"));
  assert.ok(byKind.vehicle?.some((r) => r.id === "VEH050"));
  assert.ok(byKind.trip?.some((r) => r.id === "sheet-VEH501"));
  assert.ok(byKind.trip?.some((r) => r.id === "trip-50"));
  assert.ok(byKind.issue?.some((r) => r.id === "issue-1"));
  assert.ok(byKind.depot?.some((r) => r.id === "DEP50"));
});

test("an order result carries the prefill text for the Orders screen's own search box", () => {
  const source: SearchSource = { ...empty, orders: [order(1, { orderRef: "ORD-9001" })] };
  const [result] = search(source, "9001");
  assert.equal(result!.kind, "order");
  assert.equal(result!.view, "orders");
  assert.equal(result!.prefillOrderText, "ORD-9001");
});

test("an issue result carries focusIssueId, the one screen that supports opening a specific item", () => {
  const source: SearchSource = { ...empty, issues: [issue(7, { description: "Fridge door open" })] };
  const [result] = search(source, "fridge");
  assert.equal(result!.kind, "issue");
  assert.equal(result!.view, "issues");
  assert.equal(result!.focusIssueId, "issue-7");
});

test("a depot result carries the depot filter to scope the shell", () => {
  const source: SearchSource = { ...empty, depots: ["Kandy", "Peliyagoda"] };
  const [result] = search(source, "peli");
  assert.equal(result!.kind, "depot");
  assert.equal(result!.depotFilter, "Peliyagoda");
});

test("a vehicle result has no focus id: Vehicles has no per-item deep link, so it only navigates", () => {
  const source: SearchSource = { ...empty, vehicles: [vehicle("VEH900")] };
  const [result] = search(source, "VEH900");
  assert.equal(result!.view, "vehicles");
  assert.equal(result!.focusIssueId, undefined);
  assert.equal(result!.depotFilter, undefined);
  assert.equal(result!.prefillOrderText, undefined);
});

test("groupResults keeps a fixed kind order and each group's own rank order, leaving out empty kinds", () => {
  const source: SearchSource = { ...empty, orders: [order(1, { orderRef: "ORD-K1" })], issues: [issue(1, { description: "K1 problem" })] };
  const groups = groupResults(search(source, "k1"));
  assert.deepEqual(groups.map((g) => g.kind), ["order", "issue"]);
});

test("kindLabel names every kind for the group headings", () => {
  assert.equal(kindLabel("order"), "Orders");
  assert.equal(kindLabel("vehicle"), "Vehicles");
  assert.equal(kindLabel("trip"), "Trips");
  assert.equal(kindLabel("issue"), "Issues");
  assert.equal(kindLabel("depot"), "Depots");
});

test("no match for a query present nowhere", () => {
  const source: SearchSource = { ...empty, orders: [order(1)], vehicles: [vehicle("VEH001")] };
  assert.deepEqual(search(source, "zzz-no-match"), []);
});
