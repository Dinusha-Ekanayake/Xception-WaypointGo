import test from "node:test";
import assert from "node:assert/strict";
import { CUTOFF_HOUR, nextRun } from "../src/roles/dispatcher/data/scope.ts";

// R-ORD-01: the Plan screen opens on the run being planned.

test("before the 16:00 cutoff the run being planned is today's, from 16:00 tomorrow's", () => {
  assert.equal(CUTOFF_HOUR, 16);
  assert.equal(nextRun("2026-10-06", 15), "2026-10-06");
  assert.equal(nextRun("2026-10-06", 16), "2026-10-07");
  assert.equal(nextRun("2026-10-31", 23), "2026-11-01", "across a month end");
});
