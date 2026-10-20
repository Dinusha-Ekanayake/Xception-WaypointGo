"use client";

import { useState } from "react";
import type { OrderView, StopOrderSuggestion } from "@shared/domain/types";
import type { Resource } from "@shared/api/useResource";
import { hhmm, shortDay } from "@shared/wording";

// The suggested stop order in Edit trip (R-PLN-43). The server times every order
// of a short trip (a local search on a longer one) with the timeline the plan
// uses, so the suggestion can never disagree with the check that follows. It is
// advice: Apply puts it in the dispatcher's working copy, where the trip is
// previewed and judged like any order they drag by hand, and Accept still asks
// for a reason. When the order on screen is already the best one, the card says
// so in one quiet line instead of offering anything.

export const SUGGESTED_REASON = "Stop order suggested to fit delivery windows";

export default function StopOrderAdvice({
  suggestion,
  current,
  orders,
  onApply,
}: {
  suggestion: Resource<StopOrderSuggestion>;
  /** The order on screen; a suggestion for another order is not shown. */
  current: string[];
  orders: Map<string, OrderView>;
  onApply: (orderIds: string[]) => void;
}): React.JSX.Element | null {
  const [dismissed, setDismissed] = useState<string | null>(null);
  const key = current.join(",");
  const data = suggestion.data;

  if (current.length < 2) return null;
  if (suggestion.loading && !data) {
    return (
      <p role="status" className="flex items-center gap-2 text-[13px] text-go-secondary">
        <Spark className="animate-pulse" />
        Checking for a stop order that fits the windows better…
      </p>
    );
  }
  if (!data || suggestion.error) return null;
  if (!data.improves) {
    return (
      <p role="status" className="flex items-center gap-2 text-[13px] text-go-teal">
        <Spark />
        {`${data.summary} (${data.ordersTried} orders compared)`}
      </p>
    );
  }
  if (dismissed === key) return null;

  const outlet = (id: string) => orders.get(id)?.outletId ?? "…";
  const late = data.lateStopsBefore !== data.lateStopsAfter;
  return (
    <section
      aria-label="Suggested stop order"
      className="rounded-go-card border border-go-teal/30 bg-gradient-to-r from-go-success-tint via-go-card to-go-card p-4 shadow-[0_6px_24px_rgba(3,27,8,0.06)] motion-safe:animate-[go-rise-in_320ms_ease-out]"
    >
      <div className="flex flex-wrap items-start gap-4">
        <span aria-hidden className="flex size-9 shrink-0 items-center justify-center rounded-full bg-go-mint text-go-ink">
          <Spark />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-semibold tracking-[0.06em] text-go-teal uppercase">Suggested stop order</p>
          <p className="mt-0.5 text-[15px] font-medium text-go-ink">{data.summary}</p>
          <ol aria-label="Suggested order" className="mt-2 flex flex-wrap items-center gap-1.5 text-[12px] text-go-ink">
            {data.orderIds.map((id, i) => (
              <li key={id} className="flex items-center gap-1.5">
                {i > 0 && <span aria-hidden className="text-go-secondary">›</span>}
                <span className={`rounded-full px-2 py-0.5 ${current[i] === id ? "bg-go-surface" : "bg-go-mint font-medium"}`}>{`${i + 1} ${outlet(id)}`}</span>
              </li>
            ))}
          </ol>
          <dl className="mt-2.5 flex flex-wrap gap-x-5 gap-y-1 text-[12px] text-go-secondary">
            {late && (
              <div className="flex gap-1">
                <dt>Late stops</dt>
                <dd className="font-medium text-go-ink">{`${data.lateStopsBefore} → ${data.lateStopsAfter}`}</dd>
              </div>
            )}
            <div className="flex gap-1">
              <dt>Back at the depot</dt>
              <dd className="font-medium text-go-ink">{`${hhmm(data.finishBefore)} → ${hhmm(data.finishAfter)}`}{orders.get(current[0] ?? "")?.deliveryDate && <span className="ml-1 text-[0.75em] font-normal text-go-secondary">{shortDay(orders.get(current[0] ?? "")!.deliveryDate)}</span>}</dd>
            </div>
            <div className="flex gap-1">
              <dt>Compared</dt>
              <dd className="font-medium text-go-ink">{`${data.ordersTried} orders`}</dd>
            </div>
          </dl>
        </div>
        <div className="flex shrink-0 items-center gap-2 self-center">
          <button type="button" onClick={() => setDismissed(key)} className="rounded-full px-3 py-2 text-[13px] font-medium text-go-secondary hover:text-go-ink">
            Keep mine
          </button>
          <button
            type="button"
            onClick={() => onApply(data.orderIds)}
            className="rounded-full bg-go-ink px-4 py-2 text-[13px] font-medium text-go-card transition-transform active:scale-[0.97]"
          >
            Apply suggested order
          </button>
        </div>
      </div>
    </section>
  );
}

function Spark({ className = "" }: { className?: string }): React.JSX.Element {
  return (
    <svg aria-hidden viewBox="0 0 24 24" className={`size-4 shrink-0 ${className}`} fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z" />
      <path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z" />
    </svg>
  );
}
