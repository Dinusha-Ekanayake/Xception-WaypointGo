import assert from "node:assert/strict";
import test from "node:test";
import type { ReadyTripView } from "../src/shared/domain/loading.ts";
import { DOCK_LOOK_AHEAD_DAYS, dockDay } from "../src/roles/loader/data/dockDay.ts";

// Issue #114: the dock board shows the first day with a trip still to load.

const trip = (status: ReadyTripView["status"]) => ({ status }) as ReadyTripView;

function board(days: Record<string, ReadyTripView[]>) {
  const asked: string[] = [];
  const read = async (date: string) => {
    asked.push(date);
    return days[date] ?? [];
  };
  return { asked, read };
}

test("today stays when it has a trip still to load, and nothing ahead is read", async () => {
  const { asked, read } = board({ "2026-10-03": [trip("COMPLETED"), trip("IN_PROGRESS")] });
  assert.equal(await dockDay("2026-10-03", read), "2026-10-03");
  assert.deepEqual(asked, ["2026-10-03"]);
});

test("on a Saturday with today all released, Monday's published plan is the dock's day", async () => {
  const { asked, read } = board({ "2026-10-03": [trip("COMPLETED")], "2026-10-05": [trip("NOT_STARTED")] });
  assert.equal(await dockDay("2026-10-03", read), "2026-10-05");
  assert.deepEqual(asked, ["2026-10-03", "2026-10-04", "2026-10-05"]);
});

test("with nothing to load all week, the board stays on today after one week of reads", async () => {
  const { asked, read } = board({});
  assert.equal(await dockDay("2026-10-03", read), "2026-10-03");
  assert.equal(asked.length, DOCK_LOOK_AHEAD_DAYS + 1);
});
