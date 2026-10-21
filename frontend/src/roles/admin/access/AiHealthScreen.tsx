"use client";

import { useEffect, useState } from "react";
import type { AiHealthView } from "@shared/domain/intelligence";
import { friendlyError } from "@shared/api/problem";
import { clockOn } from "@shared/wording";
import { Badge, card } from "./components";
import { agreement, byUse, empty, flagged, OUTCOME_LABEL, PURPOSE_LABEL } from "../data/aiHealth";
import { fetchAiHealth } from "../data/models";

// R-ML-27: where the language models and Jev fail. Every call through the AI
// gateway is recorded with how it ended and which prompt version it was given;
// this reads those records for the administrator. Inputs are shown masked, as
// they were sent. A use failing more than a fifth of the time is marked.

const SPANS = [1, 7, 14, 30];

const situation = (s: string) => {
  const words = s.toLowerCase().replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
};
const share = (part: number, whole: number) => (whole ? `${Math.round((part / whole) * 100)}%` : "-");

export function AiHealthScreen() {
  const [days, setDays] = useState(14);
  const [view, setView] = useState<AiHealthView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    fetchAiHealth(days, controller.signal)
      .then(setView)
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(friendlyError(e));
      });
    return () => controller.abort();
  }, [days, reload]);

  const uses = view ? byUse(view.calls) : [];

  return (
    <section className="flex flex-col gap-5" aria-label="AI health">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-go-muted">Every call to a language model and every Jev guess, and how it ended. Inputs are shown masked, as they were sent.</p>
        <span role="group" aria-label="Period" className="flex gap-1.5">
          {SPANS.map((d) => (
            <button
              key={d}
              type="button"
              aria-pressed={days === d}
              onClick={() => setDays(d)}
              className={`min-h-9 rounded-full px-3.5 text-sm font-medium ${days === d ? "bg-go-ink text-white" : "bg-go-surface text-go-ink"}`}
            >
              {d === 1 ? "Today" : `${d} days`}
            </button>
          ))}
        </span>
      </div>

      {error && (
        <div role="alert" className="flex items-center justify-between gap-3 rounded-2xl border border-go-warning-text bg-white p-4 text-sm">
          {error}
          <button type="button" onClick={() => setReload((n) => n + 1)} className="font-medium text-go-teal">Try again</button>
        </div>
      )}
      {view === null && !error && <p className="text-sm text-go-muted">Loading…</p>}
      {view && empty(view) && (
        <p className={`${card} p-5 text-sm text-go-muted`}>
          Nothing in this period. Language models answer only when AI is switched on, and Jev only in shadow.
        </p>
      )}

      {view && uses.length > 0 && (
        <div className={`${card} flex flex-col gap-3 p-5`}>
          <h2 className="text-[17px] font-medium text-go-ink">Where it fails</h2>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-left text-sm">
              <thead className="text-go-muted">
                <tr>
                  {["Use", "Calls", "Used", "Figure not in facts", "No answer", "Daily limit", "Failed", "Slowest (95%)"].map((h) => (
                    <th key={h} className="py-2 pr-3 font-normal">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {uses.map((u) => (
                  <tr key={u.purpose} className="border-t border-go-rule">
                    <td className="py-2.5 pr-3 font-medium text-go-ink">{u.label}</td>
                    <td className="py-2.5 pr-3 tabular-nums">{u.calls}</td>
                    <td className="py-2.5 pr-3 tabular-nums">{u.used}</td>
                    <td className="py-2.5 pr-3 tabular-nums">{u.unfaithful}</td>
                    <td className="py-2.5 pr-3 tabular-nums">{u.failed}</td>
                    <td className="py-2.5 pr-3 tabular-nums">{u.capped}</td>
                    <td className="py-2.5 pr-3">{flagged(u) ? <Badge tone="red">{share(u.calls - u.used, u.calls)}</Badge> : share(u.calls - u.used, u.calls)}</td>
                    <td className="py-2.5 pr-3 tabular-nums">{`${(u.worstP95 / 1000).toFixed(1)} s`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {view && view.failures.length > 0 && (
        <div className={`${card} flex flex-col gap-3 p-5`}>
          <h2 className="text-[17px] font-medium text-go-ink">Latest failures</h2>
          <ul className="flex flex-col gap-2">
            {view.failures.map((f, i) => (
              <li key={`${f.at}-${i}`} className="rounded-2xl bg-go-surface px-4 py-3 text-sm">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="font-medium text-go-ink">{PURPOSE_LABEL[f.purpose] ?? f.purpose}</span>
                  <Badge tone={f.outcome === "capped" ? "amber" : "red"}>{OUTCOME_LABEL[f.outcome] ?? f.outcome}</Badge>
                  <span className="text-go-muted">{[clockOn(f.at), f.model, f.promptVersion ? `prompt ${f.promptVersion}` : "prompt not recorded", `${f.millis} ms`].join(" · ")}</span>
                </div>
                <details className="mt-1.5">
                  <summary className="cursor-pointer text-go-teal">What was sent (masked)</summary>
                  <pre className="mt-1.5 whitespace-pre-wrap break-words text-[12px] text-go-ink">{f.sentFacts}</pre>
                </details>
              </li>
            ))}
          </ul>
        </div>
      )}

      {view && view.jev.answered + view.jev.pending + view.jev.failed > 0 && (
        <div className={`${card} flex flex-col gap-3 p-5`}>
          <h2 className="text-[17px] font-medium text-go-ink">Jev in shadow</h2>
          <p className="text-sm text-go-muted">Jev guesses each new issue's type, and the situation of each trip report sent with a type, from the masked words alone; nobody sees the guess. Agreement is with the type the person chose.</p>
          <p className="text-sm text-go-ink">
            {[agreement(view.jev.answered, view.jev.agreed) ?? "No answer yet", `${view.jev.pending} waiting`, `${view.jev.failed} gave up`].join(" · ")}
          </p>
          {view.jev.byVersion.length > 0 && (
            <ul className="flex flex-col gap-1 text-sm">
              {view.jev.byVersion.map((v) => (
                <li key={`${v.source}-${v.promptVersion}-${v.model}`} className="text-go-muted">
                  {`${v.source === "report" ? "Trip reports" : "Issues"} · ${v.promptVersion ? `Prompt ${v.promptVersion}` : "Prompt not recorded"} · ${v.model} · ${agreement(v.answered, v.agreed)}`}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {view && view.suggestions.length > 0 && (
        <div className={`${card} flex flex-col gap-3 p-5`}>
          <h2 className="text-[17px] font-medium text-go-ink">Suggested decisions</h2>
          <table className="w-full text-left text-sm">
            <thead className="text-go-muted">
              <tr>
                {["Situation", "Decided", "Took a suggestion", "Took the first"].map((h) => (
                  <th key={h} className="py-2 pr-3 font-normal">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {view.suggestions.map((s) => (
                <tr key={s.situation} className="border-t border-go-rule">
                  <td className="py-2.5 pr-3 font-medium text-go-ink">{situation(s.situation)}</td>
                  <td className="py-2.5 pr-3 tabular-nums">{s.decided}</td>
                  <td className="py-2.5 pr-3 tabular-nums">{`${s.chosen} (${share(s.chosen, s.decided)})`}</td>
                  <td className="py-2.5 pr-3 tabular-nums">{`${s.chosenFirst} (${share(s.chosenFirst, s.decided)})`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
