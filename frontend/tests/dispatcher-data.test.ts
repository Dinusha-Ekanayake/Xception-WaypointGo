import assert from "node:assert/strict";
import { test } from "node:test";
import type { OrderStatus, OrderView } from "../src/shared/domain/ordering.ts";
import type { AllocationView, PlanView, TripView } from "../src/shared/domain/planning.ts";
import type { VehicleView } from "../src/shared/domain/referencedata.ts";
import type { RunSheetStopView, RunSheetView } from "../src/shared/domain/execution.ts";
import { after, board, costNote, improvementNote, openDecisions, percent, summarise, working } from "../src/roles/dispatcher/data/plan.ts";
import { flow, matches } from "../src/roles/dispatcher/data/orders.ts";
import { attention, byUrgency, isLate, punctuality, vehicleDay } from "../src/roles/dispatcher/data/live.ts";
import type { IssueView } from "../src/shared/domain/issues.ts";
import { actionsFor, age, byUrgency as issuesByUrgency, issueCounts, nextDay } from "../src/roles/dispatcher/data/issues.ts";

// What the dispatcher's screens derive from the server's views. The server
// decides every rule; these only count and order what it said.

function order(id: string, status: OrderStatus, extra: Partial<OrderView> = {}): OrderView {
  return {
    orderId: id, orderRef: `ORD-${id}`, outletId: "OUT001", depotCode: "KDY", brandCode: "Fresh", districtName: "Kandy",
    requestedDate: "2027-03-01", deliveryDate: "2027-03-01", dateRolled: false, temperature: "ambient", itemCount: 10,
    weightKg: "100", volumeM3: "1.5", status, warehouseOrderRef: "W-1", redeliveryOf: null, deferralCount: 0,
    placedAt: "2027-02-28T04:00:00Z", lines: [], rowVersion: 1, ...extra,
  };
}

function trip(vehicleId: string, tripNumber: 1 | 2, weightKg: string, volumeM3: string): TripView {
  return {
    tripId: `${vehicleId}-${tripNumber}`, vehicleId, tripNumber, brandCode: "Fresh", districtName: "Kandy", temperature: "ambient",
    weightKg, volumeM3, plannedMinutes: "157", plannedDeparture: "03:30:00", stops: [],
  };
}

function allocation(orderId: string, decision: AllocationView["decision"]): AllocationView {
  return { orderId, decision, tripId: decision === "SERVED" ? "t" : null, bindingRule: decision === "SERVED" ? null : "R-PLN-06", reason: "no room", checks: [], source: "ENGINE", locked: false, decidedBy: null, decidedAt: null, lastServedOn: null };
}

function plan(trips: TripView[], allocations: AllocationView[], status: PlanView["status"] = "DRAFT"): PlanView {
  return {
    planId: `plan-${status}`, depotCode: "KDY", serviceDate: "2027-03-01", planVersion: 1, status, referenceVersionId: "r", ruleSetVersionId: "s",
    priorityPolicyVersionId: "p", supersedes: null, publishedAt: null, savedAt: "2027-02-28T16:41:00Z", plannedWithoutPredictor: true, trips, allocations, rowVersion: 1, engine: "priority-insertion-v1", improvement: null,
  };
}

const truck = (vehicleId: string): VehicleView => ({
  vehicleId, vehicleType: "truck", temperatureCapability: "ambient", weightCapKg: 1000, volumeCapM3: 10, kmPerL: 5,
  weeklyFuelQuotaL: 100, depotCode: "KDY", refrigerated: false, van: false,
});

test("the orders needing a decision list the longest-deferred first and unservable last", () => {
  const orders = new Map([order("a", "DEFERRED"), order("b", "DEFERRED", { deferralCount: 2 }), order("c", "UNSERVABLE", { deferralCount: 5 })].map((o) => [o.orderId, o]));
  const decisions = openDecisions(plan([], [allocation("a", "DEFERRED"), allocation("s", "SERVED"), allocation("c", "UNSERVABLE"), allocation("b", "DEFERRED")]), orders);
  assert.deepEqual(decisions.map((d) => d.allocation.orderId), ["b", "a", "c"]);
});

