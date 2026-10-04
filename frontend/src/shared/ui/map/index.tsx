"use client";

import dynamic from "next/dynamic";
import { request } from "@shared/api/client";
import type { TrailPointView } from "@shared/domain/types";
import { readTrail, readTrailPages, type TrailPoint } from "./trail.ts";
import type { MapStatus } from "./types.ts";

// Leaflet reads `window` when imported, so the canvas is loaded on the client
// only (next/dynamic with ssr: false, which must sit in a client component).
export const LiveMap = dynamic(() => import("./MapCanvas.tsx"), {
  ssr: false,
  loading: () => <div className="h-full w-full animate-pulse bg-go-subtle" aria-label="Loading the map" />,
});

export type { LiveMapProps, MapLine, MapMarker, MapStatus } from "./types.ts";
export { drivenLine, readTrail, readTrailPages, trailPoints, withLive, type TrailPoint } from "./trail.ts";
export { bearing, cluster, compass, metres, num, round6, keepTiles, SAMPLE_MS, takeSample, tileAllowed, tilesFor, travelBearing, type LatLon } from "./geo.ts";

const LEGEND: { status: MapStatus; label: string }[] = [
  { status: "on-time", label: "On time" },
  { status: "at-risk", label: "At risk" },
  { status: "late", label: "Late" },
  { status: "returning", label: "Returning" },
  { status: "offline", label: "Offline" },
];

export const STATUS_COLOR: Record<MapStatus, string> = {
  "on-time": "var(--color-go-teal)",
  "at-risk": "var(--color-go-warning)",
  late: "var(--color-go-danger)",
  returning: "var(--color-go-info)",
  offline: "var(--color-go-offline)",
};

export function MapLegend({ hint }: { hint?: string }): React.JSX.Element {
  return (
    <div className="flex h-[30px] w-max max-w-full items-center gap-3 overflow-hidden rounded-full bg-white px-3 text-[12px] whitespace-nowrap text-go-ink shadow">
      {LEGEND.map((item) => (
        <span key={item.status} className="flex shrink-0 items-center gap-1.5">
          <span className="h-2 w-2 rounded-full" style={{ background: STATUS_COLOR[item.status] }} />
          {item.label}
        </span>
      ))}
      {hint && <span className="shrink-0 text-go-teal">{hint}</span>}
    </div>
  );
}

/**
 * A trip's trail after `since` (exclusive; null for all of it), every page,
 * oldest first, with the raw time of its newest point for the next read.
 */
export function readTripTrailSince(tripId: string, since: string | null, signal: AbortSignal): Promise<{ points: TrailPoint[]; last: string | null }> {
  const path = `/api/execution/trips/${encodeURIComponent(tripId)}/trail?limit=500${since ? `&since=${encodeURIComponent(since)}` : ""}`;
  return readTrailPages((cursor) =>
    request<{ items: TrailPointView[]; nextCursor?: string | null }>(cursor ? `${path}&cursor=${encodeURIComponent(cursor)}` : path, { signal }),
  );
}

/** A trip's whole trail, every page, oldest first, as all three maps draw it. */
export function readTripTrail(tripId: string, signal: AbortSignal): Promise<TrailPoint[]> {
  const path = `/api/execution/trips/${encodeURIComponent(tripId)}/trail?limit=500`;
  return readTrail((cursor) =>
    request<{ items: TrailPointView[]; nextCursor?: string | null }>(cursor ? `${path}&cursor=${encodeURIComponent(cursor)}` : path, { signal }),
  );
}
