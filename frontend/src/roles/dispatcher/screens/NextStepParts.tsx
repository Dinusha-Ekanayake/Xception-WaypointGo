"use client";

import { useState } from "react";
import { announceChange } from "@shared/api/changes";
import { friendlyError } from "@shared/api/problem";
import { SuggestionCommandKind, type TripOutlookView } from "@shared/domain/types";
import { useCommand } from "../data/useCommand.ts";

// Beside the trip's problem room (TripOutlookChips.tsx, R-ML-35): the line that
// says a message was read automatically, with "Not a problem".

export const SITUATION: Record<string, string> = {
  BREAKDOWN: "Breakdown",
  ROAD_BLOCKED: "Road blocked",
  RUNNING_LATE: "Running late",
  STORE_CLOSED: "Store closed",
  CANNOT_UNLOAD: "Cannot unload",
  GOODS_PROBLEM: "Goods problem",
};

/**
 * R-ML-35: says plainly that a message was read automatically, without naming
 * the model or a score, and lets the dispatcher take a wrong or handled one off
 * the card with a reason (rule 8).
 */
export function ReadMessage({
  tripId,
  signal,
  online,
}: {
  tripId: string;
  signal: TripOutlookView["signals"][number];
  online: boolean;
}): React.JSX.Element {
  const { run, busy } = useCommand();
  const [asking, setAsking] = useState(false);
  const [why, setWhy] = useState("");
  const [error, setError] = useState<string | null>(null);
  const who = signal.authorRole === "store_manager" ? (signal.outletId ? `${signal.outletId}'s` : "a store's") : "the driver's";
  const what = (SITUATION[signal.situation] ?? signal.situation).toLowerCase();
  const takeOff = async () => {
    setError(null);
    const sent = await run(SuggestionCommandKind.dismissSignal, { tripId, messageId: signal.messageId, reason: why.trim() }, null);
    if (!sent.ok) return setError(friendlyError(sent.error));
    announceChange({ topic: "message" });
  };
  return (
    <div className="flex flex-col gap-1.5 text-[12px] text-go-secondary">
      <span>
        {`${what[0]!.toUpperCase()}${what.slice(1)}: from ${who} message, read automatically`}
        {!asking && (
          <button type="button" disabled={!online} onClick={() => setAsking(true)} className="ml-2 font-medium text-go-teal disabled:opacity-40">
            Not a problem
          </button>
        )}
      </span>
      {asking && (
        <div role="group" aria-label="Take it off the card" className="flex flex-wrap items-center gap-1.5">
          <input
            aria-label="Why"
            value={why}
            maxLength={300}
            onChange={(e) => setWhy(e.target.value)}
            placeholder="Why, for the record"
            className="min-h-8 min-w-0 flex-1 rounded-full border border-go-divider bg-go-card px-3 text-[13px] text-go-ink"
          />
          <button
            type="button"
            disabled={!online || busy || why.trim() === ""}
            onClick={() => void takeOff()}
            className="min-h-8 rounded-full bg-go-ink px-3 text-[13px] font-medium text-go-card disabled:opacity-40"
          >
            Take it off
          </button>
          <button type="button" onClick={() => setAsking(false)} className="min-h-8 px-2 text-[13px] font-medium text-go-teal">
            Keep
          </button>
        </div>
      )}
      {error && <p role="alert" className="text-go-danger">{error}</p>}
    </div>
  );
}

/** The report situations a message may be confirmed as, in the order a dispatcher meets them. */
const CHOICES = ["BREAKDOWN", "ROAD_BLOCKED", "RUNNING_LATE", "STORE_CLOSED", "CANNOT_UNLOAD", "GOODS_PROBLEM"];

/**
 * R-ML-37: a message read unsure, or a voice note heard over noise, is put to the
 * dispatcher in one line: what it looks like, confirmed in one tap, any other
 * situation, or not a problem. "Show it" opens the message to read or listen.
 */
export function ReviewMessage({
  tripId,
  item,
  online,
  onShow,
}: {
  tripId: string;
  item: TripOutlookView["toReview"][number];
  online: boolean;
  onShow: (messageId: string) => void;
}): React.JSX.Element {
  const { run, busy } = useCommand();
  const [error, setError] = useState<string | null>(null);
  const [more, setMore] = useState(false);
  const who = item.authorRole === "store_manager" ? (item.outletId ? `${item.outletId}'s` : "a store's") : "the driver's";
  const what = item.source === "voice" ? "voice note" : "message";
  const said = item.unclear ? `${who} ${what} was hard to hear` : `${who} ${what} was read unsure`;
  const confirm = async (situation: string) => {
    setError(null);
    const sent = await run(SuggestionCommandKind.confirmSignal, { tripId, messageId: item.messageId, situation }, null);
    if (!sent.ok) return setError(friendlyError(sent.error));
    announceChange({ topic: "message" });
  };
  const chip = "min-h-7 rounded-full bg-go-card px-2.5 text-[12px] font-medium text-go-teal shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)] disabled:opacity-40";
  const others = CHOICES.filter((c) => c !== item.guess);
  return (
    <div role="group" aria-label="Check this message" className="flex flex-wrap items-center gap-x-2 gap-y-1.5 rounded-go-card-s bg-go-warning-tint px-3 py-2 text-[12px] text-go-ink">
      <span>
        <span className="font-medium">Check this: </span>
        {said}
        {item.guess ? `. Looks like: ${(SITUATION[item.guess] ?? item.guess).toLowerCase()}.` : "."}
        <button type="button" onClick={() => onShow(item.messageId)} className="ml-2 font-medium text-go-teal">Show it</button>
      </span>
      <span className="flex flex-wrap gap-1.5">
        {item.guess && (
          <button type="button" disabled={!online || busy} onClick={() => void confirm(item.guess!)}
            className="min-h-7 rounded-full bg-go-ink px-2.5 text-[12px] font-medium text-go-card disabled:opacity-40">
            {`Yes, ${(SITUATION[item.guess] ?? item.guess).toLowerCase()}`}
          </button>
        )}
        {(more || !item.guess) && others.map((c) => (
          <button key={c} type="button" disabled={!online || busy} onClick={() => void confirm(c)} className={chip}>
            {SITUATION[c]}
          </button>
        ))}
        {item.guess && !more && (
          <button type="button" onClick={() => setMore(true)} className={chip}>Something else</button>
        )}
        <button type="button" disabled={!online || busy} onClick={() => void confirm("OTHER")} className={chip}>Not a problem</button>
      </span>
      {error && <p role="alert" className="text-[12px] text-go-danger">{error}</p>}
    </div>
  );
}