test("the board puts a vehicle's two trips side by side and marks a full one tight", () => {
  const rows = board(plan([trip("VEH002", 1, "400", "4"), trip("VEH001", 2, "100", "9.4"), trip("VEH001", 1, "500", "5")], []), [truck("VEH001"), truck("VEH002")]);
  assert.deepEqual(rows.map((row) => row.vehicleId), ["VEH001", "VEH002"]);
  assert.equal(rows[0]!.trips[0]!.weightPercent, 50);
  assert.equal(rows[0]!.trips[1]!.volumePercent, 94);
  assert.equal(rows[0]!.trips[1]!.tight, true);
  assert.equal(rows[0]!.trips[0]!.tight, false);
  assert.equal(rows[1]!.trips[1], null);
});

test("a trip on a vehicle no longer in the fleet is shown without a percentage, not as empty", () => {
  const rows = board(plan([trip("VEH009", 1, "400", "4")], []), []);
  assert.equal(rows[0]!.vehicle, undefined);
  assert.equal(rows[0]!.trips[0]!.weightPercent, null);
  assert.equal(percent("5", 0), null);
});

test("the summary counts each decision once and idle vehicles from the fleet", () => {
  const s = summarise(plan([trip("VEH001", 1, "950", "5")], [allocation("a", "SERVED"), allocation("b", "DEFERRED"), allocation("c", "UNSERVABLE")]), [truck("VEH001"), truck("VEH002")]);
  assert.deepEqual(s, { orders: 3, served: 1, deferred: 1, unservable: 1, trips: 1, tightTrips: 1, vehiclesUsed: 1, vehiclesIdle: 1 });
});

test("the screen works on the open draft when there is one, and knows what it revises", () => {
  const published = plan([], [], "PUBLISHED");
  const draft = plan([], [], "DRAFT");
  const revision = { ...draft, supersedes: published.planId };
  assert.equal(working(null, null).stage, "none");
  assert.equal(working(published, null).stage, "published");
  assert.deepEqual(working(null, draft), { stage: "draft", plan: draft, revises: null });
  assert.deepEqual(working(published, revision), { stage: "draft", plan: revision, revises: published });
});

test("a trip is back its planned minutes after it departs", () => {
  assert.equal(after("03:30:00", "157"), "06:07");
  assert.equal(after("23:30", 45), "00:15");
});

test("the order flow counts each stage from the statuses and leaves cancelled orders out", () => {
  const f = flow([order("1", "CONFIRMED"), order("2", "ALLOCATED"), order("3", "IN_TRANSIT"), order("4", "DELIVERED"), order("5", "RECEIVED"), order("6", "FAILED"), order("7", "CANCELLED"), order("8", "STOCK_UNKNOWN")]);
  assert.equal(f.due, 7);
  assert.equal(f.planned, 5);
  assert.equal(f.leftDock, 4);
  assert.equal(f.delivered, 2);
  assert.equal(f.confirmedByStore, 1);
  assert.equal(f.onTheRoad, 1);
  assert.equal(f.awaitingStore, 1);
  assert.equal(f.attention, 2);
  assert.equal(f.stockUnknown, 1);
});

test("orders filter by status group, brand and text together", () => {
  const o = order("1", "FAILED", { orderRef: "ORD0092361", outletId: "OUT057", districtName: "Galle", brandCode: "Style" });
  assert.equal(matches(o, { status: "attention", brand: "Style", text: "galle" }), true);
  assert.equal(matches(o, { status: "road", brand: "all", text: "" }), false);
  assert.equal(matches(o, { status: "all", brand: "Fresh", text: "" }), false);
  assert.equal(matches(o, { status: "all", brand: "all", text: "out057" }), true);
});

const DAY = "2027-03-01";

function stop(sequence: number, extra: Partial<RunSheetStopView> = {}): RunSheetStopView {
  return {
    deliveryId: `d${sequence}`, tripId: "t", sequence, orderId: `o${sequence}`, outletId: `OUT00${sequence}`, itemCount: 5, mallOutlet: false,
    plannedArrival: "09:00:00", windowOpen: "08:00:00", windowClose: "10:00:00", expectedArrival: null, startedAt: null, arrivedAt: null,
    completedAt: null, waitMinutes: null, lateMinutes: null, outcome: "PENDING", deliveredUnits: null, proofCaptured: false, storeAnswerWaived: null, rowVersion: 1, lines: [], ...extra,
  };
}

