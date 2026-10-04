import type { ReadyTripView } from "../../../shared/domain/loading.ts";
import { addDays } from "../../../shared/wording/index.ts";

// Which day the dock board shows (issue #114). Crews load at night for trips
// that leave early the next morning, so "tonight's departures" is not always
// today's date: it is the first day, from today, with a trip still to load.
// After the last of today's trips is released the board moves on, and on a
// Saturday it finds Monday's published plan.

/** How far ahead the dock looks for trips still to load. */
export const DOCK_LOOK_AHEAD_DAYS = 7;

/** A trip still to load: anything not yet released. */
export const stillToLoad = (trips: ReadyTripView[]): boolean => trips.some((trip) => trip.status !== "COMPLETED");

/**
 * The first of today and the following days whose trips are not all released,
 * else today. `readTrips` is asked one day at a time and the search stops at
 * the first hit.
 */
export async function dockDay(today: string, readTrips: (date: string) => Promise<ReadyTripView[]>): Promise<string> {
  for (let ahead = 0; ahead <= DOCK_LOOK_AHEAD_DAYS; ahead++) {
    const date = addDays(today, ahead);
    if (stillToLoad(await readTrips(date))) return date;
  }
  return today;
}
