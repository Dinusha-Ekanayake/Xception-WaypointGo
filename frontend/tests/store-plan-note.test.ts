import assert from "node:assert/strict";
import { test } from "node:test";
import { dayLabel } from "../src/shared/wording/index.ts";
import { planNote } from "../src/roles/store/data/format.ts";

// The delivery promise (#224): once a plan puts an order on a vehicle, the store sees the day, its stop and the planned arrival.

test("a planned order says its day, stop and expected arrival", () => {
  const note = planNote({ status: "ALLOCATED", deliveryDate: "2026-10-09", plannedStop: 3, plannedArrival: "06:10:00" });
  assert.equal(note, `Planned for ${dayLabel("2026-10-09")} · stop 3 · planned arrival 06:10`);
});

test("without an arrival time the note leaves it out rather than guess", () => {
  assert.equal(planNote({ status: "LOADING", deliveryDate: "2026-10-09", plannedStop: 1, plannedArrival: null }),
    `Planned for ${dayLabel("2026-10-09")} · stop 1`);
});

test("an order not on a plan, or one that moved off it, has no note", () => {
  assert.equal(planNote({ status: "CONFIRMED", deliveryDate: "2026-10-09", plannedStop: null }), null);
  assert.equal(planNote({ status: "DEFERRED", deliveryDate: "2026-10-09", plannedStop: 2, plannedArrival: "07:00:00" }), null);
  assert.equal(planNote({ status: "DELIVERED", deliveryDate: "2026-10-09", plannedStop: 2, plannedArrival: "07:00:00" }), null);
});
