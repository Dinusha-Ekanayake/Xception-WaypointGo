"use client";

import { useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { DecisionView } from "@shared/domain/types";
import { cx } from "@shared/ui";
import { clockOn, hhmm, stops } from "@shared/wording";
import { lateBy, tripOf, updateText, useOpenTripThread } from "../data/threads.ts";
import { depotSummaries, etaOf, groupByTrip, NEEDS_SHOWN, progress, routeLabel, summaryText, type NeedCard, type Run } from "../data/liveDesk.ts";
import { playbookOf, suggestionFor, type Suggestion } from "../data/playbooks.ts";
import { usePlaybooks } from "../data/usePlaybooks.ts";
import DecisionAsk from "./DecisionAsk.tsx";
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
  // FF-09: the day's questions asked of stores, for every depot this dispatcher oversees.
  const decisions = useResource(
    (signal: AbortSignal) => request<DecisionView[]>(`/api/issues/decisions?date=${encodeURIComponent(date)}`, { signal }),
    `decisions|${date}`,
    10_000,
  );
  // The stop a window or failed card is about, for the question to its store.
  const stopOf = (card: NeedCard) => {
    const run = runs.find((r) => r.day.vehicleId === card.vehicleId);
    if (!run) return null;
    const stop = card.kind === "window" ? run.day.current : card.kind === "failed" ? run.day.stops.find((s) => s.outcome === "FAILED") : null;
    return stop ? { stop, stops: run.day.stops } : null;
  };
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
  // The playbook for a card, its message filled from the stop it is about (issue #269). Read from the
  // server once; while it cannot be read, a card offers no steps rather than old ones.
  const playbooks = usePlaybooks();
  const suggest = (card: NeedCard): { suggestion: Suggestion; tripId: string | null; outletId: string | null } | null => {
    const playbook = playbookOf(playbooks.data, card.kind);
    if (!playbook) return null;
    const run = runs.find((r) => r.day.vehicleId === card.vehicleId);
    const stops = run?.day.stops ?? [];
    const stop =
      (card.kind === "failed" ? stops.find((s) => s.outcome === "FAILED") : card.kind === "left" ? stops.find((s) => `left-${s.deliveryId}` === card.id) : run?.day.current) ?? null;
    const suggestion = suggestionFor(playbook, {
      vehicle: card.vehicleId,
      store: stop?.outletId ?? null,
      expected: stop ? etaOf(stop, date) : null,
      "window close": stop ? clockOn(stop.windowClose, date) : null,
    });
    return { suggestion, tripId: stop?.tripId ?? null, outletId: stop?.outletId ?? null };
  };
  const write = (tripId: string | null, to: "outlet" | "driver", outletId: string | null, body: string) => {
    setNote(null);
    void openThread(tripId, { address: { to, outletId: to === "outlet" ? outletId : null }, body }).then(setNote);
  };
  // At 80 to 1,280 trips: one group per trip, the first NEEDS_SHOWN, the rest on request.
  const groups = groupByTrip(cards);
  const [showAll, setShowAll] = useState(false);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const toggle = (key: string) =>
    setOpen((was) => {
      const next = new Set(was);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  const shownGroups = showAll ? groups : groups.slice(0, NEEDS_SHOWN);
  const hiddenGroups = groups.length - shownGroups.length;
  const renderCard = (card: NeedCard): React.JSX.Element => (
    <>
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
        {(card.kind === "failed" || card.kind === "left") && card.vehicleId && (
          <Action primary className="flex-1" onClick={() => onOpenTrip(card.vehicleId!)}>
            Open trip
          </Action>
        )}
      </span>
      {(card.kind === "window" || card.kind === "failed") && stopOf(card) && (
        <DecisionAsk
          kind={card.kind}
          stop={stopOf(card)!.stop}
          stops={stopOf(card)!.stops}
          decisions={decisions.data ?? []}
          online={online}
          now={new Date()}
          date={date}
          onChanged={decisions.refresh}
        />
      )}
      {(() => {
        const suggested = suggest(card);
        return suggested ? (
          <Suggested {...suggested} online={online} showMessage={card.kind !== "window"} onWrite={write} />
        ) : playbooks.error ? (
          // Rule 9: say the advice is missing rather than show none silently.
          <p className="mt-1 text-[12px] text-go-secondary">Suggested steps could not be read.</p>
        ) : null;
      })()}
    </>
  );
  return (
    <div className="flex w-full items-start gap-[18px] max-lg:flex-col">
      <section aria-label="Needs you" className="flex w-full flex-col gap-3 lg:max-w-[372px]">
        <h2 className="flex items-baseline gap-2 text-[15px] font-medium text-go-ink">
          Needs you{" "}
          <span className="text-[12px] font-normal text-go-secondary">
            {groups.length > 0 ? `${groups.length} ${groups.length === 1 ? "item" : "items"} · most urgent first` : "most urgent first"}
          </span>
        </h2>
        {cards.length === 0 && (
          <p className="rounded-[20px] bg-white px-[18px] py-6 text-center text-[13px] text-go-secondary shadow-go-card">Nothing needs you right now.</p>
        )}
        <ul className="flex flex-col gap-3">
          {shownGroups.map((group, i) => (
            <li
              key={group.key}
              className={cx(
                "flex flex-col gap-1.5 rounded-[20px] bg-white px-[18px] py-4 shadow-go-card",
                i === 0 && group.lead.chip.tone === "danger" && "ring-2 ring-[#c08a3e]/70",
              )}
            >
              {renderCard(group.lead)}
              {group.more.length > 0 && (
                <button
                  type="button"
                  aria-expanded={open.has(group.key)}
                  onClick={() => toggle(group.key)}
                  className="mt-1 self-start text-[12.5px] font-medium text-go-teal"
                >
                  {open.has(group.key) ? "Show less" : `+${group.more.length} more on this trip`}
                </button>
              )}
              {open.has(group.key) &&
                group.more.map((card) => (
                  <div key={card.id} className="mt-2 flex flex-col gap-1.5 border-t border-go-divider pt-3">
                    {renderCard(card)}
                  </div>
                ))}
            </li>
          ))}
        </ul>
        {hiddenGroups > 0 && (
          <button
            type="button"
            onClick={() => setShowAll(true)}
            className="min-h-11 rounded-[20px] bg-white px-[18px] text-[13px] font-medium text-go-teal shadow-go-card"
          >
            Show {hiddenGroups} more {hiddenGroups === 1 ? "trip" : "trips"}
          </button>
        )}
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

/** The playbook's steps for a card, on request, and its ready message opened in the trip's thread for the dispatcher to send. */
function Suggested({
  suggestion,
  tripId,
  outletId,
  online,
  showMessage,
  onWrite,
}: {
  suggestion: Suggestion;
  tripId: string | null;
  outletId: string | null;
  online: boolean;
  /** "Notify store" already writes to the store about a closing window. */
  showMessage: boolean;
  onWrite: (tripId: string | null, to: "outlet" | "driver", outletId: string | null, body: string) => void;
}): React.JSX.Element {
  const message = showMessage ? suggestion.message : null;
  return (
    <details className="mt-1 rounded-xl bg-go-subtle px-3 py-2 text-[12.5px] text-go-ink">
      <summary className="cursor-pointer font-medium">Suggested steps</summary>
      <ol className="mt-1.5 flex list-decimal flex-col gap-1 pl-4">
        {suggestion.steps.map((step) => (
          <li key={step}>{step}</li>
        ))}
      </ol>
      {message && tripId && (
        <div className="mt-2 flex flex-col gap-1.5">
          <p className="rounded-lg bg-white px-2.5 py-2 text-go-secondary">
            <em>{message.body}</em>
          </p>
          <Action disabled={!online} onClick={() => onWrite(tripId, message.to, outletId, message.body)}>
            {message.to === "outlet" ? "Write to the store" : "Write to the driver"}
          </Action>
        </div>
      )}
    </details>
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
                  <span className="truncate text-[11.5px] text-go-secondary">{routeLabel(run) || stops(run.day.stops.length)}</span>
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
