"use client";

import { useMemo, useState } from "react";
import { ORDER_CUTOFF, OrderCommandKind, type IssueView, type OrderStatus, type OrderView, type RunSheetStopView } from "@shared/domain/types";
import { Icon, Menu, Notice, Segmented, cx, useToast } from "@shared/ui";
import { addDays, clock, dayLabel } from "@shared/wording";
import PageHeader from "../PageHeader.tsx";
import { flow } from "../data/orders.ts";
import { depotToday } from "../data/scope.ts";
import { useCommand } from "../data/useCommand.ts";
import { useHistory, useIssues, useLive, useOrders, usePlans, type HistoryDay } from "../data/useDay.ts";
import type { ViewId } from "../navigation.ts";
import OrderDrawer from "./OrderDrawer.tsx";
import OrdersTable, { type LastColumn, type OrderLine } from "./OrdersTable.tsx";
import Refusal, { refusalText } from "./Refusal.tsx";

// Figma "03 Orders" (current, upcoming, past, order details): what became of
// every order, from the order to the store's confirmation. Current is today,
// read live; Upcoming is the next two days, where a day's orders are closed so
// the plan can be made; Past is the last seven days. A row opens the order.

type Tab = "current" | "upcoming" | "past";
type CloseResult = { alreadyClosed: boolean };

const UPCOMING_DAYS = 2;
const PAST_DAYS = 7;

const STATUS_GROUPS: Array<{ id: string; label: string; statuses: OrderStatus[] | null }> = [
  { id: "all", label: "All statuses", statuses: null },
  { id: "to-plan", label: "To plan", statuses: ["CONFIRMED", "DEFERRED", "STOCK_UNKNOWN", "PARTIALLY_RESERVED"] },
  { id: "planned", label: "Planned", statuses: ["ALLOCATED", "LOADING"] },
  { id: "road", label: "On the road", statuses: ["IN_TRANSIT"] },
  { id: "delivered", label: "Delivered", statuses: ["DELIVERED", "PARTIALLY_DELIVERED", "RECEIVED", "UNCONFIRMED"] },
  { id: "problem", label: "Need attention", statuses: ["STOCK_UNKNOWN", "PARTIALLY_RESERVED", "DEFERRED", "UNSERVABLE", "PARTIALLY_DELIVERED", "FAILED", "UNCONFIRMED"] },
];

