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
