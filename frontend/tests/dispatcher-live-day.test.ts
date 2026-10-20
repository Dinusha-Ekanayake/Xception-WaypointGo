import test from "node:test";
import assert from "node:assert/strict";
import { liveDay } from "../src/roles/dispatcher/data/liveDay.ts";
import type { ReadyTripView, RunSheetView } from "../src/shared/domain/types.ts";

// Live opens on the day the work is on, not the calendar day: a trip for
// tomorrow can be loaded and leave after today's 16:00 cutoff, before midnight.

const TODAY = "2026-10-20";
const RUN = "2026-10-21";

const sheet = (done: boolean): RunSheetView => ({ stops: [{ completedAt: done ? "2026-10-20T08:00:00Z" : null }] }) as unknown as RunSheetView;
const trip = (status: string): ReadyTripView => ({ status }) as unknown as ReadyTripView;
const day = (sheets: RunSheetView[], dock: ReadyTripView[] = []) => ({ sheets, dock });

test("today while one of today's trips is still out", () => {
  assert.equal(liveDay(TODAY, RUN, day([sheet(false)]), day([sheet(false)])), TODAY);
});

test("the next run once one of its trips has left, though it is still today on the calendar", () => {
  assert.equal(liveDay(TODAY, RUN, day([sheet(true)]), day([sheet(false)])), RUN);
});

test("the next run as soon as one of its trips is being loaded", () => {
  assert.equal(liveDay(TODAY, RUN, day([]), day([], [trip("NOT_STARTED"), trip("IN_PROGRESS")])), RUN);
});

test("today while the next run has not begun, or nothing could be read", () => {
  assert.equal(liveDay(TODAY, RUN, day([]), day([], [trip("NOT_STARTED")])), TODAY);
  assert.equal(liveDay(TODAY, RUN, null, undefined), TODAY);
});
