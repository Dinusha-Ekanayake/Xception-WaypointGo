// Depot scope and the operating day, as the dispatcher sees them. Dates are the
// depot's, Asia/Colombo, never the browser's: a dispatcher travelling abroad
// still plans Colombo's day.

const ZONE = "Asia/Colombo";

/** "all" means every depot in the session's scope, which the server already limits. */
export type DepotFilter = "all" | string;

export function depotsFor(filter: DepotFilter, scope: string[]): string[] {
  return filter === "all" ? scope : scope.filter((depot) => depot === filter);
}

export function scopeLabel(filter: DepotFilter, scope: string[]): string {
  if (filter !== "all") return filter;
  return scope.length === 2 ? "both depots" : scope.length === 1 ? scope[0]! : `${scope.length} depots`;
}

/** Today's date in the depot timezone, as yyyy-mm-dd. */
export function depotToday(now: Date = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: ZONE }).format(now);
}

export function depotHour(now: Date = new Date()): number {
  return Number(new Intl.DateTimeFormat("en-GB", { timeZone: ZONE, hour: "numeric", hourCycle: "h23" }).format(now));
}

export function greeting(now: Date = new Date()): string {
  const hour = depotHour(now);
  return hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
}

/** "Mon 28 Sep · 4:12 PM" in depot time. */
export function depotStamp(now: Date = new Date()): string {
  const day = new Intl.DateTimeFormat("en-GB", { timeZone: ZONE, weekday: "short", day: "numeric", month: "short" }).format(now);
  const time = new Intl.DateTimeFormat("en-US", { timeZone: ZONE, hour: "numeric", minute: "2-digit" }).format(now);
  return `${day.replace(",", "")} · ${time}`;
}

/** "Thu 1 Oct" for a yyyy-mm-dd date. */
export function formatDay(isoDate: string): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  const date = new Date(Date.UTC(y!, m! - 1, d!));
  return new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", weekday: "short", day: "numeric", month: "short" })
    .format(date)
    .replace(",", "");
}
