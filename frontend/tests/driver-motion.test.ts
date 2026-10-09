import assert from "node:assert/strict";
import test from "node:test";
import { motion, MOVING_KMH } from "../src/roles/driver/data/motion.ts";

// R-EXE-28: the driver's messages are covered while the vehicle moves.
const T = 1_000_000;
const at = (s: number, speedKmh: number | null, lat = 7.29, lon = 80.63) => ({ at: T + s * 1000, lat, lon, speedKmh });

test("smoothed over the last three fixes, at 8 km/h or more the vehicle is moving", () => {
  assert.equal(MOVING_KMH, 8);
  assert.equal(motion([at(0, 30), at(5, 32), at(10, 28)], T + 10_000), "moving");
  assert.equal(motion([at(0, 0), at(5, 1), at(10, 0)], T + 10_000), "stopped");
  // One jumpy reading at a standstill neither locks nor frees the screen.
  assert.equal(motion([at(0, 0), at(5, 15), at(10, 0)], T + 10_000), "stopped");
  assert.equal(motion([at(0, 30), at(5, 0), at(10, 30)], T + 10_000), "moving");
});

test("a fix with no speed takes it from the distance to the fix before", () => {
  // About 0.001 degrees of latitude is 111 m; in 10 s that is about 40 km/h.
  assert.equal(motion([at(0, null, 7.29), at(10, null, 7.291)], T + 10_000), "moving");
  assert.equal(motion([at(0, null), at(10, null)], T + 10_000), "stopped");
});

test("no recent fix, or one alone with no speed, says nothing: unknown", () => {
  assert.equal(motion([], T), "unknown");
  assert.equal(motion([at(0, null)], T), "unknown");
  assert.equal(motion([at(0, 40)], T + 31_000), "unknown", "older than 30 s");
});
