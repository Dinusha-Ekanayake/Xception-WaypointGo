"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { livePoll, useDemo } from "@shared/demo/useDemo";
import type { Resource } from "@shared/api/useResource";
import type { VehiclePositionView } from "@shared/domain/types";
import type { PositionStream } from "@shared/live/usePositionStream";
import { useTripTrail } from "@shared/live/useTripTrail";
import { clock } from "@shared/wording";
import { LiveMap, MapLegend, num, withLive, type LatLon, type MapLine, type MapMarker, type TrailPoint } from "@shared/ui/map";
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
  depotFilter = "all",
  date,
  runs,
  positions,
  depotName,
  now,
  onOpenTrip,
}: {
  depots: string[];
  /** The depot pill chosen in the header; the other depots are drawn grey, as in Figma 189:21746. */
  depotFilter?: string;
  date: string;
  runs: Run[];
  positions: PositionStream | Resource<VehiclePositionView[]>;
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

  // The run path of the chosen vehicle only (R-EXE-23): its trip from the first
  // point to now, read once and then extended, reaching the truck between reads.
  const demo = useDemo();
  const tripId = chosen?.position?.tripId ?? null;
  const trail = useTripTrail(tripId, livePoll(demo, 15_000));
  const liveAt = chosen?.position ? livePoint(chosen.position) : null;
  const path = trail.points ? withLive(trail.points, liveAt) : null;

  const markers: MapMarker[] = [];
  for (const d of depotViews.data ?? []) {
    const lat = num(d.location?.latitude);
    const lon = num(d.location?.longitude);
    if (lat === null || lon === null) continue;
    const muted = depotFilter !== "all" && depotFilter !== d.depotCode && depotFilter !== d.displayName;
    const name = `${d.displayName} depot`;
    markers.push({ id: `depot:${d.depotCode}`, kind: "depot", lat, lon, label: name, ariaLabel: name, selectable: false, faded: muted });
  }
  if (chosen && path && path.length > 1) {
    const start = path[0]!;
    markers.push({ id: "trail-start", kind: "start", lat: start.lat, lon: start.lon, label: "", ariaLabel: `${chosen.day.vehicleId} trip start, ${clock(new Date(start.at))}`, selectable: false });
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
  const lines: MapLine[] = chosen && path ? [{ id: "trail", points: path, style: "driven" }] : [];
  // The depots once they are read; a vehicle when one is chosen, so choosing
  // from the list brings it into view. Keyed on the choice, not its position:
  // a re-fit on every poll would undo the dispatcher's own pan and zoom.
  const chosenAt = chosen ? markers.find((m) => m.id === chosen.day.vehicleId) ?? null : null;
  const depotCount = depotViews.data?.length ?? 0;
  // With nothing chosen, the depots and every truck on the map, as in Figma
  // 189:21746. Keyed on which vehicles are placed, not where: a truck moving
  // never refits, one appearing or leaving does.
  const placed = markers.filter((m) => m.kind === "vehicle").map((m) => m.id).join(",");
  const pathRead = path !== null && path.length > 1;
  const fit = useMemo(
    () =>
      chosenAt
        ? // The whole run path from the trip's start to the truck, once it is read.
          pathRead
          ? path!.map(({ lat, lon }) => ({ lat, lon }))
          : [{ lat: chosenAt.lat, lon: chosenAt.lon }]
        : [
            ...(depotViews.data ?? []).map((d) => ({ lat: num(d.location?.latitude), lon: num(d.location?.longitude) })),
            ...markers.filter((m) => m.kind === "vehicle").map((m) => ({ lat: m.lat, lon: m.lon })),
          ].filter((p): p is LatLon => p.lat !== null && p.lon !== null),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [selected, chosenAt !== null, pathRead, depotCount, placed],
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
        {positions.error ? (
          <p role="status" className="absolute bottom-16 left-4 z-[600] rounded-full bg-white px-3 py-1 text-[12px] text-go-danger-strong shadow">
            Positions not updated{positions.loadedAt ? ` since ${clock(positions.loadedAt)}` : ""} ·{" "}
            <button type="button" className="underline" onClick={positions.refresh}>
              Retry
            </button>
          </p>
        ) : (
          "paused" in positions &&
          positions.paused && (
            // Rule 9: the push went quiet, so the map is on a slower poll and says so.
            <p role="status" className="absolute bottom-16 left-4 z-[600] rounded-full bg-white px-3 py-1 text-[12px] text-go-warning-text shadow">
              Live updates paused · refreshing every 15 s{positions.loadedAt ? ` · last ${clock(positions.loadedAt)}` : ""}
            </p>
          )
        )}
      </div>

      <aside aria-label="Selected vehicle" className="flex w-full flex-col gap-3 rounded-[24px] bg-white p-[18px] shadow-go-card lg:max-w-[360px]">
        {chosen ? (
          <LivePanel
            run={chosen}
            date={date}
            depotName={depotName(chosen)}
            now={now}
            trailPoints={trail.points?.length ?? null}
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

/** The vehicle's latest fix as the end of its path. */
function livePoint(position: VehiclePositionView): TrailPoint | null {
  const lat = num(position.latitude);
  const lon = num(position.longitude);
  return lat === null || lon === null ? null : { lat, lon, at: Date.parse(position.recordedAt) };
}

function summary(group: MapMarker[]): string {
  return (["on-time", "at-risk", "late", "returning", "offline"] as MapStatus[])
    .map((s) => [s, group.filter((m) => m.status === s).length] as const)
    .filter(([, n]) => n > 0)
    .map(([s, n]) => `${n} ${STATUS[s].label.toLowerCase()}`)
    .join(" · ");
}
