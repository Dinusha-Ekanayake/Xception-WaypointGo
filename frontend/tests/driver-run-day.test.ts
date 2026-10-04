import assert from "node:assert/strict";
import test from "node:test";
import { lookAhead, RUN_LOOK_AHEAD_DAYS, type DayProbe } from "../src/roles/driver/data/run.ts";

// The driver's next trip when today has none (#114, deadline-day UX plan U1 and U2).

const none: DayProbe = { released: false, waiting: null };

function week(days: Record<string, DayProbe | "fails">) {
  const asked: string[] = [];
  const probe = async (date: string) => {
    asked.push(date);
    const answer = days[date];
    if (answer === "fails") throw new Error("offline");
    return answer ?? none;
  };
  return { asked, probe };
}

test("Monday's released run is found from a Saturday", async () => {
  const { probe } = week({ "2026-10-05": { released: true, waiting: null } });
  assert.deepEqual(await lookAhead("2026-10-03", probe), { date: "2026-10-05", released: true, vehicleId: null, departure: null });
});

test("a published trip the loader has not released is named, with its vehicle and departure", async () => {
  const { probe } = week({ "2026-10-05": { released: false, waiting: { vehicleId: "VEH035", departure: "04:36:00" } } });
  assert.deepEqual(await lookAhead("2026-10-04", probe), { date: "2026-10-05", released: false, vehicleId: "VEH035", departure: "04:36:00" });
});

test("the earliest day wins, whatever its state", async () => {
  const { probe } = week({
    "2026-10-06": { released: true, waiting: null },
    "2026-10-05": { released: false, waiting: { vehicleId: "VEH035", departure: "04:36:00" } },
  });
  assert.equal((await lookAhead("2026-10-04", probe))?.date, "2026-10-05");
});

test("every day is asked at once, across a month end, and a day that fails is skipped", async () => {
  const { asked, probe } = week({ "2026-10-31": "fails", "2026-11-01": { released: true, waiting: null } });
  assert.equal((await lookAhead("2026-10-30", probe))?.date, "2026-11-01");
  assert.equal(asked.length, RUN_LOOK_AHEAD_DAYS);
  assert.equal(asked.at(-1), "2026-11-06");
});

test("nothing all week leaves the driver on today", async () => {
  const { probe } = week({});
  assert.equal(await lookAhead("2026-10-04", probe), null);
});