export default function Orders({
  depots,
  scopeLabel,
  online,
  onNavigate,
}: {
  depots: string[];
  scopeLabel: string;
  online: boolean;
  onNavigate: (view: ViewId) => void;
}): React.JSX.Element {
  const today = depotToday();
  const [tab, setTab] = useState<Tab>("current");
  const [brand, setBrand] = useState("all");
  const [statusId, setStatusId] = useState("all");
  const [text, setText] = useState("");
  const [selected, setSelected] = useState<string | null>(null);

  const current = useOrders(depots, today);
  const live = useLive(depots, today);
  const issues = useIssues(depots);
  const upcomingDates = useMemo(() => Array.from({ length: UPCOMING_DAYS }, (_, i) => addDays(today, i + 1)), [today]);
  const pastDates = useMemo(() => Array.from({ length: PAST_DAYS }, (_, i) => addDays(today, -i - 1)), [today]);
  const upcoming = useHistory(depots, tab === "upcoming" ? upcomingDates : []);
  const past = useHistory(depots, tab === "past" ? pastDates : []);

  const all = current.data ?? [];
  const f = flow(all);
  const stops = useMemo(() => {
    const byOrder = new Map<string, RunSheetStopView>();
    const days: HistoryDay[] = [...(past.data ?? []), { date: today, orders: [], sheets: live.data?.sheets ?? [] }];
    for (const day of days) for (const sheet of day.sheets) for (const stop of sheet.stops) byOrder.set(stop.orderId, stop);
    return byOrder;
  }, [live.data, past.data, today]);
  const issuesByOrder = useMemo(() => {
    const byOrder = new Map<string, IssueView[]>();
    for (const issue of issues.data ?? []) {
      for (const subject of issue.subjects) if (subject.type === "order") byOrder.set(subject.id, [...(byOrder.get(subject.id) ?? []), issue]);
    }
    return byOrder;
  }, [issues.data]);
  const atRisk = (live.data?.sheets ?? []).flatMap((s) => s.stops).filter((s) => s.outcome === "PENDING" && (s.lateMinutes ?? 0) > 0).length;

  const days: HistoryDay[] =
    tab === "current" ? [{ date: today, orders: all, sheets: [] }] : tab === "upcoming" ? (upcoming.data ?? []) : [...(past.data ?? [])];
  const brands = useMemo(() => [...new Set(days.flatMap((d) => d.orders.map((o) => o.brandCode)))].sort(), [days]);
  const group = STATUS_GROUPS.find((g) => g.id === statusId)!;
  const keep = (order: OrderView) =>
    (brand === "all" || order.brandCode === brand) &&
    (group.statuses === null || group.statuses.includes(order.status)) &&
    (!text.trim() || [order.orderRef, order.outletId, order.districtName].some((v) => v.toLowerCase().includes(text.trim().toLowerCase())));

  const plans = usePlans(depots, tab === "current" ? today : (upcomingDates[0] ?? today));
  const rides = useMemo(() => {
    const byOrder = new Map<string, string>();
    for (const depot of plans.data ?? []) {
      for (const plan of [depot.draft, depot.published]) {
        for (const trip of plan?.trips ?? []) for (const stop of trip.stops) byOrder.set(stop.orderId, `${trip.vehicleId} · T${trip.tripNumber}`);
      }
    }
    return byOrder;
  }, [plans.data]);

  const line = (order: OrderView): OrderLine => ({ order, ride: rides.get(order.orderId), stop: stops.get(order.orderId), issues: issuesByOrder.get(order.orderId)?.length ?? 0 });
  const groups = days
    .map((day) => ({ title: tab === "current" ? null : dayLabel(day.date), lines: day.orders.filter(keep).map(line) }))
    .filter((g) => tab === "current" || g.lines.length > 0);
  const shown = groups.reduce((n, g) => n + g.lines.length, 0);
  const chosen = selected ? days.flatMap((d) => d.orders).find((o) => o.orderId === selected) : undefined;
  const last: LastColumn = tab === "current" ? "eta" : tab === "upcoming" ? "placed" : "done";
  const loading = tab === "current" ? !current.data && !current.error : tab === "upcoming" ? upcoming.loading && !upcoming.data : past.loading && !past.data;
  const error = tab === "current" ? current.error : tab === "upcoming" ? upcoming.error : past.error;
  const field = "flex items-center gap-1.5 rounded-full bg-go-card px-4 py-2.5 text-[14px] font-medium text-go-ink";

  return (
    <>
      <PageHeader
        title="Orders"
        subtitle={`${scopeLabel} · cutoff ${ORDER_CUTOFF}`}
        online={online}
        lastSyncedAt={current.loadedAt}
        onSync={current.refresh}
        syncing={current.loading}
      />

      <div className="flex w-full flex-wrap items-center gap-2.5">
        <Segmented
          size="md"
          label="Which orders"
          value={tab}
          onChange={(next) => (setTab(next), setSelected(null))}
          options={[
            { value: "current", label: "Current", hint: `Today · ${current.data ? f.due : "…"}` },
            { value: "upcoming", label: "Upcoming", hint: upcomingDates.map((d) => dayLabel(d).split(" ").slice(0, 2).join(" ")).join(" · ") },
            { value: "past", label: "Past", hint: `Last ${PAST_DAYS} days` },
          ]}
        />
        <span className="flex-1" />
        <label className="flex items-center gap-2 rounded-full bg-go-card px-4 py-2.5">
          <Icon name="search" />
          <input
            type="search"
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder="Search order, outlet or district"
            aria-label="Search orders"
            className="w-48 bg-transparent text-[14px] text-go-ink outline-none placeholder:text-go-placeholder"
          />
        </label>
        <Menu label="Brand" align="right" items={[{ id: "all", label: "All brands", selected: brand === "all" }, ...brands.map((b) => ({ id: b, label: b, selected: b === brand }))]} onSelect={setBrand} className={field} chevron>
          {brand === "all" ? "All brands" : brand}
        </Menu>
        <Menu label="Status" align="right" items={STATUS_GROUPS.map((g) => ({ id: g.id, label: g.label, selected: g.id === statusId }))} onSelect={setStatusId} className={field} chevron>
          {group.label}
        </Menu>
      </div>

      {error && <Refusal error={error} what="the orders" action={<Retry onClick={tab === "current" ? current.refresh : tab === "upcoming" ? upcoming.refresh : past.refresh} />} />}
      {tab === "current" && f.stockUnknown > 0 && (
        <Notice tone="warning" title={`${f.stockUnknown} ${f.stockUnknown === 1 ? "order has" : "orders have"} no stock answer from the warehouse`}>
          They are not planned until the warehouse confirms the stock. Stock is never assumed.
        </Notice>
      )}

      {tab === "current" && (
        <section aria-label="Order flow" className="flex w-full flex-wrap items-center gap-x-6 gap-y-3 rounded-go-panel bg-go-card px-5 py-3.5">
          <ol className="flex flex-wrap items-center gap-x-5 gap-y-2">
            {(
              [
                ["Planned", f.planned],
                ["Loaded", f.leftDock],
                ["Delivered", f.delivered],
                ["Confirmed by store", f.confirmedByStore],
              ] as Array<[string, number]>
            ).map(([label, value], index) => (
              <li key={label} className="flex items-center gap-5">
                {index > 0 && <Icon name="chevron-right-muted" />}
                <span className="flex flex-col">
                  <span className="text-[13px] text-go-secondary">{label}</span>
                  <span className="text-[24px] leading-tight font-medium text-go-ink tabular-nums">{current.data ? value : "…"}</span>
                </span>
              </li>
            ))}
          </ol>
          <span className="flex-1" />
          <div className="flex flex-wrap gap-2.5">
            <Tile tone="bg-go-success-tint" value={f.onTheRoad} label="On the road" onClick={() => setStatusId("road")} />
            <Tile tone="bg-go-warning-tint" value={live.data ? atRisk : null} label="At risk" onClick={() => onNavigate("live")} />
            <Tile tone="bg-go-danger-tint" value={issues.data ? issuesByOrder.size : null} label="Issues reported" onClick={() => onNavigate("issues")} />
            <Tile tone="bg-go-surface" value={f.awaitingStore} label="Awaiting store" onClick={() => setStatusId("delivered")} />
          </div>
        </section>
      )}

      {tab === "upcoming" && <UpcomingDays depots={depots} days={upcoming.data ?? []} online={online} onNavigate={onNavigate} onClosed={upcoming.refresh} />}

      <section aria-label="Orders" className="flex min-w-0 flex-1 flex-col rounded-go-panel bg-go-card px-5 pt-4 pb-3">
        <OrdersTable groups={groups} last={last} selected={selected} onSelect={setSelected} />
        {!loading && shown === 0 && (
          <div className="flex flex-col items-center gap-2 py-8 text-center text-[13px] text-go-secondary">
            {days.every((d) => d.orders.length === 0) ? "No orders in these days." : "No orders match these filters."}
            {text && (
              <button type="button" onClick={() => setText("")} className="font-medium text-go-teal">
                Clear search
              </button>
            )}
          </div>
        )}
        {loading && <p className="py-8 text-center text-[13px] text-go-secondary">Loading the orders…</p>}
      </section>

      {chosen && <OrderDrawer line={line(chosen)} issues={issuesByOrder.get(chosen.orderId) ?? []} onClose={() => setSelected(null)} />}
    </>
  );
}

