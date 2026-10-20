import type { ReadyTripView, RunSheetView } from "../../../shared/domain/types.ts";

// Which service date Live opens on. A trip belongs to the day its stores receive
// the goods, but its work starts the evening before: after the 16:00 cutoff the
// next run is planned, loaded and may leave before midnight. So Live follows the
// work, not the calendar: today while any of today's trips is still out, then
// the next run as soon as one of its trips is being loaded or has left. The day
// picker still shows any day; a day chosen there is kept.

type Day = { sheets: RunSheetView[]; dock: ReadyTripView[] } | null | undefined;

/** A released trip with a stop not yet completed. */
export const onTheRoad = (day: Day): boolean =>
  (day?.sheets ?? []).some((sheet) => sheet.stops.some((stop) => stop.completedAt === null));

/** The run has begun: a trip of it is being loaded, or has left. */
export const begun = (day: Day): boolean =>
  (day?.sheets ?? []).length > 0 || (day?.dock ?? []).some((trip) => trip.status !== "NOT_STARTED");

export function liveDay(today: string, run: string, todayLive: Day, runLive: Day): string {
  if (onTheRoad(todayLive)) return today;
  return begun(runLive) ? run : today;
}
