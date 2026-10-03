"use client";

import { useResource } from "@shared/api/useResource";
import type { OrderView, OutletView } from "@shared/domain/types";
import { Icon, Notice } from "@shared/ui";
import type { StoreGateway } from "../data/gateway.ts";
import { deferralOf } from "../data/deferral.ts";
import { cases, clock, dayLabel, depotToday, hhmm, temperatureLabel } from "../data/format.ts";
import { BackButton, Button, Muted } from "../ui.tsx";

// Figma "09 Order deferred": the day it was due, the day it now comes, the
// plan's reason, and that a deferred order goes first on the next run
// (R-PLN-21). Calls are not built, so there is no call button.

export default function DeferredOrder({
  gateway,
  order,
  outlet,
  onGotIt,
  onBack,
}: {
  gateway: StoreGateway;
  order: OrderView;
  outlet: OutletView | null;
  onGotIt: () => void;
  onBack: () => void;
}): React.JSX.Element {
  const timeline = useResource((s) => gateway.history(order.orderId, s), `${order.orderId}:${order.rowVersion}`);
  // A requested day that was closed rolled forward (R-ORD-08); the order first was due on that day.
  const rolled = useResource(order.dateRolled ? (s) => gateway.calendar(order.requestedDate, s) : null, order.dateRolled ? order.requestedDate : "");
  const first = order.dateRolled ? (rolled.data?.nextOperatingDay ?? null) : order.requestedDate;
  const d = deferralOf(order, timeline.data ?? [], first);
  const window = outlet ? `${hhmm(outlet.windowOpen)}-${hhmm(outlet.windowClose)}` : "";

  return (
    <div className="flex flex-col gap-4">
      <BackButton onClick={onBack} />
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">Order deferred</h1>
        <Muted>
          {temperatureLabel(order.temperature)} order moved to {dayLabel(d.now)}
        </Muted>
      </div>

      <section aria-label="Deferred order" className="flex flex-col gap-4 rounded-[26px] bg-white p-5 lg:p-8">
        <div className="flex items-center gap-4">
          <span aria-hidden className="flex size-14 shrink-0 items-center justify-center rounded-full bg-go-danger-tint">
            <Icon name="clock" />
          </span>
          <div className="flex min-w-0 flex-col gap-1">
            <span className="flex items-center gap-1.5 self-start rounded-full bg-go-danger-tint px-3 py-1 text-[13px] font-medium text-go-danger-strong">
              <span aria-hidden className="size-1.5 rounded-full bg-go-danger" />
              Deferred to next run
            </span>
            <h2 className="text-[22px] leading-tight font-medium text-black lg:text-[26px]">
              {order.orderRef} · {temperatureLabel(order.temperature)} · {cases(order.itemCount)}
            </h2>
          </div>
        </div>

        <div className="grid gap-3 md:grid-cols-2">
          <div className="flex flex-col gap-1 rounded-[20px] bg-go-canvas px-5 py-4">
            <span className="text-[13px] text-go-muted">{d.was.label}</span>
            <span className="text-[24px] leading-tight font-medium text-black">{dayLabel(d.was.date)}</span>
            <span className="text-[14px] text-black">{window}</span>
          </div>
          <div className="flex flex-col gap-1 rounded-[20px] bg-go-mint px-5 py-4">
            <span className="text-[13px] text-go-muted">Now</span>
            <span className="text-[24px] leading-tight font-medium text-black">{dayLabel(d.now)}</span>
            <span className="text-[14px] text-black">{window}</span>
          </div>
        </div>

        <div className="flex flex-col gap-1 rounded-[20px] bg-go-canvas px-5 py-4">
          <span className="text-[14px] font-medium text-black">Why</span>
          <span className="text-[15px] text-black">{timeline.loading && !timeline.data ? "…" : d.why}</span>
        </div>
        {timeline.error && <Notice tone="warning" title="The reason could not be read right now">{timeline.error.message}</Notice>}

        <p className="flex items-center gap-3 rounded-[16px] bg-[#e3f8ee] px-5 py-3 text-[15px] font-medium text-go-teal">
          <svg aria-hidden viewBox="0 0 24 24" className="size-5 shrink-0 fill-none stroke-current stroke-2">
            <path d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1 6.2L12 17.3 6.5 20.2l1-6.2L3 9.6l6.2-.9z" strokeLinejoin="round" />
          </svg>
          Deferred orders go first on the next run.
        </p>

        <div className="w-full sm:w-[160px]">
          <Button onClick={onGotIt}>Got it</Button>
        </div>
        {d.at && (
          <Muted>
            Deferred by {d.by} · {dayLabel(depotToday(new Date(d.at)))} {clock(d.at)}
          </Muted>
        )}
      </section>
    </div>
  );
}
