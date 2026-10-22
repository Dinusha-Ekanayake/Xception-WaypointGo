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

test("a driver's breakdown in their own words asks how long and tells the stores waiting; the dispatcher's own words get nothing", () => {
  assert.deepEqual(ids(dispatcherAnswers({ mine: false, authorRole: "driver", body: "lorry eka break down una", aboutOutletId: null, understood: "BREAKDOWN" })), ["answer.how_long", "answer.tell_waiting"]);
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

test("an off-topic message changes nothing: the chips keep answering the last real word", async () => {
  const { newestToAnswer } = await import("../src/shared/messaging/templates.ts");
  const real = store(null, "ba, ada ganna ba. heta enna");
  const junk = store("OFF_TOPIC", "<script>alert(1)</script>");
  assert.equal(newestToAnswer([real, junk, junk]), real);
  assert.equal(newestToAnswer([junk]), null);
  assert.deepEqual(ids(dispatcherAnswers(newestToAnswer([real, junk]))), ["answer.next_day", "answer.driver_skip"]);
});

test("a store that said No in the chat after its Yes loses its \"will receive\" chip; a Yes tapped after stands (R-ML-45)", async () => {
  const { refusedSinceYes } = await import("../src/shared/messaging/templates.ts");
  const yes = { outletId: "OUT005", answer: "yes" as const, answeredAt: "2027-03-01T10:00:00Z" };
  const msg = (body: string, at: string, understood: string | null = null) =>
    ({ mine: false, authorRole: "store_manager" as const, aboutOutletId: "OUT005", understood, body, createdAt: at });
  assert.deepEqual(refusedSinceYes([msg("nope", "2027-03-01T10:05:00Z", "SAYS_NO"), msg("can the driver bring crates?", "2027-03-01T10:06:00Z")], [yes]), ["OUT005"]);
  assert.deepEqual(refusedSinceYes([msg("no", "2027-03-01T09:55:00Z", "SAYS_NO")], [yes]), [], "said before the Yes");
  assert.deepEqual(refusedSinceYes([msg("no", "2027-03-01T10:05:00Z", "SAYS_NO"), msg("ok fine, come", "2027-03-01T10:07:00Z", "SAYS_YES")], [yes]), [], "changed back");
  assert.deepEqual(refusedSinceYes([msg("ba, heta enna", "2027-03-01T10:05:00Z")], [yes]), ["OUT005"], "plain words when Jev was not sure");
  const state: Reply[] = [{ id: "driver.store_will_receive", version: 1, label: "Tell the driver OUT005 will receive", to: "driver", outletId: null, body: "..." }];
  assert.deepEqual(dispatcherChips([], state, null, ["OUT005"]), []);
  assert.deepEqual(ids(dispatcherChips([], state, null, [])), ["driver.store_will_receive"]);
});

test("a driver's report gets chips by its type: a blocked road asks how long and tells the stores waiting, naming none", () => {
  const report = (reportType: string) => ({ mine: false, authorRole: "driver" as const, body: "road disruption in Colombo: Road blocked",
    aboutOutletId: null, understood: null, reportType: reportType as never });
  const road = dispatcherAnswers(report("road_disruption"));
  assert.deepEqual(road.map((r) => [r.id, r.to]), [["answer.how_long", "driver"], ["answer.tell_waiting", "channel"]]);
  assert.doesNotMatch(road[1]!.body, /OUT\d+/, "nothing about one store reaches the others (R-MSG-08)");
  assert.deepEqual(ids(dispatcherAnswers(report("vehicle_fault"))), ["answer.how_long", "answer.tell_waiting"]);
  assert.deepEqual(ids(dispatcherAnswers(report("store_closed"))), ["answer.wait_there"]);
  assert.deepEqual(ids(dispatcherAnswers(report("other"))), [], "a report sent as other is read by its words, or by Jev");
});
