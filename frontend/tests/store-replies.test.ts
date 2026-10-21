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
