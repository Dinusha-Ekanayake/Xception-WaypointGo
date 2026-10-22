import type { RunSheetStopView, RunSheetView } from "../../../shared/domain/types.ts";

// "My trips" (TripHistory.tsx): the driver's run sheets of the last days as one
// card per trip.

/** How far back the driver can look. */
export const DAYS_SHOWN = 7;

export type Trip = { key: string; date: string; vehicleId: string; number: number; of: number; stops: RunSheetStopView[] };

/** The trips of a day's run sheets, each in its stop order, numbered in the order they left. */
export function tripsOf(sheets: RunSheetView[]): Trip[] {
  const out: Trip[] = [];
  for (const sheet of sheets) {
    const byTrip = new Map<string, RunSheetStopView[]>();
    for (const s of sheet.stops) byTrip.set(s.tripId, [...(byTrip.get(s.tripId) ?? []), s]);
    const trips = [...byTrip.entries()]
      .map(([tripId, stops]) => ({ tripId, stops: [...stops].sort((a, b) => a.sequence - b.sequence) }))
      .sort((a, b) => a.stops[0]!.plannedArrival.localeCompare(b.stops[0]!.plannedArrival));
    trips.forEach((t, i) =>
      out.push({ key: t.tripId, date: sheet.serviceDate, vehicleId: sheet.vehicleId, number: i + 1, of: trips.length, stops: t.stops }),
    );
  }
  return out.sort((a, b) => b.date.localeCompare(a.date) || b.number - a.number);
}