const sheet = (vehicleId: string, stops: RunSheetStopView[]): RunSheetView => ({ vehicleId, serviceDate: DAY, stops });
const at = (time: string) => new Date(`${DAY}T${time}:00+05:30`);

test("a stop not reached is late only once its window has closed; a recorded lateness is always late", () => {
  assert.equal(isLate(DAY, stop(1), at("09:59")), false);
  assert.equal(isLate(DAY, stop(1), at("10:01")), true);
  assert.equal(isLate(DAY, stop(1, { outcome: "DELIVERED", lateMinutes: 12 }), at("09:00")), true);
  assert.equal(isLate(DAY, stop(1, { outcome: "DELIVERED", lateMinutes: 0 }), at("11:00")), false);
});

test("a vehicle's day says where it is and how the run is going", () => {
  const day = vehicleDay(sheet("VEH001", [stop(1, { outcome: "DELIVERED", proofCaptured: true }), stop(2, { outcome: "ARRIVED", arrivedAt: "x" }), stop(3)]), at("09:00"));
  assert.equal(day.state, "at-stop");
  assert.equal(day.current?.sequence, 2);
  assert.equal(day.done, 1);
  assert.equal(day.risk, "ok");
  assert.equal(vehicleDay(sheet("VEH002", [stop(1)]), at("07:00")).state, "not-started");
  assert.equal(vehicleDay(sheet("VEH002", [stop(1, { startedAt: "x" })]), at("07:00")).state, "driving");
  assert.equal(vehicleDay(sheet("VEH003", [stop(1, { outcome: "FAILED" }), stop(2, { outcome: "SKIPPED" })]), at("12:00")).state, "finished");
});

test("vehicles are listed most urgent first, and what needs the dispatcher is failed, late, then left before the store answered", () => {
  const sheets = [
    sheet("VEH001", [stop(1, { outcome: "DELIVERED", proofCaptured: true })]),
    sheet("VEH002", [stop(1)]),
    sheet("VEH003", [stop(1, { outcome: "FAILED" }), stop(2, { outcome: "DELIVERED", storeAnswerWaived: "store_absent" })]),
  ];
  const now = at("10:30");
  assert.deepEqual(byUrgency(sheets.map((s) => vehicleDay(s, now))).map((d) => d.vehicleId), ["VEH003", "VEH002", "VEH001"]);
  assert.deepEqual(attention(sheets, now).map((a) => `${a.vehicleId}:${a.kind}`), ["VEH003:failed", "VEH002:late", "VEH003:left-unanswered"]);
});

test("on time counts delivered stops inside their window; a stop not reached counts in neither", () => {
  const p = punctuality([sheet("VEH001", [stop(1, { outcome: "DELIVERED", lateMinutes: 0 }), stop(2, { outcome: "PARTIAL", lateMinutes: 15 }), stop(3), stop(4, { outcome: "FAILED" })])]);
  assert.deepEqual(p, { served: 2, onTime: 1 });
});

const ME = "me";

function issue(id: string, extra: Partial<IssueView> = {}): IssueView {
  return {
    issueId: id, type: "OTHER", severity: "MEDIUM", status: "OPEN", depotCode: "KDY", outletId: null, subjects: [{ type: "order", id: "o-1" }],
    description: "reported", assignee: null, resolutionAction: null, resolutionNote: null, raisedBy: "loader", raisedAt: "2027-03-01T03:00:00Z",
    resolvedAt: null, rowVersion: 1, ...extra,
  };
}

test("an issue offers only what its command would accept: redelivery when nothing arrived, replacement for a shortfall", () => {
  assert.deepEqual(actionsFor(issue("a", { type: "FAILED_DELIVERY" }), ME), ["take", "redelivery", "resolve", "cancel"]);
  assert.deepEqual(actionsFor(issue("b", { type: "RECEIPT_DISPUTE", assignee: ME, status: "ASSIGNED" }), ME), ["resolve", "cancel"], "A-24: goods arrived");
  const shortfall = issue("c", { type: "LOADING_SHORTFALL", subjects: [{ type: "trip", id: "t-1" }, { type: "order", id: "o-1" }] });
  assert.deepEqual(actionsFor(shortfall, ME), ["take", "replacement", "resolve", "cancel"]);
  assert.deepEqual(actionsFor(issue("d", { type: "LOADING_SHORTFALL" }), ME), ["take", "resolve", "cancel"], "no trip named, nothing to recheck");
  assert.deepEqual(actionsFor(issue("e", { status: "RESOLVED" }), ME), ["close"]);
  assert.deepEqual(actionsFor(issue("f", { status: "CLOSED" }), ME), []);
});

