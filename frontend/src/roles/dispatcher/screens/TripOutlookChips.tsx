"use client";

import { useState } from "react";
import { request } from "@shared/api/client";
import { send } from "@shared/api/commands";
import { announceChange } from "@shared/api/changes";
import { friendlyError } from "@shared/api/problem";
import { useResource } from "@shared/api/useResource";
import type { ChanceView, RunSheetStopView, TripOutlookView } from "@shared/domain/types";
import { newId, postCommand } from "@shared/messaging/useThread";
import type { ThreadDraft } from "@shared/ui";
import { EndTripForm, SkipStopForm } from "./LiveTripSkip.tsx";

// Decision suggestions part 2 (R-ML-34, docs/issues/decision-suggestions/PLAN.md):
// while a problem is open on the trip, one "Next step" card at the top of its
// chat is the only place that offers a decision. It shows the likely decisions
// for the trip and for each store still waiting; once the dispatcher confirms
// one, the same card says who was told, or offers the word to send, prewritten
// and editable. Ending the trip or skipping a store tells the stores by itself
// (R-EXE-31), so the card never offers that word twice. Never acts on its own.

/** What the card's title says happened, by situation. */
const HAPPENED: Record<string, string> = {
  BREAKDOWN: "broke down",
  ROAD_BLOCKED: "is held up by a blocked road",
  RUNNING_LATE: "is running late",
};

const SITUATION: Record<string, string> = {
  BREAKDOWN: "Breakdown",
  ROAD_BLOCKED: "Road blocked",
  RUNNING_LATE: "Running late",
  STORE_CLOSED: "Store closed",
  CANNOT_UNLOAD: "Cannot unload",
  GOODS_PROBLEM: "Goods problem",
};

const ACTION: Record<string, string> = {
  END_TRIP: "End trip",
  WAIT: "Wait",
  CARRY_ON: "Carry on",
  SKIP_NEXT_DAY: "Skip to next day",
  SKIP_CANCEL: "Skip and cancel",
  ASK_RECEIVE_LATER: "Ask to receive later",
  DELIVER: "Deliver as planned",
};

/** Below this a decision is not worth a chip. */
const SHOWN_FROM = 0.05;

type Word = { address: NonNullable<ThreadDraft["address"]>; body: string; to: string };

/** After a decision: what happened, and the word to send when the system did not already send one. */
type FollowUp = { said: string; word: Word | null };

type Open = { kind: "end" } | { kind: "skip"; stop: RunSheetStopView; then: "NEXT_DAY" | "CANCEL" };

/** The trip's outlook, read once for the chat: its card, and which reports it already covers. */
export function useTripOutlook(tripId: string | null, depotCode: string | null, online: boolean): TripOutlookView | null {
  const read = useResource(
    online && tripId ? (signal: AbortSignal) => request<TripOutlookView>(`/api/ml/trip-outlook?tripId=${encodeURIComponent(tripId)}`, { signal }) : null,
    `trip-outlook|${tripId}`,
    30_000,
    // A report, a skip or the trip ending changes the outlook at once.
    depotCode ? { depots: [depotCode], topics: ["message", "delivery", "trip"] } : undefined,
  );
  return read.data && read.data.tripId === tripId ? read.data : null;
}

/** A problem is open on the trip: the card is up, and the chat offers nothing else to decide or send. */
export function problemOpen(view: TripOutlookView | null): boolean {
  return view !== null && (view.trip.length > 0 || view.stops.length > 0);
}

