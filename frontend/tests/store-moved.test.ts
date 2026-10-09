import assert from "node:assert/strict";
import test from "node:test";
import { movedReason, movedWhy } from "../src/roles/store/data/format.ts";

// Why an order's delivery day moved, in the store's words. Orders for a day
// close at 16:00 the day before (R-ORD-01), depot time Asia/Colombo.

const order = (placedAt: string, extra: Record<string, unknown> = {}) => ({
  dateRolled: true, requestedDate: "2026-10-08", placedAt, ...extra,
});

test("an order that reached us at 16:00 or later moved because of the cutoff", () => {
  // Saved on the phone at 15:55 with no signal, arrived 16:05 (10:35 UTC).
  assert.equal(movedReason(order("2026-10-07T10:35:00Z")), "cutoff");
  assert.equal(movedReason(order("2026-10-07T10:30:00Z")), "cutoff", "exactly 16:00 is after the cutoff");
  assert.match(movedWhy(order("2026-10-07T10:35:00Z"))!, /arrived after the 16:00 cutoff/);
});

test("an order placed in time for a day with no deliveries moved because of the day", () => {
  assert.equal(movedReason(order("2026-10-07T10:29:59Z")), "closed-day");
  assert.match(movedWhy(order("2026-10-06T08:00:00Z"))!, /no deliveries that day/);
});

test("the order's own reason wins when the answer carries one", () => {
  assert.equal(movedReason(order("2026-10-06T08:00:00Z", { rolledBecause: ["cutoff"] })), "cutoff");
  assert.equal(movedReason(order("2026-10-07T11:00:00Z", { rolledBecause: ["non_operating"] })), "closed-day");
});

test("an order that did not move says nothing, and no wording mentions the server", () => {
  assert.equal(movedWhy({ dateRolled: false, requestedDate: "2026-10-08" }), null);
  for (const words of [movedWhy(order("2026-10-07T10:35:00Z")), movedWhy(order("2026-10-06T08:00:00Z"))]) {
    assert.doesNotMatch(words!, /server/i);
  }
});
