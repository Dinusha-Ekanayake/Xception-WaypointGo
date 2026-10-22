import assert from "node:assert/strict";
import { test } from "node:test";
import { orderingOpen } from "../src/roles/dispatcher/data/plan.ts";

// R-PLN-49: a day is planned once its ordering closes, 16:00 depot time the day before.
test("a day stays open for orders until 16:00 depot time the day before", () => {
  assert.equal(orderingOpen("2026-10-23", new Date("2026-10-22T15:59:00+05:30")), true);
  assert.equal(orderingOpen("2026-10-23", new Date("2026-10-22T16:00:00+05:30")), false);
  assert.equal(orderingOpen("2026-10-24", new Date("2026-10-22T18:00:00+05:30")), true, "the day after next is still open");
  assert.equal(orderingOpen("2026-10-22", new Date("2026-10-22T09:00:00+05:30")), false, "today closed yesterday");
});
