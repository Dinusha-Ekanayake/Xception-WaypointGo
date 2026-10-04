// The demo clock's date and time arithmetic (issue #231). Pure, so it is tested
// on its own: every instant is read and written in depot time (Asia/Colombo,
// UTC+05:30, no daylight saving), whatever the presenter's laptop is set to.

const DEPOT_OFFSET_MS = 5.5 * 3_600_000;
export const HOUR_MS = 3_600_000;
export const DAY_MS = 24 * HOUR_MS;

/** How far the demo clock may move from real time, either way (DemoSettings.MAX_OFFSET_SECONDS). */
export const MAX_OFFSET_MS = 7 * DAY_MS;

/** The depot date ("2026-10-05") and time ("16:05") of an instant. */
export function depotParts(at: Date | string): { date: string; time: string } {
  const shifted = new Date(new Date(at).getTime() + DEPOT_OFFSET_MS).toISOString();
  return { date: shifted.slice(0, 10), time: shifted.slice(11, 16) };
}

/** The instant of a depot date and time. Null when either is not a valid value. */
export function depotAt(date: string, time: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) return null;
  const at = new Date(`${date}T${time}:00+05:30`);
  return Number.isNaN(at.getTime()) ? null : at;
}

/** The calendar day `days` after a depot date. */
export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/**
 * Real time, worked out from the demo's own reading: what the server's clock
 * says now, less the offset it applies.
 */
export function realNow(demoNow: string, offsetSeconds: number): Date {
  return new Date(new Date(demoNow).getTime() - offsetSeconds * 1000);
}

/** The window the demo clock may be set in: seven days either side of real time. */
export function clockWindow(demoNow: string, offsetSeconds: number): { min: Date; max: Date } {
  const real = realNow(demoNow, offsetSeconds).getTime();
  return { min: new Date(real - MAX_OFFSET_MS), max: new Date(real + MAX_OFFSET_MS) };
}

export function inWindow(target: Date, window: { min: Date; max: Date }): boolean {
  return target.getTime() >= window.min.getTime() && target.getTime() <= window.max.getTime();
}

/** "3 h 15 min ahead of real time", "1 day 2 h behind real time", "On real time". */
export function offsetText(offsetSeconds: number): string {
  const minutes = Math.round(Math.abs(offsetSeconds) / 60);
  if (minutes === 0) return "On real time";
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor((minutes % 1440) / 60);
  const mins = minutes % 60;
  const parts = [days ? `${days} ${days === 1 ? "day" : "days"}` : "", hours ? `${hours} h` : "", mins ? `${mins} min` : ""].filter(Boolean);
  return `${parts.join(" ")} ${offsetSeconds > 0 ? "ahead of" : "behind"} real time`;
}

/** The steps the control room offers, smallest first on each side. */
export const STEPS: Array<{ label: string; ms: number }> = [
  { label: "-1 day", ms: -DAY_MS },
  { label: "-1 h", ms: -HOUR_MS },
  { label: "-15 min", ms: -15 * 60_000 },
  { label: "+15 min", ms: 15 * 60_000 },
  { label: "+1 h", ms: HOUR_MS },
  { label: "+1 day", ms: DAY_MS },
];

/** The demo clock moved by `ms` from where it reads now. */
export function stepped(demoNow: string, ms: number): Date {
  return new Date(new Date(demoNow).getTime() + ms);
}
