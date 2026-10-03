import assert from "node:assert/strict";
import { test } from "node:test";
import {
  addDays,
  clock,
  codeLabel,
  countdown,
  dayLabel,
  depotStamp,
  depotToday,
  DOCK_TYPE,
  durationText,
  greeting,
  hhmm,
  longDay,
  PARKING,
  temperatureLabel,
  units,
  productLines,
} from "../src/shared/wording/index.ts";

// The shared wording layer (issue #127): depot time on a 24-hour clock, whatever
// the device's zone, and the glossary's words for counts and codes.

test("time of day is depot time on a 24-hour clock, at the edges of the day", () => {
  // Colombo is UTC+05:30.
  assert.equal(clock("2026-10-04T18:30:00Z"), "00:00", "midnight in Colombo is the evening before in UTC");
  assert.equal(clock("2026-10-05T06:30:00Z"), "12:00");
  assert.equal(clock("2026-10-05T18:29:00Z"), "23:59");
  assert.equal(clock(new Date("2026-10-05T10:42:00Z")), "16:12", "never 4:12 PM");
});

test("a wall-clock time is shown as it is, and nothing reads as dashes", () => {
  assert.equal(clock("06:40:00"), "06:40");
  assert.equal(hhmm("05:00:00"), "05:00");
  assert.equal(clock(null), "--:--");
  assert.equal(clock(undefined), "--:--");
  assert.equal(clock("not a time"), "--:--");
});

test("an instant just after Colombo midnight belongs to the Colombo day, not the UTC one", () => {
  const justAfter = new Date("2026-10-04T18:45:00Z");
  assert.equal(depotToday(justAfter), "2026-10-05");
  assert.equal(depotStamp(justAfter), "Mon 5 Oct · 00:15");
});

test("dates read as day, date and month, and calendar arithmetic ignores zones", () => {
  assert.equal(dayLabel("2026-10-01"), "Thu 1 Oct");
  assert.equal(longDay("2026-10-01"), "Thu 1 October");
  assert.equal(addDays("2026-12-31", 1), "2027-01-01");
  assert.equal(addDays("2026-03-01", -1), "2026-02-28");
});

test("greetings, durations and countdowns", () => {
  assert.equal(greeting(new Date("2026-10-05T01:00:00Z")), "Good morning");
  assert.equal(greeting(new Date("2026-10-05T08:00:00Z")), "Good afternoon");
  assert.equal(greeting(new Date("2026-10-05T13:00:00Z")), "Good evening");
  assert.equal(durationText(14), "14 min");
  assert.equal(durationText(80), "1 h 20 min");
  assert.equal(countdown(245), "4:05");
  assert.equal(countdown(-3), "0:00");
});

test("counts use the glossary's words", () => {
  assert.equal(units(1), "1 unit");
  assert.equal(units(0), "0 units");
  assert.equal(units(12), "12 units");
  assert.equal(productLines(1), "1 product line");
  assert.equal(productLines(3), "3 product lines");
});

test("codes from the data are shown as words, never raw", () => {
  assert.equal(codeLabel(DOCK_TYPE, "rear_dock"), "Rear dock");
  assert.equal(codeLabel(DOCK_TYPE, "mall_bay"), "Mall bay");
  assert.equal(codeLabel(PARKING, "normal"), "", "no constraint, nothing shown");
  assert.equal(codeLabel(DOCK_TYPE, "loading_ramp"), "Loading ramp", "an unknown code still reads as words");
  assert.equal(codeLabel(DOCK_TYPE, null), "");
  assert.equal(temperatureLabel("chilled"), "Chilled");
  assert.equal(temperatureLabel("frozen"), "Chilled", "frozen is treated as chilled (R-PLN-26)");
});