test("issues are most severe first, then oldest, and counted by who has them", () => {
  const list = [
    issue("low", { severity: "LOW", raisedAt: "2027-03-01T01:00:00Z" }),
    issue("new-critical", { severity: "CRITICAL", raisedAt: "2027-03-01T05:00:00Z", assignee: ME, status: "ASSIGNED" }),
    issue("old-critical", { severity: "CRITICAL", raisedAt: "2027-03-01T02:00:00Z" }),
  ];
  assert.deepEqual(issuesByUrgency(list).map((i) => i.issueId), ["old-critical", "new-critical", "low"]);
  assert.deepEqual(issueCounts(list, ME), { open: 3, urgent: 2, unassigned: 2, mine: 1 });
});

test("an issue's age reads in minutes, hours, then days, and a redelivery defaults to the next day", () => {
  const raised = "2027-03-01T03:00:00Z";
  assert.equal(age(raised, new Date("2027-03-01T03:08:00Z")), "8 min");
  assert.equal(age(raised, new Date("2027-03-01T06:30:00Z")), "3 h");
  assert.equal(age(raised, new Date("2027-03-03T04:00:00Z")), "2 d");
  assert.equal(nextDay("2027-02-28"), "2027-03-01");
  assert.equal(nextDay("2027-12-31"), "2028-01-01");
});

test("the second pass is said only when it changed the plan or stopped early", () => {
  const base = { firstPassServed: 70, firstPassDeferred: 14, served: 73, deferred: 11, improved: true, chilledVolumeGainedM3: "30.448", stoppedBy: "NONE" as const, chilledCandidates: 26, chilledSearched: 26 };
  assert.equal(improvementNote(null), null);
  assert.deepEqual(improvementNote(base)?.title, "Refrigerated vehicles planned again: 3 more orders served");
  assert.match(improvementNote(base)!.detail, /from 14 to 11, with 30\.4 m³ more chilled/);
  assert.equal(improvementNote({ ...base, improved: false, served: 70, deferred: 14 }), null);
  assert.match(improvementNote({ ...base, improved: false, stoppedBy: "CLOCK" })!.detail, /stopped before it finished/);
  assert.match(improvementNote({ ...base, improved: false, chilledCandidates: 70, chilledSearched: 62 })!.detail, /top 62 of 70 chilled orders/);
});

test("planning v2: the cost stage is said as the optimised plan against the rules plan, or why it did not run", () => {
  const ran = {
    trigger: "DEFERRALS" as const, improved: true, rulesVehicles: 16, rulesTrips: 32, rulesLitres: "724.8",
    vehicles: 13, trips: 25, litres: "610.6", iterations: 2000, stoppedBy: "NONE" as const,
  };
  const note = costNote(ran)!;
  assert.equal(note.title, "Optimised: 13 vehicles, 25 trips, 611 L");
  assert.equal(note.detail, "Rules plan: 16 vehicles, 32 trips, 725 L. The same orders are served with 3 fewer vehicles and 114 L less fuel.");
  assert.equal(note.compare, true, "an optimised plan offers the rules plan to compare");
  assert.match(costNote({ ...ran, stoppedBy: "CLOCK" })!.detail, /time limit/);
  assert.equal(costNote({ ...ran, trigger: "SKIPPED_SIMPLE_DAY", improved: false })!.title, "Simple day: the rules plan");
  assert.equal(costNote({ ...ran, trigger: "SKIPPED_SIMPLE_DAY", improved: false })!.compare, false);
  assert.equal(costNote({ ...ran, trigger: "SKIPPED_KEPT_DECISIONS", improved: false })!.title, "Your decisions kept");
  assert.equal(costNote({ ...ran, improved: false })!.title, "The rules plan was already the cheapest found");
  assert.equal(costNote({ ...ran, trigger: "SKIPPED_DISABLED", improved: false }), null);
  assert.equal(costNote(null), null);
  assert.equal(costNote(undefined), null, "a run from before planning v2 has no cost stage");
});
