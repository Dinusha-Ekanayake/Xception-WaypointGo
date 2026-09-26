import { readFileSync } from "node:fs";
import path from "node:path";
import { csv, allocate } from "./domain.ts";
import { parseOrThrow, type Order, type ReferenceData } from "../src/shared/domain/types.ts";
import { OrderSchema } from "../src/shared/domain/types.ts";
import type { Service } from "./service.ts";

export interface ScenarioSpec {
  day: string;
  name: string;
  description: string;
  kind: string;
}

export const SCENARIOS: ScenarioSpec[] = [
  { day: "2026-02-14", name: "Original walkthrough", description: "The original order-to-receipt journey, plus the labelled oversized order.", kind: "historical" },
  { day: "2026-02-09", name: "Delivery outcomes", description: "Historical orders with simulated loading, road, partial delivery, failure, receipt and dispute states.", kind: "simulated" },
  { day: "2026-02-12", name: "Mixed brands & mall windows", description: "151 original orders, including 11 Style deliveries and 59 chilled orders, across both depots.", kind: "historical" },
  { day: "2026-02-13", name: "Heavy & chilled demand", description: "135 original orders: Fresh, Style and Tech, with 45 chilled orders and both depots.", kind: "historical" },
  { day: "2026-02-16", name: "Peak day · workshop shortage", description: "All 85 supplied S1 orders; only the 28 available scenario vehicles can run. Ten are in the workshop. Assigned this scenario date; validated with standard planning rules.", kind: "source scenario" },
  { day: "2026-02-17", name: "Weekly fuel pressure", description: "A copy of S1 demand with an opening fuel balance leaving 2 litres per vehicle. Other published trips in the week also consume this balance.", kind: "simulated" },
  { day: "2026-02-18", name: "Constraint boundaries", description: "Small, labelled fixtures for independent weight and volume overloads, impossible windows, chilled van access, and mall handling.", kind: "synthetic" },
];

const source = (
  file: string,
  ref: string,
  kind: "historical" | "simulated" | "synthetic" | "source scenario" = "historical",
): Order["source"] => ({ file, order_ref: ref, kind });

function fromRow(
  row: Record<string, string>,
  outlets: Record<string, Omit<Order, "id" | "day" | "weight" | "volume" | "units" | "temp" | "status" | "version" | "skips"> & Partial<Order>>,
  day: string,
  id: string,
  file: string,
): Order {
  const outlet = outlets[row.outlet_id as string];
  if (!outlet) throw new Error(`Unknown outlet ${row.outlet_id}`);
  return parseOrThrow(OrderSchema, {
    ...outlet,
    id,
    day,
    weight: Number(row.order_weight_kg),
    volume: Number(row.order_volume_m3),
    units: Number(row.order_units),
    temp: row.temp_requirement,
    status: "confirmed_order",
    version: 0,
    skips: Number(row.deferred_yesterday || 0),
    days_since_last_served: Number(row.days_since_last_served || 0),
    source: source(file, row.delivery_id || row.order_ref || id, file.includes("task2b") ? "source scenario" : "historical"),
  });
}

export function dayReference(ref: ReferenceData, day: string, enabled = true): ReferenceData {
  if (!enabled || day !== "2026-02-16") return ref;
  const availability = new Map(
    csv("Test Data/task2b_peak_day_fleet.csv").map((r) => [r.vehicle_id, r.status]),
  );
  return {
    ...ref,
    vehicles: ref.vehicles.map((v) => ({
      ...v,
      status: availability.get(v.vehicle_id) || "not_in_scenario",
    })),
  };
}

export function openingFuel(
  ref: ReferenceData,
  day: string,
  enabled = true,
): Record<string, { fuel: number; trips: number; end: number }> {
  // The simulated consumption is known as of Tuesday and persists until the
  // weekly reset. Earlier scenario days retain their own historical balance.
  return !enabled || day < "2026-02-17" || day > "2026-02-22"
    ? {}
    : Object.fromEntries(
        ref.vehicles.map((v) => [
          v.vehicle_id,
          { fuel: v.weekly_fuel_quota_l - 2, trips: 0, end: 210 },
        ]),
      );
}

