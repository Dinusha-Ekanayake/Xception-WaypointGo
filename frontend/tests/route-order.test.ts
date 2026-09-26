import test from "node:test";
import assert from "node:assert/strict";
import { sortDeliveryStops } from "../lib/route-order.ts";
import type { Order, PlannedRoute } from "../lib/types.ts";

test("driver follows planned departure and stop sequence regardless of random trip IDs", () => {
  const orders = [
    { id: "later", route_id: "RUN-1000", sequence: 1 },
    { id: "second", route_id: "RUN-f000", sequence: 2 },
    { id: "first", route_id: "RUN-f000", sequence: 1 },
  ] as Order[];
  const routes = [
    { id: "RUN-1000", start: 480 },
    { id: "RUN-f000", start: 210 },
  ] as PlannedRoute[];
  assert.deepEqual(sortDeliveryStops(orders, routes).map(o => o.id), ["first", "second", "later"]);
});
