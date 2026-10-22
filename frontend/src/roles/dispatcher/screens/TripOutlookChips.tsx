"use client";

import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { DecisionView, RunSheetStopView, TripOutlookView } from "@shared/domain/types";
import { ReadMessage } from "./NextStepParts.tsx";
import ProblemRoom from "./ProblemRoom.tsx";

// Decision suggestions part 3 (R-ML-36, docs/issues/decision-suggestions/PLAN.md):
// while a problem is open on the trip, its problem room at the top of the chat is
// the only place that offers a decision. Under it, the messages read automatically
// (R-ML-35), each with "Not a problem"; and, even with nothing open, the words that
// could not be read (rule 9).

/** The trip's outlook and its problem room, read once for the chat. */
export function useTripOutlook(
  tripId: string | null,
  depotCode: string | null,
  online: boolean,
  extraMinutes: number | null = null,
): TripOutlookView | null {
  const read = useResource(
    online && tripId
      ? (signal: AbortSignal) =>
          request<TripOutlookView>(
            `/api/ml/trip-outlook?tripId=${encodeURIComponent(tripId)}${extraMinutes !== null ? `&extraMinutes=${extraMinutes}` : ""}`,
            { signal },
          )
      : null,
    `trip-outlook|${tripId}|${extraMinutes ?? ""}`,
    30_000,
    // A report, a question answered, a skip or the trip ending changes the room at once.
    depotCode ? { depots: [depotCode], topics: ["message", "delivery", "trip", "issue"] } : undefined,
  );
  return read.data && read.data.tripId === tripId ? read.data : null;
}

/** A problem is open on the trip: its room is up, and the chat offers nothing else to decide or send. */
export function problemOpen(view: TripOutlookView | null): boolean {
  return view?.room != null;
}

export default function TripOutlookChips({
  view,
  threadId,
  tripId,
  vehicleId,
  stops,
  decisions,
  online,
  onExtra,
}: {
  view: TripOutlookView | null;
  threadId: string;
  tripId: string;
  vehicleId: string | null;
  /** The trip's stops from the run sheet, for the decisions' forms. */
  stops: RunSheetStopView[];
  decisions: DecisionView[];
  online: boolean;
  onExtra: (minutes: number) => void;
}): React.JSX.Element | null {
  if (!view) return null;
  // Rule 9: words Jev could not read are said even when nothing else is open, so a problem only they raised is not missed.
  const unread = view.unreadMessages > 0 ? (
    <p role="status" className="rounded-go-card-s bg-go-warning-tint px-3.5 py-2.5 text-[12px] text-go-warning-text">
      {`${view.unreadMessages === 1 ? "1 message" : `${view.unreadMessages} messages`} could not be read automatically. Read ${view.unreadMessages === 1 ? "it" : "them"} yourself.`}
    </p>
  ) : null;
  if (!view.room) return unread;
  const read = view.signals.filter((x) => x.source === "message");
  return (
    <div className="flex flex-col gap-2">
      <ProblemRoom
        room={view.room}
        threadId={threadId}
        tripId={tripId}
        vehicleId={vehicleId}
        stops={stops}
        decisions={decisions}
        reportIds={view.signals.filter((x) => x.source === "report").map((x) => x.messageId)}
        online={online}
        onExtra={onExtra}
      />
      {read.map((x) => <ReadMessage key={x.messageId} tripId={tripId} signal={x} online={online} />)}
      {unread}
      {!view.messagesRead && <span className="text-[11px] text-go-muted">From the trip&apos;s reports only; messages are not read automatically.</span>}
    </div>
  );
}
