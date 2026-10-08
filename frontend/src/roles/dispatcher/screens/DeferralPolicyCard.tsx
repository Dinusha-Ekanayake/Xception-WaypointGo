"use client";

import { useResource } from "@shared/api/useResource";
import { fetchPriorityPolicy, policyLines } from "../data/priority.ts";

// Roadmap T2B-01: the deferral policy the engine applied to this plan, in the
// order it applied it. Read from the plan's own policy version (R-PLN-21).

export default function DeferralPolicyCard({ policyVersionId }: { policyVersionId: string }): React.JSX.Element | null {
  const policy = useResource((signal: AbortSignal) => fetchPriorityPolicy(policyVersionId, signal), `policy|${policyVersionId}`);
  if (!policy.data) return null;
  const lines = policyLines(policy.data.keys);
  return (
    <section aria-label="How the engine decides what waits" className="flex flex-col gap-2 rounded-[24px] bg-go-card p-4 shadow-go-card">
      <h2 className="px-1 text-[15px] font-medium text-go-ink">How the engine decides what waits</h2>
      <p className="px-1 text-[13px] text-go-secondary">
        When the vehicles cannot carry everything, orders are served in this order. Every rule the vehicles must keep comes
        first; within them, the first line that tells two orders apart decides.
      </p>
      <ol className="flex flex-col gap-1 px-1 text-[13px]">
        {lines.map((l, i) => (
          <li key={l.rule} className="flex gap-2">
            <span className="w-5 shrink-0 text-right text-go-secondary tabular-nums">{i + 1}.</span>
            <span>
              <span className="font-medium text-go-ink">{l.rule}</span>
              {l.why && <span className="text-go-secondary"> · {l.why}</span>}
            </span>
          </li>
        ))}
      </ol>
    </section>
  );
}
