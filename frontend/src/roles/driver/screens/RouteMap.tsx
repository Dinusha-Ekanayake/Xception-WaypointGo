"use client";

import { useEffect, useMemo, useState } from "react";
import type { OutletView } from "@shared/domain/types";
import { clock, hhmm } from "@shared/wording";
import { cx } from "@shared/ui";
import { drivenLine, LiveMap, metres, num, readTripTrail, type LatLon, type MapLine, type MapMarker, type TrailPoint } from "@shared/ui/map";
import type { Stop } from "../data/run.ts";
import type { PositionRecorder } from "../data/position.ts";
import { BackIcon } from "../ui.tsx";

const TRAIL_POLL_MS = 15_000;

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
  const [server, setServer] = useState<TrailPoint[]>([]);

  // The trail the server holds for this trip, every page of it, read again on
  // a poll so points the server records (a demo simulation) reach this map too.
  // A failed read keeps the last trail rather than wiping it with no signal.
  useEffect(() => {
    setServer([]);
    const ctrl = new AbortController();
    const load = () =>
      readTripTrail(next.tripId, ctrl.signal)
        .then((points) => { if (!ctrl.signal.aborted) setServer(points); })
        .catch(() => undefined);
    void load();
    const timer = window.setInterval(() => void load(), TRAIL_POLL_MS);
    return () => {
      window.clearInterval(timer);
      ctrl.abort();
    };
  }, [next.tripId]);

  const here = recorder.here;
  const driven = useMemo(() => drivenLine(server, recorder.trail), [server, recorder.trail]);
  // With no position from this phone, the newest server point stands in for it.
  const last = server.length > 0 ? server[server.length - 1]! : null;
  const me = here ?? (last ? { lat: last.lat, lon: last.lon, heading: null } : null);
  const markers = useMemo(() => {
    const out: MapMarker[] = [];
    if (dest) out.push({ id: "dest", kind: "store", ...dest, label: next.outletId, badge: String(next.sequence).padStart(2, "0"), ariaLabel: `Next stop ${next.outletId}`, selectable: false });
    if (me) out.push({ id: "me", kind: "vehicle", lat: me.lat, lon: me.lon, heading: me.heading, status: "on-time", label: "You", ariaLabel: "Your position", selectable: false });
    return out;
  }, [dest?.lat, dest?.lon, next.outletId, next.sequence, me?.lat, me?.lon, me?.heading]);
  const lines = useMemo(() => {
    const out: MapLine[] = [{ id: "driven", points: driven, style: "driven" }];
    if (me && dest) out.push({ id: "leg", points: [me, dest], style: "planned" });
    return out;
  }, [driven, me?.lat, me?.lon, dest?.lat, dest?.lon]);
  // Fit once per stop (and once a first position arrives), not on every move:
  // a re-fit each fix would undo the driver's own pan and zoom.
  const hasMe = me !== null;
  const fit = useMemo(
    () => [me, dest].filter((p): p is NonNullable<typeof p> => p !== null).map((p) => ({ lat: p.lat, lon: p.lon })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [next.outletId, hasMe, dest !== null],
  );

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
            {me && dest && <span className="shrink-0 text-[15px] font-medium tabular-nums">{distanceText(metres(me, dest))}</span>}
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
