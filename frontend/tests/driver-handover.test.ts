import assert from "node:assert/strict";
import { test } from "node:test";
import type { Command } from "../src/shared/api/commands.ts";
import type { RunSheetStopView } from "../src/shared/domain/execution.ts";
import { LEAVE_REASONS, handoverPhase, leftBecause } from "../src/roles/driver/data/handover.ts";
import { DeliveryKind, nextStop, project } from "../src/roles/driver/data/run.ts";

// Issue #21, store-led handover: the stop screen's rules, and moving on before
// the store answered as the phone keeps it with no signal.

function stop(extra: Partial<RunSheetStopView> = {}): RunSheetStopView {
  return {
    deliveryId: "d-1", tripId: "t-1", sequence: 1, orderId: "o-1", outletId: "OUT001", itemCount: 14, mallOutlet: false,
    plannedArrival: "08:30:00", windowOpen: "08:00:00", windowClose: "10:00:00", expectedArrival: null, startedAt: null,
    arrivedAt: "2027-03-01T03:00:00Z", completedAt: null, waitMinutes: 0, lateMinutes: 0, outcome: "ARRIVED",
    deliveredUnits: null, proofCaptured: false, storeAnswerWaived: null, rowVersion: 3, lines: [], ...extra,
  };
}

test("a stop at the door is handed over, then waits for the store until its report is in", () => {
  assert.equal(handoverPhase(stop(), false, false), "arrived");
  const handed = stop({ outcome: "DELIVERED" });
  assert.equal(handoverPhase(handed, false, false), "waiting");
  assert.equal(handoverPhase(handed, true, false), "answered");
  assert.equal(handoverPhase(handed, true, true), "accepted");
});

test("moving on is final, whatever the store says afterwards", () => {
  const left = stop({ outcome: "DELIVERED", storeAnswerWaived: "store_absent" });
  assert.equal(handoverPhase(left, true, true), "left");
  assert.equal(leftBecause("store_absent"), "Store manager not available");
  assert.equal(leftBecause(null), null);
});

test("the reasons offered are exactly the ones the server keeps", () => {
  assert.deepEqual(LEAVE_REASONS.map((r) => r.reason), ["store_absent", "no_signal", "disagree"]);
});

test("moving on with no signal is kept on the phone and counts one version, like every write", () => {
  const handed = stop({ outcome: "DELIVERED", rowVersion: 4 });
  const leave: Command = {
    commandId: "c-leave", kind: DeliveryKind.leave, expectedVersion: 4,
    payload: { deliveryId: "d-1", reason: "no_signal" }, clientRecordedAt: "2027-03-01T03:20:00Z",
  };
  const [projected] = project({ vehicleId: "VEH043", serviceDate: "2027-03-01", stops: [handed] }, [{ command: leave, needsReview: false }]);
  assert.equal(projected!.storeAnswerWaived, "no_signal");
  assert.equal(projected!.rowVersion, 5);
  assert.equal(projected!.waiting, true, "it says it is still only on this phone");
});

test("a handed-over stop no longer holds up the run: the next stop is the one after it", () => {
  const handed = stop({ outcome: "DELIVERED" });
  const after = stop({ deliveryId: "d-2", sequence: 2, outcome: "PENDING", arrivedAt: null });
  const stops = [handed, after].map((s) => ({ ...s, waiting: false }));
  assert.equal(nextStop(stops)?.deliveryId, "d-2");
});
