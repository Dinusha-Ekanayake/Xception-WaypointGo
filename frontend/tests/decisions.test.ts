import assert from "node:assert/strict";
import test from "node:test";
import { dockHelper, latestFor, question, receiveTimes, standing, toAnswer } from "../src/shared/decisions/decisions.ts";
import type { DecisionView } from "../src/shared/domain/issues.ts";
import type { RunSheetStopView } from "../src/shared/domain/execution.ts";

// FF-09, R-ISS-12 to R-ISS-14: the words, the times and which question is open.

function decision(over: Partial<DecisionView> = {}): DecisionView {
  return {
    requestId: "r1", kind: "receive_late", depotCode: "Peliyagoda", serviceDate: "2027-03-01", tripId: "t1", vehicleId: "VEH020",
    deliveryId: "d4", outletId: "OUT004", aboutDeliveryId: null, aboutOutletId: null, aboutUnits: null,
    receiveAt: "2027-03-01T02:50:00Z", arrivalAt: null, deadline: "2027-03-01T02:20:00Z", state: "sent", answer: null,
    answeredVia: null, answeredAt: null, answeredLate: false, reason: null, voiceNoteId: null, askedAt: "2027-03-01T02:00:00Z",
    rowVersion: 1, ...over,
  };
}

function stop(sequence: number, outcome: RunSheetStopView["outcome"], tripId = "t1"): RunSheetStopView {
  return { deliveryId: `d${sequence}`, tripId, sequence, outletId: `OUT00${sequence}`, outcome } as RunSheetStopView;
}

test("every name and number in the question comes from the record", () => {
  assert.deepEqual(question(decision()), { text: "Can someone receive the delivery at about {time}?", vars: { time: "08:20" } });
  assert.deepEqual(question(decision({ kind: "dock_help", aboutOutletId: "OUT003", aboutUnits: 1 })).vars, { outlet: "OUT003", units: "1 unit" });
});

test("the store answers what is open, most urgent first; answered and withdrawn questions are not asked again", () => {
  const list = [
    decision({ requestId: "late", deadline: "2027-03-01T02:30:00Z" }),
    decision({ requestId: "soon", deadline: "2027-03-01T02:10:00Z" }),
    decision({ requestId: "missed", state: "expired" }),
    decision({ requestId: "done", state: "answered", answer: "yes", answeredAt: "2027-03-01T02:05:00Z", answeredVia: "app" }),
    decision({ requestId: "gone", state: "withdrawn" }),
  ];
  assert.deepEqual(toAnswer(list).map((d) => d.requestId), ["soon", "missed", "late"]);
  assert.equal(latestFor([decision({ requestId: "old" }), decision({ requestId: "new", askedAt: "2027-03-01T02:09:00Z" })], "d4", "receive_late")?.requestId, "new");
});

test("the dispatcher reads where a question stands, and when to phone", () => {
  const before = new Date("2027-03-01T02:10:00Z");
  assert.equal(standing(decision(), before).text, "Asked OUT004 · answer by 07:50");
  assert.equal(standing(decision(), new Date("2027-03-01T02:30:00Z")).tone, "call");
  assert.equal(standing(decision({ state: "answered", answer: "no", answeredVia: "phone", answeredAt: "2027-03-01T02:25:00Z", answeredLate: true })).text,
    "OUT004 said No by phone · 07:55 · after the deadline");
});

test("a store is asked about the expected arrival rounded up to five minutes, then 15 and 30 later, inside four hours", () => {
  const now = new Date("2027-03-01T02:00:00Z");
  assert.deepEqual(receiveTimes("2027-03-01T02:41:00Z", now).map((d) => d.toISOString()), [
    "2027-03-01T02:45:00.000Z", "2027-03-01T03:00:00.000Z", "2027-03-01T03:15:00.000Z",
  ]);
  assert.equal(receiveTimes(null, now)[0]!.toISOString(), "2027-03-01T02:05:00.000Z", "with no arrival, from five minutes on");
  assert.equal(receiveTimes("2027-03-01T05:50:00Z", now).length, 1, "nothing past four hours");
});

test("the store asked to help at the dock is the next stop of the same trip still to do", () => {
  const stops = [stop(1, "DELIVERED"), stop(2, "FAILED"), stop(3, "DELIVERED"), stop(4, "PENDING"), stop(5, "PENDING"), stop(6, "PENDING", "t2")];
  assert.equal(dockHelper(stops, stops[1]!)?.outletId, "OUT004");
  assert.equal(dockHelper(stops, stops[4]!), null, "no later stop on the trip");
});
