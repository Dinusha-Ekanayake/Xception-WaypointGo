import assert from "node:assert/strict";
import test from "node:test";
import { replies } from "../src/shared/messaging/templates.ts";
import type { RunSheetStopView } from "../src/shared/domain/execution.ts";
import type { DecisionView } from "../src/shared/domain/issues.ts";

// FF-08 first version: which ready replies a trip offers, every value from code.

const DAY = "2027-03-01";
function stop(sequence: number, over: Partial<RunSheetStopView> = {}): RunSheetStopView {
  return { deliveryId: `d${sequence}`, tripId: "t1", sequence, outletId: `OUT00${sequence}`, outcome: "PENDING",
    plannedArrival: "07:00:00", expectedArrival: null, ...over } as RunSheetStopView;
}
const yes = (over: Partial<DecisionView>): DecisionView => ({ requestId: "r", kind: "receive_late", outletId: "OUT004", answer: "yes",
  answeredAt: "2027-03-01T01:40:00Z", receiveAt: "2027-03-01T02:50:00Z", aboutOutletId: null, ...over } as DecisionView);

test("the next store hears its new time; the channel hears the delay without any store's name", () => {
  const late = replies({ stops: [stop(1, { outcome: "DELIVERED" }), stop(2, { expectedArrival: "2027-03-01T01:55:00Z" })], decisions: [], channelOpen: true, serviceDate: DAY });
  assert.equal(late[0]!.body, "Running 25 min late. Now expected at 07:25.");
  assert.equal(late[0]!.to, "outlet");
  const channel = late.find((r) => r.to === "channel")!;
  assert.doesNotMatch(channel.body, /OUT\d+|order/i, "nothing about one store reaches the others (R-MSG-08)");
  const onTime = replies({ stops: [stop(2)], decisions: [], channelOpen: true, serviceDate: DAY });
  assert.equal(onTime[0]!.body, "On the way. Expected at 07:00.");
  assert.equal(onTime.some((r) => r.to === "channel"), false, "no delay, nothing for every store");
});

test("a store's Yes and a stop not delivered become replies to the driver, filled from the answers", () => {
  const r = replies({
    stops: [stop(3, { outcome: "FAILED" }), stop(4)],
    decisions: [yes({}), yes({ kind: "dock_help", outletId: "OUT004", aboutOutletId: "OUT003", receiveAt: null })],
    channelOpen: false, serviceDate: DAY,
  });
  assert.deepEqual(r.map((x) => x.id), ["driver.store_will_receive", "driver.dock_help", "driver.bring_back", "store.redelivery"]);
  assert.equal(r[0]!.body, "OUT004 will receive at about 08:20. Keep the stop order.");
  assert.equal(r[1]!.body, "OUT004 agreed: unload the goods for OUT003 at their dock to reach theirs, then reload them.");
  assert.equal(r[3]!.outletId, "OUT003");
});
