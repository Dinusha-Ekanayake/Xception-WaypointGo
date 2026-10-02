"use client";

import { useState } from "react";
import type { OrderView, OutletView, PendingReceiptView } from "@shared/domain/types";
import { cx } from "@shared/ui";
import { cases, ORDER_STATUS, addDays, dayLabel, depotToday, hhmm, onTheWay, temperatureLabel } from "../data/format.ts";
import { Button, Chip, Muted } from "../ui.tsx";
import { Progress } from "./Home.tsx";

// Figma "05a Deliveries": today, upcoming and the past week. The time shown is
// the outlet's delivery window until the plan gives a predicted arrival.

type Range = "today" | "upcoming" | "past";
const SKIP = new Set(["CANCELLED"]);

export default function Deliveries({
  orders,
  outlet,
  toReceive,
  onOpen,
  onReceive,
  onTrack,
}: {
  orders: OrderView[];
  outlet: OutletView | null;
  toReceive: PendingReceiptView[];
  onOpen: (orderId: string) => void;
  onReceive: (orderId: string) => void;
  onTrack: () => void;
}): React.JSX.Element {
  const [range, setRange] = useState<Range>("today");
  const today = depotToday();
  const weekAgo = addDays(today, -7);
  const pending = new Set(toReceive.map((r) => r.orderId));
  const live = orders.filter((o) => !SKIP.has(o.status));
  const lists: Record<Range, OrderView[]> = {
    // Anything still waiting to be received belongs on today's list, whatever its date.
    today: live.filter((o) => o.deliveryDate === today || pending.has(o.orderId)),
    upcoming: live.filter((o) => o.deliveryDate > today),
    past: live.filter((o) => o.deliveryDate < today && o.deliveryDate >= weekAgo && !pending.has(o.orderId)),
  };
  const shown = lists[range];

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">Deliveries</h1>
        <Muted>{outlet ? `${outlet.districtName} · ${outlet.outletId}` : "…"}</Muted>
      </div>
      <div role="tablist" aria-label="When" className="flex gap-1 rounded-full bg-white p-1 lg:w-fit">
        {(["today", "upcoming", "past"] as const).map((r) => (
          <button
            key={r}
            type="button"
            role="tab"
            aria-selected={r === range}
            onClick={() => setRange(r)}
            className={cx("min-h-12 flex-1 rounded-full px-2 text-[15px] font-medium lg:flex-none lg:px-5", r === range ? "bg-[#031a0c] text-white" : "text-black")}
          >
            {r === "today" ? "Today" : r === "upcoming" ? "Upcoming" : "Past 7 days"} <span className="opacity-60">{lists[r].length}</span>
          </button>
        ))}
      </div>

      {shown.length === 0 && <Muted>Nothing here.</Muted>}
      {shown.map((o) => {
        const s = ORDER_STATUS[o.status];
        const receivable = pending.has(o.orderId);
        return (
          <div key={o.orderId}>
          {/* Desktop: one row per delivery, as in "05a Deliveries". */}
          <article className={cx("hidden items-center gap-4 rounded-[20px] bg-white py-3 pr-4 pl-3 lg:flex", receivable && "outline-2 outline-[#0f766e]")}>
            <span className={cx("flex w-[84px] shrink-0 flex-col items-center rounded-[14px] py-2", receivable ? "bg-go-mint" : "bg-[#fbf1e1]")}>
              <span className="text-[11px] text-go-muted">{receivable ? "Arrived" : "Window"}</span>
              <span className="text-[18px] font-semibold text-black">{outlet ? hhmm(outlet.windowOpen) : "-"}</span>
            </span>
            <span className="flex min-w-0 flex-1 flex-col gap-0.5">
              <span className="flex items-center gap-2">
                <span className="text-[17px] font-medium text-black">{o.orderRef}</span>
                <Chip outline>{temperatureLabel(o.temperature)}</Chip>
              </span>
              <span className="text-[13px] text-go-muted">
                {dayLabel(o.deliveryDate)} · {cases(o.itemCount)}
              </span>
            </span>
            <Chip tone={s.tone}>{s.label}</Chip>
            {onTheWay(o.status) && (
              <span className="w-[112px] shrink-0">
                <Button tone="plain" onClick={onTrack}>
                  Track
                </Button>
              </span>
            )}
            <span className="w-[112px] shrink-0">
              <Button tone="plain" onClick={() => onOpen(o.orderId)}>
                Details
              </Button>
            </span>
            {receivable && (
              <span className="w-[112px] shrink-0">
                <Button onClick={() => onReceive(o.orderId)}>Receive</Button>
              </span>
            )}
          </article>
          <article className={cx("flex flex-col gap-3.5 rounded-[26px] bg-white p-5 lg:hidden", receivable && "outline-2 outline-[#0f766e]")}>
            {(onTheWay(o.status) || receivable) && (
              <div className={cx("flex flex-col items-center rounded-[20px] px-4 py-4", receivable ? "bg-go-mint" : "bg-[#fbf1e1]")}>
                <span className="text-[13px] text-go-muted">{receivable ? "Arrived" : "Your window"}</span>
                <span className="text-[36px] leading-tight font-semibold text-black">
                  {receivable ? "Count it now" : outlet ? `${hhmm(outlet.windowOpen)}-${hhmm(outlet.windowClose)}` : "-"}
                </span>
              </div>
            )}
            <div className="flex items-center gap-2">
              <h2 className="flex-1 text-[24px] font-medium text-black">{o.orderRef}</h2>
              <Chip outline>{temperatureLabel(o.temperature)}</Chip>
            </div>
            <p className="text-[15px] text-black">
              {dayLabel(o.deliveryDate)} · {cases(o.itemCount)}
            </p>
            <span className="self-start">
              <Chip tone={s.tone}>{s.label}</Chip>
            </span>
            {range !== "upcoming" && <Progress status={o.status} />}
            {onTheWay(o.status) && (
              <Button tone="plain" onClick={onTrack}>
                Track delivery
              </Button>
            )}
            <Button tone="plain" onClick={() => onOpen(o.orderId)}>
              Details
            </Button>
            {receivable && (
              <Button large onClick={() => onReceive(o.orderId)}>
                Receive
              </Button>
            )}
          </article>
          </div>
        );
      })}
    </div>
  );
}
