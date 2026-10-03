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
