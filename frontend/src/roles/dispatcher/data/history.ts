import type { IssueView } from "@shared/domain/types";
import { addDays } from "../../../shared/wording/index.ts";
import { punctuality } from "./live.ts";
import { flow } from "./orders.ts";
import type { HistoryDay } from "./useDay.ts";

// The Overview summary over a range of days: orders and how many were
// delivered or deferred, the share of delivered stops on time, the issues
// reported, and trips per day for the chart. Pure.

export type Range = "today" | "7d" | "28d";

export const RANGES: Array<{ id: Range; label: string; days: number }> = [
  { id: "today", label: "Today", days: 1 },
  { id: "7d", label: "Last 7 days", days: 7 },
  { id: "28d", label: "Last 4 weeks", days: 28 },
];

/** The days of a range ending today, oldest first. */
export function rangeDates(range: Range, today: string): string[] {
  const days = RANGES.find((r) => r.id === range)!.days;
  return Array.from({ length: days }, (_, i) => addDays(today, i - days + 1));
}

export type DayPoint = { date: string; trips: number; onTime: number | null };

export type Summary = {
  orders: number;
  delivered: number;
  deferred: number;
  onTime: number | null;
  points: DayPoint[];
};

export function summarise(days: HistoryDay[]): Summary {
  let orders = 0;
  let delivered = 0;
  let deferred = 0;
  let served = 0;
  let onTime = 0;
  const points = days.map((day) => {
    const f = flow(day.orders);
    orders += f.due;
    delivered += f.delivered;
    deferred += day.orders.filter((o) => o.status === "DEFERRED").length;
    const p = punctuality(day.sheets);
    served += p.served;
    onTime += p.onTime;
    return { date: day.date, trips: day.sheets.length, onTime: p.served === 0 ? null : Math.round((p.onTime / p.served) * 100) };
  });
  return { orders, delivered, deferred, onTime: served === 0 ? null : Math.round((onTime / served) * 100), points };
}

export type Reported = { total: number; missing: number; damaged: number };

/** Issues raised since the first day of the range: short or missing units, and damaged ones. */
export function reported(issues: IssueView[], since: string): Reported {
  const inRange = issues.filter((issue) => issue.raisedAt.slice(0, 10) >= since);
  return {
    total: inRange.length,
    missing: inRange.filter((i) => i.type === "LOADING_SHORTFALL" || i.type === "STOCK_DISCREPANCY").length,
    damaged: inRange.filter((i) => i.type === "DAMAGED_GOODS").length,
  };
}
