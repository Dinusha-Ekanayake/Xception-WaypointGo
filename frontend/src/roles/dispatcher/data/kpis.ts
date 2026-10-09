import { request } from "@shared/api/client";
import { useResource, type Resource } from "@shared/api/useResource";
import type { KpiReportView } from "@shared/domain/types";

// Roadmap B-05: the pilot's KPIs for one depot, read under ml:Read on the depot.

export const RECORD_KPI_WEEK = "ml:RecordKpiWeek";
export const KPI_WEEKS = 8;

export function useKpis(depot: string | null): Resource<KpiReportView> {
  const load = depot
    ? (signal: AbortSignal) => request<KpiReportView>(`/api/ml/kpis?depot=${encodeURIComponent(depot)}&weeks=${KPI_WEEKS}`, { signal })
    : null;
  return useResource(load, `kpis|${depot ?? ""}`, 5 * 60_000);
}
