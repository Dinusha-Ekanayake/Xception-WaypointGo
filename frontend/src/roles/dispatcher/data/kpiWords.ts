// Roadmap B-05: the pilot's business KPIs in words. Pure, so it is tested
// without the app (tests/dispatcher-kpis.test.ts).

import type { KpiKey } from "../../../shared/domain/intelligence.ts";

export const KPI_ORDER: KpiKey[] = [
  "fresh_on_time_pct",
  "fresh_before_8_pct",
  "deferrals_per_run",
  "repeat_deferrals",
  "disputes_per_100",
  "write_off_lkr",
  "calls_per_trip",
  "adoption_pct",
];

export const KPI_LABEL: Record<KpiKey, string> = {
  fresh_on_time_pct: "Fresh on time",
  fresh_before_8_pct: "Fresh before 08:00",
  deferrals_per_run: "Deferrals per run",
  repeat_deferrals: "Stores deferred twice running",
  disputes_per_100: "Disputes per 100 deliveries",
  write_off_lkr: "Write-offs (LKR)",
  calls_per_trip: "Calls per trip",
  adoption_pct: "Stops recorded in the app",
};

/** Who measures it before the app does, as B-05 names the owner. */
export const KPI_OWNER: Record<KpiKey, string> = {
  fresh_on_time_pct: "Depot manager",
  fresh_before_8_pct: "Depot manager",
  deferrals_per_run: "Dispatcher lead",
  repeat_deferrals: "Dispatcher lead",
  disputes_per_100: "Depot manager",
  write_off_lkr: "Finance",
  calls_per_trip: "Dispatcher lead",
  adoption_pct: "Depot manager",
};

const PERCENT: KpiKey[] = ["fresh_on_time_pct", "fresh_before_8_pct", "adoption_pct"];

/** "66.7%", "LKR 12,000", "3.5". */
export function kpiValue(kpi: KpiKey, value: number): string {
  if (PERCENT.includes(kpi)) return `${Number(value).toFixed(1)}%`;
  if (kpi === "write_off_lkr") return `LKR ${Math.round(Number(value)).toLocaleString("en-US")}`;
  return String(Number(value));
}

/** Whether a typed figure can be sent: empty means "not this one", a share stays within 0 to 100. */
export function kpiInputError(kpi: KpiKey, raw: string): string | null {
  if (raw.trim() === "") return null;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < 0) return `${KPI_LABEL[kpi]} must be a number of 0 or more`;
  if (PERCENT.includes(kpi) && n > 100) return `${KPI_LABEL[kpi]} is a share and cannot pass 100`;
  return null;
}

/** The Mondays of the last `weeks` weeks, newest first, from a date in yyyy-mm-dd. */
export function lastMondays(today: string, weeks: number): string[] {
  const d = new Date(`${today}T00:00:00Z`);
  const back = (d.getUTCDay() + 6) % 7;
  d.setUTCDate(d.getUTCDate() - back);
  return Array.from({ length: weeks }, (_, i) => {
    const m = new Date(d);
    m.setUTCDate(d.getUTCDate() - 7 * i);
    return m.toISOString().slice(0, 10);
  });
}
