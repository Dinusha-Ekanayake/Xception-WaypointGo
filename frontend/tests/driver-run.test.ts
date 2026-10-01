import assert from "node:assert/strict";
import { test } from "node:test";
import type { Command } from "../src/shared/api/commands.ts";
import type { RunSheetStopView, RunSheetView } from "../src/shared/domain/execution.ts";
import {
  DeliveryKind,
  canDeliver,
  clock,
  lateMinutes,
  missing,
  nextStop,
  operatingDate,
  project,
  summarize,
  waitMinutes,
  type Stop,
} from "../src/roles/driver/data/run.ts";

// The run as the phone believes it is. Mirrors the server's DeliveryRecord
// transitions; the server remains the authority.

const DAY = "2027-03-01";

function stop(id: string, sequence: number, extra: Partial<RunSheetStopView> = {}): RunSheetStopView {
  return {
    deliveryId: id,
    tripId: "trip-1",
    sequence,
    orderId: `order-${id}`,
    outletId: "OUT001",
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
    proofCaptured: false,
    rowVersion: 1,
    ...extra,
  };
}

function sheet(...stops: RunSheetStopView[]): RunSheetView {
  return { vehicleId: "VEH043", serviceDate: DAY, stops };
}

function write(kind: string, payload: Record<string, unknown>, at: string, version: number, needsReview = false) {
  const command: Command = { commandId: `c-${Math.random()}`, kind, expectedVersion: version, payload, clientRecordedAt: at };
  return { command, needsReview };
}

/** 05:10 at the depots is 23:40 UTC the evening before. */
const colombo = (time: string) => new Date(`${DAY}T${time}:00+05:30`).toISOString();

test("lateness is measured against the window close, never the plan", () => {
  assert.equal(lateMinutes(DAY, "07:30:00", new Date(colombo("07:30"))), 0);
  assert.equal(lateMinutes(DAY, "07:30:00", new Date(new Date(colombo("07:30")).getTime() + 1000)), 1, "one second over is a minute late");
  assert.equal(lateMinutes(DAY, "07:30:00", new Date(colombo("07:50"))), 20);
  assert.equal(lateMinutes(DAY, "07:30:00", new Date(colombo("06:00"))), 0, "behind plan and inside the window is not late");
});

test("an early arrival waits until the window opens", () => {
  assert.equal(waitMinutes(DAY, "05:00:00", new Date(colombo("04:50"))), 10);
  assert.equal(waitMinutes(DAY, "05:00:00", new Date(colombo("05:00"))), 0);
  assert.equal(waitMinutes(DAY, "05:00", new Date(colombo("04:59"))), 1);
});

test("with nothing waiting the run is the server's copy", () => {
  const stops = project(sheet(stop("a", 1), stop("b", 2)), []);
  assert.deepEqual(stops.map((s) => [s.deliveryId, s.outcome, s.rowVersion, s.waiting]), [
    ["a", "PENDING", 1, false],
    ["b", "PENDING", 1, false],
  ]);
});

test("waiting writes are applied in order and each moves the version on by one", () => {
  const stops = project(sheet(stop("a", 1), stop("b", 2)), [
    write(DeliveryKind.start, { deliveryId: "a" }, colombo("04:40"), 1),
    write(DeliveryKind.arrive, { deliveryId: "a" }, colombo("04:50"), 2),
    write(DeliveryKind.record, { deliveryId: "a", outcome: "DELIVERED" }, colombo("05:15"), 3),
    write(DeliveryKind.proof, { deliveryId: "a" }, colombo("05:16"), 4),
  ]);
  const a = stops[0]!;
  assert.equal(a.outcome, "DELIVERED");
  assert.equal(a.rowVersion, 5, "four writes after version 1");
  assert.equal(a.startedAt, colombo("04:40"));
  assert.equal(a.arrivedAt, colombo("04:50"));
  assert.equal(a.waitMinutes, 10);
  assert.equal(a.lateMinutes, 0);
  assert.equal(a.completedAt, colombo("05:15"));
  assert.equal(a.proofCaptured, true);
  assert.equal(a.waiting, true);
  assert.deepEqual([stops[1]!.outcome, stops[1]!.rowVersion, stops[1]!.waiting], ["PENDING", 1, false]);
});

test("an arrival with no start starts the stop, as the server does", () => {
  const [a] = project(sheet(stop("a", 1)), [write(DeliveryKind.arrive, { deliveryId: "a" }, colombo("07:50"), 1)]);
  assert.equal(a!.startedAt, colombo("07:50"));
  assert.equal(a!.lateMinutes, 20);
});

test("a write the server refused is not applied", () => {
  const [a] = project(sheet(stop("a", 1)), [
    write(DeliveryKind.arrive, { deliveryId: "a" }, colombo("05:10"), 1, true),
  ]);
  assert.deepEqual([a!.outcome, a!.rowVersion, a!.waiting], ["PENDING", 1, false]);
});