export default function TripOutlookChips({
  view,
  threadId,
  tripId,
  vehicleId,
  stops,
  openStops,
  online,
  onDraft,
}: {
  view: TripOutlookView | null;
  threadId: string;
  tripId: string;
  vehicleId: string | null;
  /** The trip's stops from the run sheet, for the decisions' forms. */
  stops: RunSheetStopView[];
  openStops: RunSheetStopView[];
  online: boolean;
  /** Puts a word in the composer, to edit before sending. */
  onDraft: (draft: ThreadDraft) => void;
}): React.JSX.Element | null {
  const [open, setOpen] = useState<Open | null>(null);
  const [after, setAfter] = useState<FollowUp | null>(null);
  const vehicle = vehicleId ?? "The vehicle";

  if (after) return <AfterDecision followUp={after} threadId={threadId} online={online} onDraft={onDraft} onClose={() => setAfter(null)} />;
  if (!view || !problemOpen(view)) return null;

  if (open?.kind === "end") {
    const waiting = openStops.length;
    return (
      <EndTripForm
        tripId={tripId}
        openStops={openStops}
        onSent={() => setAfter({
          said: `Done: the trip ended. ${waiting === 1 ? "The store still waiting was" : `The ${waiting} stores still waiting were`} told their delivery moves to the next delivery day.`,
          word: { address: { to: "driver", outletId: null }, body: "The trip is ended. Please bring the goods back to the depot.", to: "the driver" },
        })}
        onDone={() => setOpen(null)}
      />
    );
  }
  if (open?.kind === "skip") {
    const { stop, then } = open;
    return (
      <SkipStopForm
        stop={stop}
        initialThen={then}
        onSent={() => setAfter({
          said: `Done: ${stop.outletId} ${then === "CANCEL" ? "is cancelled for today" : "moves to the next delivery day"}. The store was told.`,
          word: null,
        })}
        onDone={() => setOpen(null)}
      />
    );
  }

  const what = view.situation ? HAPPENED[view.situation] ?? null : null;
  const pick = (action: string, outletId: string | null) => {
    const stop = outletId ? stops.find((s) => s.outletId === outletId) ?? null : null;
    if (action === "END_TRIP") return setOpen({ kind: "end" });
    if ((action === "SKIP_NEXT_DAY" || action === "SKIP_CANCEL") && stop) {
      return setOpen({ kind: "skip", stop, then: action === "SKIP_CANCEL" ? "CANCEL" : "NEXT_DAY" });
    }
    if (action === "WAIT") {
      return setAfter({
        said: "Waiting. Tell everyone on the trip?",
        word: { address: { to: "all", outletId: null }, body: `${vehicle} ${what ?? "is held up"}. We will tell you the new time as soon as we know.`, to: "everyone on the trip" },
      });
    }
    if (action === "CARRY_ON") {
      return setAfter({
        said: "Carrying on. Tell the driver?",
        word: { address: { to: "driver", outletId: null }, body: "Carry on with the next stop.", to: "the driver" },
      });
    }
    if (action === "ASK_RECEIVE_LATER" && outletId) {
      return setAfter({
        said: `Ask ${outletId} whether it can receive later?`,
        word: {
          address: { to: "outlet", outletId },
          body: `${vehicle} is held up on the way to you. Can you still receive today after your window? Please reply with the latest time.`,
          to: outletId,
        },
      });
    }
    if (action === "DELIVER" && outletId) return setAfter({ said: `Noted: ${outletId} is delivered as planned.`, word: null });
  };

  const first = view.stops[0];
  const title = what
    ? `${vehicle} ${what}`
    : first ? `${first.outletId} · ${(SITUATION[first.situation] ?? first.situation).toLowerCase()}` : "A problem on the trip";
  return (
    <section aria-label="Next step" className="flex max-h-[40dvh] flex-col gap-2.5 overflow-y-auto rounded-go-card-s bg-go-surface px-3.5 py-3">
      <span className="text-[13px] font-medium text-go-ink">{`Next step · ${title}`}</span>
      {view.trip.length > 0 && <Row label={`${vehicle} · the trip`} chances={view.trip} online={online} onPick={(a) => pick(a, null)} />}
      {view.stops.map((s) => (
        <Row key={s.deliveryId} label={`${s.outletId} · ${SITUATION[s.situation] ?? s.situation}`} chances={s.chances} online={online} onPick={(a) => pick(a, s.outletId)} />
      ))}
      <span className="text-[11px] text-go-muted">Likely from the trip&apos;s reports. You confirm every decision.</span>
    </section>
  );
}

/** What happened, and the word to send: sent from here, edited in the composer, or left. */
function AfterDecision({
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

function Row({
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
