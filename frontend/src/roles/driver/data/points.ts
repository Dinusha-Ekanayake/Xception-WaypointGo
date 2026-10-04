import type { PositionPoint } from "@shared/domain/types";

// Pure shaping of phone fixes into what delivery:RecordPositions accepts
// (R-EXE-18): six decimals on coordinates, one decimal on sensor values,
// heading below 360, at most 100 points a batch.

const round6 = (value: number) => Math.round(value * 1e6) / 1e6;

/** One batch per flush, at most 100 points as the server accepts (R-EXE-18). */
export function batches(points: PositionPoint[], size = 100): PositionPoint[][] {
  const out: PositionPoint[][] = [];
  for (let i = 0; i < points.length; i += size) out.push(points.slice(i, i + size));
  return out;
}

export function toPoint(c: { latitude: number; longitude: number; accuracy?: number | null; heading?: number | null; speed?: number | null }, at: number): PositionPoint {
  const point: PositionPoint = { recordedAt: new Date(at).toISOString(), latitude: round6(c.latitude), longitude: round6(c.longitude) };
  if (c.accuracy != null && Number.isFinite(c.accuracy)) point.accuracyM = Math.round(c.accuracy * 10) / 10;
  if (c.heading != null && Number.isFinite(c.heading)) point.headingDeg = Math.min(359.9, Math.round(c.heading * 10) / 10);
  if (c.speed != null && Number.isFinite(c.speed)) point.speedKmh = Math.round(c.speed * 36) / 10;
  return point;
}

