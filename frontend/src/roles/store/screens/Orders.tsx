import type { ApiError } from "@shared/api/problem";
import type { OrderView } from "@shared/domain/types";
import { Notice } from "@shared/ui";
import { cases, ORDER_STATUS, dayLabel, depotToday, temperatureLabel } from "../data/format.ts";
import { Chip, Muted } from "../ui.tsx";

// Every order for this outlet, newest delivery day first. Tapping one opens its
// timeline, where it can be changed or cancelled until it is planned.

export default function Orders({
  orders,
  loading,
  error,
  onOpen,
  onPlace,
}: {
  orders: OrderView[];
  loading: boolean;
  error: ApiError | Error | null;
  onOpen: (orderId: string) => void;
  onPlace: () => void;
}): React.JSX.Element {
  const today = depotToday();
  const days = [...new Set(orders.map((o) => o.deliveryDate))].sort().reverse();

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-end gap-3">
        <h1 className="flex-1 text-[32px] leading-tight font-medium text-black">Orders</h1>
        <button type="button" onClick={onPlace} className="min-h-12 rounded-[22px] bg-go-mint px-5 text-[15px] font-medium text-black">
          + New order
        </button>
      </div>
      {error && <Notice tone="danger" title="Could not load your orders">{error.message}</Notice>}
      {loading && orders.length === 0 && <Muted>Loading…</Muted>}
      {!loading && orders.length === 0 && <Muted>No orders yet.</Muted>}
      {days.map((d) => (
        <section key={d} aria-label={dayLabel(d)} className="flex flex-col gap-2.5">
          <h2 className="text-[17px] font-medium text-black">
            {d === today ? "Today · " : ""}
            {dayLabel(d)}
          </h2>
          {orders
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
                    {temperatureLabel(o.temperature)} · {cases(o.itemCount)}
                    {o.dateRolled ? ` · moved from ${dayLabel(o.requestedDate)}` : ""}
                    {o.deferralCount > 0 ? ` · deferred ${o.deferralCount}×` : ""}
                  </span>
                </button>
              );
            })}
        </section>
      ))}
    </div>
  );
}
