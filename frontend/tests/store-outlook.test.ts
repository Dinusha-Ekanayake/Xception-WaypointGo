import assert from "node:assert/strict";
import { test } from "node:test";
import type { DayOutlookView, OutlookStatus } from "../src/shared/domain/types.ts";
import { needsWarning, outlookChip, suggestDay } from "../src/roles/store/data/outlook.ts";

// Issue #224 (R-ML-07): the date strip's chip and the day it suggests instead of a busy one.

const day = (date: string, status: OutlookStatus): DayOutlookView => ({ date, status, load: null, reason: "" });

test("each status has one plain label and tone", () => {
  assert.deepEqual(outlookChip("ON_TRACK"), { label: "On track", tone: "success" });
  assert.deepEqual(outlookChip("AT_RISK"), { label: "At risk", tone: "danger" });
  assert.equal(outlookChip("TOO_EARLY").label, "Too early");
});

test("only a busy or at-risk day warns", () => {
  assert.equal(needsWarning(day("2026-10-09", "BUSY")), true);
  assert.equal(needsWarning(day("2026-10-09", "AT_RISK")), true);
  assert.equal(needsWarning(day("2026-10-09", "TOO_EARLY")), false);
  assert.equal(needsWarning(undefined), false);
});

test("the suggestion is the nearest day on track, the earlier on a tie", () => {
  const days = [day("2026-10-07", "ON_TRACK"), day("2026-10-08", "BUSY"), day("2026-10-09", "AT_RISK"), day("2026-10-10", "BUSY"), day("2026-10-11", "ON_TRACK")];
  assert.equal(suggestDay(days, "2026-10-09"), "2026-10-07");
  assert.equal(suggestDay(days, "2026-10-10"), "2026-10-11");
});

test("a day a trip already serves the district wins over a nearer one", () => {
  const days = [day("2026-10-08", "ON_TRACK"), day("2026-10-09", "BUSY"), day("2026-10-12", "ON_TRACK")];
  assert.equal(suggestDay(days, "2026-10-09", ["2026-10-12"]), "2026-10-12");
});

test("nothing is suggested when no day is on track", () => {
  assert.equal(suggestDay([day("2026-10-08", "BUSY"), day("2026-10-09", "TOO_EARLY"), day("2026-10-10", "CLOSED")], "2026-10-08"), null);
});
