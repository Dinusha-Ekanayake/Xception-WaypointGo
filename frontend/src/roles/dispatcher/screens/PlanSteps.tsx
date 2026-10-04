"use client";

import { Icon, PrimaryButton } from "@shared/ui";

// Figma "Plan" step bar: Decide, View plan, Publish, with the step you are on
// filled, a check on a step that is done, a progress line under the bar, and one
// black button on the right: the next step, or on Publish the publish itself.
// It looks the same on every step; what a step adds lives in its own card.

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
  next,
  published = false,
}: {
  tab: Tab;
  onTab: (tab: Tab) => void;
  decide: string;
  view: string;
  publish: string;
  /** Every order that needed a decision has one. */
  decideDone: boolean;
  /** The one button on the right. */
  next: { label: string; onClick: () => void; disabled?: boolean };
  /** The plan is out: every step shows done. */
  published?: boolean;
}): React.JSX.Element {
  const current = tab === "compare" ? 1 : ORDER.indexOf(tab);
  const notes: Record<Step, [string, string]> = {
    decide: ["Decide", decide],
    view: ["View plan", view],
    publish: ["Publish", publish],
  };

  return (
    <div className="relative w-full overflow-hidden rounded-go-card-l bg-go-card p-2">
      <div className="flex w-full flex-wrap items-center gap-2">
        <div role="tablist" aria-label="Plan steps" className="flex flex-1 flex-wrap gap-1">
          {ORDER.map((id, index) => {
            const selected = id === tab;
            const done = (id === "decide" ? decideDone && tab !== "decide" : index < current) || (published && !selected);
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
                  className={`flex size-7 shrink-0 items-center justify-center rounded-full text-[13px] font-medium ${done ? "bg-go-teal text-go-card" : selected ? "bg-go-ink text-go-card" : "bg-go-surface text-go-secondary"}`}
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
        <PrimaryButton disabled={next.disabled} onClick={next.onClick}>
          {next.label}
        </PrimaryButton>
      </div>
      <span aria-hidden className="absolute bottom-0 left-0 h-[3px] bg-go-teal transition-[width]" style={{ width: `${((current + 1) / ORDER.length) * 100}%` }} />
    </div>
  );
}
