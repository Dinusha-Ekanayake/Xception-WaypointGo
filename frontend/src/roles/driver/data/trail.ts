import type { TrailPointView } from "@shared/domain/types";
import { num, type LatLon } from "../../../shared/ui/map/geo.ts";

// The trail the server holds for a trip, as the driver's map draws it. Pure,
// so Node tests reach it; RouteMap does the reading.

/** `at` in epoch milliseconds, as the phone stamped it. */
export type TrailPoint = LatLon & { at: number };

/** Usable points only: a low quality fix or a missing coordinate is skipped, never drawn at 0,0. */
export function trailPoints(items: TrailPointView[]): TrailPoint[] {
  const out: TrailPoint[] = [];
  for (const p of items) {
    if (p.lowQuality) continue;
    const lat = num(p.latitude);
    const lon = num(p.longitude);
    if (lat !== null && lon !== null) out.push({ lat, lon, at: Date.parse(p.recordedAt) });
  }
  return out;
}

/** Every page of a trip's trail, oldest first, so a long trip keeps its newest part. */
export async function readTrail(
  load: (cursor: string | null) => Promise<{ items: TrailPointView[]; nextCursor?: string | null }>,
  maxPages = 20,
): Promise<TrailPoint[]> {
  const out: TrailPoint[] = [];
  let cursor: string | null = null;
  for (let i = 0; i < maxPages; i++) {
    const page = await load(cursor);
    out.push(...trailPoints(page.items));
    cursor = page.nextCursor ?? null;
    if (!cursor) break;
  }
  return out;
}

/**
 * The line driven so far: the server's trail, then only what this phone kept
 * after the server's newest point, so a point already sent is not drawn twice.
 */
export function drivenLine(server: TrailPoint[], local: TrailPoint[]): LatLon[] {
  const newest = server.length > 0 ? server[server.length - 1]!.at : -Infinity;
  const pending = local.filter((p) => p.at > newest);
  return [...server, ...pending].map(({ lat, lon }) => ({ lat, lon }));
}
