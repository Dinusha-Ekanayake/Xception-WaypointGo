import assert from "node:assert/strict";
import { test } from "node:test";
import type { DeliveryRecordView } from "../src/shared/domain/execution.ts";
import { storeReplies } from "../src/shared/messaging/templates.ts";

const delivery = (extra: Partial<DeliveryRecordView>) => ({ outcome: "PENDING", startedAt: null, reportedProblem: null, ...extra }) as DeliveryRecordView;

test("a store found closed is offered to say it is open now, or to take the next day, to the dispatcher", () => {
  const r = storeReplies(delivery({ outcome: "ARRIVED", reportedProblem: "STORE_CLOSED" }));
  assert.deepEqual(r.map((x) => x.id), ["storereply.open_now", "storereply.next_day"]);
  assert.ok(r.every((x) => x.to === "dispatch"), "the dispatcher decides");
});

test("each situation has its own replies, and a delivery done or not yet started has none", () => {
  assert.equal(storeReplies(delivery({ outcome: "ARRIVED", reportedProblem: "CANNOT_UNLOAD" }))[0]!.id, "storereply.space_clear");
  assert.equal(storeReplies(delivery({ outcome: "ARRIVED" }))[0]!.to, "driver");
  assert.equal(storeReplies(delivery({ startedAt: "2026-10-21T03:00:00Z" }))[0]!.id, "storereply.ready");
  assert.equal(storeReplies(delivery({ outcome: "SKIPPED" }))[0]!.id, "storereply.understood");
  assert.deepEqual(storeReplies(delivery({ outcome: "DELIVERED" })), []);
  assert.deepEqual(storeReplies(delivery({})), [], "not on the way yet");
  assert.deepEqual(storeReplies(null), []);
});

test("the newest word from the dispatcher decides the answers: a delay asks whether the store can still receive", () => {
  const delay = { mine: false, authorRole: "dispatcher" as const, body: "VEH037 is delayed: breakdown" };
  const r = storeReplies(delivery({ startedAt: "2026-10-21T03:00:00Z" }), undefined, delay);
  assert.deepEqual(r.map((x) => x.id), ["storereply.later_today", "storereply.next_day"]);
  assert.ok(r.every((x) => x.to === "dispatch"));
  // Once the delivery is done, a delay notice asks nothing.
  assert.deepEqual(storeReplies(delivery({ outcome: "DELIVERED" }), undefined, delay).map((x) => x.id), ["storereply.understood"]);
  // The store's own message asks it nothing.
  assert.deepEqual(storeReplies(delivery({}), undefined, { ...delay, mine: true }), []);
  // Any other word from the dispatcher gets a plain acknowledgement; a driver's does not.
  assert.deepEqual(storeReplies(delivery({}), undefined, { mine: false, authorRole: "dispatcher", body: "Your order goes tomorrow." }).map((x) => x.id), ["storereply.understood"]);
  assert.deepEqual(storeReplies(delivery({}), undefined, { mine: false, authorRole: "driver", body: "Five minutes away." }), []);
});
