import assert from "node:assert/strict";
import { test } from "node:test";
import type { IssueView, ReadyTripView, RunSheetStopView, RunSheetView, VehiclePositionView } from "../src/shared/domain/types.ts";
import { vehicleDay } from "../src/roles/dispatcher/data/live.ts";
import { activity, depotClock, filterRuns, limits, needCards, runsOf, tripBudget } from "../src/roles/dispatcher/data/liveDesk.ts";

const DATE = "2027-03-01";
const NOW = new Date("2027-03-01T10:42:00Z"); // 16:12 in Colombo
const at = (utc: string) => `2027-03-01T${utc}:00Z`;

function stop(vehicle: string, seq: number, extra: Partial<RunSheetStopView> = {}): RunSheetStopView {
  return {
    deliveryId: `d-${vehicle}-${seq}`, tripId: `t-${vehicle}`, sequence: seq, orderId: `o-${seq}`, outletId: `OUT0${60 + seq}`, itemCount: 10,
    mallOutlet: false, plannedArrival: "16:30:00", windowOpen: "09:00:00", windowClose: "17:00:00", expectedArrival: null, startedAt: at("08:50"),
    arrivedAt: null, completedAt: null, waitMinutes: null, lateMinutes: null, outcome: "PENDING", deliveredUnits: null, proofCaptured: false, rowVersion: 1, lines: [], ...extra,
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

test("needs you: a failed stop first, then the window closing soonest, then an issue, then a proof owed", () => {
  const runs = runsOf(
    days([
      sheet("VEH001", [stop("VEH001", 1, { outcome: "DELIVERED", completedAt: at("09:00"), arrivedAt: at("09:00") }), stop("VEH001", 2, { expectedArrival: at("11:40") })]),
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
  assert.deepEqual(cards.map((c) => c.kind), ["failed", "window", "issue", "proof"]);
  assert.equal(cards[1]!.chip.text, "48 min left", "17:00 less 16:12");
  assert.equal(cards[1]!.title, "OUT062 · may miss its window");
  assert.equal(cards[2]!.meta, "reported 16:02");
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
