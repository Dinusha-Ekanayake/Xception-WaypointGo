import type { AiHealthView, CallDayView } from "../../../shared/domain/intelligence.ts";

// R-ML-27: the AI health screen's sums, pure so they are tested without a browser.
// A use's failure share counts every call that was not used, the capped ones too,
// because a capped call is also an explanation nobody got.

export const PURPOSE_LABEL: Record<string, string> = {
  explain_plan: "Plan explanations",
  explain_order: "Order explanations",
};

export const OUTCOME_LABEL: Record<string, string> = {
  unfaithful: "Answer named a figure not in the facts",
  failed: "No answer",
  capped: "Daily limit reached",
};

export type UseSummary = {
  purpose: string;
  label: string;
  calls: number;
  used: number;
  unfaithful: number;
  failed: number;
  capped: number;
  /** Not used, of all calls, 0 to 1. */
  failureShare: number;
  /** The slowest day's 95th percentile, in milliseconds. */
  worstP95: number;
};

export function byUse(days: CallDayView[]): UseSummary[] {
  const out = new Map<string, UseSummary>();
  for (const d of days) {
    const s = out.get(d.purpose) ?? {
      purpose: d.purpose,
      label: PURPOSE_LABEL[d.purpose] ?? d.purpose.replace(/_/g, " "),
      calls: 0, used: 0, unfaithful: 0, failed: 0, capped: 0, failureShare: 0, worstP95: 0,
    };
    s.used += d.used;
    s.unfaithful += d.unfaithful;
    s.failed += d.failed;
    s.capped += d.capped;
    s.calls = s.used + s.unfaithful + s.failed + s.capped;
    s.worstP95 = Math.max(s.worstP95, d.p95Millis);
    out.set(d.purpose, s);
  }
  return [...out.values()]
    .map((s) => ({ ...s, failureShare: s.calls ? (s.calls - s.used) / s.calls : 0 }))
    .sort((a, b) => b.failureShare - a.failureShare || b.calls - a.calls);
}

/** "18 of 20 agreed (90%)", or null when Jev has answered nothing yet. */
export function agreement(answered: number, agreed: number): string | null {
  if (!answered) return null;
  return `${agreed} of ${answered} agreed (${Math.round((agreed / answered) * 100)}%)`;
}

/** A use is flagged when more than a fifth of its calls fail, over at least five calls. */
export function flagged(s: UseSummary): boolean {
  return s.calls >= 5 && s.failureShare > 0.2;
}

export function empty(view: AiHealthView): boolean {
  return view.calls.length === 0 && view.jev.answered + view.jev.pending + view.jev.failed === 0 && view.suggestions.length === 0;
}
