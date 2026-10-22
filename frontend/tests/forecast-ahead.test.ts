import assert from "node:assert/strict";
import { test } from "node:test";
import { forecastSource, weekAhead, type ForecastWeek } from "../src/roles/dispatcher/data/forecast.ts";

const week = (over: Partial<ForecastWeek>): ForecastWeek => ({
  key: 202644, index: 1, isoYear: 2026, isoWeek: 44, start: "2026-10-26", operatingDays: 6, holidayDays: 0, paydays: 1,
  festival: null, generatedDays: 6, brands: [], total: 600, chilled: 200, fleetM3: 1000, refrigeratedM3: 400,
  vehicles: 10, refrigeratedVehicles: 3, ...over,
});

// Task 2A in planning: the week's forecast against the fleet's room that week.
test("a week ahead says how much of the fleet and of the reefers it fills", () => {
  assert.deepEqual(
    { ...weekAhead(week({})), week: undefined },
    { week: undefined, fleetShare: 0.6, reeferShare: 0.5, tone: "fine" },
  );
  assert.equal(weekAhead(week({ chilled: 380 })).tone, "tight", "95% of the reefers");
  assert.equal(weekAhead(week({ total: 1100 })).tone, "over", "past the fleet's room");
  assert.equal(weekAhead(week({ refrigeratedM3: 0 })).tone, "over", "chilled demand and no reefer");
  assert.equal(weekAhead(week({ fleetM3: 0, refrigeratedM3: 0, chilled: 0 })).fleetShare, null);
});

test("the source says model or estimate, never a model's name when the estimate answered", () => {
  assert.equal(forecastSource({ status: "READY", modelLabel: "datathon-task2a@2026.10.1", degraded: false }), "Model forecast");
  assert.equal(forecastSource({ status: "READY", modelLabel: "deterministic", degraded: true }), "Estimated from past orders, no model");
  assert.equal(forecastSource({ status: "NONE", modelLabel: null, degraded: false }), "No forecast yet");
});
