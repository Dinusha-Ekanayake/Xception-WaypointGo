// Pure map arithmetic shared by the tile proxy, the map and the driver's
// recorder. No browser and no Leaflet here, so Node tests reach all of it.

/** Sri Lanka, as R-EXE-18 bounds a fix. Tiles outside it are refused. */
export const SRI_LANKA = { south: 5.8, north: 9.9, west: 79.5, east: 82.0 } as const;
export const MIN_ZOOM = 5;
export const MAX_ZOOM = 17;

export type LatLon = { lat: number; lon: number };

function tileX(lon: number, z: number): number {
  return Math.floor(((lon + 180) / 360) * 2 ** z);
}

function tileY(lat: number, z: number): number {
  const r = (lat * Math.PI) / 180;
  return Math.floor(((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2) * 2 ** z);
}

/** True for a tile inside the zoom range and the Sri Lanka box, so the proxy is never an open relay. */
export function tileAllowed(z: number, x: number, y: number): boolean {
  if (![z, x, y].every(Number.isInteger) || z < MIN_ZOOM || z > MAX_ZOOM) return false;
  return (
    x >= tileX(SRI_LANKA.west, z) && x <= tileX(SRI_LANKA.east, z) &&
    y >= tileY(SRI_LANKA.north, z) && y <= tileY(SRI_LANKA.south, z)
  );
}

/**
 * The tiles a run's map will need, so a phone can keep them while it has a
 * signal (issue #201): the whole area of the stops at the overview zooms, and
 * the streets around each stop at the close ones. Overview first, then by
 * stop, and never more than `cap`, which stays under the worker's tile cache
 * (scripts/sw-cache.mjs TILE_LIMIT) so prefetching never evicts the map in use.
 */
export function tilesFor(points: LatLon[], cap = 600, overview = [9, 10, 11, 12, 13], close = [14, 15]): string[] {
  if (points.length === 0) return [];
  const out = new Set<string>();
  const add = (z: number, x: number, y: number) => {
    if (out.size < cap && tileAllowed(z, x, y)) out.add(`/map-tiles/${z}/${x}/${y}.png`);
  };
  const lats = points.map((p) => p.lat);
  const lons = points.map((p) => p.lon);
  for (const z of overview) {
    // One tile of margin around the stops, for the road in and out.
    for (let x = tileX(Math.min(...lons), z) - 1; x <= tileX(Math.max(...lons), z) + 1; x++)
      for (let y = tileY(Math.max(...lats), z) - 1; y <= tileY(Math.min(...lats), z) + 1; y++) add(z, x, y);
  }
  for (const z of close)
    for (const p of points)
      for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) add(z, tileX(p.lon, z) + dx, tileY(p.lat, z) + dy);
  return [...out];
}

/** Great-circle distance in metres. */
export function metres(a: LatLon, b: LatLon): number {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLon = (b.lon - a.lon) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * 6_371_000 * Math.asin(Math.sqrt(h));
}

/**
 * The recorder keeps a fix when 30 seconds have passed or the phone moved
 * 150 metres since the last kept one (issue #161, 4.3).
 */
export function keepFix(last: { at: number; where: LatLon } | null, at: number, here: LatLon): boolean {
  if (!last) return true;
  return at - last.at >= 30_000 || metres(last.where, here) >= 150;
}

/** Six decimals at most, as the server accepts (R-EXE-18). */
export function round6(value: number): number {
  return Math.round(value * 1e6) / 1e6;
}

export type Clusterable = { id: string; x: number; y: number };

/**
 * Groups screen points within `radius` pixels on a grid: one cluster per
 * occupied cell, so the result does not depend on input order beyond the cell.
 */
export function cluster<T extends Clusterable>(points: T[], radius = 48): T[][] {
  const cells = new Map<string, T[]>();
  for (const p of points) {
    const key = `${Math.floor(p.x / radius)}:${Math.floor(p.y / radius)}`;
    const cell = cells.get(key);
    if (cell) cell.push(p);
    else cells.set(key, [p]);
  }
  return [...cells.values()];
}

/** Eight compass headings, as the Figma truck marker set draws them. */
export function compass(headingDeg: number | null): "N" | "NE" | "E" | "SE" | "S" | "SW" | "W" | "NW" {
  const names = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"] as const;
  if (headingDeg === null || !Number.isFinite(headingDeg)) return "N";
  return names[Math.round((((headingDeg % 360) + 360) % 360) / 45) % 8];
}

/** Coordinates arrive as decimal strings or numbers. */
export function num(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

/**
 * Fetches the tiles so the service worker keeps them (cache first, so a tile
 * already kept costs nothing). Four at a time, quietly: a tile that fails is
 * only one the map draws plain later. Stops when `signal` aborts.
 */
export async function keepTiles(urls: string[], signal: AbortSignal): Promise<void> {
  let next = 0;
  const worker = async () => {
    while (!signal.aborted && next < urls.length) {
      const url = urls[next++]!;
      await fetch(url, { signal }).catch(() => undefined);
    }
  };
  await Promise.all([worker(), worker(), worker(), worker()]);
}
