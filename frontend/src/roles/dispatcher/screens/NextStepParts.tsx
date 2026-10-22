"use client";

import { useState } from "react";
import { send } from "@shared/api/commands";
import { announceChange } from "@shared/api/changes";
import { friendlyError } from "@shared/api/problem";
import { SuggestionCommandKind, type ChanceView, type TripOutlookView } from "@shared/domain/types";
import { newId, postCommand } from "@shared/messaging/useThread";
import type { ThreadDraft } from "@shared/ui";
import { useCommand } from "../data/useCommand.ts";

// The parts of the trip's Next step card (TripOutlookChips.tsx, R-ML-34, R-ML-35):
// a row of likely decisions, the follow-up after one is confirmed, and the line
// that says a message was read automatically.

export const SITUATION: Record<string, string> = {
  BREAKDOWN: "Breakdown",
  ROAD_BLOCKED: "Road blocked",
  RUNNING_LATE: "Running late",
  STORE_CLOSED: "Store closed",
  CANNOT_UNLOAD: "Cannot unload",
  GOODS_PROBLEM: "Goods problem",
};

export const ACTION: Record<string, string> = {
  END_TRIP: "End trip",
  WAIT: "Wait",
  CARRY_ON: "Carry on",
  SKIP_NEXT_DAY: "Skip to next day",
  SKIP_CANCEL: "Skip and cancel",
  ASK_RECEIVE_LATER: "Ask to receive later",
  DELIVER: "Deliver as planned",
};

/** Below this a decision is not worth a chip. */
export const SHOWN_FROM = 0.05;

export type Word = { address: NonNullable<ThreadDraft["address"]>; body: string; to: string };

/** After a decision: what happened, and the word to send when the system did not already send one. */
export type FollowUp = { said: string; word: Word | null };

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

/** What happened, and the word to send: sent from here, edited in the composer, or left. */
export function AfterDecision({
  followUp,
  threadId,
  online,
  onDraft,
  onClose,
}: {
  followUp: FollowUp;
  threadId: string;
  online: boolean;
  onDraft: (draft: ThreadDraft) => void;
  onClose: () => void;
}): React.JSX.Element {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const word = followUp.word;
  const sendNow = async () => {
    if (!word) return;
    setBusy(true);
    setError(null);
    try {
      await send(postCommand({
        threadId,
        to: word.address.to,
        ...(word.address.outletId ? { outletId: word.address.outletId } : {}),
        body: word.body,
        clientMessageId: newId(),
      }));
      announceChange({ topic: "message" });
      onClose();
    } catch (failure) {
      setError(friendlyError(failure));
    } finally {
      setBusy(false);
    }
  };
  return (
    <section aria-label="Next step" className="flex flex-col gap-2 rounded-go-card-s bg-go-surface px-3.5 py-3">
      <p role="status" className="text-[13px] font-medium text-go-ink">{followUp.said}</p>
      {word && <p className="rounded-xl bg-go-card px-3 py-2 text-[13px] text-go-ink">{word.body}</p>}
      {error && <p role="alert" className="text-[12px] text-go-danger">{error}</p>}
      <div className="flex flex-wrap gap-1.5">
        {word && (
          <>
            <button
              type="button"
              disabled={!online || busy}
              onClick={() => void sendNow()}
              className="min-h-8 rounded-full bg-go-ink px-3 text-[13px] font-medium text-go-card disabled:opacity-40"
            >
              {busy ? "Sending…" : `Send to ${word.to}`}
            </button>
            <button
              type="button"
              onClick={() => {
                onDraft({ address: word.address, body: word.body });
                onClose();
              }}
              className="min-h-8 rounded-full bg-go-card px-3 text-[13px] font-medium text-go-teal shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)]"
            >
              Edit first
            </button>
          </>
        )}
        <button type="button" onClick={onClose} className="min-h-8 px-2 text-[13px] font-medium text-go-teal">
          {word ? "Not now" : "Done"}
        </button>
      </div>
    </section>
  );
}

export function Row({
  label,
  chances,
  online,
  onPick,
}: {
  label: string;
  chances: ChanceView[];
  online: boolean;
  onPick: (action: string) => void;
}): React.JSX.Element {
  const shown = chances.filter((c) => c.chance >= SHOWN_FROM);
  return (
    <div role="group" aria-label={label} className="flex flex-col gap-1">
      <span className="text-[12px] text-go-secondary">{label}</span>
      <div className="flex flex-wrap gap-1.5">
        {shown.map((c, i) => (
          <button
            key={c.action}
            type="button"
            disabled={!online}
            onClick={() => onPick(c.action)}
            title={c.warning ?? undefined}
            className={
              i === 0
                ? "min-h-8 rounded-full bg-go-ink px-3 text-[13px] font-medium text-go-card disabled:opacity-40"
                : "min-h-8 rounded-full bg-go-card px-3 text-[13px] font-medium text-go-teal shadow-[inset_0_0_0_1px_rgb(0_0_0/0.08)] disabled:opacity-40"
            }
          >
            {ACTION[c.action] ?? c.action}
            <span className={i === 0 ? "font-normal text-go-card/70" : "font-normal text-go-secondary"}>{` · ${Math.round(c.chance * 100)}%`}</span>
          </button>
        ))}
      </div>
      {shown.filter((c) => c.warning).map((c) => (
        <p key={c.action} className="text-[12px] text-go-warning-text">{`${ACTION[c.action] ?? c.action}: ${c.warning}`}</p>
      ))}
    </div>
  );
}
