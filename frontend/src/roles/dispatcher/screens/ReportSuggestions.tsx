"use client";

import { useEffect, useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import { SuggestionCommandKind, type DecisionAction, type RunSheetStopView, type SuggestionsView } from "@shared/domain/types";
import type { ThreadDraft } from "@shared/ui";
import { clockOn, hhmm } from "@shared/wording";
import { fill } from "../data/playbooks.ts";
import { useCommand } from "../data/useCommand.ts";
import { EndTripForm, SkipStopForm } from "./LiveTripSkip.tsx";

// Issue #269 in the trip's chat (docs/issues/decision-suggestions/PLAN.md):
// under a report about a stop, the decisions most likely here, checked by the
// server against the rules, at most three, never preselected. A tap opens the
// decision already set (Skip, End trip) or does the small thing (the playbook's
// message into the composer); the dispatcher still confirms. What was shown and
// what was taken is kept, so the chances can later be counted from real choices.

const LABEL: Record<DecisionAction, string> = {
  SKIP_NEXT_DAY: "Skip to next day",
  SKIP_CANCEL: "Skip and cancel",
  ASK_RECEIVE_LATER: "Ask the store to receive later",
  WAIT: "Wait a little",
  CALL_STORE: "Call the store",
  END_TRIP: "End trip",
  TELL_STORE: "Tell the store",
  NOTED: "Noted, carry on",
};

/** What a tap does once the dispatcher has done the thing themselves, in plain words. */
const DONE: Partial<Record<DecisionAction, string>> = {
  CALL_STORE: "Noted: you are calling the store. Skip it from here if nobody comes.",
  WAIT: "Noted: waiting. The stop stays on the trip.",
  NOTED: "Noted. It stays in the Issues list for the day's end.",
  ASK_RECEIVE_LATER: "Ask the store from the trip page's question, with the time.",
};

/** What was shown this page's life, by stop, situation and chips: recorded once (a decision taken without a chip is logged against it). */
const recordedShown = new Set<string>();

export default function ReportSuggestions({
  stop,
  report,
  openStops,
  vehicleId,
  online,
  wholeTrip = false,
  onDraft,
}: {
  /** The stop the report is about, done or not; for a report about the vehicle, the next stop still open. */
  stop: RunSheetStopView;
  /** The report's type, which names the situation. */
  report: string;
  /** The trip's stops not yet done, for End trip. */
  openStops: RunSheetStopView[];
  vehicleId: string | null;
  online: boolean;
  /** The report is about the vehicle, not one stop (a breakdown, a blocked road): a word goes to everyone on the trip. */
  wholeTrip?: boolean;
  /** Puts a message in the composer. */
  onDraft: (draft: ThreadDraft) => void;
}): React.JSX.Element | null {
  const read = useResource(
    online
      ? (signal: AbortSignal) =>
          request<SuggestionsView>(`/api/ml/decision-suggestions?deliveryId=${encodeURIComponent(stop.deliveryId)}&situation=${encodeURIComponent(report)}`, { signal })
      : null,
    `suggest|${stop.deliveryId}|${report}|${stop.rowVersion}`,
  );
  const { run } = useCommand();
  const [chosen, setChosen] = useState<DecisionAction | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const view = read.data;
  // The chips are on screen: say so once, so a skip or End trip taken another way is logged against them.
  const shownKey = view && view.suggestions.length > 0 ? `${stop.deliveryId}|${view.situation}|${view.suggestions.map((s) => s.action).join(",")}` : null;
  useEffect(() => {
    if (!shownKey || !online || recordedShown.has(shownKey) || !view) return;
    recordedShown.add(shownKey);
    void run(SuggestionCommandKind.record, { deliveryId: stop.deliveryId, situation: view.situation, shown: view.suggestions.map((s) => s.action), shownOnly: true }, null);
    // Keyed on what was shown, not on the view object a re-read replaces.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shownKey, online]);
  if (!view || view.suggestions.length === 0) return null;
  const shown = view.suggestions.map((s) => s.action);

  const record = (action: DecisionAction | null) =>
    void run(SuggestionCommandKind.record, { deliveryId: stop.deliveryId, situation: view.situation, shown, ...(action ? { chosen: action } : {}) }, null);

  const pick = (action: DecisionAction) => {
    setNote(null);
    if (action === "SKIP_NEXT_DAY" || action === "SKIP_CANCEL" || action === "END_TRIP") {
      setChosen(action);
      return;
    }
    if (action === "TELL_STORE" && view.playbook.message) {
      const body = fill(view.playbook.message.template, {
        vehicle: vehicleId,
        store: wholeTrip ? "your store" : stop.outletId,
        expected: stop.expectedArrival ? clockOn(stop.expectedArrival) : null,
        "window close": hhmm(stop.windowClose),
      });
      if (body) onDraft({ address: wholeTrip ? { to: "all", outletId: null } : { to: "outlet", outletId: stop.outletId }, body });
    }
    setNote(DONE[action] ?? null);
    record(action);
  };

  if (chosen === "SKIP_NEXT_DAY" || chosen === "SKIP_CANCEL") {
    return (
      <SkipStopForm
        stop={stop}
        initialThen={chosen === "SKIP_CANCEL" ? "CANCEL" : "NEXT_DAY"}
        onSent={() => record(chosen)}
        onDone={() => setChosen(null)}
      />
    );
  }
  if (chosen === "END_TRIP") {
    return <EndTripForm tripId={stop.tripId} openStops={openStops} onSent={() => record("END_TRIP")} onDone={() => setChosen(null)} />;
  }

  return (
    <div role="group" aria-label="Suggested decisions" className="flex flex-col gap-1.5">
      <span className="text-[12px] font-medium text-go-secondary">
        {view.basis === "learned" ? "Suggested from what dispatchers chose here" : "Suggested by the rules"}
      </span>
      <div className="flex flex-wrap gap-1.5">
        {view.suggestions.map((s) => (
          <button
            key={s.action}
            type="button"
            disabled={!online}
            onClick={() => pick(s.action)}
            title={s.warning ?? undefined}
            className="min-h-8 rounded-full bg-go-card px-3 text-[13px] font-medium text-go-teal shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)] disabled:opacity-40"
          >
            {LABEL[s.action]}
            {/* Counts in words once learned (PLAN, How it works 4), never a bare percentage. */}
            {s.chosenTimes != null && s.shownTimes ? (
              <span className="font-normal text-go-secondary">{` · chosen ${s.chosenTimes} of ${s.shownTimes} times`}</span>
            ) : null}
          </button>
        ))}
      </div>
      {view.suggestions.filter((s) => s.warning).map((s) => (
        <p key={s.action} className="text-[12px] text-go-warning-text">{`${LABEL[s.action]}: ${s.warning}`}</p>
      ))}
      {note && <p role="status" className="text-[12px] text-go-secondary">{note}</p>}
    </div>
  );
}
