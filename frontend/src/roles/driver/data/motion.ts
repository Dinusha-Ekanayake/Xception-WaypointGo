import { metres } from "../../../shared/ui/map/geo.ts";

// Whether the vehicle is moving, for the gate on the driver's messages
// (R-EXE-28): no phone use while driving (Motor Traffic Act s.152A). Pure, so
// the rule is tested without a browser. The speed is smoothed over the last
// three fixes so one jumpy reading neither locks nor frees the screen; a fix
// with no speed of its own takes the speed from the distance to the one before.

/** At or above this smoothed speed the vehicle is moving. */
export const MOVING_KMH = 8;
/** A fix older than this says nothing about now. */
export const FRESH_MS = 30_000;

export type Motion = "moving" | "stopped" | "unknown";
export type MotionFix = { at: number; lat: number; lon: number; speedKmh: number | null };

export function motion(fixes: MotionFix[], now: number): Motion {
  const fresh = fixes.filter((f) => now - f.at <= FRESH_MS).sort((a, b) => a.at - b.at);
  const speeds: number[] = [];
  for (let i = Math.max(0, fresh.length - 3); i < fresh.length; i++) {
    const f = fresh[i]!;
    if (f.speedKmh !== null && Number.isFinite(f.speedKmh)) {
      speeds.push(Math.max(0, f.speedKmh));
    } else if (i > 0) {
      const before = fresh[i - 1]!;
      const seconds = (f.at - before.at) / 1000;
      if (seconds > 0) speeds.push((metres(before, f) / seconds) * 3.6);
    }
  }
  if (speeds.length === 0) return "unknown";
  return speeds.reduce((a, b) => a + b, 0) / speeds.length >= MOVING_KMH ? "moving" : "stopped";
}
