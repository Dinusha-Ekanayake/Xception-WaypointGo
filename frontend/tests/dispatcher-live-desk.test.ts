import assert from "node:assert/strict";
import { test } from "node:test";
import type { IssueView, ReadyTripView, RunSheetStopView, RunSheetView, VehiclePositionView } from "../src/shared/domain/types.ts";
import { vehicleDay } from "../src/roles/dispatcher/data/live.ts";
import { activity, depotClock, filterRuns, goodsToday, limits, needCards, runsOf, tripBudget } from "../src/roles/dispatcher/data/liveDesk.ts";

const DATE = "2027-03-01";
const NOW = new Date("2027-03-01T10:42:00Z"); // 16:12 in Colombo
const at = (utc: string) => `2027-03-01T${utc}:00Z`;

function stop(vehicle: string, seq: number, extra: Partial<RunSheetStopView> = {}): RunSheetStopView {
  return {
    deliveryId: `d-${vehicle}-${seq}`, tripId: `t-${vehicle}`, sequence: seq, orderId: `o-${seq}`, outletId: `OUT0${60 + seq}`, itemCount: 10,
    mallOutlet: false, plannedArrival: "16:30:00", windowOpen: "09:00:00", windowClose: "17:00:00", expectedArrival: null, startedAt: at("08:50"),
    arrivedAt: null, completedAt: null, waitMinutes: null, lateMinutes: null, outcome: "PENDING", deliveredUnits: null, proofCaptured: false, storeAnswerWaived: null, rowVersion: 1, lines: [], ...extra,
  };
}

function trip(vehicleId: string, brand = "Style"): ReadyTripView {
  return {
    tripId: `t-${vehicleId}`, vehicleId, tripNumber: 2, tripsForVehicle: 2, plannedDeparture: "14:00:00", status: "COMPLETED", brandCode: brand,
    districtName: "Matara", temperature: "ambient", dockCode: "D1", stopCount: 2, orderCount: 2, weightKg: "900", volumeM3: "7.5", volumeCapM3: "34",
    holder: null, releasedAt: at("08:50"), rowVersion: 3,
  };
}

const sheet = (vehicleId: string, stops: RunSheetStopView[]): RunSheetView => ({ vehicleId, serviceDate: DATE, stops });
const days = (sheets: RunSheetView[]) => sheets.map((s) => vehicleDay(s, NOW));

test("a run joins its loading trip by the stop's trip, and its position", () => {
  const pos: VehiclePositionView = { vehicleId: "VEH020", tripId: "t-VEH020", latitude: "6.0", longitude: "80.4", headingDeg: null, accuracyM: null, recordedAt: at("10:30"), offline: true };
  const [run] = runsOf(days([sheet("VEH020", [stop("VEH020", 1)])]), [trip("VEH020")], [pos], { VEH020: "Kandy" }, DATE, NOW);
  assert.equal(run!.trip?.brandCode, "Style");
  assert.equal(run!.status, "offline");
  assert.equal(run!.depot, "Kandy");
});

test("needs you: a failed stop first, then the window closing soonest, then an issue, then a stop left before the store answered", () => {
  const runs = runsOf(
    days([
      sheet("VEH001", [stop("VEH001", 1, { outcome: "DELIVERED", completedAt: at("09:00"), arrivedAt: at("09:00"), storeAnswerWaived: "store_absent" }), stop("VEH001", 2, { expectedArrival: at("11:40") })]),
      sheet("VEH002", [stop("VEH002", 1, { outcome: "FAILED", completedAt: at("09:10") })]),
    ]),
    [trip("VEH001"), trip("VEH002")],
    [],
    {},
    DATE,
    NOW,
  );
  const issue = { issueId: "i1", type: "STOCK_DISCREPANCY", severity: "HIGH", status: "OPEN", depotCode: "Kandy", outletId: "OUT085", subjects: [], description: "1 unit missing", assignee: null, resolutionAction: null, resolutionNote: null, raisedBy: "u", raisedAt: at("10:32"), resolvedAt: null, rowVersion: 1 } as IssueView;
  const cards = needCards(runs, [issue], DATE, NOW);
  assert.deepEqual(cards.map((c) => c.kind), ["failed", "window", "issue", "left"]);
  assert.equal(cards[3]!.chip.text, "Store manager not available");
  assert.match(cards[3]!.detail, /no photo or signature/);
  assert.equal(cards[1]!.chip.text, "48 min left", "17:00 less 16:12");
  assert.equal(cards[1]!.title, "OUT062 · may miss its window");
  assert.equal(cards[2]!.meta, "reported 16:02");
});

test("a goods problem is no card but an orange stop, for today's open ones only (R-ISS-16)", () => {
  const base = { severity: "MEDIUM", depotCode: "Kandy", subjects: [], description: "2 units damaged", assignee: null, resolutionAction: null, resolutionNote: null, raisedBy: "u", resolvedAt: null, rowVersion: 1 };
  const goods = [
    { ...base, issueId: "g1", type: "DAMAGED_GOODS", status: "OPEN", outletId: "OUT085", raisedAt: at("10:32"), quiet: true },
    { ...base, issueId: "g2", type: "DAMAGED_GOODS", status: "RESOLVED", outletId: "OUT062", raisedAt: at("10:40"), quiet: true },
    { ...base, issueId: "g3", type: "STOCK_DISCREPANCY", status: "OPEN", outletId: "OUT070", raisedAt: "2026-01-01T04:00:00Z", quiet: true },
    { ...base, issueId: "l1", type: "VEHICLE_FAULT", status: "OPEN", outletId: "OUT099", raisedAt: at("10:45") },
  ] as IssueView[];
  assert.deepEqual(needCards([], goods, DATE, NOW).map((c) => c.issueId), ["l1"]);
  assert.deepEqual([...goodsToday(goods, DATE)], ["OUT085"]);
});

