import assert from "node:assert/strict";
import { test } from "node:test";
import type { OrderView, StatusChangeView } from "../src/shared/domain/ordering.ts";
import { deferralOf, whyOf } from "../src/roles/store/data/deferral.ts";

// "09 Order deferred": where an order moved from and to, and the plan's reason in words.

test("the reason is the plan's words after its rule, or a known code spelled out", () => {
  assert.equal(whyOf("deferred by plan 0192f1 (R-PLN-02): no cold space"), "No cold space");
  assert.equal(whyOf("stock_unresolved"), "The warehouse had not confirmed the stock by the cutoff");
  assert.equal(whyOf(null), "No reason was recorded");
});

const order = (over: Partial<OrderView>) =>
  ({ orderId: "o", requestedDate: "2026-10-03", deliveryDate: "2026-10-04", deferralCount: 1, dateRolled: false, ...over }) as OrderView;

const entry = (to: StatusChangeView["to"], reason: string, actorId: string | null, at: string): StatusChangeView => ({ from: null, to, reason, actorId, at });

test("a first deferral moved from the day it was due, and says who deferred it and when", () => {
  const timeline = [entry("CONFIRMED", "placed", "u", "2026-10-01T06:00:00Z"), entry("DEFERRED", "deferred by plan p (R-PLN-02): no cold space", "dispatcher", "2026-10-02T11:10:00Z")];
  assert.deepEqual(deferralOf(order({}), timeline, "2026-10-03"), {
    was: { label: "Was", date: "2026-10-03" },
    now: "2026-10-04",
    why: "No cold space",
    by: "the dispatcher",
    at: "2026-10-02T11:10:00Z",
  });
});

test("after a second deferral, or when the first day cannot be read, it says what was asked for", () => {
  assert.deepEqual(deferralOf(order({ deferralCount: 2 }), [], "2026-10-03").was, { label: "Asked for", date: "2026-10-03" });
  assert.deepEqual(deferralOf(order({ dateRolled: true }), [], null).was, { label: "Asked for", date: "2026-10-03" });
  assert.equal(deferralOf(order({}), [entry("DEFERRED", "stock_unresolved", null, "2026-10-02T10:30:00Z")], "2026-10-03").by, "the cutoff");
});
