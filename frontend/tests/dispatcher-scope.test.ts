import test from "node:test";
import assert from "node:assert/strict";
import { CUTOFF_HOUR, nextRun } from "../src/roles/dispatcher/data/scope.ts";

// R-ORD-01, R-ORD-16: the Plan screen opens on tomorrow's run, which takes every
// order placed before today's cutoff and any earlier one never planned.

test("the run being planned is tomorrow's, at any hour", () => {
  assert.equal(CUTOFF_HOUR, 16);
  assert.equal(nextRun("2026-10-06"), "2026-10-07");
  assert.equal(nextRun("2026-10-31"), "2026-11-01", "across a month end");
});
