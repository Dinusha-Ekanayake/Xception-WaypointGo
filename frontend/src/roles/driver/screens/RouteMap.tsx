"use client";

import { useEffect, useMemo, useState } from "react";
import { request } from "@shared/api/client";
import type { OutletView, TrailPointView } from "@shared/domain/types";
import { clock } from "@shared/wording";
import { LiveMap, num, type LatLon, type MapLine, type MapMarker } from "@shared/ui/map";
import type { Stop } from "../data/run.ts";
import type { PositionRecorder } from "../data/position.ts";

// Figma "Route: Map" (83:2164): own position with heading, the trail driven so
// far, a dashed leg to the next stop and its pin. Turn by turn is the phone's
// maps app (D4): Navigate hands off, and only to an exact store location.

export function exactPoint(outlet: OutletView | undefined): LatLon | null {
  const loc = outlet?.location;
  if (!loc || loc.precision !== "exact") return null;
  const lat = num(loc.latitude);
  const lon = num(loc.longitude);
  return lat === null || lon === null ? null : { lat, lon };
}

export function navigateUrl(to: LatLon): string {
  return `https://www.google.com/maps/dir/?api=1&destination=${to.lat},${to.lon}`;
}

export default function RouteMap({
  next,
  outlet,
  recorder,
  syncedAt,
}: {
  next: Stop;
  outlet: OutletView | undefined;
  recorder: PositionRecorder;
  syncedAt: Date | null;
}): React.JSX.Element {
  const dest = exactPoint(outlet);
  const [server, setServer] = useState<LatLon[]>([]);

  // The trail the server already holds for this trip, then what this phone adds.
  useEffect(() => {
    const ctrl = new AbortController();
    request<{ items: TrailPointView[] }>(`/api/execution/trips/${encodeURIComponent(next.tripId)}/trail?limit=200`, { signal: ctrl.signal })
      .then((page) => setServer(page.items.filter((p) => !p.lowQuality).map((p) => ({ lat: num(p.latitude) ?? 0, lon: num(p.longitude) ?? 0 }))))
      .catch(() => setServer([]));
    return () => ctrl.abort();
  }, [next.tripId]);

  const here = recorder.here;
  const driven = useMemo(() => [...server, ...recorder.trail], [server, recorder.trail]);
  const markers: MapMarker[] = [];
  if (dest) markers.push({ id: "dest", kind: "store", ...dest, label: next.outletId, badge: String(next.sequence).padStart(2, "0"), ariaLabel: `Next stop ${next.outletId}`, selectable: false });
  if (here) markers.push({ id: "me", kind: "vehicle", lat: here.lat, lon: here.lon, heading: here.heading, status: "on-time", label: "You", ariaLabel: "Your position", selectable: false });
  const lines: MapLine[] = [{ id: "driven", points: driven, style: "driven" }];
  if (here && dest) lines.push({ id: "leg", points: [here, dest], style: "planned" });
  const fit = [here, dest].filter((p): p is NonNullable<typeof p> => p !== null).map((p) => ({ lat: p.lat, lon: p.lon }));

  return (
    <div className="relative h-[calc(100dvh-64px)] w-full">
      <LiveMap markers={markers} lines={lines} fit={fit} className="h-full w-full" background="var(--color-go-subtle)" overlay={
        <>
          {syncedAt && <span className="rounded-full bg-white px-3 py-1 text-[13px] text-go-ink shadow">Synced {clock(syncedAt)}</span>}
        </>
      } />
      <div className="absolute inset-x-4 bottom-6 z-[600] flex flex-col gap-2">
        {!here && (
          <p role="status" className="rounded-[16px] bg-white px-4 py-3 text-[14px] text-go-ink shadow">
            {recorder.state === "denied" ? "Location off · the dispatcher sees your stops only" : "Finding your position"}
          </p>
        )}
        {dest ? (
          <a href={navigateUrl(dest)} target="_blank" rel="noreferrer" className="flex h-14 items-center justify-center rounded-full bg-go-ink text-[17px] font-medium text-go-canvas">
            Navigate to {next.outletId}
          </a>
        ) : (
          <p className="rounded-[16px] bg-white px-4 py-3 text-[14px] text-go-ink shadow">No exact location for this store yet · Approximate · {outlet?.districtName ?? "district"}</p>
        )}
      </div>
    </div>
  );
}
