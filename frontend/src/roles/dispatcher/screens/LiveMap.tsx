"use client";

import { useState } from "react";
import { request } from "@shared/api/client";
import { useResource, type Resource } from "@shared/api/useResource";
import type { TrailPointView, VehiclePositionView } from "@shared/domain/types";
import { clock } from "@shared/wording";
import { LiveMap, MapLegend, num, type LatLon, type MapLine, type MapMarker } from "@shared/ui/map";
import type { MapStatus } from "../data/live.ts";
import { hhmm } from "../data/plan.ts";
import { runTitle, type Run } from "../data/liveDesk.ts";
import { useDepots } from "../data/useDay.ts";
import LivePanel from "./LivePanel.tsx";
import { STATUS } from "./LiveParts.tsx";
import { RoadCard } from "./LiveTimeline.tsx";

// Figma "05 Live · map" (189:21358, 189:21553, 189:21746): the map wide, one
// panel beside it that is the list of vehicles on the road until one is chosen,
// then that vehicle. A truck is drawn only where it was seen: with no fix it is
// listed as "No live location · stops only", never placed at a guess. The
// header's depot and status filters choose what is drawn.

export default function LiveMapView({
  depots,
  date,
  runs,
  positions,
  depotName,
  now,
  onOpenTrip,
}: {
  depots: string[];
  date: string;
  runs: Run[];
  positions: Resource<VehiclePositionView[]>;
  depotName: (run: Run) => string;
  now: Date;
  onOpenTrip: (vehicleId: string) => void;
}): React.JSX.Element {
  const depotViews = useDepots(depots);
  const [selected, setSelected] = useState<string | null>(null);
  const chosen = runs.find((r) => r.day.vehicleId === selected) ?? null;

  const trail = useResource(
    chosen?.position?.tripId ? (signal: AbortSignal) => request<{ items: TrailPointView[] }>(`/api/execution/trips/${encodeURIComponent(chosen.position!.tripId!)}/trail?limit=200`, { signal }) : null,
    `trail|${chosen?.position?.tripId ?? ""}`,
    15_000,
  );

  const shownDepots = new Set(runs.map((r) => r.depot).filter(Boolean));
  const markers: MapMarker[] = [];
  for (const d of depotViews.data ?? []) {
    const lat = num(d.location?.latitude);
    const lon = num(d.location?.longitude);
    if (lat !== null && lon !== null && (shownDepots.size === 0 || shownDepots.has(d.depotCode))) {
      markers.push({ id: `depot:${d.depotCode}`, kind: "depot", lat, lon, label: d.displayName, ariaLabel: `${d.displayName} depot`, selectable: false });
    }
  }
  for (const r of runs) {
    const lat = num(r.position?.latitude);
    const lon = num(r.position?.longitude);
    if (lat === null || lon === null) continue;
    const next = r.day.current;
    markers.push({
      id: r.day.vehicleId,
      kind: "vehicle",
      lat,
      lon,
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
    .map((d) => ({ lat: num(d.location?.latitude), lon: num(d.location?.longitude) }))
    .filter((p): p is LatLon => p.lat !== null && p.lon !== null);
  const unplaced = runs.filter((r) => num(r.position?.latitude) === null);

  return (
    <div className="flex w-full items-stretch gap-[18px] max-lg:flex-col">
      <div className="relative min-h-[620px] min-w-0 flex-1">
        <LiveMap
          className="h-full min-h-[620px] w-full rounded-[24px]"
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
          legend={<MapLegend hint={chosen ? undefined : "Select a vehicle"} />}
        />
        {positions.error && (
          <p role="status" className="absolute bottom-16 left-4 z-[600] rounded-full bg-white px-3 py-1 text-[12px] text-go-danger-strong shadow">
            Positions not updated{positions.loadedAt ? ` since ${clock(positions.loadedAt)}` : ""} ·{" "}
            <button type="button" className="underline" onClick={positions.refresh}>
              Retry
            </button>
          </p>
        )}
      </div>

      <aside aria-label="Selected vehicle" className="flex w-full flex-col gap-3 rounded-[24px] bg-white p-[18px] shadow-go-card lg:max-w-[360px]">
        {chosen ? (
          <LivePanel
            run={chosen}
            date={date}
            depotName={depotName(chosen)}
            now={now}
            trailPoints={trail.data?.items.length ?? null}
            onBack={() => setSelected(null)}
            onOpenTrip={() => onOpenTrip(chosen.day.vehicleId)}
          />
        ) : (
          <>
            <div className="flex items-baseline justify-between">
              <h2 className="text-[17px] font-medium text-go-ink">On the road</h2>
              <span className="text-[12px] text-go-secondary">select one for details</span>
            </div>
            {runs.length === 0 && <p className="py-6 text-center text-[13px] text-go-secondary">No vehicle matches. A vehicle appears here when the loader releases it.</p>}
            {runs.map((r) => (
              <RoadCard key={r.day.vehicleId} run={r} onSelect={() => setSelected(r.day.vehicleId)} />
            ))}
            {unplaced.length > 0 && (
              <p className="px-1 text-[12px] text-go-secondary">
                No live location · stops only: {unplaced.map((r) => runTitle(r)).join(", ")}
              </p>
            )}
          </>
        )}
      </aside>
    </div>
  );
}

function summary(group: MapMarker[]): string {
  return (["on-time", "at-risk", "late", "returning", "offline"] as MapStatus[])
    .map((s) => [s, group.filter((m) => m.status === s).length] as const)
    .filter(([, n]) => n > 0)
    .map(([s, n]) => `${n} ${STATUS[s].label.toLowerCase()}`)
    .join(" · ");
}
