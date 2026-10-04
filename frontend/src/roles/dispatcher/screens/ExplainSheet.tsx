"use client";

import { SecondaryButton, Sheet } from "@shared/ui";
import type { Explanation } from "../data/explain.ts";

// The explanation of one deferral, in a pop-up (issue #267): what happened, the
// rule that decided it, what was checked, and what the dispatcher can do next.
// The words are the planning module's; this lays them out to be read aloud.

function Part({ title, children }: { title: string; children: React.ReactNode }): React.JSX.Element {
  return (
    <section className="flex flex-col gap-1.5">
      <h3 className="text-[13px] font-semibold text-go-ink">{title}</h3>
      {children}
    </section>
  );
}

const list = "flex list-disc flex-col gap-1 pl-5 text-[13px] text-go-ink";

export default function ExplainSheet({ explanation, onClose }: { explanation: Explanation; onClose: () => void }): React.JSX.Element {
  const { stopped, met, fits, refused } = explanation;
  return (
    <Sheet label="Why this order was not placed" onClose={onClose}>
      <h2 className="text-[20px] font-medium text-go-ink">Why this order was not placed</h2>
      <p className="text-[14px] text-go-ink">{explanation.headline}</p>

      <Part title="The reason">
        <p className="rounded-go-card bg-go-warning-tint px-3.5 py-2.5 text-[13px] text-go-warning-text">
          {explanation.rule && <strong className="font-semibold">{`${explanation.rule}. `}</strong>}
          {explanation.reason}
        </p>
      </Part>

      {stopped.length > 0 && (
        <Part title="What stopped it">
          <ul className={list}>
            {stopped.map((item, index) => (
              <li key={index}>
                <strong className="font-semibold">{item.label}</strong>
                {item.detail ? `: ${item.detail}` : ""}
              </li>
            ))}
          </ul>
        </Part>
      )}

      {met.length > 0 && (
        <Part title="What was fine">
          <p className="text-[13px] text-go-secondary">{met.join(" · ")}</p>
        </Part>
      )}

      {explanation.canPlace && (
      <Part title="Where it could go">
        {fits === null ? (
          <p className="text-[13px] text-go-secondary">Checking every vehicle…</p>
        ) : fits.length > 0 ? (
          <ul className={list}>
            {fits.map((place) => (
              <li key={place}>{place}</li>
            ))}
          </ul>
        ) : (
          <p className="text-[13px] text-go-ink">Nowhere today.</p>
        )}
        {refused.length > 0 && (
          <ul className="flex list-disc flex-col gap-1 pl-5 text-[13px] text-go-secondary">
            {refused.map((place) => (
              <li key={place.where}>
                <em>{place.where}</em>
                {`: ${place.why}`}
              </li>
            ))}
          </ul>
        )}
      </Part>
      )}

      <Part title="What you can do">
        <p className="text-[13px] text-go-ink">{explanation.next}</p>
      </Part>

      <SecondaryButton onClick={onClose}>Close</SecondaryButton>
    </Sheet>
  );
}
