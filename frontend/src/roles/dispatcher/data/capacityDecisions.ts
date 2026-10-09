import { request } from "@shared/api/client";
import { useResource, type Resource } from "@shared/api/useResource";
import type { CapacityAction, CapacityDecisionView } from "@shared/domain/types";

// Roadmap T2-01: the decisions recorded against short weeks, read beside the
// forecast and under the same permission (ml:Read on the depot's forecast).

export const RECORD_CAPACITY_DECISION = "ml:RecordCapacityDecision";

export const ACTION_LABEL: Record<CapacityAction, string> = {
  HIRE_VEHICLES: "Hire vehicles",
  MOVE_WORKSHOP: "Move workshop days",
  ADVISE_STORES: "Advise stores",
  ACCEPT_SHORTFALL: "Accept the shortfall",
  OTHER: "Other",
};

export const ACTIONS = Object.keys(ACTION_LABEL) as CapacityAction[];

/** One read per depot, newest first across them. */
export function useCapacityDecisions(depots: string[]): Resource<CapacityDecisionView[]> {
  const load =
    depots.length === 0
      ? null
      : async (signal: AbortSignal) =>
          (
            await Promise.all(
              depots.map((d) => request<CapacityDecisionView[]>(`/api/ml/capacity/decisions?depot=${encodeURIComponent(d)}`, { signal })),
            )
          )
            .flat()
            .sort((a, b) => b.decidedAt.localeCompare(a.decidedAt));
  return useResource(load, `capacity-decisions|${depots.join(",")}`, 5 * 60_000);
}

/** What the decision says, in one line: "Hire 2 refrigerated vehicles". */
export function decisionSummary(d: Pick<CapacityDecisionView, "action" | "vehicles" | "refrigerated">): string {
  if (d.action === "HIRE_VEHICLES" && d.vehicles) {
    return `Hire ${d.vehicles} ${d.refrigerated ? "refrigerated " : ""}${d.vehicles === 1 ? "vehicle" : "vehicles"}`;
  }
  return ACTION_LABEL[d.action];
}
