"use client";

import { useEffect, useState, type ReactNode } from "react";
import { type ApiError, friendlyError } from "@shared/api/problem";
import type { DeliveryRecordView, IssueView, OrderStatus, OrderView, OutletView, PendingReceiptView } from "@shared/domain/types";
import { Icon, Notice, SkeletonRows, cx } from "@shared/ui";
import { units, addDays, clock, cutoffLabel, dayLabel, depotToday, editable, greeting, hhmm, onTheWay, temperatureLabel, untilCutoff } from "../data/format.ts";
import { isOpenIssue, issueCard, loaderShortUnits } from "../data/issues.ts";
import { aheadLabel, nextDelivery } from "../data/nextDelivery.ts";
import NextStop from "./NextStop.tsx";
import { Button, Card, Chip, Muted } from "../ui.tsx";

// Figma "02 Home": the next delivery, what needs attention, and tomorrow's order
// against the 16:00 cutoff. ETA before a plan is published is the outlet's
// window only; a predicted time needs the plan (issue #18, open decision 2).

const STEPS = ["Confirmed", "Loaded", "Left", "Arriving", "Received"] as const;
const REACHED: Partial<Record<OrderStatus, number>> = { CONFIRMED: 1, ALLOCATED: 1, LOADING: 1, IN_TRANSIT: 3, DELIVERED: 4, PARTIALLY_DELIVERED: 4, RECEIVED: 5 };

export function Progress({ status }: { status: OrderStatus }): React.JSX.Element {
  const done = REACHED[status] ?? 0;
  return (
    <ol className="flex w-full" aria-label="Delivery progress">
      {STEPS.map((step, i) => (
        <li key={step} className="relative flex flex-1 flex-col items-center gap-1" aria-current={i === done ? "step" : undefined}>
          {i > 0 && <span aria-hidden className={cx("absolute top-3 left-[calc(-50%+13px)] h-0.5 w-[calc(100%-26px)] transition-colors duration-[250ms]", i <= done ? "bg-go-success" : "bg-[#dfe7e6]")} />}
          <span
            className={cx(
              "relative flex size-[26px] items-center justify-center rounded-full transition-colors duration-[250ms]",
              i < done ? "bg-go-success" : i === done ? "border-[3px] border-go-success bg-white" : "bg-[#f1f6f5]",
            )}
          >
            {i < done && <Icon name="check-white" />}
          </span>
          <span className={cx("text-[12px] font-medium", i <= done ? "text-black" : "text-[#a9a9a9]")}>{step}</span>
        </li>
      ))}
    </ol>
  );
}

