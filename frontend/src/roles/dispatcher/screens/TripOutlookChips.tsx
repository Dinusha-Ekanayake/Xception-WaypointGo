"use client";

import { useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { RunSheetStopView, TripOutlookView } from "@shared/domain/types";
import type { ThreadDraft } from "@shared/ui";
import { EndTripForm, SkipStopForm } from "./LiveTripSkip.tsx";
import { AfterDecision, ReadMessage, Row, SITUATION, type FollowUp } from "./NextStepParts.tsx";

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
  if (!view) return null;
  // Rule 9: words Jev could not read are said even when nothing else is open, so a problem only they raised is not missed.
  const unread = view.unreadMessages > 0 ? (
    <p role="status" className="text-[12px] text-go-warning-text">
      {`${view.unreadMessages === 1 ? "1 message" : `${view.unreadMessages} messages`} could not be read automatically. Read ${view.unreadMessages === 1 ? "it" : "them"} yourself.`}
    </p>
  ) : null;
  if (!problemOpen(view)) {
    return unread && <div className="rounded-go-card-s bg-go-warning-tint px-3.5 py-2.5">{unread}</div>;
  }

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
      {view.signals.filter((x) => x.source === "message").map((x) => (
        <ReadMessage key={x.messageId} tripId={tripId} signal={x} online={online} />
      ))}
      {unread}
      <span className="text-[11px] text-go-muted">
        {view.messagesRead ? "Likely from the trip's reports and messages." : "Likely from the trip's reports only; messages are not read automatically."}
        {" You confirm every decision."}
      </span>
    </section>
  );
}