test("writes for another stop, or of another kind, leave the run alone", () => {
  const [a] = project(sheet(stop("a", 1)), [
    write(DeliveryKind.arrive, { deliveryId: "zzz" }, colombo("05:10"), 1),
    write("delivery:ReportFault", { vehicleId: "VEH043", kind: "road", description: "x" }, colombo("05:11"), 1),
  ]);
  assert.deepEqual([a!.outcome, a!.rowVersion, a!.waiting], ["PENDING", 1, false]);
});

test("the next stop is the first with no outcome, and none once the run is done", () => {
  const stops = project(sheet(stop("a", 1, { outcome: "DELIVERED" }), stop("b", 2, { outcome: "SKIPPED" }), stop("c", 3, { outcome: "ARRIVED" })), []);
  assert.equal(nextStop(stops)?.deliveryId, "c");
  assert.equal(nextStop(project(sheet(stop("a", 1, { outcome: "FAILED" })), [])), null);
});

test("a late arrival at a mall can only be recorded as not delivered", () => {
  assert.equal(canDeliver({ mallOutlet: true, lateMinutes: 12 }), false);
  assert.equal(canDeliver({ mallOutlet: true, lateMinutes: 0 }), true);
  assert.equal(canDeliver({ mallOutlet: false, lateMinutes: 90 }), true, "an ordinary outlet is delivered late");
});

test("what a record still needs, in the driver's words", () => {
  const arrived = { ...stop("a", 1, { outcome: "ARRIVED", lateMinutes: 0 }), waiting: false } as Stop;
  const draft = { outcome: "DELIVERED" as const, deliveredUnits: 40, reason: "", dispositionNote: "" };

  assert.equal(missing(arrived, draft, false), null);
  assert.match(missing({ ...arrived, outcome: "PENDING" }, draft, false)!, /arrival/);

  const late = { ...arrived, lateMinutes: 20 };
  assert.match(missing(late, draft, false)!, /late/);
  assert.equal(missing(late, { ...draft, reason: "Accident on the Kandy road" }, false), null);
  assert.equal(missing(late, draft, true), null, "lateness the phone could not time needs no reason");

  const partial = { ...draft, outcome: "PARTIAL" as const, deliveredUnits: 38 };
  assert.match(missing(arrived, { ...partial, deliveredUnits: 40 }, false)!, /between 1 and 39/);
  assert.match(missing(arrived, { ...partial, deliveredUnits: 0 }, false)!, /between 1 and 39/);
  assert.match(missing(arrived, partial, false)!, /why/);
  assert.match(missing(arrived, { ...partial, reason: "Two cartons crushed" }, false)!, /not delivered/);
  assert.equal(missing(arrived, { ...partial, reason: "Two cartons crushed", dispositionNote: "On the vehicle" }, false), null);

  const failed = { ...draft, outcome: "FAILED" as const };
  assert.match(missing({ ...arrived, outcome: "PENDING" }, failed, false)!, /why/, "a stop can fail before it is reached");
  assert.match(missing(arrived, { ...failed, reason: "refused" }, false)!, /goods/);
  assert.equal(missing(arrived, { ...failed, reason: "refused", dispositionNote: "Returned" }, false), null);

  const mallLate = { ...arrived, mallOutlet: true, lateMinutes: 5 };
  assert.match(missing(mallLate, { ...draft, reason: "Traffic" }, false)!, /mall/);
  assert.equal(missing(mallLate, { ...failed, reason: "mall_window_closed", dispositionNote: "On the vehicle" }, false), null);
});

test("the summary counts outcomes and the proof still owed", () => {
  const stops = project(
    sheet(
      stop("a", 1, { outcome: "DELIVERED", proofCaptured: true }),
      stop("b", 2, { outcome: "PARTIAL" }),
      stop("c", 3, { outcome: "FAILED" }),
      stop("d", 4, { outcome: "SKIPPED" }),
      stop("e", 5),
    ),
    [],
  );
  assert.deepEqual(summarize(stops), { total: 5, finished: 4, delivered: 1, partial: 1, failed: 1, skipped: 1, proofOwed: 1 });
});

test("dates and clocks are the depots', whatever the phone's time zone", () => {
  assert.equal(operatingDate(new Date("2027-02-28T19:00:00Z")), "2027-03-01", "00:30 in Colombo is already the next day");
  assert.equal(operatingDate(new Date("2027-02-28T18:00:00Z")), "2027-02-28");
  assert.equal(clock("05:20:00"), "05:20");
  assert.equal(clock("2027-02-28T23:50:00Z"), "05:20");
  assert.equal(clock(null), "--:--");
});