export default function Home({
  orders,
  loading,
  error,
  displayName,
  outlet,
  toReceive,
  deliveries,
  issues,
  onOpen,
  onPlace,
  onReceive,
  onTrack,
  notifications,
  onRetry,
}: {
  orders: OrderView[];
  loading: boolean;
  error: ApiError | Error | null;
  displayName: string;
  outlet: OutletView | null;
  toReceive: PendingReceiptView[];
  /** Today's stops at this outlet; empty when Execution does not answer. */
  deliveries: DeliveryRecordView[];
  issues: IssueView[];
  onOpen: (orderId: string) => void;
  onPlace: () => void;
  onReceive: (orderId: string) => void;
  onTrack: () => void;
  /** Read the orders again after a failure. */
  onRetry?: () => void;
  /** The notifications column on desktops (issue #118). */
  notifications: ReactNode;
}): React.JSX.Element {
  const [left, setLeft] = useState(() => untilCutoff());
  useEffect(() => {
    const t = setInterval(() => setLeft(untilCutoff()), 30_000);
    return () => clearInterval(t);
  }, []);

  const today = depotToday();
  // Before the cutoff an order is for tomorrow's run; after it, the day after (R-ORD-01).
  const next = addDays(today, left > 0 ? 1 : 2);
  // Today's delivery on the way, else the next one planned or confirmed (UX plan U3).
  const next_ = nextDelivery(orders, today);
  const coming = next_?.order ?? null;
  const forNext = orders.filter((o) => o.deliveryDate === next && o.status !== "CANCELLED");
  const stop = coming ? (deliveries.find((d) => d.orderId === coming.orderId) ?? null) : null;
  const shortage = coming ? issues.find((i) => i.type === "LOADING_SHORTFALL" && isOpenIssue(i) && i.subjects.some((s) => s.id === coming.orderId)) : undefined;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">
          {greeting()}, {displayName}
        </h1>
        <Muted>
          {dayLabel(today)} · {outlet ? `${outlet.districtName} ${outlet.outletId} · delivery window ${hhmm(outlet.windowOpen)}-${hhmm(outlet.windowClose)}` : "…"}
        </Muted>
      </div>

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start">
        <div className="flex min-w-0 flex-col gap-5">
          {error && <Notice tone="danger" title="Could not load your orders" onRetry={onRetry}>{friendlyError(error)}</Notice>}

          {/* Moves into Track's first card when the delivery is tracked (shared element, UX polish 4). */}
          <Card label="Next delivery" style={{ viewTransitionName: "vt-delivery" }}>
            <div className="flex items-center gap-1.5">
              <p className="flex-1 text-[13px] font-light text-go-muted">
                {next_
                  ? next_.when === "today"
                    ? `Next delivery · ${next_.position} of ${next_.ofDay} today`
                    : <>
                        Next delivery · {dayLabel(next_.order.deliveryDate)}
                        {/* Kept together on a narrow phone, never "1 of / 3". */}
                        {next_.ofDay > 1 && <span className="whitespace-nowrap"> · 1 of {next_.ofDay}</span>}
                      </>
                  : "No delivery planned"}
              </p>
              {coming && <Chip>{next_?.when === "ahead" ? aheadLabel(coming.status) : onTheWay(coming.status) ? "On the way" : "Arrived"}</Chip>}
              {coming && <Chip outline>{temperatureLabel(coming.temperature)}</Chip>}
              {stop && <Chip outline>{stop.vehicleId}</Chip>}
            </div>
            {coming ? (
              <>
                <NextStop stop={stop} order={coming} outlet={outlet} />
                <Progress status={coming.status} />
                {/* "Shortage notice" of "02 Home": a grey card, not a warning; the loader already told dispatch. */}
                {shortage && (
                  <button type="button" onClick={() => onOpen(coming.orderId)} className="flex flex-col gap-1.5 rounded-[18px] bg-go-surface px-4 py-3.5 text-left">
                    <span className="flex items-center gap-2">
                      <Chip outline>{coming.orderRef}</Chip>
                      <span className="text-[15px] font-medium text-black">{temperatureLabel(coming.temperature)}</span>
                    </span>
                    <span className="text-[20px] font-medium text-black">
                      {loaderShortUnits(shortage) > 0
                        ? `${units(loaderShortUnits(shortage))} short - ${Math.max(0, coming.itemCount - loaderShortUnits(shortage))} of ${coming.itemCount} coming`
                        : issueCard(shortage, coming, clock).title}
                    </span>
                    <span className="text-[13px] text-go-secondary">Reported at loading · comes next delivery</span>
                  </button>
                )}
                <div className="flex gap-2.5">
                  <Button tone="plain" onClick={onTrack}>
                    Track delivery
                  </Button>
                  <Button disabled={!toReceive.some((r) => r.orderId === coming.orderId)} onClick={() => onReceive(coming.orderId)}>
                    Receive delivery
                  </Button>
                </div>
              </>
            ) : (
              loading ? <SkeletonRows label="Loading…" /> : <Muted>No delivery is planned for you yet. Your next order shows here once it is confirmed.</Muted>
            )}
          </Card>

          <Card label={`Order for ${dayLabel(next)}`}>
            <div className="flex items-start gap-2">
              <div className="flex flex-1 flex-col gap-[3px]">
                <h2 className="text-[18px] font-medium text-black">Order for {dayLabel(next)}</h2>
                <Muted>Closes 16:00 · {cutoffLabel(left)}</Muted>
              </div>
              <Chip tone={forNext.length ? "ok" : "muted"}>{forNext.length ? `${forNext.length} placed` : "Not placed yet"}</Chip>
            </div>
            <ul className="grid grid-cols-2 gap-2.5">
              {(["ambient", "chilled"] as const).map((t) => {
                const placed = forNext.filter((o) => o.temperature === t);
                const total = placed.reduce((s, o) => s + o.itemCount, 0);
                const first = placed[0];
                return (
                  <li key={t}>
                    <button
                      type="button"
                      disabled={!first}
                      onClick={() => first && onOpen(first.orderId)}
                      className="flex min-h-[72px] w-full flex-col gap-0.5 rounded-[16px] bg-go-canvas px-3.5 py-2.5 text-left disabled:cursor-default"
                    >
                      <span className="text-[15px] font-medium">{temperatureLabel(t)}</span>
                      <span className="text-[13px] text-go-muted">
                        {first ? `${first.orderRef} · ${units(total)}${placed.some((o) => editable(o.status)) ? " · change" : ""}` : "Not placed"}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
            <button type="button" onClick={onPlace} className="flex min-h-12 w-full items-center justify-center rounded-[22px] bg-go-mint px-4 text-[16px] font-medium text-black">
              +&nbsp;&nbsp;{forNext.length ? "Place another order" : `Place order for ${dayLabel(next)}`}
            </button>
          </Card>
        </div>

        {/* Desktop: the notifications column from "02 Home". */}
        <aside className="hidden lg:block">
          <Card label="Notifications">{notifications}</Card>
        </aside>
      </div>
    </div>
  );
}
