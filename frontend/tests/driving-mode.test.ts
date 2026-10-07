import assert from "node:assert/strict";
import test from "node:test";
import { CHIME_GAP_MS, mayChime, OFF, onMotion, openByHand, slideOut, STOPPED_TO_EXIT_MS } from "../src/roles/driver/data/drivingMode.ts";

// The driving screen comes on by itself when the vehicle moves and goes off by
// itself after it has stood still a while (Figma "Driving mode", R-EXE-28).

test("moving turns it on; a short stop does not turn it off; a long one does", () => {
  let s = onMotion(OFF, "moving", 0, true);
  assert.equal(s.on, true);
  s = onMotion(s, "stopped", 10_000, true);
  s = onMotion(s, "stopped", 10_000 + STOPPED_TO_EXIT_MS - 1, true);
  assert.equal(s.on, true, "a traffic light or a jam keeps it on");
  s = onMotion(s, "moving", 120_000, true);
  s = onMotion(s, "stopped", 130_000, true);
  assert.equal(s.stoppedSince, 130_000, "moving again restarts the count");
  s = onMotion(s, "stopped", 130_000 + STOPPED_TO_EXIT_MS, true);
  assert.equal(s.on, false);
});

test("sliding out while moving holds it off until the vehicle stops and moves again", () => {
  let s = slideOut(onMotion(OFF, "moving", 0, true), "moving");
  s = onMotion(s, "moving", 5_000, true);
  assert.equal(s.on, false, "the driver said no");
  s = onMotion(s, "stopped", 10_000, true);
  s = onMotion(s, "moving", 20_000, true);
  assert.equal(s.on, true, "a new movement after a stop");
});

test("opened by hand it stays until the slider; with no location nothing changes; no trip, no screen", () => {
  let s = openByHand(OFF);
  s = onMotion(s, "stopped", 0, true);
  s = onMotion(s, "stopped", STOPPED_TO_EXIT_MS * 2, true);
  assert.equal(s.on, true);
  assert.deepEqual(onMotion(OFF, "unknown", 0, true), OFF);
  assert.equal(onMotion(onMotion(OFF, "moving", 0, true), "moving", 1, false).on, false, "the trip ended");
});

test("a message chimes at most once in five minutes; a changed plan always", () => {
  assert.equal(mayChime(null, 0, false), true);
  assert.equal(mayChime(0, CHIME_GAP_MS - 1, false), false);
  assert.equal(mayChime(0, CHIME_GAP_MS - 1, true), true);
  assert.equal(mayChime(0, CHIME_GAP_MS, false), true);
});