// Additive, versioned seed: do not replace existing orders, plans, events or accounts.
export async function seedScenarios(s: Service): Promise<void> {
  if (process.env.DEMO_MODE === "0") return;
  (await s.transaction(async () => {
    await s.run("LOCK TABLE settings IN EXCLUSIVE MODE");
    if ((await s.get("SELECT 1 FROM settings WHERE key=$1", "scenario_seed_v2"))) return;
    const outlets = Object.fromEntries(
      s.ref.outlets.map((o) => [o.outlet_id, o]),
    );
    const historical = csv("Training Data/deliveries_train.csv");
    const peak = csv("Test Data/task2b_peak_day_scenarios.csv");
    const existingDays = new Set([
      ...(await s.orders()).map((o) => o.day),
      ...(await s.plans()).map((p) => p.day),
    ]);
    const installed = ["2026-02-14"];
    for (const spec of SCENARIOS.filter((x) => x.day !== "2026-02-14")) {
      if (existingDays.has(spec.day)) continue;
      let rows: Order[] = [];
      if (["2026-02-09", "2026-02-12", "2026-02-13"].includes(spec.day))
        rows = historical
          .filter((r) => r.order_date === spec.day)
          .map((r) =>
            fromRow(r, outlets as Parameters<typeof fromRow>[1], spec.day, r.delivery_id, "Training Data/deliveries_train.csv"),
          );
      if (["2026-02-16", "2026-02-17"].includes(spec.day))
        rows = peak.map((r) =>
          fromRow(
            r,
            outlets as Parameters<typeof fromRow>[1],
            spec.day,
            (spec.day.endsWith("16") ? "PEAK-" : "FUEL-") + r.order_ref,
            "Test Data/task2b_peak_day_scenarios.csv",
          ),
        );
      if (spec.day === "2026-02-09") {
        const base = rows.find(
          (o) => o.outlet_id === "OUT001" && o.temp === "ambient",
        )!;
        for (let i = 0; i < 8; i++)
          rows.push({
            ...base,
            id: `EXTRA-OUT001-${i + 1}`,
            units: 8,
            weight: 40,
            volume: 0.2,
            source: source("Delivery outcome fixture", base.id, "simulated"),
          });
      }
      if (spec.day === "2026-02-18") {
        const outlet001 = outlets["OUT001"];
        if (!outlet001) throw new Error("Missing outlet OUT001");
        const ambient: Order = parseOrThrow(OrderSchema, {
          ...outlet001,
          day: spec.day,
          id: "EDGE-BASE",
          units: 10,
          weight: 50,
          volume: 0.3,
          temp: "ambient",
          status: "confirmed_order",
          version: 0,
          skips: 0,
          source: source("Constraint boundary fixtures", "OUT001", "synthetic"),
        });
        const mall = s.ref.outlets.find(
          (o) => o.brand === "Style" && o.mall_window,
        )!;
        rows = [
          { ...ambient, id: "EDGE-WEIGHT", weight: Math.max(...s.ref.vehicles.map((v) => v.weight_cap_kg)) + 1 },
          { ...ambient, id: "EDGE-VOLUME", volume: Math.max(...s.ref.vehicles.map((v) => v.volume_cap_m3)) + 1 },
          { ...ambient, id: "EDGE-WINDOW", window_open_time: "03:00", window_close_time: "03:05" },
          { ...ambient, id: "EDGE-CHILLED-VAN", temp: "chilled" },
          { ...ambient, ...mall, id: "EDGE-MALL", source: source("Constraint boundary fixtures", mall.outlet_id, "synthetic") },
          { ...ambient, ...mall, id: "EDGE-MALL-HANDLING", window_open_time: "09:00", window_close_time: "09:05", mall_window: "09:00-09:05", source: source("Constraint boundary fixtures", mall.outlet_id, "synthetic") },
        ];
      }
      for (const o of rows) (await s.save(o));
      installed.push(spec.day);
    }
    (await s.run("INSERT INTO settings VALUES($1,$2) ON CONFLICT(key) DO UPDATE SET value=excluded.value", "scenario_days", JSON.stringify(installed)));
    if (installed.includes("2026-02-09")) (await seedOutcomes(s));
    (await s.run("INSERT INTO settings VALUES($1,$2)", "scenario_seed_v2", "1"));
  }));
}

