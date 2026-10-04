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
  decideBlocks = false,
}: {
  tab: Tab;
  onTab: (tab: Tab) => void;
  decide: string;
  view: string;
  publish: string;
  /** Every order that needed a decision has one. */
  decideDone: boolean;
  /** The one button on the right. */
  next: { label: string; onClick: () => void; disabled?: boolean; hint?: string };
  /** Decide still holds orders that block Publish: its step shows a warning. */
  decideBlocks?: boolean;
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
            const selected = id === tab || (tab === "compare" && id === "view");
            const warn = id === "decide" && decideBlocks && tab !== "decide";
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
                  className={`flex size-7 shrink-0 items-center justify-center rounded-full text-[13px] font-medium ${warn ? "bg-go-warning-tint text-go-warning-text" : done ? "bg-go-teal text-go-card" : selected ? "bg-go-ink text-go-card" : "bg-go-surface text-go-secondary"}`}
                >
                  {warn ? "!" : done ? <Icon name="check-white" /> : index + 1}
                </span>
                <span className="flex flex-col">
                  <span className="text-[15px] font-medium text-go-ink">{notes[id][0]}</span>
                  <span className={`text-xs ${warn ? "font-medium text-go-warning-text" : "text-go-secondary"}`}>{notes[id][1]}</span>
                </span>
              </button>
            );
          })}
        </div>
        <span className="flex items-center gap-3">
          {next.disabled && next.hint && <span className="max-w-[200px] text-right text-xs text-go-warning-text">{next.hint}</span>}
          <PrimaryButton disabled={next.disabled} onClick={next.onClick}>
            {next.label}
          </PrimaryButton>
        </span>
      </div>
      <span aria-hidden className="absolute bottom-0 left-0 h-[3px] bg-go-teal transition-[width]" style={{ width: `${((current + 1) / ORDER.length) * 100}%` }} />
    </div>
  );
}
