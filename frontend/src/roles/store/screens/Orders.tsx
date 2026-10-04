"use client";

import { type ApiError, friendlyError } from "@shared/api/problem";
import type { OrderView } from "@shared/domain/types";
import { Notice, SkeletonRows, cx, usePersistentState } from "@shared/ui";
import { units, ORDER_STATUS, dayLabel, depotToday, planNote, temperatureLabel } from "../data/format.ts";
import { Chip, Muted } from "../ui.tsx";

// Every order for this outlet, newest delivery day first. Tapping one opens its
// timeline, where it can be changed or cancelled until it is planned. By
// default the list shows what is still open: cancelled and received orders are
// a filter away.

type Filter = "open" | "received" | "cancelled" | "all";

const FILTERS: { id: Filter; label: string; keep: (o: OrderView) => boolean }[] = [
  { id: "open", label: "Open", keep: (o) => o.status !== "CANCELLED" && o.status !== "RECEIVED" },
  { id: "received", label: "Received", keep: (o) => o.status === "RECEIVED" },
  { id: "cancelled", label: "Cancelled", keep: (o) => o.status === "CANCELLED" },
  { id: "all", label: "All", keep: () => true },
];

const EMPTY: Record<Filter, string> = {
  open: "No open orders. Received and cancelled ones are under their filters.",
  received: "No received orders yet. An order moves here once you confirm its delivery.",
  cancelled: "No cancelled orders.",
  all: "No orders yet. Place one from Home before the 16:00 cutoff.",
};

export default function Orders({
  orders,
  loading,
  error,
  onOpen,
  onPlace,
  onRetry,
}: {
  orders: OrderView[];
  loading: boolean;
  error: ApiError | Error | null;
  onOpen: (orderId: string) => void;
  onPlace: () => void;
  /** Read the orders again after a failure. */
  onRetry?: () => void;
}): React.JSX.Element {
  const [filter, setFilter] = usePersistentState<Filter>("store:orders:filter", "open");
  const today = depotToday();
  const keep = FILTERS.find((f) => f.id === filter)!.keep;
  const shown = orders.filter(keep);
  const days = [...new Set(shown.map((o) => o.deliveryDate))].sort().reverse();

  return (
    <div className="flex flex-col gap-5">
      {/* From lg the sync pill and the bell sit top right over the page (TopBar); keep New order clear of them. */}
      <div className="flex items-end gap-3 lg:mr-[260px]">
        <h1 className="flex-1 text-[32px] leading-tight font-medium text-black">Orders</h1>
        <button type="button" onClick={onPlace} className="min-h-12 rounded-[22px] bg-go-mint px-5 text-[15px] font-medium text-black">
          + New order
        </button>
      </div>

      <div role="group" aria-label="Show" className="flex gap-1 rounded-full bg-white p-1 lg:w-fit">
        {FILTERS.map((f) => (
          <button
            key={f.id}
            type="button"
            aria-pressed={f.id === filter}
            onClick={() => setFilter(f.id)}
            className={cx("min-h-12 flex-1 rounded-full px-3 text-[15px] font-medium lg:flex-none lg:px-5", f.id === filter ? "bg-[#031a0c] text-white" : "text-black")}
          >
            {f.label} <span className="opacity-60">({orders.filter(f.keep).length})</span>
          </button>
        ))}
      </div>

      {error && <Notice tone="danger" title="Could not load your orders" onRetry={onRetry}>{friendlyError(error)}</Notice>}
      {loading && orders.length === 0 && <SkeletonRows label="Loading…" />}
      {!loading && shown.length === 0 && <Muted>{EMPTY[filter]}</Muted>}
      {days.map((d) => (
        <section key={d} aria-label={dayLabel(d)} className="flex flex-col gap-2.5">
          <h2 className="text-[17px] font-medium text-black">
            {d === today ? "Today · " : ""}
            {dayLabel(d)}
          </h2>
          {shown
            .filter((o) => o.deliveryDate === d)
            .map((o) => {
              const s = ORDER_STATUS[o.status];
              return (
                <button
                  key={o.orderId}
                  type="button"
                  onClick={() => onOpen(o.orderId)}
                  className="flex min-h-16 w-full flex-col gap-1.5 rounded-[20px] bg-white px-4 py-3.5 text-left"
                >
                  <span className="flex w-full items-center gap-2">
                    <span className="flex-1 text-[16px] font-medium text-black">{o.orderRef}</span>
                    <Chip tone={s.tone}>{s.label}</Chip>
                  </span>
                  <span className="text-[13px] text-go-muted">
                    {temperatureLabel(o.temperature)} · {units(o.itemCount)}
                    {o.dateRolled ? ` · moved from ${dayLabel(o.requestedDate)}` : ""}
                    {o.deferralCount > 0 ? ` · deferred ${o.deferralCount}×` : ""}
                  </span>
                  {planNote(o) && <span className="text-[13px] font-medium text-go-teal">{planNote(o)}</span>}
                </button>
              );
            })}
        </section>
      ))}
    </div>
  );
}
