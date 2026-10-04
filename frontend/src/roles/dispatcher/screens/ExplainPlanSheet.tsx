"use client";

import { SecondaryButton, Sheet } from "@shared/ui";
import type { PlanExplanation } from "../data/explain.ts";

// The plan explained in a pop-up (issue #267): what it carries, why orders were
// left off, what was decided by hand, the planner's notes and what comes next.

const title = "text-[13px] font-semibold text-go-ink";
const list = "flex list-disc flex-col gap-1 pl-5 text-[13px] text-go-ink";

export default function ExplainPlanSheet({ explanation, onClose }: { explanation: PlanExplanation; onClose: () => void }): React.JSX.Element {
  const { leftOff, notes } = explanation;
  return (
    <Sheet label="This plan explained" onClose={onClose}>
      <h2 className="text-[20px] font-medium text-go-ink">This plan explained</h2>
      <p className="text-[14px] text-go-ink">
        {explanation.headline} <strong className="font-semibold">{explanation.carries}</strong>
      </p>

      <section className="flex flex-col gap-1.5">
        <h3 className={title}>Orders left off, and why</h3>
        {leftOff.length === 0 && explanation.cannotBeServed === 0 ? (
          <p className="text-[13px] text-go-ink">None. Every order is on a trip.</p>
        ) : (
          <ul className={list}>
            {leftOff.map((group) => (
              <li key={group.label}>
                <strong className="font-semibold">{`${group.count} · ${group.label}`}</strong>
                {group.example ? (
                  <>
                    {": "}
                    <em>{group.example}</em>
                  </>
                ) : null}
              </li>
            ))}
            {explanation.cannotBeServed > 0 && (
              <li>
                <strong className="font-semibold">{`${explanation.cannotBeServed} cannot be served`}</strong>
                {": larger than any vehicle can carry. The store manager can be told from the plan."}
              </li>
            )}
          </ul>
        )}
      </section>

      {(explanation.byHand || notes.length > 0) && (
        <section className="flex flex-col gap-1.5">
          <h3 className={title}>How it was made</h3>
          <ul className={list}>
            {explanation.byHand && <li>{explanation.byHand}</li>}
            {notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </section>
      )}

      <section className="flex flex-col gap-1.5">
        <h3 className={title}>What comes next</h3>
        <p className="text-[13px] text-go-ink">{explanation.next}</p>
      </section>

      <SecondaryButton onClick={onClose}>Close</SecondaryButton>
    </Sheet>
  );
}
