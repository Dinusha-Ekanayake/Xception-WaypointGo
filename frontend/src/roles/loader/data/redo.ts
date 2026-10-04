import type { Command } from "@shared/api/commands";
import type { StoredEntry } from "@shared/offline";

// Redoing a loader check the server held as a conflict (issue #28, sync:Resolve).
// The loader's writes carry the trip's manifest version, and each one applied
// moves it on by one (useTrip). So a redo is checked against the version the
// server holds now plus the loader's writes for that trip still waiting on this
// device, which will apply first.

export function tripOf(entry: StoredEntry): string | null {
  const payload = (entry.payload as Command | null)?.payload as { tripId?: unknown } | null | undefined;
  return typeof payload?.tripId === "string" ? payload.tripId : null;
}

export function redoVersion(tripId: string, current: number, waiting: StoredEntry[]): number {
  return current + waiting.filter((e) => e.kind.startsWith("loading:") && tripOf(e) === tripId).length;
}
