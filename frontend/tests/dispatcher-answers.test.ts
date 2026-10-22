import assert from "node:assert/strict";
import { test } from "node:test";
import { dispatcherAnswers, dispatcherChips, type Reply } from "../src/shared/messaging/templates.ts";

// R-ML-43: the dispatcher's chips answer what the store's or the driver's newest message does.
const store = (understood: string | null, body = "...") =>
  ({ mine: false, authorRole: "store_manager" as const, body, aboutOutletId: "OUT005", understood });
const ids = (r: Reply[]) => r.map((x) => x.id);

test("a store that cannot receive gets the next delivery day, and the driver is told to skip it", () => {
  const r = dispatcherAnswers(store("SAYS_NO", "we cannt acept at hat time"));
  assert.deepEqual(ids(r), ["answer.next_day", "answer.driver_skip"]);
  assert.equal(r[1]!.to, "driver");
  assert.match(r[1]!.body, /^OUT005 cannot receive today/);
});

test("a store open again is passed to the driver first", () => {
  assert.deepEqual(ids(dispatcherAnswers(store("STORE_OPEN_NOW", "we are open now"))), ["answer.tell_driver", "answer.thanks"]);
});

test("a store asking when is answered with its own expected time from the trip's state", () => {
  const time: Reply = { id: "store.running_late", version: 1, label: "Tell OUT005 the new time", to: "outlet", outletId: "OUT005", body: "Running 20 min late. Now expected at 12:40." };
  assert.deepEqual(dispatcherAnswers(store("ASKS_WHEN", "when r u coming?"), [time]), [time]);
});

test("off topic and thanks get nothing; words not read get thanks and the driver told", () => {
  assert.deepEqual(dispatcherAnswers(store("OFF_TOPIC", "win a prize click here")), []);
  assert.deepEqual(dispatcherAnswers(store("THANKS")), []);
  assert.deepEqual(ids(dispatcherAnswers(store(null, "ok"))), ["answer.thanks", "answer.tell_driver"]);
});

test("a driver's breakdown in their own words asks how long; the dispatcher's own words get nothing", () => {
  assert.deepEqual(ids(dispatcherAnswers({ mine: false, authorRole: "driver", body: "lorry eka break down una", aboutOutletId: null, understood: "BREAKDOWN" })), ["answer.how_long"]);
  assert.deepEqual(dispatcherAnswers({ ...store("SAYS_NO"), mine: true }), []);
});

test("not read surely, a store's plain Singlish no still gets the next day and the skip", () => {
  assert.deepEqual(ids(dispatcherAnswers(store(null, "ba, ada ganna ba. heta enna"))), ["answer.next_day", "answer.driver_skip"]);
  assert.deepEqual(ids(dispatcherAnswers(store(null, "about the shelf"))), ["answer.thanks", "answer.tell_driver"]);
});

test("after a store says no, the trip's older word that it will receive steps aside", () => {
  const answers = dispatcherAnswers(store("SAYS_NO", "ba, ada ganna ba"));
  const state: Reply[] = [
    { id: "driver.store_will_receive", version: 1, label: "Tell the driver OUT005 will receive", to: "driver", outletId: null, body: "OUT005 will receive at about 12:40." },
    { id: "store.OUT009", version: 1, label: "Tell OUT009 the new time", to: "outlet", outletId: "OUT009", body: "..." },
  ];
  assert.deepEqual(ids(dispatcherChips(answers, state, "OUT005")), ["answer.next_day", "answer.driver_skip", "store.OUT009"]);
  assert.equal(dispatcherChips(dispatcherAnswers(store("THANKS")), state, "OUT005").length, 2, "nothing refused: the trip's replies stay");
});
