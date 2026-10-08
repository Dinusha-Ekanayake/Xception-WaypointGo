import assert from "node:assert/strict";
import { test } from "node:test";
import { measuredLine } from "../src/roles/admin/data/measured.ts";

// R-PLN-47: the reload time a depot actually takes, beside the rule.

test("the reload rule says what each depot measured", () => {
  const line = measuredLine("trip.reload.min", [
    { key: "trip.reload.min", depotCode: "PLG", median: 28, p75: 35, samples: 18, from: "2026-09-11", to: "2026-10-09" },
  ]);
  assert.equal(line, "Measured at PLG: median 28 min, 3 in 4 within 35 min, from 18 reloads in the last 4 weeks.");
});

test("with too few reloads it says why there is no measure, and other rules say nothing", () => {
  assert.match(measuredLine("trip.reload.min", [])!, /^Not measured yet/);
  assert.equal(measuredLine("trips.max.per.vehicle", []), null);
});
