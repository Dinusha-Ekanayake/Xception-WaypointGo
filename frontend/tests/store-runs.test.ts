import assert from "node:assert/strict";
import { test } from "node:test";
import type { DeliveryRecordView } from "../src/shared/domain/execution.ts";
import type { IssueView } from "../src/shared/domain/issues.ts";
import type { OrderView } from "../src/shared/domain/ordering.ts";
import type { ReceiptView } from "../src/shared/domain/receipt.ts";
import { countStatus, issueBehind, makeUpSteps, pastWeek, runStatus, runsOf, upcomingDays } from "../src/roles/store/data/runs.ts";

// The rows of the store's Deliveries tab (Figma "05a") and the make-up delivery's steps ("05b").

const order = (orderId: string, over: Partial<OrderView> = {}): OrderView =>
  ({ orderId, orderRef: `REF-${orderId}`, depotCode: "KDY", temperature: "ambient", itemCount: 5, status: "IN_TRANSIT", deliveryDate: "2026-10-03", redeliveryOf: null, placedAt: "2026-10-01T06:00:00Z", lines: [], ...over }) as OrderView;

const record = (orderId: string, over: Partial<DeliveryRecordView> = {}): DeliveryRecordView =>
  ({
    deliveryId: `dlv-${orderId}`, orderId, tripId: "trip-1", vehicleId: "VEH043", serviceDate: "2026-10-03", outcome: "PENDING",
    arrivedAt: null, completedAt: null, proofId: null, lines: [], stopSequence: 3, tripStopCount: 7, plannedArrival: "05:44:00",
    expectedArrival: null, releasedAt: "2026-10-02T23:00:00Z", ...over,
  }) as DeliveryRecordView;

const issue = (over: Partial<IssueView>): IssueView =>
  ({ issueId: "iss", type: "LOADING_SHORTFALL", status: "OPEN", subjects: [], description: "", resolutionAction: null, raisedAt: "2026-10-01T00:25:00Z", resolvedAt: null, ...over }) as IssueView;

test("a run is one vehicle's visit: its orders, cases, stop, and what the loader kept back", () => {
  const orders = [order("a", { temperature: "chilled", itemCount: 6 }), order("b", { itemCount: 7 })];
  const records = [record("a"), record("b"), record("c", { tripId: "trip-2", vehicleId: "VEH057", plannedArrival: "10:40:00" })];
  const short = issue({ subjects: [{ type: "order", id: "a" }], description: "SHORT at loading: 1 units. next run" });
  const [first, second] = runsOf(records, orders, [short, issue({ status: "RESOLVED", subjects: [{ type: "order", id: "b" }], description: "MISSING at loading: 4 units." })]);
  assert.equal(first!.vehicleId, "VEH043");
  assert.equal(first!.cases, 13);
  assert.equal(first!.refrigerated, true, "a chilled order rides a refrigerated vehicle");
  assert.equal(first!.short, 1, "a resolved shortfall no longer counts");
  assert.deepEqual(first!.stop, { sequence: 3, of: 7 });
  assert.equal(second!.vehicleId, "VEH057", "later arrival second");
  assert.equal(second!.refrigerated, false);
});

test("a run is on time, late, at the dock, or waiting for the store's count", () => {
  const [run] = runsOf([record("a")], [order("a")], []);
  assert.deepEqual(runStatus(run!, new Set(), "07:30:00"), { label: "On the way · on time", tone: "ok" });
  assert.equal(runStatus(run!, new Set(), "05:30:00").label, "On the way · running late");
  const [arrived] = runsOf([record("a", { arrivedAt: "2026-10-03T00:12:00Z" })], [order("a")], []);
  assert.equal(runStatus(arrived!, new Set(), "07:30:00").label, "At your dock");
  assert.equal(runStatus(arrived!, new Set(["a"]), "07:30:00").label, "Delivered · to receive");
});

