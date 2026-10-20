"use client";

import { useResource } from "@shared/api/useResource";
import { fetchPriorityPolicy, policyLines } from "../data/priority.ts";

// Roadmap T2B-01: the deferral policy the engine applied to this plan, in the
// order it applied it. Read from the plan's own policy version (R-PLN-21).
// Folded to one line until opened.

export default function DeferralPolicyCard({ policyVersionId }: { policyVersionId: string }): React.JSX.Element | null {
  const policy = useResource((signal: AbortSignal) => fetchPriorityPolicy(policyVersionId, signal), `policy|${policyVersionId}`);
  if (!policy.data) return null;
  const lines = policyLines(policy.data.keys);
  return (
    // Folded by default: the dispatcher reads it once, not every time they decide.
    <section aria-label="How the engine decides what waits" className="rounded-[24px] bg-go-card shadow-go-card">
      <details className="group">
        <summary className="flex min-h-12 cursor-pointer list-none items-center gap-3 px-5 py-3 [&::-webkit-details-marker]:hidden">
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="text-[15px] font-medium text-go-ink">How the engine decides what waits</span>
            {lines[0] && <span className="truncate text-[13px] text-go-secondary group-open:hidden">{`First: ${lines[0].rule} · ${lines.length} rules in order`}</span>}
          </span>
          <span aria-hidden className="text-[13px] font-medium text-go-teal">
            <span className="group-open:hidden">Show</span>
            <span className="hidden group-open:inline">Hide</span>
          </span>
        </summary>
        <div className="flex flex-col gap-2 px-5 pb-4">
          <p className="text-[13px] text-go-secondary">
            When the vehicles cannot carry everything, orders are served in this order. Every rule the vehicles must keep comes
            first; within them, the first line that tells two orders apart decides.
          </p>
          <ol className="flex flex-col gap-1 text-[13px]">
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
        </div>
      </details>
    </section>
  );
}
