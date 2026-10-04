"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { livePoll, useDemo } from "@shared/demo/useDemo";
import { useResource, type Resource } from "@shared/api/useResource";
import type { VehiclePositionView } from "@shared/domain/types";
import { clock } from "@shared/wording";
import { LiveMap, MapLegend, num, readTripTrail, type LatLon, type MapLine, type MapMarker } from "@shared/ui/map";
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
  // A filter that hides the chosen vehicle also ends the choice.
  useEffect(() => {
    if (selected !== null && chosen === null) setSelected(null);
  }, [selected, chosen]);

  const demo = useDemo();
  const tripId = chosen?.position?.tripId ?? null;
  const trail = useResource(
    tripId ? (signal: AbortSignal) => readTripTrail(tripId, signal) : null,
    `trail|${tripId ?? ""}`,
    livePoll(demo, 15_000),
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
  const lines: MapLine[] = chosen && trail.data ? [{ id: "trail", points: trail.data, style: "driven" }] : [];
  // The depots once they are read; a vehicle when one is chosen, so choosing
  // from the list brings it into view. Keyed on the choice, not its position:
  // a re-fit on every poll would undo the dispatcher's own pan and zoom.
  const chosenAt = chosen ? markers.find((m) => m.id === chosen.day.vehicleId) ?? null : null;
  const depotCount = depotViews.data?.length ?? 0;
  const fit = useMemo(
    () =>
      chosenAt
        ? [{ lat: chosenAt.lat, lon: chosenAt.lon }]
        : (depotViews.data ?? [])
            .map((d) => ({ lat: num(d.location?.latitude), lon: num(d.location?.longitude) }))
            .filter((p): p is LatLon => p.lat !== null && p.lon !== null),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [selected, chosenAt !== null, depotCount],
  );
  const clusterVehicles = useCallback(
    (group: MapMarker[]): MapMarker => ({
      id: `cluster:${group.map((m) => m.id).join(",")}`,
      kind: "cluster",
      lat: group.reduce((s, m) => s + m.lat, 0) / group.length,
      lon: group.reduce((s, m) => s + m.lon, 0) / group.length,
      count: group.length,
      label: `${group.length} vehicles`,
      sub: summary(group),
      ariaLabel: `${group.length} vehicles close together, zoom in`,
    }),
    [],
  );
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
          clusterVehicles={clusterVehicles}
          legend={<MapLegend hint={chosen ? undefined : "Select a vehicle"} />}
        />
        {chosen && trail.error && (
          <p role="status" className="absolute bottom-24 left-4 z-[600] rounded-full bg-white px-3 py-1 text-[12px] text-go-danger-strong shadow">
            Trail not available ·{" "}
            <button type="button" className="underline" onClick={trail.refresh}>
              Retry
            </button>
          </p>
        )}
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
            trailPoints={trail.data?.length ?? null}
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
