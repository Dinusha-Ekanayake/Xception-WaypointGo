// Pure wording for the measured planning parameters (R-PLN-47), testable without the app.

/** R-PLN-47: a parameter as a depot actually works it, from at least ten released second trips. */
export type PlanningMeasured = { key: string; depotCode: string; median: number; p75: number; samples: number; from: string; to: string };

/** "Measured at PLG: median 28 min, 3 in 4 within 35 min, from 18 reloads in the last 4 weeks", or why there is none. */
export function measuredLine(key: string, measured: PlanningMeasured[] | undefined): string | null {
  if (key !== "trip.reload.min") return null;
  const mine = (measured ?? []).filter((m) => m.key === key);
  if (mine.length === 0) return "Not measured yet: it needs ten released second trips at a depot in the last 4 weeks.";
  return mine
    .map((m) => `Measured at ${m.depotCode}: median ${m.median} min, 3 in 4 within ${m.p75} min, from ${m.samples} reloads in the last 4 weeks.`)
    .join(" ");
}
