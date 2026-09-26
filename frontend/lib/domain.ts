import { readFileSync, existsSync } from "node:fs";
import path from "node:path";
import { parse } from "csv-parse/sync";
import {
  CalendarCsvRowSchema,
  DistrictTravelSchema,
  OutletSchema,
  ServiceAllowanceSchema,
  VehicleSchema,
  parseOrThrow,
  type CalendarRow,
  type Day,
  type DistrictTravel,
  type Order,
  type Outlet,
  type PlannedRoute,
  type ReferenceData,
  type Reservations,
  type RouteErrorCode,
  type ValidateRouteResult,
  type Vehicle,
  REASONS,
} from "../src/shared/domain/types.ts";

export { REASONS };
export type { ReferenceData, Order, Vehicle, PlannedRoute };

// Shared dataset directory: `data/` next to the frontend when run from the
// repo root, or one level up when run from `frontend/`.
export const DATA = existsSync(path.join(process.cwd(), "data"))
  ? path.join(process.cwd(), "data")
  : path.join(process.cwd(), "..", "data");

/** Read a CSV file from the dataset directory (raw string records). */
export function csv(file: string): Record<string, string>[] {
  return parse(readFileSync(path.join(DATA, file), "utf8"), {
    columns: true,
    skip_empty_lines: true,
  }) as Record<string, string>[];
}

/** Load + runtime-validate the General Data reference tables. */
export function loadReference(): ReferenceData {
  const outlets: Outlet[] = csv("General Data/outlets.csv").map((row, i) =>
    parseOrThrow(OutletSchema, { ...row, mall_window: row.mall_window ?? "" }),
  );
  void outlets[0]; // keep array type narrow; index unused
  const vehicles: Vehicle[] = csv("General Data/vehicles.csv").map((row) =>
    parseOrThrow(VehicleSchema, row),
  );
  const calendar: CalendarRow[] = csv("General Data/calendar.csv").map((row) =>
    parseOrThrow(CalendarCsvRowSchema, row),
  );
  const district_travel: DistrictTravel[] = csv(
    "General Data/district_travel.csv",
  ).map((row) => parseOrThrow(DistrictTravelSchema, row));
  const service_allowance = csv("General Data/service_allowance.csv").map(
    (row) => parseOrThrow(ServiceAllowanceSchema, row),
  );
  void vehicles;
  return { outlets, vehicles, calendar, district_travel, service_allowance };
}

export const minutes = (s: string): number => {
  const [h, m] = s.split(":").map(Number);
  return (h ?? 0) * 60 + (m ?? 0);
};

export const clock = (n: number): string =>
  `${String(Math.floor(n / 60)).padStart(2, "0")}:${String(
    Math.floor(n % 60),
  ).padStart(2, "0")}`;

export function operating(day: string, ref: ReferenceData): boolean {
  const r = ref.calendar.find((r) => r.date === day);
  if (!r)
    throw Error(
      "Date is outside the supplied operating calendar (2024-01-01 to 2026-06-28).",
    );
  return r.is_operating === "1";
}

export function nextOperating(day: string, ref: ReferenceData): string {
  const d = new Date(day + "T00:00:00Z");
  for (let i = 0; i < 14; i++) {
    d.setUTCDate(d.getUTCDate() + 1);
    const s = d.toISOString().slice(0, 10);
    if (operating(s, ref)) return s;
  }
  throw Error("No eligible operating day found.");
}

export function eligibleDay(timestamp: string, ref: ReferenceData): string {
  const local = new Date(new Date(timestamp).getTime() + 330 * 60000);
  const first = nextOperating(local.toISOString().slice(0, 10), ref);
  return local.getUTCHours() >= 16 ? nextOperating(first, ref) : first;
}

