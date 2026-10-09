import assert from "node:assert/strict";
import { test } from "node:test";
import { kpiInputError, kpiValue, lastMondays } from "../src/roles/dispatcher/data/kpiWords.ts";

// Roadmap B-05: the pilot KPIs in words.

test("a share reads as a percentage, write-offs in rupees, the rest as the number", () => {
  assert.equal(kpiValue("fresh_on_time_pct", 66.66), "66.7%");
  assert.equal(kpiValue("write_off_lkr", 12000), "LKR 12,000");
  assert.equal(kpiValue("calls_per_trip", 3.5), "3.5");
});

test("an empty figure is left out, a negative one or a share above 100 is refused", () => {
  assert.equal(kpiInputError("calls_per_trip", ""), null);
  assert.match(kpiInputError("calls_per_trip", "-1")!, /0 or more/);
  assert.match(kpiInputError("adoption_pct", "120")!, /cannot pass 100/);
  assert.equal(kpiInputError("adoption_pct", "88.5"), null);
});

test("the weeks offered are the last Mondays, newest first", () => {
  assert.deepEqual(lastMondays("2026-10-08", 3), ["2026-10-05", "2026-09-28", "2026-09-21"]);
  assert.deepEqual(lastMondays("2026-10-05", 1), ["2026-10-05"]);
});
