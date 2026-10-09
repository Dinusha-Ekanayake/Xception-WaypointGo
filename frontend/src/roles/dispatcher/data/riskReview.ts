import { request } from "@shared/api/client";
import { useResource, type Resource } from "@shared/api/useResource";
import type { RiskReviewReport, RiskStopView } from "@shared/domain/types";

// Roadmap T1-01: before Publish, the stops likely to arrive after their window,
// each kept or swapped with a reason. The figures are the estimate the plan
// board uses, labelled as such until the trust gates pass. Advice only.

export const RECORD_RISK_REVIEW = "ml:RecordRiskReview";

export function useRiskReview(planId: string | null): Resource<RiskReviewReport> {
  return useResource(
    planId ? (signal: AbortSignal) => request<RiskReviewReport>(`/api/ml/plans/${encodeURIComponent(planId)}/risk-review`, { signal }) : null,
    `risk-review|${planId ?? ""}`,
  );
}

/** Why the stop is at risk, in the dispatcher's words. */
export function riskWhy(stop: Pick<RiskStopView, "minutesToClose" | "stopOfTrip">): string {
  const close =
    stop.minutesToClose < 0
      ? `planned ${-stop.minutesToClose} min after the window closes`
      : stop.minutesToClose === 0
        ? "planned as the window closes"
        : `only ${stop.minutesToClose} min before the window closes`;
  return stop.stopOfTrip > 1 ? `${close} · stop ${stop.stopOfTrip} of the trip` : close;
}

/** The summary line: how many stops, and how many are still to review. */
export function riskSummary(stops: RiskStopView[]): string {
  const open = stops.filter((s) => s.review === null).length;
  const n = `${stops.length} ${stops.length === 1 ? "stop is" : "stops are"} likely to be late`;
  return open === 0 ? `${n} · all reviewed` : `${n} · ${open} to review`;
}