test("the past week's chip is the store's count", () => {
  const receipt = (status: ReceiptView["status"], received: number | null): ReceiptView =>
    ({ status, lines: [{ productId: "Milk", expectedQuantity: 3, receivedQuantity: received }] }) as ReceiptView;
  assert.equal(countStatus([receipt("CONFIRMED", 3)]).label, "Confirmed");
  assert.deepEqual(countStatus([receipt("PARTIAL", 2), receipt("CONFIRMED", 3)]), { label: "Confirmed · 1 short", tone: "danger", short: 1, known: true });
  assert.equal(countStatus([receipt("DISPUTED", 1)]).label, "Disputed");
  assert.equal(countStatus([null]).known, false);
});

test("upcoming orders group by day; a day waits for the plan until all of it is planned", () => {
  const days = upcomingDays(
    [
      order("a", { deliveryDate: "2026-10-04", status: "CONFIRMED" }),
      order("b", { deliveryDate: "2026-10-04", status: "ALLOCATED" }),
      order("c", { deliveryDate: "2026-10-05", status: "ALLOCATED" }),
      order("d", { deliveryDate: "2026-10-05", status: "CANCELLED" }),
      order("e", { deliveryDate: "2026-10-03" }),
    ],
    "2026-10-03",
  );
  assert.deepEqual(days.map((d) => [d.date, d.orders.length, d.status.label]), [["2026-10-04", 2, "Waiting for plan"], ["2026-10-05", 1, "Planned"]]);
});

test("a make-up delivery shows the problem, the booking, then what is still to come", () => {
  const original = issue({ type: "RECEIPT_DISPUTE", subjects: [{ type: "order", id: "orig" }], status: "RESOLVED", resolutionAction: "REDELIVERY", resolvedAt: "2026-10-01T13:40:00Z" });
  const makeUp = order("m", { redeliveryOf: "orig", status: "ALLOCATED" });
  assert.equal(issueBehind(makeUp, [issue({ subjects: [{ type: "order", id: "orig" }], raisedAt: "2026-10-01T00:00:00Z" }), original]), original);
  const steps = makeUpSteps({ order: makeUp, issue: original, timeline: [], record: null, dock: "rear_dock", window: "05:00-07:30", today: "2026-10-03" });
  assert.deepEqual(
    steps.map((s) => [s.title, s.detail, s.done]),
    [
      ["Short reported", "Thu 1 Oct · 05:55", true],
      ["Make-up booked", "Thu 1 Oct · 19:10", true],
      ["Loading at depot KDY", "Today · before the run", false],
      ["Arriving at your rear dock", "Window 05:00-07:30", false],
      ["You confirm what arrived", "Count and confirm with PIN", false],
    ],
  );
  const arrived = makeUpSteps({ order: makeUp, issue: original, timeline: [], record: record("m", { arrivedAt: "2026-10-03T05:10:00Z" }), dock: "rear_dock", window: null, today: "2026-10-03" });
  assert.deepEqual(arrived.slice(2, 4).map((s) => [s.detail, s.done]), [["Today · 04:30", true], ["Arrived 10:40", true]]);
});

test("the past week skips a day that cannot be read, and says so only when none can", async () => {
  const signal = new AbortController().signal;
  const gateway = {
    deliveries: async (_outlet: string, date: string) => {
      if (date === "2026-10-01") throw new Error("down");
      return date === "2026-10-02" ? [record("a", { serviceDate: date })] : [];
    },
    receipt: async () => {
      throw new Error("no receipt");
    },
  };
  const week = await pastWeek(gateway, "OUT085", "2026-10-03", signal);
  assert.equal(week.records.length, 1);
  assert.equal(week.receipts.get("a"), null);
  const down = { deliveries: async () => Promise.reject(new Error("all down")), receipt: gateway.receipt };
  await assert.rejects(pastWeek(down, "OUT085", "2026-10-03", signal), /all down/);
});
