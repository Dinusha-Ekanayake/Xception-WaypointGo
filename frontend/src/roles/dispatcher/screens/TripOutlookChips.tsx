"use client";

import { useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { ChanceView, RunSheetStopView, TripOutlookView } from "@shared/domain/types";
import type { ThreadDraft } from "@shared/ui";
import { EndTripForm, SkipStopForm } from "./LiveTripSkip.tsx";

// Decision suggestions part 2 (R-ML-34, docs/issues/decision-suggestions/PLAN.md):
// at the top of the trip's chat, what is likely for the whole trip and for each
// store still waiting, from the trip's open reports. A breakdown changes every
// store's chances at once. A tap opens the decision already set (End trip, Skip)
// or puts a word in the composer; the dispatcher still confirms. Never acts.

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

/** What a tap says when there is nothing to send: the decision is the dispatcher's to carry out. */
const NOTED: Record<string, string> = {
  WAIT: "Noted: waiting. The stops stay on the trip.",
  CARRY_ON: "Noted: the trip carries on.",
  DELIVER: "Noted: delivered as planned.",
};

/** Below this a decision is not worth a chip. */
const SHOWN_FROM = 0.05;

type Open = { kind: "end" } | { kind: "skip"; stop: RunSheetStopView; then: "NEXT_DAY" | "CANCEL" };

/** The trip's outlook, read once for the chat: its chips, and which reports it already covers. */
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

export default function TripOutlookChips({
  view,
  tripId,
  vehicleId,
  stops,
  openStops,
  online,
  onDraft,
}: {
  view: TripOutlookView | null;
  tripId: string;
  vehicleId: string | null;
  /** The trip's stops from the run sheet, for the decisions' forms. */
  stops: RunSheetStopView[];
  openStops: RunSheetStopView[];
  online: boolean;
  onDraft: (draft: ThreadDraft) => void;
}): React.JSX.Element | null {
  const [open, setOpen] = useState<Open | null>(null);
  const [note, setNote] = useState<string | null>(null);
  if (!view || (view.trip.length === 0 && view.stops.length === 0)) return null;

  if (open?.kind === "end") {
    return <EndTripForm tripId={tripId} openStops={openStops} onDone={() => setOpen(null)} />;
  }
  if (open?.kind === "skip") {
    return <SkipStopForm stop={open.stop} initialThen={open.then} onDone={() => setOpen(null)} />;
  }

  const pick = (action: string, outletId: string | null) => {
    setNote(null);
    const stop = outletId ? stops.find((s) => s.outletId === outletId) ?? null : null;
    if (action === "END_TRIP") return setOpen({ kind: "end" });
    if ((action === "SKIP_NEXT_DAY" || action === "SKIP_CANCEL") && stop) {
      return setOpen({ kind: "skip", stop, then: action === "SKIP_CANCEL" ? "CANCEL" : "NEXT_DAY" });
    }
    if (action === "ASK_RECEIVE_LATER" && outletId) {
      onDraft({
        address: { to: "outlet", outletId },
        body: `${vehicleId ?? "The vehicle"} is held up on the way to you. Can you still receive today after your window? Please reply with the latest time.`,
      });
      return;
    }
    setNote(NOTED[action] ?? null);
  };

  return (
    <section aria-label="Likely decisions" className="flex max-h-[40dvh] flex-col gap-2.5 overflow-y-auto rounded-go-card-s bg-go-surface px-3.5 py-3">
      <span className="text-[12px] font-medium text-go-secondary">Likely decisions · from the trip&apos;s reports</span>
      {view.trip.length > 0 && (
        <Row
          label={`${vehicleId ?? "Trip"} · ${SITUATION[view.situation ?? ""] ?? "On the road"}`}
          chances={view.trip}
          online={online}
          onPick={(a) => pick(a, null)}
        />
      )}
      {view.stops.map((s) => (
        <Row
          key={s.deliveryId}
          label={`${s.outletId} · ${SITUATION[s.situation] ?? s.situation}`}
          chances={s.chances}
          online={online}
          onPick={(a) => pick(a, s.outletId)}
        />
      ))}
      {note && <p role="status" className="text-[12px] text-go-secondary">{note}</p>}
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
      <span className="text-[13px] font-medium text-go-ink">{label}</span>
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
