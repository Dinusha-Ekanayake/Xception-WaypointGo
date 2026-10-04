import type { DayOutlookView, OutlookStatus } from "../../../shared/domain/types.ts";
import type { Tone } from "../../../shared/ui/primitives.tsx";

// Issue #224 (R-ML-07): how likely a delivery day is to be kept, as the store
// sees it while choosing one, and the day to suggest instead. Advice only: the
// order goes for the day the manager picks, and the plan the day before decides.

/** How far ahead a store chooses a day: four weeks (P-34). */
export const BOOKING_DAYS = 28;

const CHIP: Record<OutlookStatus, { label: string; tone: Tone }> = {
  ON_TRACK: { label: "On track", tone: "success" },
  BUSY: { label: "Busy", tone: "warning" },
  AT_RISK: { label: "At risk", tone: "danger" },
  TOO_EARLY: { label: "Too early", tone: "muted" },
  CLOSED: { label: "Closed", tone: "muted" },
};

export function outlookChip(status: OutlookStatus): { label: string; tone: Tone } {
  return CHIP[status];
}

/** A busy or at-risk day is worth a word before the order is sent. */
export function needsWarning(day: DayOutlookView | undefined): boolean {
  return day?.status === "BUSY" || day?.status === "AT_RISK";
}

/**
 * The on-track day to suggest instead of a busy one: a day a trip already
 * serves the district first (R-ORD-13), then the nearest, the earlier on a tie.
 * Never a day before the first one still open, and none when nothing is on track.
 */
export function suggestDay(days: DayOutlookView[], chosen: string, shared: string[] = []): string | null {
  const target = Date.parse(`${chosen}T00:00:00Z`);
  const distance = (d: string) => Math.abs(Date.parse(`${d}T00:00:00Z`) - target);
  const candidates = days.filter((d) => d.status === "ON_TRACK" && d.date !== chosen);
  if (candidates.length === 0) return null;
  const ranked = [...candidates].sort((a, b) => {
    const sharedA = shared.includes(a.date) ? 0 : 1;
    const sharedB = shared.includes(b.date) ? 0 : 1;
    if (sharedA !== sharedB) return sharedA - sharedB;
    const byDistance = distance(a.date) - distance(b.date);
    return byDistance !== 0 ? byDistance : a.date.localeCompare(b.date);
  });
  return ranked[0].date;
}
