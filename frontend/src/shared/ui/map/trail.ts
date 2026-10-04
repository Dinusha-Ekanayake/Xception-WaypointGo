import type { TrailPointView } from "@shared/domain/types";
import { num, type LatLon } from "./geo.ts";

// The trail the server holds for a trip, as every map draws it. Pure, so Node
// tests reach it; `readTripTrail` in index.tsx does the reading.

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
  return (await readTrailPages(load, maxPages)).points;
}

/**
 * Every page, and the newest point's `recordedAt` exactly as the server wrote
 * it (low quality ones included), so the next read can ask only for what came
 * after it without losing a microsecond to a Date.
 */
export async function readTrailPages(
  load: (cursor: string | null) => Promise<{ items: TrailPointView[]; nextCursor?: string | null }>,
  maxPages = 20,
): Promise<{ points: TrailPoint[]; last: string | null }> {
  const points: TrailPoint[] = [];
  let last: string | null = null;
  let cursor: string | null = null;
  for (let i = 0; i < maxPages; i++) {
    const page = await load(cursor);
    points.push(...trailPoints(page.items));
    last = page.items.at(-1)?.recordedAt ?? last;
    cursor = page.nextCursor ?? null;
    if (!cursor) break;
  }
  return { points, last };
}

/**
 * The line to draw for a vehicle on the move: the trail so far, then its live
 * position when that is newer than the trail's end, so the line reaches the
 * truck between trail reads instead of trailing behind it.
 */
export function withLive(trail: TrailPoint[], live: TrailPoint | null): TrailPoint[] {
  const end = trail.at(-1);
  if (!live || (end && live.at <= end.at)) return trail;
  return [...trail, live];
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
