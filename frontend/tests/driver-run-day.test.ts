import assert from "node:assert/strict";
import test from "node:test";
import { nextRunDay, RUN_LOOK_AHEAD_DAYS } from "../src/roles/driver/data/run.ts";

// Issue #114: with no run today, the driver follows the next released trip.

function days(sheets: Record<string, number>) {
  const asked: string[] = [];
  const read = async (date: string) => {
    asked.push(date);
    return { sheets: [{ stops: [] }, { stops: Array.from({ length: sheets[date] ?? 0 }) }] };
  };
  return { asked, read };
}

test("Monday's released trip is found from a Saturday, and the search stops there", async () => {
  const { asked, read } = days({ "2026-10-05": 1, "2026-10-06": 1 });
  assert.equal(await nextRunDay("2026-10-03", read), "2026-10-05");
  assert.deepEqual(asked, ["2026-10-04", "2026-10-05"]);
});

test("nothing released all week leaves the driver on today", async () => {
  const { asked, read } = days({});
  assert.equal(await nextRunDay("2026-10-30", read), null);
  assert.equal(asked.length, RUN_LOOK_AHEAD_DAYS);
  assert.equal(asked.at(-1), "2026-11-06", "across a month end");
});

