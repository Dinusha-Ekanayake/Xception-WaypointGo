"use client";

import { Icon, PrimaryButton } from "@shared/ui";

// Figma "Plan" step bar: Decide, View plan, Publish, with the step you are on
// filled, a check on a step that is done, a progress line under the bar, and one
// black button on the right that takes you to the next step.

export type Tab = "decide" | "view" | "publish" | "compare";
type Step = "decide" | "view" | "publish";

const ORDER: Step[] = ["decide", "view", "publish"];

export default function PlanSteps({
  tab,
  onTab,
  decide,
  view,
  publish,
  decideDone,
  nextLabel,
}: {
  tab: Tab;
  onTab: (tab: Tab) => void;
  decide: string;
  view: string;
  publish: string;
  /** Every order that needed a decision has one. */
  decideDone: boolean;
  /** The button on the right; omitted on the last step. */
  nextLabel: string | null;
}): React.JSX.Element {
  const current = tab === "compare" ? 1 : ORDER.indexOf(tab);
  const notes: Record<Step, [string, string]> = {
    decide: ["Decide", decide],
    view: ["View plan", view],
    publish: ["Publish", publish],
  };
  const next = ORDER[Math.min(current + 1, ORDER.length - 1)]!;

  return (
    <div className="relative w-full overflow-hidden rounded-[24px] bg-go-card p-2 shadow-go-card">
      <div className="flex w-full flex-wrap items-center gap-2">
        <div role="tablist" aria-label="Plan steps" className="flex flex-1 flex-wrap gap-1">
          {ORDER.map((id, index) => {
            const selected = id === tab || (tab === "compare" && id === "view");
            const done = id === "decide" ? decideDone && tab !== "decide" : index < current;
            return (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={selected}
                onClick={() => onTab(id)}
                className={`flex min-w-[150px] flex-1 items-center gap-3 rounded-go-card px-4 py-2 text-left ${selected ? "bg-go-success-tint" : ""}`}
              >
                <span
                  aria-hidden
                  className={`flex size-7 shrink-0 items-center justify-center rounded-full text-[13px] font-medium ${selected || done ? "bg-go-ink text-go-card" : "bg-go-surface text-go-secondary"}`}
                >
                  {done ? <Icon name="check-white" /> : index + 1}
                </span>
                <span className="flex flex-col">
                  <span className="text-[15px] font-medium text-go-ink">{notes[id][0]}</span>
                  <span className="text-xs text-go-secondary">{notes[id][1]}</span>
                </span>
              </button>
            );
          })}
        </div>
        {nextLabel && <PrimaryButton onClick={() => onTab(next)}>{nextLabel}</PrimaryButton>}
      </div>
      <span aria-hidden className="absolute bottom-0 left-0 h-[3px] bg-go-teal transition-[width]" style={{ width: `${((current + 1) / ORDER.length) * 100}%` }} />
    </div>
  );
}
