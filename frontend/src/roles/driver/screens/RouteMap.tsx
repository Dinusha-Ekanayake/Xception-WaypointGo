"use client";

import { useEffect, useMemo, useState } from "react";
import { request } from "@shared/api/client";
import type { OutletView, TrailPointView } from "@shared/domain/types";
import { clock, hhmm } from "@shared/wording";
import { cx } from "@shared/ui";
import { LiveMap, metres, num, type LatLon, type MapLine, type MapMarker } from "@shared/ui/map";
import type { Stop } from "../data/run.ts";
import type { PositionRecorder } from "../data/position.ts";
import { BackIcon } from "../ui.tsx";

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

/** Straight-line distance, which a road route is never shorter than. */
export function distanceText(m: number): string {
  return m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(m < 10000 ? 1 : 0)} km`;
}

/** The store's receiving window, when the run carries one. */
function windowText(outlet: OutletView | undefined): string | null {
  const open = outlet?.effectiveWindowOpen ?? outlet?.windowOpen;
  const close = outlet?.effectiveWindowClose ?? outlet?.windowClose;
  return open && close ? `${hhmm(open)}-${hhmm(close)}` : null;
}

export default function RouteMap({
  next,
  outlet,
  recorder,
  syncedAt,
  onBack,
  className = "h-[calc(100dvh-64px)] w-full",
}: {
  next: Stop;
  outlet: OutletView | undefined;
  recorder: PositionRecorder;
  syncedAt: Date | null;
  /** The enlarged map: Back sits on the map, above the tiles. */
  onBack?: () => void;
  /** Its size: below the header on the Map screen; the whole pane beside the run on a landscape tablet. */
  className?: string;
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
    <div className={cx("relative", className)}>
      <LiveMap markers={markers} lines={lines} fit={fit} className="h-full w-full" background="var(--color-go-subtle)" overlay={
        <>
          {syncedAt && <span className="rounded-full bg-go-card px-3 py-1 text-[13px] text-go-ink shadow">Synced {clock(syncedAt)}</span>}
        </>
      } />
      {onBack && (
        <button
          type="button"
          onClick={onBack}
          aria-label="Back to run sheet"
          className="absolute top-4 left-4 z-[700] flex h-11 items-center gap-2 rounded-full bg-go-card px-4 text-[17px] font-medium text-go-ink shadow-md active:scale-95"
        >
          <BackIcon />
          Back
        </button>
      )}
      <div className="absolute inset-x-3 bottom-[max(1rem,env(safe-area-inset-bottom))] z-[600] flex flex-col gap-2">
        {!here && (
          <p role="status" className="self-start rounded-full bg-go-card px-4 py-2 text-[13px] text-go-ink shadow">
            {recorder.state === "denied" ? "Location off · the dispatcher sees your stops only" : "Finding your position"}
          </p>
        )}
        <section aria-label="Next stop" className="flex flex-col gap-3 rounded-[24px] bg-go-card p-4 text-go-ink shadow-[0_10px_30px_rgba(0,0,0,0.18)]">
          <div className="flex items-center gap-3">
            <span aria-hidden className="flex size-10 shrink-0 items-center justify-center rounded-full bg-go-ink text-[14px] font-semibold text-go-canvas">
              {String(next.sequence).padStart(2, "0")}
            </span>
            <span className="flex min-w-0 flex-1 flex-col">
              <span className="truncate text-[17px] font-medium">{next.outletId}</span>
              <span className="truncate text-[13px] text-go-muted">
                {[outlet?.districtName, windowText(outlet)].filter(Boolean).join(" · ")}
              </span>
            </span>
            {here && dest && <span className="shrink-0 text-[15px] font-medium tabular-nums">{distanceText(metres(here, dest))}</span>}
          </div>
          {dest ? (
            <a href={navigateUrl(dest)} target="_blank" rel="noreferrer" className="flex h-14 items-center justify-center rounded-full bg-go-ink text-[17px] font-medium text-go-canvas active:scale-[0.98]">
              Navigate to {next.outletId}
            </a>
          ) : (
            <p className="text-[14px] text-go-muted">No exact location for this store yet · Approximate · {outlet?.districtName ?? "district"}</p>
          )}
        </section>
      </div>
    </div>
  );
}
