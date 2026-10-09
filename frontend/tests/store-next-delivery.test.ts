import assert from "node:assert/strict";
import test from "node:test";
import type { OrderView } from "../src/shared/domain/ordering.ts";
import { aheadLabel, nextDelivery } from "../src/roles/store/data/nextDelivery.ts";

// Deadline-day UX plan U3: Home's Next delivery card never says "nothing" while a delivery is planned.

const order = (ref: string, deliveryDate: string, status: OrderView["status"]) => ({ orderId: ref, orderRef: ref, deliveryDate, status }) as OrderView;

test("today's delivery on the way still comes first", () => {
  const next = nextDelivery([order("A", "2026-10-05", "ALLOCATED"), order("B", "2026-10-04", "IN_TRANSIT")], "2026-10-04");
  assert.equal(next?.order.orderRef, "B");
  assert.equal(next?.when, "today");
});

test("with nothing today, the next planned delivery is shown, with how many come that day", () => {
  const next = nextDelivery(
    [order("C", "2026-10-07", "CONFIRMED"), order("B", "2026-10-05", "ALLOCATED"), order("A", "2026-10-05", "ALLOCATED"), order("X", "2026-10-05", "CANCELLED")],
    "2026-10-04",
  );
  assert.deepEqual([next?.order.orderRef, next?.when, next?.position, next?.ofDay], ["A", "ahead", 1, 2]);
});

test("a delivered or cancelled order ahead is not a next delivery; nothing at all is null", () => {
  assert.equal(nextDelivery([order("A", "2026-10-05", "CANCELLED")], "2026-10-04"), null);
  assert.equal(nextDelivery([], "2026-10-04"), null);
});

test("the chip says where the delivery stands", () => {
  assert.equal(aheadLabel("ALLOCATED"), "Planned");
  assert.equal(aheadLabel("CONFIRMED"), "Waiting for the plan");
  assert.equal(aheadLabel("LOADING"), "Loading");
});

// Roadmap T1-04, MF-06: how long to hold someone at the dock, as a range when the prediction has one.
test("the unloading time is a range when there is one, else the minutes alone, else nothing", async () => {
  const { unloadingLabel } = await import("../src/roles/store/data/format.ts");
  assert.deepEqual(unloadingLabel({ expectedServiceMinutes: "18.4", expectedServiceLow: "14.6", expectedServiceHigh: "24.2" }),
    { key: "unloading {low} to {high} min", values: { low: 15, high: 24 } });
  assert.deepEqual(unloadingLabel({ expectedServiceMinutes: 18, expectedServiceLow: null, expectedServiceHigh: null }),
    { key: "unloading about {n} min", values: { n: 18 } });
  assert.deepEqual(unloadingLabel({ expectedServiceMinutes: 18, expectedServiceLow: 18.2, expectedServiceHigh: 18.4 }),
    { key: "unloading about {n} min", values: { n: 18 } }, "a range that rounds to one number reads as the minutes");
  assert.equal(unloadingLabel({ expectedServiceMinutes: null }), null);
  assert.equal(unloadingLabel({}), null);
});