export function validateRoute(
  orders: Order[],
  v: Vehicle,
  ref: ReferenceData,
  start = 210,
  fuelUsed = 0,
  tripsUsed = 0,
): ValidateRouteResult {
  const errors: RouteErrorCode[] = [];
  if (v.status && v.status !== "available") errors.push("unavailable");
  if (!orders.length)
    return {
      errors: ["empty"],
      stops: [],
      fuel: 0,
      start,
      end: start,
      distance: 0,
    };
  if (tripsUsed >= 2) errors.push("trip_limit");
  if (orders.reduce((n, o) => n + o.weight, 0) > v.weight_cap_kg + 1e-8)
    errors.push("weight");
  if (orders.reduce((n, o) => n + o.volume, 0) > v.volume_cap_m3 + 1e-8)
    errors.push("volume");
  if (new Set(orders.map((o) => o.brand + ":" + o.district)).size > 1)
    errors.push("group");
  if (orders.some((o) => o.depot !== v.depot)) errors.push("depot");
  if (orders.some((o) => o.temp === "chilled") && v.temp !== "reefer")
    errors.push("temperature");
  if (
    orders.some((o) => o.parking_constraint === "van_only") &&
    v.type !== "van"
  )
    errors.push("access");
  const t = ref.district_travel.find((t) => t.district === orders[0]!.district);
  if (!t) {
    errors.push("window");
    return {
      errors: [...new Set(errors)],
      stops: [],
      fuel: 0,
      distance: 0,
      start,
      end: start,
    };
  }
  const outbound = Number(t.depot_to_district_freeflow_min) * 1.25;
  const between = Number(t.inter_stop_freeflow_min) * 1.25;
  let now = start + outbound;
  const stops: ValidateRouteResult["stops"] = [];
  for (const [i, o] of orders.entries()) {
    if (i) now += between;
    let opening = minutes(o.window_open_time);
    let closing = minutes(o.window_close_time);
    if (o.brand === "Fresh") closing = Math.min(closing, 480);
    if (o.mall_window) {
      const [a, b] = o.mall_window.split("-") as [string, string];
      opening = Math.max(opening, minutes(a));
      closing = Math.min(closing, minutes(b));
    }
    const arrival = Math.max(now, opening);
    const allowance = ref.service_allowance.find(
      (s) => s.brand === o.brand && s.dock_type === o.dock_type,
    );
    const service = Number(allowance?.service_allowance_min ?? 15);
    if (arrival > closing || (o.mall_window && arrival + service > closing))
      errors.push("window");
    stops.push({
      order_id: o.id,
      arrival: Math.round(arrival),
      eta: clock(arrival),
      service,
      sequence: i + 1,
    });
    now = arrival + service;
  }
  const distance =
    2 * Number(t.depot_to_district_km) +
    (orders.length - 1) * Number(t.inter_stop_km);
  const fuel = distance / v.km_per_l;
  if (fuel + fuelUsed > v.weekly_fuel_quota_l + 1e-8) errors.push("fuel");
  return {
    errors: [...new Set(errors)],
    stops,
    fuel: Math.ceil(fuel * 1e6) / 1e6,
    distance,
    start,
    end: Math.ceil(now + outbound + 20),
  };
}

interface MutableRoute extends ValidateRouteResult {
  id: string;
  vehicle_id: string;
  orders: Order[];
}

export function allocate(
  orders: Order[],
  ref: ReferenceData,
  day: Day,
  reservations: Reservations = {},
): {
  day: string;
  routes: PlannedRoute[];
  deferred: PlannedRoute extends never
    ? never
    : import("../src/shared/domain/types").DeferredEntry[];
  published: boolean;
  revision: number;
} {
  if (!operating(day, ref))
    throw Error("Planning is unavailable on a non-operating date.");
  const routes: MutableRoute[] = [];
  const deferred: import("../src/shared/domain/types").DeferredEntry[] = [];
  const vehicles = ref.vehicles
    .filter((v) => !v.status || v.status === "available")
    .sort(
      (a, b) =>
        Number(a.type === "van") - Number(b.type === "van") ||
        Number(a.temp === "reefer") - Number(b.temp === "reefer") ||
        a.vehicle_id.localeCompare(b.vehicle_id),
    );
  for (const o of [...orders].sort(
    (a, b) =>
      (b.skips || 0) - (a.skips || 0) ||
      Number(a.brand !== "Fresh") - Number(b.brand !== "Fresh") ||
      Number(a.temp !== "chilled") - Number(b.temp !== "chilled") ||
      a.window_close_time.localeCompare(b.window_close_time) ||
      a.id.localeCompare(b.id),
  )) {
    let assigned = false;
    const failures = new Set<RouteErrorCode>();
    for (const route of routes) {
      const v = vehicles.find((v) => v.vehicle_id === route.vehicle_id)!;
      const others = routes.filter(
        (r) => r.vehicle_id === v.vehicle_id && r !== route,
      );
      if (others.some((r) => r.start > route.start)) continue;
      const result = validateRoute(
        [...route.orders, o],
        v,
        ref,
        route.start,
        (reservations[v.vehicle_id]?.fuel || 0) +
          others.reduce((n, r) => n + r.fuel, 0),
        others.length,
      );
      if (!result.errors.length) {
        Object.assign(route, result);
        route.orders.push(o);
        assigned = true;
        break;
      }
    }
    if (!assigned)
      for (const v of vehicles) {
        const existing = routes.filter((r) => r.vehicle_id === v.vehicle_id);
        const base = reservations[v.vehicle_id] || {
          fuel: 0,
          trips: 0,
          end: 210,
        };
        const result = validateRoute(
          [o],
          v,
          ref,
          Math.max(210, base.end || 210, ...existing.map((r) => r.end)),
          (base.fuel || 0) + existing.reduce((n, r) => n + r.fuel, 0),
          existing.length + (base.trips || 0),
        );
        if (result.errors.length) {
          result.errors.forEach((e) => failures.add(e));
          continue;
        }
        routes.push({
          id: `RUN-${String(routes.length + 1).padStart(3, "0")}`,
          vehicle_id: v.vehicle_id,
          orders: [o],
          ...result,
        });
        assigned = true;
        break;
      }
    if (!assigned)
      deferred.push({
        order_id: o.id,
        reason:
          "No fit in current plan: " +
          [...failures]
            .filter((k) => !["group", "depot"].includes(k))
            .map((k) => REASONS[k])
            .join(", "),
        next_date: nextOperating(day, ref) as Day,
        repeat: (o.skips ?? 0) > 0,
        justification: "",
      });
  }
  return {
    day,
    revision: 0,
    routes: routes.map(({ orders, ...r }) => ({
      ...r,
      order_ids: orders.map((o) => o.id),
    })),
    deferred,
    published: false,
  };
}
