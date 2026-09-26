import test from "node:test";
import assert from "node:assert/strict";
import { eligibleDay, loadReference, validateRoute } from "../lib/domain.ts";
import type { Order, Vehicle, ReferenceData } from "../src/shared/domain/types.ts";
const v: Vehicle = {
  vehicle_id: "V1",
  type: "van",
  temp: "reefer",
  weight_cap_kg: 1000,
  volume_cap_m3: 5,
  fuel_type: "diesel",
  km_per_l: 10,
  weekly_fuel_quota_l: 20,
  depot: "A",
};
const order: Order = {
  id: "O1",
  outlet_id: "OUT1",
  brand: "Fresh",
  district: "Colombo",
  depot: "A",
  dock_type: "dock",
  parking_constraint: "van_only",
  mall_window: "",
  window_open_time: "04:00",
  window_close_time: "08:00",
  day: "2026-02-14",
  weight: 500,
  volume: 2,
  units: 50,
  temp: "chilled",
  status: "confirmed_order",
  version: 0,
  skips: 0,
};
const ref: ReferenceData = {
  outlets: [],
  vehicles: [v],
  calendar: [],
  district_travel: [
    {
      district: "Colombo",
      depot_to_district_freeflow_min: 40,
      inter_stop_freeflow_min: 8,
      depot_to_district_km: 20,
      inter_stop_km: 4,
    },
  ],
  service_allowance: [
    { brand: "Fresh", dock_type: "dock", service_allowance_min: 15 },
  ],
};
for (const [name, change, vehicle, code] of [
  ["weight", { weight: 1001 }, {}, "weight"],
  ["volume", { volume: 5.01 }, {}, "volume"],
  ["temperature", {}, { temp: "ambient" }, "temperature"],
  ["access", {}, { type: "truck" }, "access"],
  ["depot", {}, { depot: "B" }, "depot"],
  ["window", { window_close_time: "04:10" }, {}, "window"],
  ["mall handling", { mall_window: "04:00-04:30" }, {}, "window"],
] as const)
  test(`independent ${name} constraint is enforced`, () =>
    assert.ok(
      validateRoute(
        [{ ...order, ...change }],
        { ...v, ...vehicle },
        ref,
      ).errors.includes(code),
    ));
test("return distance consumes fuel and turnaround precedes another trip", () => {
  const r = validateRoute([order], v, ref);
  assert.equal(r.distance, 40);
  assert.equal(r.fuel, 4);
  assert.equal(r.stops[0].eta, "04:20");
  assert.equal(r.end, 345);
  assert.ok(validateRoute([order], v, ref, 210, 17).errors.includes("fuel"));
  assert.ok(
    validateRoute([order], v, ref, 210, 0, 2).errors.includes("trip_limit"),
  );
});
test("16:00 cutoff and Sunday roll to eligible operating runs", () => {
  const r = loadReference();
  assert.equal(eligibleDay("2026-02-13T15:59:00+05:30", r), "2026-02-14");
  assert.equal(eligibleDay("2026-02-13T16:00:00+05:30", r), "2026-02-16");
});