async function seedOutcomes(s: Service): Promise<void> {
  const day = "2026-02-09";
  const dispatcher = { id: "dispatcher@waypoint.local", role: "dispatcher", scope: "all" } as const;
  const command = async (
    u: { id: string; role: string; scope: string },
    kind: string,
    data: Record<string, unknown> = {},
  ): Promise<unknown> =>
    (await s.apply(
      u as Parameters<Service["apply"]>[0],
      { id: `seed-${kind}-${(data.order_id as string) || day}`, kind, day, client_time: day + "T06:00:00+05:30", ...data },
    ));
  // Use the same validation/transitions as real actions. No direct invention of completed statuses.
  const rows = (await s.orders()).filter((o) => o.day === day);
  const plan = allocate(rows, s.ref, day, (await s.reservations(day)));
  for (const d of plan.deferred)
    d.justification = "No fit in this assisted plan; dispatcher to review next eligible run.";
  (await s.run("INSERT INTO plans VALUES($1,$2)", day, JSON.stringify(plan)));
  (await command(dispatcher, "publish"));
  const fresh = async (id: string): Promise<Order> =>
    JSON.parse(String((await s.get("SELECT body FROM orders WHERE id=$1", id))!.body)) as Order;
  const act = async (
    kind: "load" | "shortfall" | "depart" | "arrive" | "deliver" | "receive" | "dispute",
    id: string,
    data: Record<string, unknown> = {},
  ): Promise<unknown> => {
    const o = (await fresh(id));
    const role = { load: "loader", shortfall: "loader", depart: "driver", arrive: "driver", deliver: "driver", receive: "store", dispute: "store" }[kind];
    const scope = role === "loader" ? o.depot : role === "driver" ? o.vehicle_id : o.outlet_id;
    return (await command(
      { id: `${role}@waypoint.local`, role, scope: scope as string },
      kind,
      { order_id: id, version: o.version, ...data },
    ));
  };
  const published = (await s.plans()).find((p) => p.day === day)!;
  const assigned = published.routes.flatMap((r) => r.order_ids);
  const showcase = assigned.filter((id) => id.startsWith("SHOW-"));
  const targets = [...showcase, ...assigned.filter((id) => !id.startsWith("SHOW-"))].slice(0, 7);
  // Fully load the routes carrying these outcomes; unrelated routes remain planned.
  const chosenRoutes = new Set<string | undefined>();
  for (const id of targets) chosenRoutes.add((await fresh(id)).route_id);
  for (const r of published.routes.filter((r) => chosenRoutes.has(r.id)))
    for (const id of r.order_ids) (await act("load", id));
  const photo =
    "data:image/png;base64," +
    readFileSync(path.join(process.cwd(), "public/assets/proof-sample.png")).toString("base64");
  const signature =
    "data:image/png;base64," +
    readFileSync(path.join(process.cwd(), "public/assets/signature-sample.png")).toString("base64");
  const outcomes = ["departed", "arrived", "delivered", "partial", "failed", "confirmed", "disputed"] as const;
  for (const [i, id] of targets.entries()) {
    const goal = outcomes[i]!;
    (await act("depart", id));
    if (goal === "departed") continue;
    (await act("arrive", id));
    if (goal === "arrived") continue;
    const o = (await fresh(id));
    const outcome = goal === "partial" ? "partial" : goal === "failed" ? "failed" : "delivered";
    (await act("deliver", id, {
      outcome,
      count: outcome === "failed" ? 0 : outcome === "partial" ? Math.max(1, o.units - 1) : o.units,
      receiver: "Store receiver",
      note:
        outcome === "failed"
          ? "Outlet shutter closed, no receiving staff."
          : outcome === "partial"
            ? "One damaged case, retained on vehicle."
            : "Photo proof attached at handover.",
      photo,
      signature,
    }));
    const saved = (await fresh(id));
    saved.proof = { ...(saved.proof as object), demo: true } as typeof saved.proof;
    (await s.save(saved));
    if (goal === "confirmed") (await act("receive", id));
    if (goal === "disputed")
      (await act("dispute", id, { note: "Receipt discrepancy: store counted one fewer case." }));
  }
  const untouched = (await s.orders()).filter((o) => o.day === day && o.status === "planned");
  if (untouched[0])
    (await act("shortfall", untouched[0]!.id, { count: 1, note: "Damaged carton found during loading." }));
  if (untouched[1]) (await act("load", untouched[1]!.id));
}
