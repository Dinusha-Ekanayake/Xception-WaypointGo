"use client";

import type { ConstraintResultView } from "@shared/domain/types";
import { ruleLabel } from "@shared/wording";

/**
 * Every rule the engine or the override path judged, in words, on expand
 * (PLAN.md decision 2). The rule's id is a tooltip for someone who needs it,
 * never the text: a code on screen means nothing to the person planning.
 */
export default function CheckList({ checks, title }: { checks: ConstraintResultView[]; title?: string }): React.JSX.Element | null {
  if (checks.length === 0) return null;
  const failed = checks.filter((check) => !check.passed).length;
  return (
    <details className="rounded-go-card bg-go-subtle px-3.5 py-2.5 text-[13px] text-go-ink">
      <summary className="cursor-pointer font-medium">
        {title ?? (checks.length === 1 ? "The 1 check" : `All ${checks.length} checks`)}
        {failed > 0 ? ` · ${failed} failed` : ""}
      </summary>
      <ul className="mt-2 flex flex-col gap-1.5">
        {checks.map((check, index) => (
          <li key={`${check.ruleId}-${index}`} className="flex gap-2 text-xs">
            <span className={`shrink-0 font-medium ${check.passed ? "text-go-success" : "text-go-danger-strong"}`}>{check.passed ? "Passed" : "Failed"}</span>
            <span title={check.ruleId} className="shrink-0 font-medium">
              {ruleLabel(check.ruleId)}
            </span>
            <span className="text-go-secondary">
              {check.reason}
              {check.slack !== null && ` · room ${check.slack}`}
            </span>
          </li>
        ))}
      </ul>
    </details>
  );
}
