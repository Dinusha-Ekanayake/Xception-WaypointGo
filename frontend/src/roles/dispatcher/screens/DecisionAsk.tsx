"use client";

import { useState } from "react";
import { friendlyError } from "@shared/api/problem";
import { caseSummary, dockHelper, latestFor, question, receiveTimes, standing } from "@shared/decisions/decisions";
import { IssueCommandKind, type AnswerDecision, type AskDecision, type DecisionView, type RunSheetStopView } from "@shared/domain/types";
import { cx } from "@shared/ui";
import { clock, clockOn, units } from "@shared/wording";
import { useCommand } from "../data/useCommand.ts";
import { Action } from "./LiveParts.tsx";

// Live decisions on a "Needs you" card (roadmap FF-09): instead of phoning, the
// dispatcher asks the store in one tap, every name and number filled by the
// system. A closing window asks the store whether someone can receive later
// (never a mall: its window is fixed). Goods that stayed on the vehicle ask the
// next store on the trip to let the crew unload them at its dock to reach its
// own, and reload them. The answer shows here; with none by the deadline, the
// dispatcher phones and records what the store said.

const TONE = { waiting: "text-go-secondary", yes: "text-go-teal", no: "text-go-danger-strong", call: "text-go-warning-text", neutral: "text-go-secondary" } as const;

export default function DecisionAsk({
  kind,
  stop,
  stops,
  decisions,
  online,
  now,
  date,
  onChanged,
}: {
  /** window: the stop's window is closing; failed: its goods stayed on the vehicle. */
  kind: "window" | "failed";
  stop: RunSheetStopView;
  /** The vehicle's stops, to find the next store for dock help. */
  stops: RunSheetStopView[];
  decisions: DecisionView[];
  online: boolean;
  now: Date;
  /** The service day, for the window's time in the summary. */
  date: string;
  onChanged: () => void;
}): React.JSX.Element | null {
  const { run, busy } = useCommand();
  const [error, setError] = useState<string | null>(null);
  const [phoning, setPhoning] = useState(false);
  const helper = kind === "failed" ? dockHelper(stops, stop) : null;
  const asked = kind === "window" ? stop : helper;
  if (!asked) return null;
  const decisionKind = kind === "window" ? "receive_late" : "dock_help";
  const latest = latestFor(decisions, asked.deliveryId, decisionKind);
  const open = latest !== null && latest.state !== "withdrawn";

  const ask = async (payload: AskDecision) => {
    setError(null);
    const sent = await run(IssueCommandKind.askDecision, payload, null);
    if (sent.ok) onChanged();
    else setError(friendlyError(sent.error));
  };
  const record = async (d: DecisionView, answer: "yes" | "no") => {
    setError(null);
    const payload: AnswerDecision = { requestId: d.requestId, answer, byPhone: true };
    const sent = await run(IssueCommandKind.answerDecision, payload, d.rowVersion);
    if (sent.ok) {
      setPhoning(false);
      onChanged();
    } else setError(friendlyError(sent.error));
  };

  if (kind === "window" && stop.mallOutlet) {
    return <p className="text-[12px] text-go-secondary">A mall window is fixed: a late stop there is redelivered, not asked about.</p>;
  }

  return (
    <div data-testid="decision-ask" className="flex flex-col gap-1.5 rounded-xl bg-go-subtle px-3 py-2.5 text-[12.5px] text-go-ink">
      {open && latest ? (
        <>
          <p className="text-go-secondary">{fill(question(latest))}</p>
          <p data-testid="decision-standing" className={cx("font-medium", TONE[standing(latest, now).tone])}>
            {standing(latest, now).text}
            {latest.reason ? ` · ${latest.reason}` : ""}
          </p>
          {latest.answer === null && !phoning && (
            <Action disabled={!online || busy} onClick={() => setPhoning(true)}>
              Record what the store said
            </Action>
          )}
          {latest.answer === null && phoning && (
            <span className="flex gap-2" role="group" aria-label={`What ${latest.outletId} said by phone`}>
              <Action primary disabled={busy} className="flex-1" onClick={() => void record(latest, "yes")}>Yes, by phone</Action>
              <Action disabled={busy} className="flex-1" onClick={() => void record(latest, "no")}>No, by phone</Action>
            </span>
          )}
        </>
      ) : kind === "window" ? (
        <>
          <p className="font-medium">Ask {stop.outletId}: can someone receive at about</p>
          <span className="flex flex-wrap gap-1.5" role="group" aria-label={`Times to ask ${stop.outletId} about`}>
            {receiveTimes(stop.expectedArrival, now).map((t) => (
              <Action key={t.toISOString()} disabled={!online || busy} onClick={() => void ask({ kind: "receive_late", deliveryId: stop.deliveryId, receiveAt: t.toISOString() })}>
                {clockOn(t)}
              </Action>
            ))}
          </span>
        </>
      ) : (
        <>
          <p>
            {stop.outletId}&apos;s {units(unitsOf(stop))} stay on the vehicle, in front of {asked.outletId}&apos;s goods.
          </p>
          <Action disabled={!online || busy} onClick={() => void ask({ kind: "dock_help", deliveryId: asked.deliveryId, aboutDeliveryId: stop.deliveryId })}>
            Ask {asked.outletId} to help at the dock
          </Action>
        </>
      )}
      {error && <p role="alert" className="text-go-danger-strong">{error}</p>}
      <Summary lines={caseSummary(stop, decisions, date)} />
    </div>
  );
}

/** What happened on the stop, from the record alone, so the dispatcher picks up a case without reading the thread. */
function Summary({ lines }: { lines: ReturnType<typeof caseSummary> }): React.JSX.Element | null {
  if (lines.length < 2) return null;
  return (
    <details data-testid="case-summary" className="text-[12px] text-go-secondary">
      <summary className="cursor-pointer font-medium text-go-ink">What happened</summary>
      <ol className="mt-1 flex flex-col gap-0.5">
        {lines.map((l, i) => (
          <li key={i}>
            {l.at ? `${clockOn(l.at)} · ` : ""}
            {l.text}
          </li>
        ))}
      </ol>
    </details>
  );
}

function unitsOf(stop: RunSheetStopView): number {
  const lines = stop.lines.reduce((sum, l) => sum + l.orderedUnits, 0);
  return lines > 0 ? lines : stop.itemCount;
}

function fill(words: { text: string; vars: Record<string, string> }): string {
  return words.text.replace(/\{(\w+)\}/g, (whole, key: string) => words.vars[key] ?? whole);
}