/** Figma "03b Orders: upcoming": one card per day with where its orders stand, and Close orders once the cutoff has passed. */
function UpcomingDays({
  depots,
  days,
  online,
  onNavigate,
  onClosed,
}: {
  depots: string[];
  days: HistoryDay[];
  online: boolean;
  onNavigate: (view: ViewId) => void;
  onClosed: () => void;
}): React.JSX.Element {
  const toast = useToast();
  const { busy, run } = useCommand();

  const close = async (depot: string, date: string) => {
    const sent = await run<CloseResult>(OrderCommandKind.closeForDay, { depotCode: depot, serviceDate: date }, null);
    if (!sent.ok) return toast({ tone: "error", ...refusalText(sent.error, "closing orders") });
    toast(
      sent.result.alreadyClosed
        ? { title: "Already closed", detail: `Orders for ${depot} on ${dayLabel(date)} were closed before.` }
        : { title: "Orders closed", detail: `${depot} · ${dayLabel(date)}. New orders for that day roll to the next run.` },
    );
    onClosed();
  };

  return (
    <section aria-label="Upcoming days" className="flex w-full flex-col gap-2 rounded-go-panel bg-go-card p-3">
      <div className="flex gap-2.5 max-md:flex-col">
        {days.map((day) => {
          const inPlan = day.orders.filter((o) => o.status === "ALLOCATED").length;
          const deferred = day.orders.filter((o) => o.status === "DEFERRED").length;
          const closesOn = addDays(day.date, -1);
          const closed = closesOn < depotToday() || (closesOn === depotToday() && clock(new Date()) >= ORDER_CUTOFF);
          const count = `${day.orders.length} ${day.orders.length === 1 ? "order" : "orders"}`;
          return (
            <div key={day.date} className="flex min-w-0 flex-1 flex-wrap items-center gap-2 rounded-go-card bg-go-surface px-4 py-3">
              <div className="min-w-[140px] flex-1">
                <p className="text-[15px] font-medium text-go-ink">{`${dayLabel(day.date)}${closed ? "" : " · open"}`}</p>
                <p className="text-xs text-go-secondary">
                  {closed ? `Closed ${ORDER_CUTOFF} · ${count}` : `Closes ${dayLabel(closesOn).split(" ")[0]} ${ORDER_CUTOFF} · ${count} so far`}
                </p>
              </div>
              {inPlan > 0 && <span className="rounded-full bg-go-success-tint px-2.5 py-1 text-xs text-go-teal">{`${inPlan} in plan drafts`}</span>}
              {deferred > 0 && <span className="rounded-full bg-go-warning-tint px-2.5 py-1 text-xs text-go-warning-text">{`${deferred} deferred`}</span>}
              <Menu
                label={`Close orders for ${dayLabel(day.date)}`}
                align="right"
                disabled={!online || busy}
                items={depots.map((depot) => ({ id: depot, label: `Close ${depot}`, hint: "Refused before the cutoff" }))}
                onSelect={(depot) => void close(depot, day.date)}
                className="rounded-full bg-go-card px-3 py-1.5 text-[13px] font-medium text-go-ink"
                chevron
              >
                Close orders
              </Menu>
              <button type="button" onClick={() => onNavigate("plan")} className="flex items-center gap-0.5 text-[13px] font-medium text-go-teal">
                Open plan <Icon name="chevron-right" />
              </button>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function Tile({ tone, value, label, onClick }: { tone: string; value: number | null; label: string; onClick: () => void }): React.JSX.Element {
  return (
    <button type="button" onClick={onClick} className={cx("flex min-w-[104px] items-center gap-3 rounded-go-card px-3.5 py-2 text-left", tone)}>
      <span className="flex flex-col">
        <span className="text-xl font-medium text-go-ink tabular-nums">{value ?? "…"}</span>
        <span className="text-xs text-go-secondary">{label}</span>
      </span>
      <Icon name="chevron-right-muted" />
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
