import assert from "node:assert/strict";
import test from "node:test";
import { addDays, clockWindow, depotAt, depotParts, inWindow, offsetText, realNow, stepped, STEPS } from "../src/roles/admin/demo/clock.ts";

// Issue #231: the demo clock is set to a depot date and time, or stepped, and
// stays within seven days of real time.

test("an instant reads as the depot's date and time, across midnight", () => {
  assert.deepEqual(depotParts("2026-10-04T18:20:00Z"), { date: "2026-10-04", time: "23:50" });
  assert.deepEqual(depotParts("2026-10-04T18:40:00Z"), { date: "2026-10-05", time: "00:10" });
});

test("a depot date and time is one instant, whatever the laptop's zone", () => {
  assert.equal(depotAt("2026-10-05", "05:00")?.toISOString(), "2026-10-04T23:30:00.000Z");
  assert.equal(depotAt("2026-10-05", ""), null);
  assert.equal(depotAt("", "05:00"), null);
  assert.equal(addDays("2026-10-31", 1), "2026-11-01");
});

test("steps move from the demo clock as it reads now", () => {
  const now = "2026-10-05T10:35:00Z"; // 16:05 in the depot
  const plusDay = STEPS.find((s) => s.label === "+1 day")!;
  const minusHour = STEPS.find((s) => s.label === "-1 h")!;
  assert.deepEqual(depotParts(stepped(now, plusDay.ms)), { date: "2026-10-06", time: "16:05" });
  assert.deepEqual(depotParts(stepped(now, minusHour.ms)), { date: "2026-10-05", time: "15:05" });
});

test("the clock is set only within seven days of real time", () => {
  // The demo reads two days ahead of real time.
  const now = "2026-10-07T00:00:00Z";
  const offset = 2 * 86_400;
  assert.equal(realNow(now, offset).toISOString(), "2026-10-05T00:00:00.000Z");
  const window = clockWindow(now, offset);
  assert.equal(window.max.toISOString(), "2026-10-12T00:00:00.000Z");
  assert.equal(window.min.toISOString(), "2026-09-28T00:00:00.000Z");
  assert.ok(inWindow(new Date("2026-10-11T23:59:00Z"), window));
  assert.ok(!inWindow(new Date("2026-10-12T00:01:00Z"), window));
});

test("how far the demo is from real time reads in words", () => {
  assert.equal(offsetText(0), "On real time");
  assert.equal(offsetText(3 * 3600 + 15 * 60), "3 h 15 min ahead of real time");
  assert.equal(offsetText(-(86_400 + 2 * 3600)), "1 day 2 h behind real time");
});
