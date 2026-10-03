import assert from "node:assert/strict";
import test from "node:test";
import { paceOf, timeToDeparture } from "../src/roles/loader/data/manifest.ts";

// Figma 05 "14 min to departure · on pace". Departure is depot time (Asia/Colombo, UTC+5:30).
const now = new Date("2026-10-01T22:00:00Z"); // 03:30 in Colombo

test("the loader is on pace while the share loaded keeps up with the time passed", () => {
  // Took the trip at 03:00, departs 04:30: at 03:30 a third of the time has passed.
  assert.equal(paceOf(4, 10, "2026-10-01T21:30:00Z", "04:30:00", now), "on");
  assert.equal(paceOf(3, 10, "2026-10-01T21:30:00Z", "04:30:00", now), "on", "within a tenth is still on pace");
  assert.equal(paceOf(2, 10, "2026-10-01T21:30:00Z", "04:30:00", now), "behind");
});

test("pace says nothing once departure has passed or the trip is empty", () => {
  assert.equal(paceOf(3, 10, "2026-10-01T21:30:00Z", "03:00:00", now), null);
  assert.equal(paceOf(0, 0, "2026-10-01T21:30:00Z", "04:30:00", now), null);
});

test("time to departure is a duration, for the translated sentence around it", () => {
  assert.equal(timeToDeparture("03:44:00", now), "14 min");
  assert.equal(timeToDeparture("04:50:00", now), "1 h 20 min");
  assert.equal(timeToDeparture("03:00:00", now), "");
});