test("filters keep a depot and a status, and count what the chips show", () => {
  const runs = runsOf(days([sheet("VEH001", [stop("VEH001", 1, { expectedArrival: at("11:40") })]), sheet("VEH002", [stop("VEH002", 1)])]), [], [], { VEH001: "Kandy", VEH002: "Peliyagoda" }, DATE, NOW);
  const kandy = filterRuns(runs, "Kandy", "all");
  assert.equal(kandy.inDepot, 1);
  assert.equal(kandy.atRisk, 1);
  assert.equal(filterRuns(runs, "all", "at-risk").runs.length, 1);
});

test("limits: trip time since release against the brand's budget, fuel, and load", () => {
  const [run] = runsOf(days([sheet("VEH020", [stop("VEH020", 1)])]), [trip("VEH020")], [], {}, DATE, NOW);
  const l = limits(run!, { vehicleId: "VEH020", weekStarting: DATE, quotaLitres: "200", usedLitres: "38", remainingLitres: "162" }, NOW);
  assert.deepEqual(l.map((x) => x.value), ["112 / 480 min", "19% used", "7.5 / 34 m³"]);
  assert.equal(tripBudget("Fresh"), 270);
});

test("activity reads newest first from what the trip recorded", () => {
  const [run] = runsOf(days([sheet("VEH020", [stop("VEH020", 1, { outcome: "DELIVERED", arrivedAt: at("10:00"), completedAt: at("10:05") }), stop("VEH020", 2)])]), [trip("VEH020")], [], {}, DATE, NOW);
  assert.deepEqual(activity(run!, "Kandy depot").map((a) => `${a.at} ${a.text}`), ["15:35 Delivered at OUT061", "15:30 Arrived at OUT061", "14:20 Left Kandy depot"]);
  assert.equal(depotClock(at("10:42")), "16:12");
});

test("needs you groups a trip's cards under its most urgent one, issues on their own", async () => {
  const { groupByTrip } = await import("../src/roles/dispatcher/data/liveDesk.ts");
  const card = (id: string, vehicleId: string | null) =>
    ({ id, kind: vehicleId ? "failed" : "issue", minutesLeft: null, chip: { text: "", tone: "muted" }, meta: "", title: id, detail: "", vehicleId, issueId: null }) as const;
  const groups = groupByTrip([card("a1", "VEH001"), card("b1", "VEH002"), card("i1", null), card("a2", "VEH001"), card("a3", "VEH001")]);
  assert.deepEqual(groups.map((g) => [g.lead.id, g.more.map((m) => m.id)]), [["a1", ["a2", "a3"]], ["b1", []], ["i1", []]]);
});

test("R-EXE-25: offline is the driver's phone not reaching the server, not a stale fix", () => {
  const stale: VehiclePositionView = { vehicleId: "VEH020", tripId: "t-VEH020", latitude: "6.0", longitude: "80.4", headingDeg: null, accuracyM: null, recordedAt: at("10:00"), offline: true };
  const fresh: VehiclePositionView = { ...stale, recordedAt: at("10:41"), offline: false };
  const run = (pos: VehiclePositionView, connected: boolean) =>
    runsOf(days([sheet("VEH020", [stop("VEH020", 1)])]), [trip("VEH020")], [pos], {}, DATE, NOW, [
      { vehicleId: "VEH020", driverId: "u-1", lastSeenAt: at("10:41"), connected },
    ])[0]!;
  assert.notEqual(run(stale, true).status, "offline", "connected, though the last fix is old: the driver still hears notifications");
  assert.equal(run(fresh, false).status, "offline", "not connected, though the last fix is fresh");
  // Without a presence read, the last fix still decides.
  assert.equal(runsOf(days([sheet("VEH020", [stop("VEH020", 1)])]), [trip("VEH020")], [stale], {}, DATE, NOW)[0]!.status, "offline");
});

test("a stop the driver reported closed is a card to decide, first with the failed ones (R-EXE-32)", () => {
  const runs = runsOf(days([sheet("VEH001", [stop("VEH001", 1, { outcome: "ARRIVED", reportedProblem: "STORE_CLOSED" }), stop("VEH001", 2)])]), [], [], {}, DATE, NOW);
  const card = needCards(runs, [], DATE, NOW).find((c) => c.id.startsWith("reported-"))!;
  assert.equal(card.title, "OUT061 · store closed");
  assert.equal(card.chip.text, "Decide");
});

test("each store waiting gets its own time, in trip order; a store already served gets none", async () => {
  const { eachStoreMessages } = await import("../src/shared/messaging/templates.ts");
  const msgs = eachStoreMessages({
    serviceDate: DATE,
    stops: [
      stop("VEH001", 2, { expectedArrival: at("11:40") }),
      stop("VEH001", 1, { outcome: "DELIVERED" }),
      stop("VEH001", 3, { expectedArrival: at("12:30") }),
    ],
  });
  assert.deepEqual(msgs.map((m) => m.outletId), ["OUT062", "OUT063"]);
  assert.notEqual(msgs[0]!.body, msgs[1]!.body);
});
