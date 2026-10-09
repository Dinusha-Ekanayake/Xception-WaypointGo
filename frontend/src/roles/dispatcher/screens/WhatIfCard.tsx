"use client";

import { useState } from "react";
import { useResource } from "@shared/api/useResource";
import { useFriendlyText } from "@shared/api/useFriendlyText";
import { RichText, SecondaryButton } from "@shared/ui";
import { fetchWhatIf, fetchWhatIfVehicles, vehicleChoice, whatIfWords } from "../data/whatIf.ts";

// Roadmap AI-1: "what if this vehicle came back?". The solver answers by running
// the day twice on the server; a model may reword the answer, checked against
// its numbers, and the code's own words always show.

export default function WhatIfCard({ planId, rowVersion }: { planId: string; rowVersion: number }): React.JSX.Element | null {
  const vehicles = useResource((signal: AbortSignal) => fetchWhatIfVehicles(planId, signal), `whatif-vehicles|${planId}`);
  const [chosen, setChosen] = useState("");
  const [asked, setAsked] = useState<string | null>(null);
  const answer = useResource(asked ? (signal: AbortSignal) => fetchWhatIf(planId, asked, signal) : null, `whatif|${planId}|${rowVersion}|${asked}`);
  const words = answer.data ? whatIfWords(answer.data) : null;
  const friendly = useFriendlyText(words && asked ? `plan:${planId}:${rowVersion}:whatif:${asked}` : null, words);
  if (!vehicles.data || vehicles.data.length === 0) return null;
  return (
    <section aria-label="What if a vehicle comes back" className="flex flex-col gap-2 rounded-[24px] bg-go-card p-4 shadow-go-card">
      <h2 className="px-1 text-[15px] font-medium text-go-ink">What if a vehicle comes back?</h2>
      <p className="px-1 text-[13px] text-go-secondary">The planner runs this day again with one more vehicle. Nothing in the plan changes.</p>
      <div className="flex flex-wrap items-center gap-2 px-1">
        <label htmlFor="whatif-vehicle" className="sr-only">Vehicle</label>
        <select
          id="whatif-vehicle"
          value={chosen}
          onChange={(e) => setChosen(e.target.value)}
          className="rounded-go-input bg-go-surface px-3 py-2 text-sm text-go-ink"
        >
          <option value="">Choose a vehicle</option>
          {vehicles.data.map((v) => (
            <option key={v.vehicleId} value={v.vehicleId}>{vehicleChoice(v)}</option>
          ))}
        </select>
        <SecondaryButton disabled={!chosen || answer.loading} onClick={() => setAsked(chosen)}>
          {answer.loading ? "Planning…" : "Show the difference"}
        </SecondaryButton>
      </div>
      {answer.error != null && <p className="px-1 text-[13px] text-go-danger">The planner could not run this day again just now.</p>}
      {words && (
        <div className="flex flex-col gap-1.5 px-1 text-[13px] text-go-ink" data-testid="whatif-answer">
          {friendly && (
            <div className="rounded-go-card bg-go-success-tint px-3 py-2">
              <RichText text={friendly} className="text-[13px] text-go-ink" />
              <p className="text-[11px] text-go-secondary">Worded by an AI model from the facts below.</p>
            </div>
          )}
          <p>{words.whatIf}</p>
          {words.nowServed.length > 0 && <p><strong className="font-semibold">Now served:</strong> {words.nowServed.join("; ")}</p>}
          {words.nowDeferred.length > 0 && <p><strong className="font-semibold">Now deferred:</strong> {words.nowDeferred.join("; ")}</p>}
          <p className="text-go-secondary">{words.next}</p>
        </div>
      )}
    </section>
  );
}
