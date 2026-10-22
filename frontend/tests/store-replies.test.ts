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

test("the newest word from the dispatcher decides the answers, and each goes back the way it came", () => {
  const delay = { mine: false, authorRole: "dispatcher" as const, body: "VEH037 is delayed: road blocked", audience: "all" as const };
  const r = storeReplies(delivery({ startedAt: "2026-10-21T03:00:00Z" }), undefined, delay);
  assert.deepEqual(r.map((x) => x.id), ["storereply.later_today", "storereply.next_day"]);
  assert.ok(r.every((x) => x.to === "dispatch"), "a notice to everyone is answered to the dispatcher");

  // "can u wait" to the stores still waiting: Yes or No, back to the dispatcher and those stores, never the driver.
  const asked = { mine: false, authorRole: "dispatcher" as const, body: "can u wait", audience: "channel" as const };
  const yesNo = storeReplies(delivery({ startedAt: "2026-10-21T03:00:00Z" }), undefined, asked);
  assert.deepEqual(yesNo.map((x) => [x.id, x.to]), [["storereply.yes_wait", "channel"], ["storereply.no", "channel"]]);
  const toStore = storeReplies(delivery({}), undefined, { ...asked, audience: "outlet" });
  assert.deepEqual(toStore.map((x) => x.to), ["dispatch", "dispatch"], "asked the store alone: answered to the dispatcher");

  // Once the delivery is done, a word from the dispatcher is acknowledged.
  assert.deepEqual(storeReplies(delivery({ outcome: "DELIVERED" }), undefined, delay).map((x) => x.id), ["storereply.understood"]);
  // The store's own message asks it nothing; with no word from the dispatcher the delivery's state decides.
  assert.deepEqual(storeReplies(delivery({}), undefined, { ...asked, mine: true }), []);
  assert.deepEqual(storeReplies(delivery({ startedAt: "2026-10-21T03:00:00Z" }), undefined, { ...asked, mine: true }).map((x) => [x.id, x.to]),
    [["storereply.ready", "driver"]]);
});

test("what Jev read the dispatcher's words as steers the store's chips, whatever the words (R-ML-43)", () => {
  const on = delivery({ startedAt: "2026-10-21T03:00:00Z" });
  const said = (understood: string | null, body = "...") => ({ mine: false, authorRole: "dispatcher" as const, body, audience: "outlet" as const, understood });
  // "oya ta 30 min inna puluwanda" read as asking whether the store can wait: no question mark needed.
  assert.deepEqual(storeReplies(on, undefined, said("ASKS_CAN_WAIT", "oya ta 30 min inna puluwanda")).map((x) => x.id), ["storereply.yes_wait", "storereply.no"]);
  assert.deepEqual(storeReplies(on, undefined, said("BREAKDOWN", "lorry eka break down")).map((x) => x.id), ["storereply.later_today", "storereply.next_day"]);
  assert.deepEqual(storeReplies(on, undefined, said("SAYS_NO")).map((x) => x.id), ["storereply.understood"]);
  // The off-topic guard and thanks: no chips at all.
  assert.deepEqual(storeReplies(on, undefined, said("OFF_TOPIC", "buy cheap phones now")), []);
  assert.deepEqual(storeReplies(on, undefined, said("THANKS", "thanks")), []);
  // Not read surely (Jev off or unsure): the rules' own reading of the words, then the general Yes or No.
  assert.deepEqual(storeReplies(on, undefined, said(null, "oyata tikak inna puluwanda?")).map((x) => x.id), ["storereply.yes_wait", "storereply.no"]);
  assert.deepEqual(storeReplies(on, undefined, said(null, "Is the side gate usable?")).map((x) => x.id), ["storereply.yes", "storereply.no"]);
});
