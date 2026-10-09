import assert from "node:assert/strict";
import { test } from "node:test";
import { deliveryOf, redoVersion } from "../src/roles/driver/data/redo.ts";
import { isEvening, outletPoints, plannedOutletIds } from "../src/roles/driver/data/prefetch.ts";

// Roadmap F-06: a driver's held stop write is redone on the stop's current
// version, and the evening before, the map of tomorrow's planned stops is kept.

const held = (kind: string, deliveryId?: string) => ({ kind, payload: { kind, payload: deliveryId ? { deliveryId } : { vehicleId: "VEH043" } } });

test("a held stop write is redone on the stop's version plus the writes still waiting on it", () => {
  assert.equal(deliveryOf(held("delivery:Start", "d-1")), "d-1");
  assert.equal(deliveryOf(held("delivery:ReportVehicleStatus")), null);
  const waiting = [held("delivery:RecordArrival", "d-1"), held("delivery:Record", "d-2"), held("message:Post")];
  assert.equal(redoVersion("d-1", 4, waiting), 5);
  assert.equal(redoVersion("d-2", 4, waiting), 5);
  assert.equal(redoVersion("d-3", 4, waiting), 4);
});

test("tomorrow's map is read from 16:00, for this driver's trips only, in stop order", () => {
  assert.equal(isEvening(15), false);
  assert.equal(isEvening(16), true);
  const stop = (sequence: number, outletId: string) => ({ sequence, outletId });
  const plan = {
    trips: [
      { vehicleId: "VEH043", stops: [stop(2, "OUT2"), stop(1, "OUT1")] },
      { vehicleId: "VEH099", stops: [stop(1, "OUT9")] },
      { vehicleId: "VEH043", stops: [stop(1, "OUT1"), stop(2, "OUT3")] },
    ],
  } as unknown as Parameters<typeof plannedOutletIds>[1];
  assert.deepEqual(plannedOutletIds(["VEH043"], plan), ["OUT1", "OUT2", "OUT3"]);
  assert.deepEqual(plannedOutletIds(["VEH043"], null), []);
  assert.deepEqual(
    outletPoints([{ location: { latitude: "7.29", longitude: "80.63" } }, { location: null }] as Parameters<typeof outletPoints>[0]),
    [{ lat: 7.29, lon: 80.63 }],
  );
});
