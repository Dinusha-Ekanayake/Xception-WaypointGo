import { request } from "../../../shared/api/client.ts";
import type { PriorityPolicyView } from "../../../shared/domain/planning.ts";

// Roadmap T2B-01, R-PLN-21: the written deferral policy on the Decide screen is
// the policy version the plan was made with, read from the server, so it can
// never say one order while the engine applies another.

export function fetchPriorityPolicy(policyVersionId: string, signal?: AbortSignal) {
  return request<PriorityPolicyView>(`/api/plans/policies/${encodeURIComponent(policyVersionId)}`, { signal });
}

/** Each key in words, as the dispatcher would explain it to a store. */
const WORDS: Record<string, { rule: string; why: string }> = {
  PRIOR_SKIP: { rule: "Deferred on an earlier run", why: "no store is skipped twice in a row" },
  FRESH: { rule: "Fresh before other brands", why: "perishable, and it sells in the morning" },
  CHILLED: { rule: "Chilled before ambient", why: "it can only ride in a refrigerated vehicle" },
  STRICT_WINDOW: { rule: "Mall docks and short windows", why: "a missed slot cannot be made up later in the day" },
  CADENCE: { rule: "Brands ordered less often", why: "a weekly order deferred waits a week" },
  EARLIEST_CLOSE: { rule: "Earliest window close", why: "the store that closes first is served first" },
  LONGEST_DISTANCE: { rule: "Farther outlets", why: "a tie-break: far trips are harder to fit later" },
  LARGEST_VOLUME: { rule: "Larger orders", why: "a tie-break: more goes out" },
  LONGEST_UNSERVED: { rule: "Longest since last served", why: "a tie-break: who has waited longest" },
};

/** The policy as numbered lines; a key this screen does not know reads as words, never as a raw code. */
export function policyLines(keys: string[]): Array<{ rule: string; why: string | null }> {
  return keys.map((k) => WORDS[k] ?? { rule: k.toLowerCase().replace(/_/g, " ").replace(/^./, (c) => c.toUpperCase()), why: null });
}
