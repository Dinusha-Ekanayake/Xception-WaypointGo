"use client";

import { useMemo, useState } from "react";
import { OrderCommandKind, type OrderView } from "@shared/domain/types";
import { FilterTabs, Icon, Notice, Pill, SecondaryButton } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import { STATUS, flow, matches, size, type StatusFilter } from "../data/orders.ts";
import { dayLabel } from "../data/scope.ts";
import { useCommand } from "../data/useCommand.ts";
import { useOrders, usePlans } from "../data/useDay.ts";
import DayPicker from "./DayTools.tsx";
import Refusal from "./Refusal.tsx";

// Figma "03 Orders: current": what became of every order due on a day, from the
// order to the store's confirmation. The design's Upcoming and Past tabs are
// the day picker; its ETA column waits on a live ETA per order.

const COLUMNS = "grid grid-cols-[120px_minmax(150px,1.3fr)_84px_150px_120px_minmax(170px,1fr)] items-center gap-3 px-1";

type CloseResult = { alreadyClosed: boolean };

export default function Orders({
  depots,
  scopeLabel,
  date,
  onDate,
  online,
}: {
  depots: string[];
  scopeLabel: string;
  date: string;
  onDate: (date: string) => void;
  online: boolean;
}): React.JSX.Element {
  const orders = useOrders(depots, date);
  const plans = usePlans(depots, date);
  const { busy, run } = useCommand();
  const [status, setStatus] = useState<StatusFilter>("all");
  const [brand, setBrand] = useState("all");
  const [text, setText] = useState("");
  const [closed, setClosed] = useState<string | null>(null);
  const [failure, setFailure] = useState<Error | null>(null);

  const all = orders.data ?? [];
  const f = flow(all);
  const brands = useMemo(() => [...new Set(all.map((order) => order.brandCode))].sort(), [all]);
  const rows = useMemo(() => all.filter((order) => matches(order, { status, brand, text })), [all, status, brand, text]);

  // Where each order rides, from the published plan; a draft is not a promise.
  const rides = useMemo(() => {
    const byOrder = new Map<string, string>();
    for (const depot of plans.data ?? []) {
      for (const trip of depot.published?.trips ?? []) {
        for (const stop of trip.stops) byOrder.set(stop.orderId, `${trip.vehicleId} · T${trip.tripNumber}`);
      }
    }
    return byOrder;
  }, [plans.data]);

  const close = async (depot: string) => {
    setClosed(null);
    setFailure(null);
    const sent = await run<CloseResult>(OrderCommandKind.closeForDay, { depotCode: depot, serviceDate: date }, null);
    if (!sent.ok) return setFailure(sent.error);
    setClosed(
      sent.result.alreadyClosed
        ? `Orders for ${depot} on ${dayLabel(date)} were already closed.`
        : `Orders for ${depot} on ${dayLabel(date)} are closed. New orders for that day roll to the next run.`,
    );
    orders.refresh();
  };

  const steps: Array<[string, number]> = [
    ["Due", f.due],
    ["Planned", f.planned],
    ["Left the dock", f.leftDock],
    ["Delivered", f.delivered],
    ["Confirmed by the store manager", f.confirmedByStore],
  ];

  return (
    <>
      <PageHeader
        title="Orders"
        subtitle={`${orders.data ? `${f.due} due` : "Loading"} · ${scopeLabel} · ${dayLabel(date)}`}
        online={online}
        lastSyncedAt={orders.loadedAt}
        onSync={orders.refresh}
        syncing={orders.loading}
        tools={<DayPicker date={date} onDate={onDate} />}
      />

      {orders.error && <Refusal error={orders.error} what="the orders" action={<Retry onClick={orders.refresh} />} />}
      {failure && <Refusal error={failure} what="closing orders" />}
      {closed && <Notice tone="info" title={closed} live />}
      {f.stockUnknown > 0 && (
        <Notice tone="warning" title={`${f.stockUnknown} ${f.stockUnknown === 1 ? "order has" : "orders have"} no stock answer from the warehouse`}>
          They are not planned until the warehouse confirms the stock. Stock is never assumed.
        </Notice>
      )}

      <section aria-label="Order flow" className="flex w-full flex-wrap items-center gap-x-6 gap-y-3 rounded-[24px] bg-white px-5 py-4 shadow-go-card">
        <ol className="flex flex-wrap items-center gap-x-4 gap-y-2">
          {steps.map(([label, value], index) => (
            <li key={label} className="flex items-center gap-4">
              {index > 0 && <Icon name="chevron-right" />}
              <span className="flex flex-col">
                <span className="text-xs text-go-secondary">{label}</span>
                <span className="text-2xl font-medium tabular-nums text-go-ink">{orders.data ? value : "…"}</span>
              </span>
            </li>
          ))}
        </ol>
        <div className="flex-1" />
        <div className="flex flex-wrap gap-2">
          <Tile tone="bg-go-success-tint" value={f.onTheRoad} label="On the road" onClick={() => setStatus("road")} />
          <Tile tone="bg-go-danger-tint" value={f.attention} label="Need attention" onClick={() => setStatus("attention")} />
          <Tile tone="bg-go-surface" value={f.awaitingStore} label="Awaiting the store manager" onClick={() => setStatus("done")} />
        </div>
      </section>

      <section aria-label="Orders" className="flex min-w-0 flex-1 flex-col rounded-[24px] bg-white px-5 pt-3.5 pb-3 shadow-go-card">
        <div className="flex flex-wrap items-center gap-2 pb-3">
          <FilterTabs
            label="Filter orders"
            value={status}
            onChange={setStatus}
            options={[
              { value: "all", label: `All (${all.length})` },
              { value: "attention", label: `Need attention (${f.attention})` },
              { value: "to-plan", label: "To plan" },
              { value: "planned", label: "Planned" },
              { value: "road", label: "On the road" },
              { value: "done", label: "Done" },
            ]}
          />
          <div className="flex-1" />
          <label className="flex items-center gap-1.5 rounded-go-card-s bg-go-surface px-3 py-[7px]">
            <Icon name="search" />
            <input
              type="search"
              value={text}
              onChange={(event) => setText(event.target.value)}
              placeholder="Order, outlet or district"
              aria-label="Search orders"
              className="w-44 bg-transparent text-[13px] text-go-ink outline-none placeholder:text-go-placeholder"
            />
          </label>
          <select value={brand} onChange={(event) => setBrand(event.target.value)} aria-label="Brand" className="rounded-go-card-s bg-go-surface px-3 py-2 text-[13px] text-go-ink">
            <option value="all">All brands</option>
            {brands.map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </div>

        <div className="overflow-x-auto">
          <div role="table" aria-label="Orders due" className="min-w-[860px]">
            <div role="row" className={`${COLUMNS} border-b border-go-rule pb-2 text-xs text-go-secondary`}>
              {["Order", "Outlet", "Type", "Size", "Vehicle · trip", "Status"].map((heading) => (
                <span key={heading} role="columnheader">
                  {heading}
                </span>
              ))}
            </div>
            {rows.map((order) => (
              <OrderRow key={order.orderId} order={order} ride={rides.get(order.orderId)} />
            ))}
          </div>
        </div>
        {orders.data && rows.length === 0 && (
          <p className="py-8 text-center text-[13px] text-go-secondary">
            {all.length === 0 ? `No orders are due on ${dayLabel(date)}.` : "No orders match these filters."}
          </p>
        )}
        {!orders.data && !orders.error && <p className="py-8 text-center text-[13px] text-go-secondary">Loading the orders…</p>}
      </section>

      <section aria-label="Close orders" className="flex w-full flex-wrap items-center gap-3 rounded-[24px] bg-white px-5 py-4 shadow-go-card">
        <div className="min-w-[240px] flex-1">
          <h2 className="text-[15px] font-medium text-go-ink">Close orders for {dayLabel(date)}</h2>
          <p className="text-xs text-go-secondary">After the cutoff, closing stops new orders for the day so the plan can be made. Before the cutoff it is refused.</p>
        </div>
        {depots.map((depot) => (
          <SecondaryButton key={depot} disabled={!online || busy} onClick={() => void close(depot)}>
            Close {depot}
          </SecondaryButton>
        ))}
      </section>
    </>
  );
}

function OrderRow({ order, ride }: { order: OrderView; ride: string | undefined }): React.JSX.Element {
  const state = STATUS[order.status];
  return (
    <div role="row" className={`${COLUMNS} min-h-[44px] border-b border-go-rule py-2 text-[13px] text-go-ink`}>
      <span role="cell" className="font-medium">
        {order.orderRef}
      </span>
      <span role="cell" className="truncate">
        {order.outletId} · {order.districtName}
        <span className="text-go-secondary"> · {order.brandCode}</span>
      </span>
      <span role="cell">
        <Pill tone={order.temperature === "chilled" ? "info" : "muted"}>{order.temperature === "chilled" ? "Chilled" : "Ambient"}</Pill>
      </span>
      <span role="cell" className="tabular-nums">
        {size(order)}
      </span>
      <span role="cell" className={ride ? "" : "text-go-secondary"}>
        {ride ?? "Not on a plan"}
      </span>
      <span role="cell" className="flex flex-wrap items-center gap-1.5">
        <Pill tone={state.tone}>{state.label}</Pill>
        {order.deferralCount > 0 && <span className="text-xs text-go-warning-text">deferred {order.deferralCount}×</span>}
        {order.dateRolled && <span className="text-xs text-go-secondary">moved from {dayLabel(order.requestedDate)}</span>}
      </span>
    </div>
  );
}

function Tile({ tone, value, label, onClick }: { tone: string; value: number; label: string; onClick: () => void }): React.JSX.Element {
  return (
    <button type="button" onClick={onClick} className={`flex min-w-[104px] flex-col rounded-go-card px-3.5 py-2 text-left ${tone}`}>
      <span className="text-xl font-medium tabular-nums text-go-ink">{value}</span>
      <span className="text-xs text-go-secondary">{label}</span>
    </button>
  );
}

export function Retry({ onClick }: { onClick: () => void }): React.JSX.Element {
  return (
    <button type="button" onClick={onClick} className="shrink-0 rounded-go-chip bg-white px-2.5 py-[5px] text-[11px] font-medium text-go-teal">
      Try again
    </button>
  );
}
