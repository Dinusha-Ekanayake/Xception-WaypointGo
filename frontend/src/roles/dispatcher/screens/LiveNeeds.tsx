"use client";

import { useState } from "react";
import { cx } from "@shared/ui";
import { lateBy, tripOf, updateText, useOpenTripThread } from "../data/threads.ts";
import { depotSummaries, progress, routeLabel, summaryText, type NeedCard, type Run } from "../data/liveDesk.ts";
import { Action, Bar, Chip, NOT_AVAILABLE_NOTE, STATUS } from "./LiveParts.tsx";

// Figma "05 Live · Needs you" (189:20983): what needs the dispatcher on the
// left, most urgent first, and the trip board of every vehicle on the road on
// the right, with what the system closed on its own under it.

export default function LiveNeeds({
  cards,
  runs,
  date,
  closedOnTheirOwn,
  online,
  onOpenTrip,
  onOpenIssue,
  onViewAll,
}: {
  cards: NeedCard[];
  runs: Run[];
  date: string;
  closedOnTheirOwn: number;
  online: boolean;
  onOpenTrip: (vehicleId: string) => void;
  onOpenIssue: (issueId: string) => void;
  onViewAll: () => void;
}): React.JSX.Element {
  const unavailableShown = cards.some((c) => c.kind === "offline");
  const openThread = useOpenTripThread();
  const [note, setNote] = useState<string | null>(null);
  // "Notify store" opens the trip's thread written to the store, with the new expected arrival.
  const notify = (vehicleId: string | null) => {
    const run = runs.find((r) => r.day.vehicleId === vehicleId);
    const next = run?.day.current ?? null;
    if (!run || !next) return;
    setNote(null);
    void openThread(tripOf(run), { address: { to: "outlet", outletId: next.outletId }, body: updateText(next, lateBy(next, date)) }).then(setNote);
  };
  return (
    <div className="flex w-full items-start gap-[18px] max-lg:flex-col">
      <section aria-label="Needs you" className="flex w-full flex-col gap-3 lg:max-w-[372px]">
        <h2 className="flex items-baseline gap-2 text-[15px] font-medium text-go-ink">
          Needs you <span className="text-[12px] font-normal text-go-secondary">most urgent first</span>
        </h2>
        {cards.length === 0 && (
          <p className="rounded-[20px] bg-white px-[18px] py-6 text-center text-[13px] text-go-secondary shadow-go-card">Nothing needs you right now.</p>
        )}
        <ul className="flex flex-col gap-3">
          {cards.map((card, i) => (
            <li
              key={card.id}
              className={cx(
                "flex flex-col gap-1.5 rounded-[20px] bg-white px-[18px] py-4 shadow-go-card",
                i === 0 && card.chip.tone === "danger" && "ring-2 ring-[#c08a3e]/70",
              )}
            >
              <span className="flex items-center justify-between gap-2">
                <Chip tone={card.chip.tone}>{card.chip.text}</Chip>
                <span className="text-[12px] text-go-secondary">{card.meta}</span>
              </span>
              <span className="text-[15px] font-semibold text-go-ink">{card.title}</span>
              <span className="text-[12.5px] text-go-secondary">{card.detail}</span>
              <span className="mt-1.5 flex gap-2">
                {card.kind === "window" && (
                  <>
                    <Action primary disabled={!online} className="flex-1" onClick={() => notify(card.vehicleId)}>Notify store</Action>
                    {card.vehicleId && <Action onClick={() => onOpenTrip(card.vehicleId!)}>Open trip</Action>}
                  </>
                )}
                {card.kind === "offline" && (
                  <>
                    <Action primary unavailable className="flex-1">Call driver</Action>
                    {card.vehicleId && <Action onClick={() => onOpenTrip(card.vehicleId!)}>Open trip</Action>}
                  </>
                )}
                {card.kind === "issue" && card.issueId && (
                  <Action primary className="flex-1" onClick={() => onOpenIssue(card.issueId!)}>
                    Book make-up
                  </Action>
                )}
                {(card.kind === "failed" || card.kind === "proof") && card.vehicleId && (
                  <Action primary className="flex-1" onClick={() => onOpenTrip(card.vehicleId!)}>
                    Open trip
                  </Action>
                )}
              </span>
            </li>
          ))}
        </ul>
        {note && <p role="status" className="px-1 text-[11px] text-go-warning-text">{note}</p>}
        {unavailableShown && <p className="px-1 text-[11px] text-go-secondary">{NOT_AVAILABLE_NOTE}</p>}
        {!online && <p className="px-1 text-[11px] text-go-warning-text">Offline: actions are paused.</p>}
      </section>

      <div className="flex w-full min-w-0 flex-1 flex-col gap-3 lg:pt-[34px]">
        <TripBoard runs={runs} onOpenTrip={onOpenTrip} />
        <section aria-label="Handled by the system" className="flex items-center gap-3 rounded-[20px] bg-white px-[18px] py-3.5 shadow-go-card">
          <span aria-hidden className="size-2 shrink-0 rounded-full bg-go-teal" />
          <span className="text-[14px] font-medium text-go-ink">{closedOnTheirOwn} closed on their own</span>
          <span className="min-w-0 flex-1 truncate text-[12.5px] text-go-secondary">delivered in the window, proof taken · no action needed</span>
          <button type="button" onClick={onViewAll} className="shrink-0 text-[13px] font-medium text-go-teal">
            View all ›
          </button>
        </section>
      </div>
    </div>
  );
}

/** Every vehicle on the road as a bar of its stops closed, in its status colour. */
export function TripBoard({ runs, onOpenTrip }: { runs: Run[]; onOpenTrip: (vehicleId: string) => void }): React.JSX.Element {
  const summaries = depotSummaries(runs);
  return (
    <section aria-label="Trip board" className="flex flex-col gap-1 rounded-[20px] bg-white px-[18px] py-4 shadow-go-card">
      <h2 className="mb-1 flex items-baseline gap-2 text-[15px] font-medium text-go-ink">
        Trip board <span className="text-[12px] font-normal text-go-secondary">on the road now</span>
      </h2>
      {runs.length === 0 && <p className="py-6 text-center text-[13px] text-go-secondary">No vehicle is on the road. A vehicle appears here when the loader releases it.</p>}
      <ul className="flex flex-col">
        {runs.map((run) => {
          const status = STATUS[run.status];
          return (
            <li key={run.day.vehicleId}>
              <button
                type="button"
                onClick={() => onOpenTrip(run.day.vehicleId)}
                aria-label={`${run.day.vehicleId}, ${status.label}, ${run.day.done} of ${run.day.stops.length} stops done`}
                className="grid w-full grid-cols-[120px_1fr_88px] items-center gap-4 rounded-xl px-1 py-2.5 text-left hover:bg-go-subtle"
              >
                <span className="flex min-w-0 flex-col">
                  <span className="text-[13px] font-semibold text-go-ink">{run.day.vehicleId}</span>
                  <span className="truncate text-[11.5px] text-go-secondary">{routeLabel(run) || `${run.day.stops.length} stops`}</span>
                </span>
                <Bar share={progress(run)} className={status.bar} thick />
                <span className={cx("text-right text-[12.5px] font-medium", status.text)}>{status.label}</span>
              </button>
            </li>
          );
        })}
      </ul>
      {summaries.length > 1 && (
        <ul className="mt-2 flex flex-col gap-1.5">
          {summaries.map((s) => (
            <li key={s.depot} className="rounded-xl bg-go-subtle px-3 py-2 text-[12.5px] text-go-ink">
              {s.depot} · {summaryText(s)}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
