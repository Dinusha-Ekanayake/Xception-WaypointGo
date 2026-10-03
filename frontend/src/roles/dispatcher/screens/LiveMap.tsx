"use client";

import { useMemo, useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { TrailPointView, VehiclePositionView } from "@shared/domain/types";
import { FilterTabs, Pill, type Tone } from "@shared/ui";
import { clock } from "@shared/wording";
import { LiveMap, MapLegend, num, type LatLon, type MapLine, type MapMarker } from "@shared/ui/map";
import { mapStatus, type MapStatus, type VehicleDay } from "../data/live.ts";
import { hhmm } from "../data/plan.ts";
import { useDepots, usePositions } from "../data/useDay.ts";

// Figma "05 Live" map view (189:11320) with 05b (selected, at risk) and 05d
// (offline). A truck is drawn only where it was seen: with no fix it is listed
// as "No live location · stops only", never placed at a guess. The timeline
// view beside this one stays the accessible list of every vehicle.

const STATUS: Record<MapStatus, { label: string; tone: Tone }> = {
  "on-time": { label: "On time", tone: "success" },
  "at-risk": { label: "At risk", tone: "warning" },
  late: { label: "Late", tone: "danger" },
  returning: { label: "Returning", tone: "info" },
  offline: { label: "Offline", tone: "muted" },
};

type Row = { day: VehicleDay; position: VehiclePositionView | null; status: MapStatus; at: LatLon | null };


export default function LiveMapView({ depots, depotOf, date, days, now }: { depots: string[]; depotOf: Record<string, string>; date: string; days: VehicleDay[]; now: Date }): React.JSX.Element {
  const positions = usePositions(depots, date);
  const depotViews = useDepots(depots);
  const [depot, setDepot] = useState<string>("all");
  const [filter, setFilter] = useState<"all" | "at-risk" | "offline">("all");
  const [selected, setSelected] = useState<string | null>(null);

  const rows: Row[] = useMemo(() => {
    const byVehicle = new Map((positions.data ?? []).map((p) => [p.vehicleId, p]));
    return days.map((day) => {
      const position = byVehicle.get(day.vehicleId) ?? null;
      const lat = num(position?.latitude);
      const lon = num(position?.longitude);
      return { day, position, status: mapStatus(date, day, position, now), at: lat !== null && lon !== null ? { lat, lon } : null };
    });
  }, [positions.data, days, date, now]);

  const inDepot = rows.filter((r) => depot === "all" || depotOf[r.day.vehicleId] === depot);
  const shown = inDepot.filter((r) => filter === "all" || (filter === "offline" ? r.status === "offline" : r.status === "at-risk" || r.status === "late"));
  const chosen = rows.find((r) => r.day.vehicleId === selected) ?? null;

  const trail = useResource(
    chosen?.position?.tripId ? (signal: AbortSignal) => request<{ items: TrailPointView[] }>(`/api/execution/trips/${encodeURIComponent(chosen.position!.tripId!)}/trail?limit=200`, { signal }) : null,
    `trail|${chosen?.position?.tripId ?? ""}`,
    15_000,
  );

  const markers: MapMarker[] = [];
  for (const d of depotViews.data ?? []) {
    const lat = num(d.location?.latitude);
    const lon = num(d.location?.longitude);
    if (lat !== null && lon !== null && (depot === "all" || depot === d.depotCode)) {
      markers.push({ id: `depot:${d.depotCode}`, kind: "depot", lat, lon, label: d.displayName, ariaLabel: `${d.displayName} depot`, selectable: false });
    }
  }
  for (const r of shown) {
    if (!r.at) continue;
    const next = r.day.current;
    markers.push({
      id: r.day.vehicleId,
      kind: "vehicle",
      ...r.at,
      heading: num(r.position?.headingDeg),
      status: r.status,
      faded: r.status === "offline",
      label: r.day.vehicleId,
      ariaLabel: `${r.day.vehicleId}, ${STATUS[r.status].label.toLowerCase()}, stop ${r.day.done + (next ? 1 : 0)} of ${r.day.stops.length}${next ? `, window closes ${hhmm(next.windowClose)}` : ""}`,
    });
  }
  const lines: MapLine[] = [];
  if (chosen && trail.data) {
    const points = trail.data.items.filter((p) => !p.lowQuality).map((p) => ({ lat: num(p.latitude) ?? 0, lon: num(p.longitude) ?? 0 }));
    lines.push({ id: "trail", points, style: "driven" });
  }
  const fit = (depotViews.data ?? [])
    .filter((d) => depot === "all" || depot === d.depotCode)
    .map((d) => ({ lat: num(d.location?.latitude), lon: num(d.location?.longitude) }))
    .filter((p): p is LatLon => p.lat !== null && p.lon !== null);

  const count = (s: MapStatus[]) => inDepot.filter((r) => s.includes(r.status)).length;
  const unplaced = shown.filter((r) => !r.at);

  return (
    <div className="flex min-h-0 w-full flex-1 gap-[18px] max-lg:flex-col">
      <div className="relative min-h-[560px] min-w-0 flex-1">
        <LiveMap
          className="h-full min-h-[560px] w-full rounded-[24px]"
          markers={markers}
          lines={lines}
          fit={fit}
          selectedId={selected}
          onSelect={setSelected}
          clusterVehicles={(group) => ({
            id: `cluster:${group.map((m) => m.id).join(",")}`,
            kind: "cluster",
            lat: group.reduce((s, m) => s + m.lat, 0) / group.length,
            lon: group.reduce((s, m) => s + m.lon, 0) / group.length,
            count: group.length,
            label: `${group.length} vehicles`,
            sub: summary(group),
            ariaLabel: `${group.length} vehicles close together, zoom in`,
          })}
          overlay={
            <div className="flex flex-wrap gap-2 rounded-full bg-white p-1 shadow">
              <FilterTabs label="Depot" value={depot} onChange={setDepot} options={[{ value: "all", label: depots.length > 1 ? "Both" : "All" }, ...depots.map((d) => ({ value: d, label: d }))]} />
              <FilterTabs label="Status" value={filter} onChange={setFilter} options={[
                { value: "all", label: `All ${inDepot.length}` },
                { value: "at-risk", label: `At risk ${count(["at-risk", "late"])}` },
                { value: "offline", label: `Offline ${count(["offline"])}` },
              ]} />
            </div>
          }
          legend={<MapLegend hint={chosen ? undefined : "Select a vehicle"} />}
        />
        {positions.error && (
          <p role="status" className="absolute bottom-16 left-4 z-[600] rounded-full bg-white px-3 py-1 text-[12px] text-go-danger-strong shadow">
            Positions not updated{positions.loadedAt ? ` since ${clock(positions.loadedAt)}` : ""} · <button type="button" className="underline" onClick={positions.refresh}>Retry</button>
          </p>
        )}
      </div>

      <aside aria-label="Selected vehicle" className="flex w-full flex-col gap-3 rounded-[24px] bg-white p-4 shadow-go-card lg:max-w-[340px]">
        {chosen ? <Panel row={chosen} date={date} trailPoints={trail.data?.items.length ?? null} onClose={() => setSelected(null)} /> : (
          <p className="text-[13px] text-go-secondary">Select a vehicle on the map to see its trip, its next stop and how late it is.</p>
        )}
        {unplaced.length > 0 && (
          <div className="border-t border-go-rule pt-3">
            <h3 className="text-[13px] font-medium text-go-ink">No live location · stops only</h3>
            <ul className="mt-1 flex flex-col gap-1">
              {unplaced.map((r) => (
                <li key={r.day.vehicleId}>
                  <button type="button" onClick={() => setSelected(r.day.vehicleId)} className="flex w-full items-center justify-between text-left text-[13px] text-go-ink">
                    <span>{r.day.vehicleId} · {r.day.done} of {r.day.stops.length} stops</span>
                    <Pill tone={STATUS[r.status].tone}>{STATUS[r.status].label}</Pill>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}
      </aside>
    </div>
  );
}

function summary(group: MapMarker[]): string {
  const parts = (["on-time", "at-risk", "late", "returning", "offline"] as MapStatus[])
    .map((s) => [s, group.filter((m) => m.status === s).length] as const)
    .filter(([, n]) => n > 0)
    .map(([s, n]) => `${n} ${STATUS[s].label.toLowerCase()}`);
  return parts.join(" · ");
}

function Panel({ row, date, trailPoints, onClose }: { row: Row; date: string; trailPoints: number | null; onClose: () => void }): React.JSX.Element {
  const { day, position, status } = row;
  const next = day.current;
  const lateBy = next?.expectedArrival ? Math.round((new Date(next.expectedArrival).getTime() - new Date(`${date}T${next.windowClose.slice(0, 5)}:00+05:30`).getTime()) / 60_000) : null;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h2 className="text-[17px] font-medium text-go-ink">{day.vehicleId}{next ? ` · ${next.outletId}` : ""}</h2>
          <p className="text-[13px] text-go-secondary">{day.done} of {day.stops.length} stops done</p>
        </div>
        <button type="button" aria-label="Close" onClick={onClose} className="text-go-secondary">×</button>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Pill tone={STATUS[status].tone}>{STATUS[status].label}</Pill>
        {position ? (
          <span className="text-[12px] text-go-secondary">{status === "offline" ? `Last seen ${clock(position.recordedAt)}` : `Updated ${clock(position.recordedAt)}`}</span>
        ) : (
          <span className="text-[12px] text-go-secondary">No live location · stops only</span>
        )}
      </div>
      {next && (
        <div className="rounded-go-card bg-go-subtle p-3 text-[13px] text-go-ink">
          <p className="font-medium">Next · stop {next.sequence} · {next.outletId}</p>
          <p className="text-go-secondary">
            Window {hhmm(next.windowOpen)} to {hhmm(next.windowClose)} · {next.expectedArrival ? `expected ${clock(next.expectedArrival)}` : `planned ${hhmm(next.plannedArrival)}`}
            {lateBy !== null && lateBy > 0 ? ` · ${lateBy} min past the window` : ""}
          </p>
        </div>
      )}
      <ol className="flex flex-col text-[13px] text-go-ink">
        {day.stops.map((stop) => (
          <li key={stop.deliveryId} className="flex items-center gap-2 border-t border-go-rule py-1.5 first:border-t-0">
            <span className="w-6 tabular-nums text-go-secondary">{String(stop.sequence).padStart(2, "0")}</span>
            <span className="flex-1 truncate">{stop.outletId}</span>
            <span className="text-go-secondary">{stop.outcome === "PENDING" ? hhmm(stop.plannedArrival) : stop.outcome.toLowerCase()}</span>
          </li>
        ))}
      </ol>
      {trailPoints !== null && <p className="text-[12px] text-go-secondary">Location trail · {trailPoints} points drawn on the map</p>}
    </div>
  );
}
