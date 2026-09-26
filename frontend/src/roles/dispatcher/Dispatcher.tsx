"use client";

import { useState } from "react";
import {
  Badge,
  Brand,
  Btn,
  Empty,
  Modal,
  NoteForm,
  fmtDayLabel,
  inputClass,
  STATUS_LABELS,
} from "@shared/ui/components";
import AssignmentReview from "./AssignmentReview";
import PublishReview from "./PublishReview";
import ExceptionReview from "./ExceptionReview";
import type {
  ActFn,
  AppState,
  Order,
  PlannedRoute,
  Plan,
  Vehicle,
} from "@shared/domain/types";

export type DispatcherTab = "overview" | "orders" | "plan" | "live" | "capacity";
export interface DispatcherProps {
  state: AppState;
  act: ActFn;
  busy: boolean;
  onDetail: (o: Order) => void;
  tab: string;
}
const terminal = ["delivered", "partial", "failed", "confirmed", "disputed", "resolved"];
const time = (n: number) =>
  `${String(Math.floor(n / 60)).padStart(2, "0")}:${String(Math.floor(n % 60)).padStart(2, "0")}`;
const minutes = (s: string) => {
  const [h, m] = s.split(":").map(Number);
  return h * 60 + m;
};
const week = (day: string) => {
  const d = new Date(day + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
};
function go(tab: string) {
  window.dispatchEvent(new CustomEvent("dtab", { detail: tab }));
}
function downloadCsv(filename: string, rows: (string | number)[][]): void {
  const csv = rows
    .map((r) =>
      r.map((c) => `"${String(c).replaceAll('"', '""')}"`).join(","),
    )
    .join("\n");
  const blob = new Blob([csv], { type: "text/csv" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
function Metric({
  label,
  value,
  note,
}: {
  label: string;
  value: string | number;
  note: string;
}) {
  return (
    <div className="border-r border-line px-5 py-4 last:border-0">
      <dt className="text-copy text-muted">{label}</dt>
      <dd className="m-0 mt-1 text-3xl font-semibold tracking-tight">
        {value}
      </dd>
      <p className="m-0 mt-1 text-caption text-muted">{note}</p>
    </div>
  );
}
function LoadBar({
  label,
  value,
  max,
}: {
  label: string;
  value: number;
  max: number;
}) {
  const ratio = max ? value / max : 0;
  return (
    <div>
      <div className="flex justify-between gap-2 text-caption">
        <span>{label}</span>
        <span className="font-mono">{Math.round(ratio * 100)}%</span>
      </div>
      <div className="mt-1 h-1.5 rounded bg-track">
        <div
          className={`h-full rounded ${ratio > 0.9 ? "bg-warn" : "bg-accent"}`}
          style={{ width: `${Math.min(100, ratio * 100)}%` }}
        />
      </div>
    </div>
  );
}

function RouteCard({
  route,
  vehicle,
  rows,
  published,
  busy,
  onMove,
  onDetail,
}: {
  route: PlannedRoute;
  vehicle: Vehicle;
  rows: Record<string, Order>;
  published: boolean;
  busy: boolean;
  onMove: (o: Order) => void;
  onDetail: (o: Order) => void;
}) {
  const orders = route.order_ids.map((id) => rows[id]).filter(Boolean);
  const weight = orders.reduce((n, o) => n + o.weight, 0);
  const volume = orders.reduce((n, o) => n + o.volume, 0);
  const tripStatus = orders.some((o) => o.status === "shortfall")
    ? "On hold"
    : orders.length &&
        orders.every((o) => terminal.includes(o.status as (typeof terminal)[number]))
      ? "Completed"
      : orders.some((o) =>
          ["departed", "arrived", "delivered", "loaded"].includes(o.status),
        )
        ? "On the road"
        : published
          ? "Published"
          : "Draft";
  return (
    <details
      className="group rounded-xl border border-line bg-white"
      data-testid={`route-${route.id}`}
    >
      <summary className="grid cursor-pointer list-none gap-4 p-4 lg:grid-cols-[minmax(180px,1fr)_140px_140px_110px]">
        <div>
          <div className="flex items-center gap-2">
            <strong className="font-mono text-copy">{vehicle.vehicle_id}</strong>
            <span className="text-caption text-muted">
              {vehicle.temp === "reefer" ? "Refrigerated" : "Ambient"}
            </span>
            <span className="text-caption text-muted">· {tripStatus}</span>
          </div>
          <p className="my-1 text-copy">
            {orders[0]?.brand} · {orders[0]?.district} · {orders.length} orders
          </p>
          <span className="text-caption text-muted">
            {route.id} · {time(route.start)} departure · Manifest v1
          </span>
        </div>
        <div className="self-center">
          <LoadBar
            label={`${weight.toFixed(0)} kg`}
            value={weight}
            max={vehicle.weight_cap_kg}
          />
        </div>
        <div className="self-center">
          <LoadBar
            label={`${volume.toFixed(1)} m³`}
            value={volume}
            max={vehicle.volume_cap_m3}
          />
        </div>
        <div className="self-center text-right text-caption">
          <div className="font-mono">{route.distance.toFixed(0)} km</div>
          <div className="mt-1">{route.fuel.toFixed(1)} L</div>
          <div className="mt-2 font-medium text-accent group-open:hidden">
            View stops ↓
          </div>
        </div>
      </summary>
      <div className="border-t border-line p-4">
        <p className="mt-0 text-caption text-muted">
          Planned times include a 25% travel buffer, receiving waits, handling,
          return travel and 20-minute turnaround. Available again{" "}
          {time(route.end)}.
        </p>
        <ol className="m-0 list-none p-0">
          {route.stops.map((stop, i) => {
            const o = rows[stop.order_id];
            if (!o) return null;
            const close = Math.min(
              minutes(o.window_close_time),
              o.brand === "Fresh" ? 480 : 1440,
            );
            const margin = Math.floor(
              close - stop.arrival - (o.mall_window ? stop.service : 0),
            );
            return (
              <li
                key={stop.order_id}
                className="flex flex-wrap items-center gap-3 border-t border-line py-3 first:border-0"
              >
                <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-accentbg text-caption font-semibold text-accent">
                  {i + 1}
                </span>
                <div className="min-w-0 flex-1">
                  <button
                    onClick={() => onDetail(o)}
                    className="text-left text-copy font-semibold underline decoration-line underline-offset-4"
                  >
                    {o.outlet_id} · {o.id}
                  </button>
                  <p className="my-1 text-caption text-muted">
                    {o.units} cases · {o.temp} · {o.window_open_time}-
                    {o.window_close_time}
                    {o.parking_constraint === "van_only" ? " · Van only" : ""}
                  </p>
                  {published && <Badge status={o.status} />}
                </div>
                <div className="text-right">
                  <strong className="font-mono text-copy">{stop.eta}</strong>
                  <p className="my-1 text-caption text-muted">
                    {stop.service} min handling
                  </p>
                  <span
                    className={`text-caption ${margin < 20 ? "text-warn" : "text-ok"}`}
                  >
                    {margin} min window margin
                  </span>
                </div>
                {!published && (
                  <Btn size="sm" disabled={busy} onClick={() => onMove(o)}>
                    Move
                  </Btn>
                )}
              </li>
            );
          })}
        </ol>
      </div>
    </details>
  );
}

export default function Dispatcher({
  state,
  act,
  busy,
  onDetail,
  tab,
}: DispatcherProps) {
  const view =
    (
      {
        allocation: "plan",
        progress: "live",
        exceptions: "live",
        fleet: "capacity",
      } as Record<string, string>
    )[tab] ||
    tab ||
    "plan";
  const [day, setDay] = useState(state.demo ? "2026-02-14" : new Date(state.now).toLocaleDateString("en-CA", { timeZone: "Asia/Colombo" }));
  const [depot, setDepot] = useState("All depots");
  const [query, setQuery] = useState("");
  const [move, setMove] = useState<Order | null>(null);
  const [publication, setPublication] = useState<{ plan: Plan; orders: Order[] } | null>(null);
  const [guide, setGuide] = useState(false);
  const [exception, setException] = useState<Order | null>(null);
  const [note, setNote] = useState<{
    kind: "resolve" | "defer_note";
    order: Order;
  } | null>(null);
  const plan = state.plans.find((p) => p.day === day);
  const published = !!plan?.published;
  const current = Object.fromEntries(state.orders.map((o) => [o.id, o]));
  // Published decisions retain their original demand snapshot even after carryover.
  const dayOrders =
    plan?.published && plan.orders
      ? plan.orders.map((o) => (current[o.id]?.day === day ? current[o.id] : o))
      : state.orders.filter((o) => o.day === day);
  const byId = Object.fromEntries(dayOrders.map((o) => [o.id, o]));
  const inDepot = (o: { depot: string }) =>
    depot === "All depots" || o.depot === depot;
  const orders = dayOrders.filter(inDepot);
  const reference = state.planning?.[day];
  const vehicles = (reference?.vehicles || state.vehicles).filter(inDepot);
  const vehicleMap = Object.fromEntries(
    state.vehicles.map((v) => [v.vehicle_id, v]),
  );
  const routes = (plan?.routes || []).filter(
    (r) => vehicleMap[r.vehicle_id] && inDepot(vehicleMap[r.vehicle_id]),
  );
  const deferred = (plan?.deferred || [])
    .filter((d) => byId[d.order_id] && inDepot(byId[d.order_id]))
    .sort((a, b) => Number(b.repeat) - Number(a.repeat));
  const notesRequired = (plan?.deferred || []).filter(
    (d) => d.repeat && !d.justification,
  ).length;
  const assigned = routes.reduce((n, r) => n + r.order_ids.length, 0);
  const exceptions = state.orders.filter(
    (o) =>
      o.day === day &&
      inDepot(o) &&
      (["shortfall", "partial", "failed", "disputed"].includes(o.status) || (o.status === "confirmed" && o.proof?.outcome === "partial")),
  );
  const days = [
    ...new Set([
      ...state.orders.map((o) => o.day),
      ...state.plans.map((p) => p.day),
      day,
    ]),
  ].sort();
  const filtered = orders.filter((o) =>
    `${o.id} ${o.outlet_id} ${o.brand} ${o.district}`
      .toLowerCase()
      .includes(query.toLowerCase()),
  );
  const dailyFuel = routes.reduce((n, r) => n + r.fuel, 0);
  const weeklyFuel = (v: Vehicle) =>
    (reference?.reservations[v.vehicle_id]?.fuel ??
      state.plans
        .filter(
          (p) => p.published && p.day !== day && week(p.day) === week(day),
        )
        .flatMap((p) => p.routes)
        .filter((r) => r.vehicle_id === v.vehicle_id)
        .reduce((n, r) => n + r.fuel, 0)) +
    (plan?.routes
      .filter((r) => r.vehicle_id === v.vehicle_id)
      .reduce((n, r) => n + r.fuel, 0) || 0);
  const fleetFuel = vehicles.reduce((n, v) => n + weeklyFuel(v), 0);
  const fleetQuota = vehicles.reduce((n, v) => n + v.weekly_fuel_quota_l, 0);
  const action = (
    kind: Parameters<ActFn>[0],
    data: Record<string, unknown> = {},
  ) => act(kind, { day, revision: plan?.revision || 0, ...data });
  const dateLabel = fmtDayLabel(day);
  return (
    <main className="mx-auto max-w-[1500px] px-4 pb-12 md:px-8">
      <header className="flex flex-wrap items-center justify-between gap-4 border-b border-line py-5">
        <div>
          <p className="m-0 text-copy text-muted">Waypoint / Dispatch</p>
          <h1 className="m-0 mt-1 text-2xl font-semibold">
            {view === "overview"
              ? "Operations overview"
              : view === "plan"
                ? "Make every delivery count"
                : view === "orders"
                  ? "Confirmed demand"
                  : view === "live"
                    ? "Delivery progress"
                    : "Fleet availability"}
          </h1>
        </div>
        <nav
          aria-label="Dispatcher"
          className="flex flex-wrap gap-1 rounded-full bg-white p-1"
        >
          {[
            ["overview", "Overview", "Operations overview"],
            ["orders", "Orders", "Order queue"],
            ["plan", "Plan", "Allocation board"],
            ["live", "Progress", "Live runs"],
            ["capacity", "Fleet", "Fleet and capacity"],
          ].map(([key, label, aria]) => (
            <button
              key={key}
              aria-label={aria}
              aria-current={view === key ? "page" : undefined}
              onClick={() => go(key)}
              className={`min-h-11 rounded-full px-5 text-copy font-semibold ${view === key ? "bg-accent text-white" : "text-muted hover:bg-accentbg"}`}
            >
              {label}
              {key === "live" && exceptions.length > 0
                ? ` (${exceptions.length})`
                : ""}
            </button>
          ))}
        </nav>
      </header>
      <div className="flex flex-wrap items-end gap-4 py-5">
        <label className="text-caption text-muted">
          Delivery date
          <select
            aria-label="Run day"
            value={day}
            onChange={(e) => {
              setDay(e.target.value);
              setMove(null);
            }}
            className={`${inputClass} mt-1 block`}
          >
            {days.map((d) => (
              <option key={d} value={d}>
                {fmtDayLabel(d)} · {d}
              </option>
            ))}
          </select>
        </label>
        <label className="text-caption text-muted">
          Depot
          <select
            aria-label="Depot"
            value={depot}
            onChange={(e) => setDepot(e.target.value)}
            className={`${inputClass} mt-1 block`}
          >
            {["All depots", "Peliyagoda", "Kandy"].map((d) => (
              <option key={d}>{d}</option>
            ))}
          </select>
        </label>
        <div className="ml-auto text-right text-copy">
          <span
            className={`rounded-full px-3 py-1 ${published ? "bg-accentbg text-accent" : "bg-white text-muted"}`}
          >
            {published
              ? "Published · locked"
              : plan
                ? `Draft ${plan.revision}`
                : "Not planned"}
          </span>
          <p className="mb-0 mt-2 text-caption text-muted">
            {state.demo ? "Scenario clock" : "Order clock"} ·{" "}
            {new Date(state.now).toLocaleString("en-GB", {
              timeZone: "Asia/Colombo",
            })}
          </p>
        </div>
      </div>
      {state.demo && !!state.scenarios?.length && (
        <details className="mb-5 rounded-xl border border-line bg-white px-4 py-3">
          <summary className="cursor-pointer text-copy font-medium">
            Judge scenarios and operating assumptions
          </summary>
          <p className="text-copy text-muted">
            Synthetic competition records. Historical days use original order
            IDs. Added outcomes and stress cases are labelled. ETAs are plans,
            not live tracking.
          </p>
          <div className="grid gap-2 md:grid-cols-3">
            {state.scenarios.map((s) => (
              <button
                key={s.day}
                onClick={() => setDay(s.day)}
                className={`rounded-lg border p-3 text-left ${s.day === day ? "border-accent bg-accentbg" : "border-line"}`}
              >
                <strong className="text-copy">{s.name}</strong>
                <p className="my-1 text-caption text-muted">
                  {s.day} · {s.kind}
                </p>
                <span className="text-caption text-muted">{s.description}</span>
              </button>
            ))}
          </div>
        </details>
      )}
      {(view === "plan" || view === "orders") && (
        <>
          <dl className="m-0 mb-5 grid grid-cols-2 overflow-hidden rounded-xl border border-line bg-white lg:grid-cols-4">
            <Metric
              label="Orders to serve"
              value={orders.length}
              note={`${orders.reduce((n, o) => n + o.volume, 0).toFixed(1)} m³ requested`}
            />
            <Metric
              label="Assigned"
              value={plan ? assigned : "-"}
              note={
                plan
                  ? `${routes.length} feasible trips`
                  : "Generate a draft to allocate"
              }
            />
            <Metric
              label="Deferred"
              value={plan ? deferred.length : "-"}
              note={
                plan
                  ? `${deferred.filter((d) => d.repeat).length} previously skipped`
                  : "No decisions made yet"
              }
            />
            <Metric
              label="Planned fuel"
              value={plan ? `${dailyFuel.toFixed(1)} L` : "-"}
              note="Includes return to home depot"
            />
          </dl>
          <div className="mb-5 flex flex-wrap items-center justify-between gap-4 rounded-xl bg-accent p-5 text-white">
            <div>
              <h2 className="m-0 text-lg font-semibold">
                {published
                  ? `${dateLabel} is ready for the docks`
                  : plan
                    ? "Review the tradeoffs, then publish"
                    : "Build a feasible delivery plan"}
              </h2>
              <p className="m-0 mt-1 max-w-2xl text-copy text-white/80">
                {published
                  ? "Orders and receiving times are shared. Published decisions remain in the audit trail."
                  : notesRequired
                    ? `${notesRequired} repeat deferral${notesRequired === 1 ? " needs" : "s need"} a concrete follow-up before publication.`
                    : "Priority: previously skipped orders, then Fresh, chilled goods and closing windows. Assisted planning; the dispatcher decides."}
              </p>
            </div>
            <div className="flex flex-wrap gap-3">
              {!published && (
                <>
                  <Btn
                    disabled={busy || !orders.length}
                    onClick={() => action("plan")}
                    aria-label={plan ? "Regenerate draft" : "Generate draft"}
                  >
                    {plan ? "Regenerate" : "Generate draft"}
                  </Btn>
                  <button
                    disabled={busy || !plan || !!notesRequired}
                    onClick={() => plan && setPublication({ plan, orders: dayOrders })}
                    aria-label="Publish plan"
                    className="min-h-11 rounded-full bg-white px-5 text-copy font-semibold text-accent disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    Publish plan
                  </button>
                </>
              )}
            </div>
          </div>
        </>
      )}
      {view === "overview" && (
        <>
          <dl className="m-0 mb-5 grid grid-cols-2 overflow-hidden rounded-xl border border-line bg-white lg:grid-cols-4">
            <Metric
              label="Orders today"
              value={orders.length}
              note={
                plan
                  ? `${Math.max(0, orders.length - assigned)} awaiting a plan`
                  : "Generate a draft to allocate"
              }
            />
            <Metric
              label="On the road"
              value={routes.length}
              note={`${routes.length} trips across your network`}
            />
            <Metric
              label="Deliveries completed"
              value={
                dayOrders.filter((o) =>
                  terminal.includes(
                    (current[o.id] || o).status as (typeof terminal)[number],
                  ),
                ).length
              }
              note={`${dayOrders.filter((o) => (current[o.id] || o).status === "confirmed").length} confirmed by stores`}
            />
            <Metric
              label="Needs attention"
              value={exceptions.length + deferred.length}
              note={`${exceptions.length} open issues · ${deferred.length} deferred`}
            />
          </dl>
          <div className="mb-5 flex flex-wrap items-center justify-between gap-4 rounded-xl bg-accent p-5 text-white">
            <div>
              <h2 className="m-0 text-lg font-semibold">
                Keep every delivery moving. {dateLabel}.
              </h2>
              <p className="m-0 mt-1 max-w-2xl text-copy text-white/80">
                {published
                  ? "Published decisions are shared with loaders, drivers and stores."
                  : "Priority: previously skipped orders, then Fresh, chilled goods and closing windows."}{" "}
                Last updated{" "}
                {new Date(state.updated).toLocaleString("en-GB", {
                  timeZone: "Asia/Colombo",
                })}
                . No live GPS is inferred.
              </p>
            </div>
            <div className="flex flex-wrap gap-3">
              <Btn onClick={() => go("plan")} aria-label="Plan deliveries">
                Plan deliveries
              </Btn>
              <Btn
                onClick={() =>
                  downloadCsv(`waypoint-report-${day}.csv`, [
                    ["metric", "value"],
                    ["day", day],
                    ["depot", depot],
                    ["orders", orders.length],
                    ["assigned", assigned],
                    ["deferred", deferred.length],
                    ["exceptions", exceptions.length],
                    ["trips", routes.length],
                    ["fuel_l", dailyFuel.toFixed(1)],
                  ])
                }
                aria-label="Export report"
              >
                Export report
              </Btn>
              <Btn onClick={() => setGuide(true)} aria-label="Help and walkthrough">
                Help & walkthrough
              </Btn>
            </div>
          </div>
          <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_350px]">
            <section className="rounded-xl border border-line bg-white">
              <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line p-4">
                <h2 className="m-0 text-lg font-semibold">Today&apos;s orders</h2>
                <div className="flex gap-2">
                  <Btn
                    size="sm"
                    onClick={() =>
                      downloadCsv(
                        `waypoint-orders-${day}.csv`,
                        [
                          [
                            "order",
                            "outlet",
                            "brand",
                            "district",
                            "weight_kg",
                            "volume_m3",
                            "status",
                            "window",
                          ],
                          ...orders.map((o) => [
                            o.id,
                            o.outlet_id,
                            o.brand,
                            o.district,
                            o.weight,
                            o.volume,
                            (current[o.id] || o).status,
                            `${o.window_open_time}-${o.window_close_time}`,
                          ]),
                        ],
                      )
                    }
                    aria-label="Export orders"
                  >
                    Export orders
                  </Btn>
                  <Btn size="sm" onClick={() => go("orders")}>
                    View all orders
                  </Btn>
                </div>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-left text-copy">
                  <thead className="bg-canvas text-caption text-muted">
                    <tr>
                      {[
                        "Order / outlet",
                        "Requirements",
                        "Load",
                        "Receiving window",
                        "Status",
                      ].map((x) => (
                        <th key={x} className="p-4 font-medium">
                          {x}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {orders.slice(0, 5).map((o) => {
                      const live = current[o.id] || o;
                      return (
                        <tr key={o.id} className="border-t border-line">
                          <td className="p-4">
                            <button
                              onClick={() => onDetail(live)}
                              className="font-mono text-caption text-accent underline"
                            >
                              {o.id}
                            </button>
                            <p className="my-1">
                              {o.outlet_id} · {o.district}
                            </p>
                          </td>
                          <td className="p-4">
                            <Brand order={o} />
                          </td>
                          <td className="p-4 whitespace-nowrap">
                            {o.weight} kg
                            <br />
                            {o.volume} m³
                          </td>
                          <td className="p-4 font-mono text-caption whitespace-nowrap">
                            {o.window_open_time}-{o.window_close_time}
                          </td>
                          <td className="p-4">
                            <Badge status={live.status} />
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
                {!orders.length && (
                  <Empty title="No orders for this day">
                    Choose another run from the delivery date selector.
                  </Empty>
                )}
              </div>
            </section>
            <aside className="rounded-xl border border-line bg-white">
              <div className="flex items-center justify-between gap-3 border-b border-line p-4">
                <h2 className="m-0 text-lg font-semibold">Needs attention</h2>
                <Btn size="sm" onClick={() => go("live")}>
                  Review exceptions
                </Btn>
              </div>
              {!exceptions.length && !deferred.length ? (
                <Empty title="Nothing needs attention">
                  Loading and delivery issues will appear here after
                  synchronization.
                </Empty>
              ) : (
                <>
                  {exceptions.slice(0, 3).map((o) => (
                    <article
                      key={o.id}
                      className="border-b border-line p-4 last:border-0"
                    >
                      <Badge status={o.status} />
                      <h3 className="mb-1 text-copy font-semibold">
                        {o.outlet_id} · {o.id}
                      </h3>
                      <p className="my-1 text-copy text-muted">
                        {o.status === "shortfall"
                          ? o.shortfall?.note
                          : o.status === "disputed"
                            ? o.dispute?.note
                            : o.proof?.note || "Review the delivery outcome."}
                      </p>
                      <button
                        onClick={() => onDetail(o)}
                        className="mt-1 text-caption text-accent underline"
                      >
                        View shared record
                      </button>
                    </article>
                  ))}
                  {deferred.slice(0, 2).map((d) => {
                    const o = byId[d.order_id];
                    if (!o) return null;
                    return (
                      <article
                        key={d.order_id}
                        className="border-b border-line p-4 last:border-0"
                      >
                        <Badge status="deferred" />
                        <h3 className="mb-1 text-copy font-semibold">
                          {o.outlet_id} · {o.id}
                        </h3>
                        <p className="my-1 text-copy text-muted">{d.reason}</p>
                      </article>
                    );
                  })}
                </>
              )}
            </aside>
          </div>
        </>
      )}
      {view === "orders" && (
        <section className="rounded-xl border border-line bg-white">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line p-4">
            <h2 className="m-0 text-lg font-semibold">
              {dateLabel} order queue
            </h2>
            <input
              aria-label="Search orders"
              placeholder="Search outlet, brand or order"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className={inputClass}
            />
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy">
              <thead className="bg-canvas text-caption text-muted">
                <tr>
                  {[
                    "Order / outlet",
                    "Requirements",
                    "Load",
                    "Receiving window",
                    "Status",
                  ].map((x) => (
                    <th key={x} className="p-4 font-medium">
                      {x}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filtered.map((o) => (
                  <tr key={o.id} className="border-t border-line">
                    <td className="p-4">
                      <button
                        onClick={() => onDetail(current[o.id] || o)}
                        className="font-mono text-caption text-accent underline"
                      >
                        {o.id}
                      </button>
                      <p className="my-1">
                        {o.outlet_id} · {o.district}
                      </p>
                      {o.skips > 0 && (
                        <span className="text-caption text-grape">
                          Previously skipped {o.skips} time(s)
                        </span>
                      )}
                    </td>
                    <td className="p-4">
                      <Brand order={o} />
                      {o.parking_constraint === "van_only" && (
                        <p className="text-caption">Van only</p>
                      )}
                    </td>
                    <td className="p-4 whitespace-nowrap">
                      {o.weight} kg
                      <br />
                      {o.volume} m³
                    </td>
                    <td className="p-4 font-mono text-caption whitespace-nowrap">
                      {o.window_open_time}-{o.window_close_time}
                    </td>
                    <td className="p-4">
                      <Badge status={o.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!filtered.length && (
              <Empty title="No orders match">
                Choose another run or clear the search.
              </Empty>
            )}
          </div>
        </section>
      )}
      {view === "plan" && (
        <div className="grid items-start gap-5 xl:grid-cols-[340px_minmax(0,1fr)]">
          <aside className="overflow-hidden rounded-xl border border-line bg-white">
            <div className="border-b border-line bg-[#f1edf7] p-5">
              <h2 className="m-0 text-lg font-semibold">Deferral decisions</h2>
              <p className="mb-0 mt-1 text-copy text-muted">
                Protect the stores that have already waited.
              </p>
            </div>
            {!plan ? (
              <Empty title="No decisions yet">
                Generate a draft to see what fits and what needs attention.
              </Empty>
            ) : !deferred.length ? (
              <Empty title="Every order has a place">
                All orders in this depot filter fit the current plan.
              </Empty>
            ) : (
              deferred.map((d) => {
                const o = byId[d.order_id];
                return (
                  <article
                    key={d.order_id}
                    className="border-b border-line p-5 last:border-0"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <strong className="text-copy">
                        {o.brand} · {o.outlet_id}
                      </strong>
                      {d.repeat && (
                        <span className="rounded bg-[#efe8fb] px-2 py-1 text-caption text-grape">
                          Repeat skip
                        </span>
                      )}
                    </div>
                    <p className="my-1 font-mono text-caption text-muted">{o.id}</p>
                    <p className="my-2 text-copy">
                      {o.weight} kg · {o.volume} m³ · {o.temp}
                      {o.parking_constraint === "van_only" ? " · van only" : ""}
                    </p>
                    <p className="my-3 border-l-2 border-grape pl-3 text-copy">
                      {d.reason}
                    </p>
                    <p className="text-caption text-muted">
                      {published ? "Queued for" : "Next eligible run"}{" "}
                      {fmtDayLabel(d.next_date)}. Allocation is still required.
                    </p>
                    {d.justification && (
                      <p className="rounded-lg bg-canvas p-3 text-copy">
                        <strong>Follow-up:</strong> {d.justification}
                      </p>
                    )}
                    {!published && (
                      <div className="flex flex-wrap gap-2">
                        <Btn
                          size="sm"
                          variant="primary"
                          onClick={() => setMove(o)}
                        >
                          Explore alternatives
                        </Btn>
                        <Btn
                          size="sm"
                          onClick={() =>
                            setNote({ kind: "defer_note", order: o })
                          }
                          aria-label={`Explain deferral ${o.id}`}
                        >
                          {d.justification
                            ? "Edit follow-up"
                            : "Record follow-up"}
                        </Btn>
                      </div>
                    )}
                    <button
                      onClick={() => onDetail(current[o.id] || o)}
                      className="mt-3 text-caption text-accent underline"
                    >
                      View order history
                    </button>
                  </article>
                );
              })
            )}
          </aside>
          <section>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="m-0 text-lg font-semibold">Trip manifest</h2>
              <span className="text-caption text-muted">
                {routes.length} trips ·{" "}
                {
                  vehicles.filter((v) => !v.status || v.status === "available")
                    .length
                }{" "}
                available vehicles
              </span>
            </div>
            <div className="space-y-3">
              {routes.map((r) => (
                <RouteCard
                  key={r.id}
                  route={r}
                  vehicle={vehicleMap[r.vehicle_id]}
                  rows={byId}
                  published={published}
                  busy={busy}
                  onMove={setMove}
                  onDetail={(o) => onDetail(current[o.id] || o)}
                />
              ))}
              {!routes.length && (
                <div className="rounded-xl border border-dashed border-line bg-white">
                  <Empty
                    title={
                      plan
                        ? "No feasible trips in this filter"
                        : "Your plan starts here"
                    }
                  >
                    {plan
                      ? "Review deferrals, fleet availability and receiving windows."
                      : "Generate a draft. Each trip shows both load limits and the receiving time at every stop."}
                  </Empty>
                </div>
              )}
            </div>
          </section>
        </div>
      )}
      {view === "live" &&
        (!published ? (
          <div className="rounded-xl border border-line bg-white">
            <Empty title="No published plan">
              Publish {dateLabel}'s plan to share it with loaders, drivers and
              stores.
            </Empty>
          </div>
        ) : (
          <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_350px]">
            <section>
              <h2 className="m-0 mb-2 text-lg font-semibold">
                {
                  routes
                    .flatMap((r) => r.order_ids)
                    .filter((id) => terminal.includes(current[id]?.status))
                    .length
                }{" "}
                of {assigned} delivery attempts recorded
              </h2>
              <p className="mb-5 mt-1 text-copy text-muted">
                Last server refresh{" "}
                {new Date(state.updated).toLocaleTimeString("en-GB", {
                  timeZone: "Asia/Colombo",
                })}
                . Unsent driver actions are visible only on their device. No
                live GPS is inferred.
              </p>
              <div className="space-y-3">
                {routes.map((r) => (
                  <RouteCard
                    key={r.id}
                    route={r}
                    vehicle={vehicleMap[r.vehicle_id]}
                    rows={current}
                    published
                    busy={busy}
                    onMove={setMove}
                    onDetail={onDetail}
                  />
                ))}
              </div>
            </section>
            <aside className="rounded-xl border border-line bg-white">
              <h2 className="m-0 border-b border-line p-5 text-lg font-semibold">
                Needs attention · {exceptions.length}
              </h2>
              {!exceptions.length ? (
                <Empty title="No reported exceptions">
                  Loading and delivery issues will appear after synchronization.
                </Empty>
              ) : (
                exceptions.map((o) => (
                  <article
                    key={o.id}
                    className="border-b border-line p-5 last:border-0"
                  >
                    <Badge status={o.status} />
                    <h3 className="mb-1 text-copy font-semibold">
                      {o.outlet_id} · {o.id}
                    </h3>
                    <p className="text-copy text-muted">
                      {o.status === "shortfall"
                        ? o.shortfall?.note
                        : o.status === "disputed"
                          ? o.dispute?.note
                          : o.proof?.note || "Review the delivery outcome."}
                    </p>
                    <p className="text-caption text-muted">
                      Next owner:{" "}
                      {o.status === "shortfall"
                        ? "dispatcher, then loader recheck"
                        : "dispatcher and store manager"}
                    </p>
                    {["partial", "failed", "disputed"].includes(o.status) || (o.status === "confirmed" && o.proof?.outcome === "partial") ? (
                      <Btn onClick={() => setException(o)}>Resolve delivery exception</Btn>
                    ) : null}
                    {o.status === "shortfall" && (
                      <Btn
                        size="sm"
                        variant="primary"
                        onClick={() => setNote({ kind: "resolve", order: o })}
                        aria-label="Record resolution"
                      >
                        Record replacement
                      </Btn>
                    )}
                    <button
                      onClick={() => onDetail(o)}
                      className="mt-3 block text-caption text-accent underline"
                    >
                      View shared record
                    </button>
                  </article>
                ))
              )}
            </aside>
          </div>
        ))}
      {view === "capacity" && (
        <section className="overflow-hidden rounded-xl border border-line bg-white">
          <div className="border-b border-line p-5">
            <h2 className="m-0 text-lg font-semibold">
              Capacity that can actually run
            </h2>
            <p className="mb-0 mt-1 text-copy text-muted">
              {
                vehicles.filter((v) => !v.status || v.status === "available")
                  .length
              }{" "}
              available of {vehicles.length} vehicles. Fuel reserved this week:{" "}
              {fleetFuel.toFixed(1)} / {fleetQuota.toFixed(0)} L. Includes this
              draft.
            </p>
            <p className="mb-0 text-caption text-muted">
              Weight and volume alone do not establish feasibility. Access,
              temperature, windows and turnaround also constrain every trip.
            </p>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy">
              <thead className="bg-canvas text-caption text-muted">
                <tr>
                  {[
                    "Vehicle / depot",
                    "Capability",
                    "Availability",
                    "Trips today",
                    "Fuel this week",
                  ].map((x) => (
                    <th key={x} className="p-4 font-medium">
                      {x}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {vehicles.map((v) => (
                  <tr key={v.vehicle_id} className="border-t border-line">
                    <td className="p-4">
                      <strong className="font-mono text-caption">
                        {v.vehicle_id}
                      </strong>
                      <p className="my-1 text-caption text-muted">{v.depot}</p>
                    </td>
                    <td className="p-4 text-caption">
                      {v.temp === "reefer" ? "Refrigerated" : "Ambient"}{" "}
                      {v.type}
                      <br />
                      {v.weight_cap_kg} kg · {v.volume_cap_m3} m³
                    </td>
                    <td className="p-4 text-caption">
                      {(v.status || "available").replaceAll("_", " ")}
                    </td>
                    <td className="p-4 font-mono text-caption">
                      {
                        routes.filter((r) => r.vehicle_id === v.vehicle_id)
                          .length
                      }{" "}
                      / 2
                    </td>
                    <td className="min-w-40 p-4">
                      <LoadBar
                        label={`${weeklyFuel(v).toFixed(1)} / ${v.weekly_fuel_quota_l} L`}
                        value={weeklyFuel(v)}
                        max={v.weekly_fuel_quota_l}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="border-t border-line p-5">
            <h2 className="m-0 text-lg font-semibold">
              Store directory ·{" "}
              {
                state.outlets.filter(
                  (o) => depot === "All depots" || o.depot === depot,
                ).length
              }
            </h2>
            <p className="mb-0 mt-1 text-copy text-muted">
              Receiving windows, access and ordering rules for every store.
            </p>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy">
              <thead className="bg-canvas text-caption text-muted">
                <tr>
                  {[
                    "Store / depot",
                    "Brand",
                    "Access",
                    "Receiving window",
                  ].map((x) => (
                    <th key={x} className="p-4 font-medium">
                      {x}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {state.outlets
                  .filter((o) => depot === "All depots" || o.depot === depot)
                  .map((o) => (
                    <tr key={o.outlet_id} className="border-t border-line">
                      <td className="p-4">
                        <strong className="font-mono text-caption">
                          {o.outlet_id}
                        </strong>
                        <p className="my-1 text-caption text-muted">
                          {o.district} · {o.depot}
                        </p>
                      </td>
                      <td className="p-4 text-caption">
                        {o.brand} · {o.district}
                      </td>
                      <td className="p-4 text-caption">
                        {o.parking_constraint.replaceAll("_", " ")} ·{" "}
                        {o.dock_type.replaceAll("_", " ")}
                      </td>
                      <td className="p-4 font-mono text-caption whitespace-nowrap">
                        {o.window_open_time}-{o.window_close_time}
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      {exception && <ExceptionReview order={exception} act={act} busy={busy} onClose={() => setException(null)} />}
      {note && (
        <NoteForm
          title={
            note.kind === "resolve"
              ? "Resolve loading shortfall"
              : "Explain deferral"
          }
          label={
            note.kind === "resolve"
              ? "How were the missing or damaged goods replaced?"
              : "Why must this order wait, and what will you do before the next run?"
          }
          busy={busy}
          onClose={() => setNote(null)}
          onSubmit={async (data) => {
            if (
              await action(note.kind, {
                order_id: note.order.id,
                version: note.order.version,
                ...data,
              })
            )
              setNote(null);
          }}
        />
      )}
      {move && plan && !published && (
        <AssignmentReview
          order={move}
          plan={plan}
          act={act}
          busy={busy}
          onClose={() => setMove(null)}
        />
      )}
      {publication && <PublishReview plan={publication.plan} orders={publication.orders}
        act={act} busy={busy} onClose={() => setPublication(null)} />}
      {guide && (
        <Modal title="Your Waypoint walkthrough" onClose={() => setGuide(false)}>
          <ol className="m-0 list-none space-y-3 p-0 text-copy">
            <li><strong>1. Store:</strong> place an order before the 16:00 cutoff and wait for the confirmed order ID.</li>
            <li><strong>2. Dispatcher:</strong> select the run day, generate a draft, explain repeat deferrals, then publish.</li>
            <li><strong>3. Loader:</strong> open the dated trip, load in stop order, and flag any shortfall before departure.</li>
            <li><strong>4. Dispatcher:</strong> record the replacement; the loader rechecks and marks every order loaded.</li>
            <li><strong>5. Driver:</strong> start the stop when safely stopped, record arrival and the delivery outcome with receiver, photo and signature.</li>
            <li><strong>6. Store:</strong> confirm receipt or report an issue independently from the driver record.</li>
            <li><strong>7. Offline recovery:</strong> work stays on the device without a connection and syncs by ID when it returns. Rejected records stay in Needs review.</li>
          </ol>
          <p className="mt-4 text-caption text-muted">
            ETAs are plans with a disclosed travel buffer, not live GPS. There
            is no trained lateness or demand forecast in this build.
          </p>
        </Modal>
      )}
    </main>
  );
}
