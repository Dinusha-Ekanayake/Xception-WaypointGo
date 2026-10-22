import assert from "node:assert/strict";
import { test } from "node:test";
import type { RunSheetStopView, RunSheetView } from "../src/shared/domain/execution.ts";
import { tripsOf } from "../src/roles/driver/data/history.ts";

// "My trips": a day's run sheet becomes one card per trip, numbered in the order the trips left.
const stop = (tripId: string, sequence: number, plannedArrival: string): RunSheetStopView =>
  ({ deliveryId: `${tripId}-${sequence}`, tripId, sequence, plannedArrival, outcome: "DELIVERED" }) as RunSheetStopView;

test("a vehicle's two trips in a day are two cards, the earlier one Trip 1, each in its stop order", () => {
  const sheet = {
    vehicleId: "VEH043", serviceDate: "2026-10-22",
    stops: [stop("late", 2, "14:10:00"), stop("early", 2, "06:40:00"), stop("late", 1, "13:30:00"), stop("early", 1, "05:20:00")],
  } as RunSheetView;
  const trips = tripsOf([sheet]);
  assert.deepEqual(trips.map((t) => [t.key, t.number, t.of]), [["late", 2, 2], ["early", 1, 2]], "newest first");
  assert.deepEqual(trips[1]!.stops.map((s) => s.sequence), [1, 2]);
});

test("days are newest first", () => {
  const day = (date: string) => ({ vehicleId: "VEH043", serviceDate: date, stops: [stop(`t-${date}`, 1, "05:00:00")] }) as RunSheetView;
  assert.deepEqual(tripsOf([day("2026-10-20"), day("2026-10-22"), day("2026-10-21")]).map((t) => t.date), ["2026-10-22", "2026-10-21", "2026-10-20"]);
});
