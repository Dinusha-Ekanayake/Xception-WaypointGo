"use client";

import { useState } from "react";
import { Badge, Empty, card, field, secondary } from "./components";
import { DEMO_DEPOTS } from "./fixtures";

const VEHICLES = [
  { id: "WP-1042", type: "Van", depot: "PELIYAGODA", capacity: "1,200 kg", status: "Available" },
  { id: "WP-2088", type: "Truck", depot: "PELIYAGODA", capacity: "5,500 kg", status: "On a trip" },
  { id: "WP-1176", type: "Van", depot: "KANDY", capacity: "1,200 kg", status: "Available" },
  { id: "WP-3041", type: "Truck", depot: "KANDY", capacity: "5,500 kg", status: "Workshop" },
  { id: "WP-1223", type: "Van", depot: "PELIYAGODA", capacity: "1,200 kg", status: "On a trip" },
  { id: "WP-3095", type: "Truck", depot: "KANDY", capacity: "5,500 kg", status: "Available" },
] as const;

const FORECASTS = [
  { depot: "PELIYAGODA", orders: 128, previous: 112, vans: 7, trucks: 4 },
  { depot: "KANDY", orders: 94, previous: 88, vans: 5, trucks: 3 },
] as const;

export function VehiclesScreen() {
  const [depot, setDepot] = useState("all");
  const [status, setStatus] = useState("all");
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<string | null>(null);
  const rows = VEHICLES.filter((vehicle) => (depot === "all" || vehicle.depot === depot) && (status === "all" || vehicle.status === status) && `${vehicle.id} ${vehicle.type}`.toLowerCase().includes(query.toLowerCase()));
  const vehicle = VEHICLES.find((item) => item.id === selected);
  return <div className="space-y-5"><div><h2 className="text-2xl font-semibold">Vehicles</h2><p className="mt-1 text-sm text-[#58685f]">A sample fleet overview for the admin workspace.</p></div>
    <div className="grid gap-4 sm:grid-cols-3"><Summary value={VEHICLES.length} label="Vehicles"/><Summary value={VEHICLES.filter((item) => item.status === "Available").length} label="Available"/><Summary value={VEHICLES.filter((item) => item.status === "Workshop").length} label="In workshop"/></div>
    <div className={`${card} grid gap-3 p-4 md:grid-cols-3`}><label className="text-sm font-medium">Search vehicle<input className={`${field} mt-1`} type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Vehicle ID or type"/></label><label className="text-sm font-medium">Depot<select className={`${field} mt-1`} value={depot} onChange={(event) => setDepot(event.target.value)}><option value="all">All depots</option>{DEMO_DEPOTS.map((item) => <option key={item}>{item}</option>)}</select></label><label className="text-sm font-medium">Status<select className={`${field} mt-1`} value={status} onChange={(event) => setStatus(event.target.value)}><option value="all">All statuses</option>{["Available", "On a trip", "Workshop"].map((item) => <option key={item}>{item}</option>)}</select></label></div>
    {rows.length ? <div className={`${card} divide-y divide-[#e9efea]`}>{rows.map((item) => <div key={item.id} className="flex flex-wrap items-center gap-4 p-5"><div className="grid size-11 place-items-center rounded-xl bg-[#e5f4ef] text-lg text-[#006b57]">▣</div><div className="min-w-40 flex-1"><p className="font-semibold">{item.id} · {item.type}</p><p className="text-sm text-[#58685f]">{item.depot} · {item.capacity}</p></div><Badge tone={item.status === "Available" ? "green" : item.status === "Workshop" ? "amber" : "blue"}>{item.status}</Badge><button className={secondary} onClick={() => setSelected(selected === item.id ? null : item.id)}>{selected === item.id ? "Hide details" : "View details"}</button>{vehicle?.id === item.id && <div className="w-full rounded-xl bg-[#f2f8f5] p-4 text-sm"><strong>Sample vehicle record</strong><p className="mt-1 text-[#58685f]">Depot: {item.depot} · Type: {item.type} · Capacity: {item.capacity} · Day status: {item.status}</p></div>}</div>)}</div> : <Empty>No vehicles match these filters.</Empty>}
    <p className="text-xs text-[#6c7e74]">Fleet data here is mock data. Vehicle status changes require a live backend connection.</p>
  </div>;
}

export function ForecastsScreen() {
  const [depot, setDepot] = useState("all");
  const rows = FORECASTS.filter((item) => depot === "all" || item.depot === depot);
  const totalOrders = rows.reduce((sum, item) => sum + item.orders, 0);
  const totalVehicles = rows.reduce((sum, item) => sum + item.vans + item.trucks, 0);
  const vehiclesInView = VEHICLES.filter((vehicle) => depot === "all" || vehicle.depot === depot);
  return <div className="space-y-5"><div><h2 className="text-2xl font-semibold">Forecasts</h2><p className="mt-1 text-sm text-[#58685f]">Illustrative order demand and vehicle needs for the next operating day.</p></div>
    <div className="rounded-2xl border border-[#f1dca7] bg-[#fff7e6] p-4 text-sm text-[#6a4b11]"><strong>Coming later.</strong> Forecast actions are not implemented by the backend. These figures are sample data for reviewing the UI.</div>
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4"><Summary value={totalOrders} label="Forecast orders"/><Summary value={totalVehicles} label="Suggested vehicles"/><Summary value={vehiclesInView.filter((vehicle) => vehicle.status === "Available").length} label="Available vehicles"/><Summary value={vehiclesInView.filter((vehicle) => vehicle.status === "Workshop").length} label="Workshop vehicles"/></div>
    <label className={`${card} block max-w-sm p-4 text-sm font-medium`}>Depot<select className={`${field} mt-1`} value={depot} onChange={(event) => setDepot(event.target.value)}><option value="all">All depots</option>{FORECASTS.map((item) => <option key={item.depot}>{item.depot}</option>)}</select></label>
    <div className="grid gap-4 lg:grid-cols-3">{rows.map((item) => { const change = Math.round((item.orders - item.previous) / item.previous * 100); return <article key={item.depot} className={`${card} p-5`}><p className="text-xs font-bold uppercase tracking-wider text-[#64776b]">{item.depot}</p><div className="mt-4 flex items-end gap-2"><strong className="text-4xl text-[#075c4b]">{item.orders}</strong><span className="pb-1 text-sm text-[#58685f]">orders</span></div><p className="mt-2 text-sm text-[#58685f]">{change >= 0 ? "+" : ""}{change}% against the sample comparison day</p><div className="mt-5 border-t border-[#e8efea] pt-4 text-sm"><p>Suggested: <strong>{item.vans} vans</strong> · <strong>{item.trucks} trucks</strong></p></div></article>; })}</div>
  </div>;
}

function Summary({ value, label }: { value: number; label: string }) { return <div className={`${card} p-5`}><p className="text-3xl font-semibold text-[#0a6b63]">{value}</p><p className="mt-1 text-sm text-[#58685f]">{label}</p></div>; }
