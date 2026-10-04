import { metres, type LatLon } from "../../../shared/ui/map/geo.ts";
import type { Stop } from "./run.ts";

// When the phone records the vehicle's position (R-EXE-23). Pure, so the rule
// is tested without a browser: from the moment the driver starts the trip,
// through every stop, and on the drive back, until the vehicle is back at its
// depot. Not before: a phone at the depot with the run sheet open records nothing.

/** Within this distance of the depot the vehicle is back, and recording ends. */
export const BACK_AT_DEPOT_M = 200;

/** A return leg that never reaches the depot (phone left in the cab) stops after this long. */
export const RETURN_LIMIT_MS = 4 * 60 * 60 * 1000;

export type RecordingStop = Pick<Stop, "tripId" | "outcome" | "startedAt" | "completedAt">;

const started = (stop: RecordingStop) => stop.startedAt !== null || stop.outcome !== "PENDING";
const finished = (stop: RecordingStop) => stop.outcome !== "PENDING" && stop.outcome !== "ARRIVED";

/** Within the depot's radius: the vehicle is back. */
export function backAtDepot(here: LatLon | null, depot: LatLon | null): boolean {
  return here !== null && depot !== null && metres(here, depot) <= BACK_AT_DEPOT_M;
}

/**
 * The trip to record positions for now, or null to record nothing.
 *
 * - The trip is the latest one, in stop order, the driver has started: starting
 *   the next trip ends the one before.
 * - It records while any of its stops is still to do.
 * - Once every stop is closed it keeps recording the drive back, until the
 *   vehicle is within {@link BACK_AT_DEPOT_M} of the depot, or
 *   {@link RETURN_LIMIT_MS} has passed since the last stop. No control on the
 *   phone: it ends by itself.
 *
 * @param stops the day's stops in run order
 * @param ended trips already back at the depot, kept on the phone across reloads
 */
export function recordingTrip(
  stops: RecordingStop[],
  { here, depot, ended, now }: { here: LatLon | null; depot: LatLon | null; ended: ReadonlySet<string>; now: number },
): string | null {
  const trip = [...stops].reverse().find(started)?.tripId ?? null;
  if (trip === null || ended.has(trip)) return null;
  const own = stops.filter((stop) => stop.tripId === trip);
  if (own.some((stop) => !finished(stop))) return trip;
  if (backAtDepot(here, depot)) return null;
  const closed = Math.max(...own.map((stop) => (stop.completedAt ? Date.parse(stop.completedAt) : Number.NaN)).filter(Number.isFinite));
  if (Number.isFinite(closed) && now - closed > RETURN_LIMIT_MS) return null;
  return trip;
}

/** True while the trip's stops are all closed and the vehicle is driving back. */
export function returning(stops: RecordingStop[], trip: string | null): boolean {
  if (trip === null) return false;
  const own = stops.filter((stop) => stop.tripId === trip);
  return own.length > 0 && own.every(finished);
}
