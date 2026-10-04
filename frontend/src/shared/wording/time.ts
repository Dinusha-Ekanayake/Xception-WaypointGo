import type { IsoDate } from "../domain/common.ts";
import { businessNow } from "./now.ts";

// Every time and date a person reads, in one place (docs/architecture/GLOSSARY.md,
// "Time"). Depot time, Asia/Colombo, on a 24-hour clock, whatever the device's own
// zone: a dispatcher travelling abroad still plans Colombo's day.

export const DEPOT_ZONE = "Asia/Colombo";

const TIME = new Intl.DateTimeFormat("en-GB", { timeZone: DEPOT_ZONE, hour: "2-digit", minute: "2-digit", hour12: false });
const DAY = new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", weekday: "short", day: "numeric", month: "short" });
const LONG_DAY = new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", weekday: "short", day: "numeric", month: "long" });
const TODAY = new Intl.DateTimeFormat("en-CA", { timeZone: DEPOT_ZONE });
const HOUR = new Intl.DateTimeFormat("en-GB", { timeZone: DEPOT_ZONE, hour: "numeric", hourCycle: "h23" });

/**
 * "06:40": the time of day in depot time.
 *
 * Takes an instant (ISO string or Date), or a depot wall-clock time such as
 * "06:40:00", which is shown as it is. Nothing yet reads "--:--".
 */
export function clock(value: string | Date | null | undefined): string {
  if (!value) return "--:--";
  if (typeof value === "string" && /^\d{2}:\d{2}/.test(value)) return value.slice(0, 5);
  const date = typeof value === "string" ? new Date(value) : value;
  return Number.isNaN(date.getTime()) ? "--:--" : TIME.format(date);
}

/** "05:00" from a depot wall-clock time such as "05:00:00". */
export function hhmm(time: string): string {
  return time.slice(0, 5);
}

/** "Thu 1 Oct" for a yyyy-mm-dd date: the calendar day, never shifted by a zone. */
export function dayLabel(date: IsoDate): string {
  return DAY.format(new Date(`${date}T00:00:00Z`)).replace(",", "");
}

/** "Thu 1 October", where there is room to spell the month. */
export function longDay(date: IsoDate): string {
  return LONG_DAY.format(new Date(`${date}T00:00:00Z`)).replace(",", "");
}

/** "Thu 1 Oct · 16:12": the depot's date and time now, for a page header. */
export function depotStamp(now: Date = businessNow()): string {
  return `${dayLabel(depotToday(now))} · ${clock(now)}`;
}

/** Today's date in depot time, as yyyy-mm-dd. */
export function depotToday(now: Date = businessNow()): IsoDate {
  return TODAY.format(now);
}

/** The calendar day `days` after `date` (negative for before). */
export function addDays(date: IsoDate, days: number): IsoDate {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** The hour of the day in depot time, 0 to 23. */
export function depotHour(now: Date = businessNow()): number {
  return Number(HOUR.format(now));
}

export function greeting(now: Date = businessNow()): string {
  const hour = depotHour(now);
  return hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
}

/** "14 min" or "1 h 20 min". */
export function durationText(minutes: number): string {
  const m = Math.max(0, Math.round(minutes));
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`;
}

/** "4:05" left on a countdown, from seconds. */
export function countdown(seconds: number): string {
  const s = Math.max(0, Math.ceil(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
