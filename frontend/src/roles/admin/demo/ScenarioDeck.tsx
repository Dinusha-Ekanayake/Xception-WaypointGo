"use client";

import { useState } from "react";
import { Badge, card, secondary } from "../access/components";
import { SCENARIOS, type SetupAction } from "./scenarios";

// The scenario deck (issue #231): pick a scenario, read what it shows, prepare
// it with the control room's own actions, then demonstrate it in the role apps.

const SETUP_LABEL: Record<SetupAction, string> = {
  "before-cutoff": "Clock 15:30",
  "after-cutoff": "Clock 16:05",
  "early-morning": "Clock 05:00",
  "start-vehicles": "Start vehicles",
};

export default function ScenarioDeck({ onSetup, busy }: { onSetup: (action: SetupAction) => void; busy: boolean }): React.JSX.Element {
  const [chosen, setChosen] = useState(SCENARIOS[0]!.key);
  const scenario = SCENARIOS.find((s) => s.key === chosen) ?? SCENARIOS[0]!;
  return (
    <section className={`${card} flex flex-col gap-4 p-5`}>
      <h2 className="text-lg font-medium text-go-ink">Scenarios</h2>
      <div className="flex flex-wrap gap-2" role="tablist" aria-label="Scenario">
        {SCENARIOS.map((s) => (
          <button key={s.key} type="button" role="tab" aria-selected={s.key === chosen}
            className={s.key === chosen ? "inline-flex min-h-11 items-center rounded-full bg-go-ink px-4 text-sm font-medium text-go-card" : secondary}
            onClick={() => setChosen(s.key)}>
            {s.title}
          </button>
        ))}
      </div>
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-base font-medium text-go-ink">{scenario.title}</h3>
          {scenario.register && <Badge tone="blue">{scenario.register}</Badge>}
          {scenario.roles.map((r) => <Badge key={r}>{r}</Badge>)}
        </div>
        <p className="text-sm text-go-ink">{scenario.situation}</p>
        <ol className="flex list-decimal flex-col gap-1 pl-5 text-sm text-go-ink">
          {scenario.steps.map((step) => <li key={step}>{step}</li>)}
        </ol>
        <p className="text-sm text-go-secondary"><span className="font-medium text-go-ink">Expected:</span> {scenario.expected}</p>
        {scenario.needs && <p className="text-sm text-[#7a5200]">Needs: {scenario.needs}</p>}
        {scenario.setup.length > 0 && (
          <div className="flex flex-wrap gap-2">
            {scenario.setup.map((action) => (
              <button key={action} type="button" className={secondary} disabled={busy} onClick={() => onSetup(action)}>
                {SETUP_LABEL[action]}
              </button>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
