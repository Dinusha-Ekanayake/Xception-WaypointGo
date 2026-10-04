export { dayLabel, depotHour, depotStamp, depotToday, greeting } from "../../../shared/wording/index.ts";
import { addDays } from "../../../shared/wording/index.ts";
// Depot scope and the operating day, as the dispatcher sees them. Dates are the
// depot's, Asia/Colombo, never the browser's: a dispatcher travelling abroad
// still plans Colombo's day.

/** "all" means every depot in the session's scope, which the server already limits. */
export type DepotFilter = "all" | string;

export function depotsFor(filter: DepotFilter, scope: string[]): string[] {
  return filter === "all" ? scope : scope.filter((depot) => depot === filter);
}

export function scopeLabel(filter: DepotFilter, scope: string[]): string {
  if (filter !== "all") return filter;
  return scope.length === 2 ? "both depots" : scope.length === 1 ? scope[0]! : `${scope.length} depots`;
}


/** R-ORD-01: a run's ordering closes at 16:00, depot time, on the calendar day before it (A-22). */
export const CUTOFF_HOUR = 16;

/** The run being planned: today's until the 16:00 cutoff, then tomorrow's. */
export function nextRun(today: string, hour: number): string {
  return hour >= CUTOFF_HOUR ? addDays(today, 1) : today;
}
