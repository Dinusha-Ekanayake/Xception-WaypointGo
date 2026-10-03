"use client";

import { useState } from "react";
import { useResource } from "@shared/api/useResource";
import type { DeliveryRecordView, IssueView, OrderView, OutletView, PendingReceiptView } from "@shared/domain/types";
import { Notice, cx } from "@shared/ui";
import type { StoreGateway } from "../../data/gateway.ts";
import { ORDER_STATUS, units, clock, dayLabel, depotToday, hhmm, temperatureLabel } from "../../data/format.ts";
import { isOpenIssue } from "../../data/issues.ts";
import { countStatus, issueBehind, pastWeek, runStatus, runsOf, upcomingDays, type Run, type Status } from "../../data/runs.ts";
import { Muted } from "../../ui.tsx";
import MakeUpDrawer from "./MakeUpDrawer.tsx";
import Row from "./Row.tsx";

// Figma "05a Deliveries" and "05b make-up delivery". A row is one vehicle's
// visit: the outlet's orders on one trip, with the time it is expected, its
// stop on the trip and what the loader kept back. Orders not yet on a vehicle
// show on their own, against the outlet's window. The past week is read only
// when its tab is opened.

type Range = "today" | "upcoming" | "past";

const orderCount = (n: number) => `${n} ${n === 1 ? "order" : "orders"}`;
const shortDay = (date: string) => dayLabel(date).split(" ").slice(0, 2).join(" ");

export default function Deliveries({
  gateway,
  orders,
  deliveries,
  issues,
  outlet,
  toReceive,
  onOpen,
  onOrders,
  onReceive,
  onTrack,
}: {
  gateway: StoreGateway;
  orders: OrderView[];
  /** Today's stops at this outlet. */
  deliveries: DeliveryRecordView[];
  issues: IssueView[];
  outlet: OutletView | null;
  toReceive: PendingReceiptView[];
  onOpen: (orderId: string) => void;
  onOrders: () => void;
  onReceive: (orderId: string) => void;
  onTrack: (vehicleId: string) => void;
}): React.JSX.Element {
  const [range, setRange] = useState<Range>("today");
  const [makeUp, setMakeUp] = useState<string | null>(null);
  const today = depotToday();
  const outletId = outlet?.outletId ?? "";
  const pending = new Set(toReceive.map((r) => r.orderId));
  const live = orders.filter((o) => o.status !== "CANCELLED");

  const runs = runsOf(deliveries, orders, issues);
  const onVehicle = new Set(deliveries.map((d) => d.orderId));
  // Not on a vehicle yet, or delivered on an earlier day and still waiting to be counted.
  const loose = live.filter((o) => (o.deliveryDate === today || pending.has(o.orderId)) && !onVehicle.has(o.orderId));
  const days = upcomingDays(live, today);
  // The key names the tab too: a resource reads again only when its key changes.
  const past = useResource(range === "past" && outletId ? (s) => pastWeek(gateway, outletId, today, s) : null, range === "past" ? `${outletId}|${today}` : "");
  const pastRuns = past.data ? runsOf(past.data.records, orders, issues).sort((a, b) => b.serviceDate.localeCompare(a.serviceDate) || b.eta.getTime() - a.eta.getTime()) : [];
  const pastOpen = issues.filter((i) => isOpenIssue(i) && i.subjects.some((s) => pastRuns.some((r) => r.records.some((d) => d.orderId === s.id)))).length;

  const counts: Record<Range, number | null> = {
    today: runs.length + loose.length,
    upcoming: days.reduce((s, d) => s + d.orders.length, 0),
    past: past.data ? pastRuns.length : null,
  };
  const windowOpen = outlet ? hhmm(outlet.windowOpen) : "-";

  const runRow = (run: Run) => {
    const receivable = run.records.find((r) => pending.has(r.orderId));
    const moving = !run.arrivedAt && !run.completedAt;
    const stop = run.stop.of ? `Stop ${run.stop.sequence} of ${run.stop.of}` : `Stop ${run.stop.sequence}`;
    const firstMakeUp = run.orders.find((o) => o.redeliveryOf !== null);
    return (
      <Row
        key={`${run.serviceDate}|${run.tripId}`}
        time={run.arrivedAt ? { label: "Arrived", value: clock(run.arrivedAt), tone: "mint" } : { label: "Expected", value: clock(run.eta), tone: "warm" }}
        title={run.vehicleId}
        tag={run.refrigerated ? "Refrigerated vehicle" : undefined}
        line={`${run.makeUp ? "Make-up delivery" : "Regular delivery"} · ${orderCount(run.records.length)} · ${units(run.units)}`}
        sub={run.short > 0 ? `${stop} · ${units(run.short)} short at loading` : stop}
        status={runStatus(run, pending, outlet?.windowClose ?? null)}
        highlight={receivable !== undefined}
        actions={[
          ...(moving ? [{ label: "Track", onClick: () => onTrack(run.vehicleId) }] : []),
          receivable
            ? { label: "Receive", tone: "ink" as const, onClick: () => onReceive(receivable.orderId) }
            : firstMakeUp
              ? { label: "Details", onClick: () => setMakeUp(firstMakeUp.orderId) }
              : { label: "Details", onClick: () => onOpen(run.records[0]!.orderId) },
        ]}
      />
    );
  };

  const looseRow = (o: OrderView) => {
    const waiting = pending.has(o.orderId);
    const status: Status = waiting
      ? { label: "Delivered · to receive", tone: "ink" }
      : o.status === "ALLOCATED"
        ? { label: "Scheduled", tone: "muted" }
        : ORDER_STATUS[o.status];
    return (
      <Row
        key={o.orderId}
        time={{ label: o.deliveryDate === today ? "Window" : shortDay(o.deliveryDate), value: windowOpen, tone: waiting ? "mint" : "plain" }}
        title={o.orderRef}
        tag={temperatureLabel(o.temperature)}
        line={`${o.redeliveryOf ? "Make-up delivery" : "Regular order"} · ${units(o.itemCount)}`}
        sub={waiting ? "Delivered, waiting for your count" : o.status === "ALLOCATED" ? "Planned · the vehicle shows once it leaves the depot" : undefined}
        status={status}
        highlight={waiting}
        actions={[
          waiting
            ? { label: "Receive", tone: "ink", onClick: () => onReceive(o.orderId) }
            : o.redeliveryOf
              ? { label: "Details", onClick: () => setMakeUp(o.orderId) }
              : { label: "Details", onClick: () => onOpen(o.orderId) },
        ]}
      />
    );
  };

  const pastRow = (run: Run) => {
    const count = countStatus(run.records.map((r) => past.data?.receipts.get(r.orderId) ?? null));
    const done = run.completedAt ? ` · ${run.signed ? "signed" : "handed over"} ${clock(run.completedAt)}` : "";
    return (
      <Row
        key={`${run.serviceDate}|${run.tripId}`}
        time={{ label: shortDay(run.serviceDate), value: clock(run.arrivedAt ?? run.completedAt ?? run.eta) }}
        title={run.vehicleId}
        tag={run.refrigerated ? "Refrigerated vehicle" : undefined}
        line={`${run.records.some((r) => r.outcome === "FAILED") ? "Not delivered" : "Delivered"} · ${orderCount(run.records.length)} · ${units(run.units)}${done}`}
        sub={!count.known ? "The count could not be read" : count.short > 0 ? `${units(count.short)} short on your count` : "All received in full"}
        status={count}
        actions={[{ label: "View", onClick: () => onOpen(run.records[0]!.orderId) }]}
      />
    );
  };

  const heading = (title: string, note: string) => (
    <div className="flex items-baseline gap-3">
      <h2 className="text-[20px] font-medium text-black">{title}</h2>
      <span className="text-[13px] text-go-muted">{note}</span>
    </div>
  );

  const open = makeUp ? (orders.find((o) => o.orderId === makeUp) ?? null) : null;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">Deliveries</h1>
        <Muted>{outlet ? `${outlet.districtName} · ${outlet.outletId}` : "…"}</Muted>
      </div>
      <div role="tablist" aria-label="When" className="flex gap-1 rounded-full bg-white p-1">
        {(["today", "upcoming", "past"] as const).map((r) => (
          <button
            key={r}
            type="button"
            role="tab"
            aria-selected={r === range}
            onClick={() => setRange(r)}
            className={cx("min-h-12 flex-1 rounded-full px-2 text-[15px] font-medium lg:flex-none lg:px-5", r === range ? "bg-[#031a0c] text-white" : "text-black")}
          >
            {r === "today" ? "Today" : r === "upcoming" ? "Upcoming" : "Past 7 days"}
            {counts[r] !== null && <span className="ml-1.5 opacity-60">{counts[r]}</span>}
          </button>
        ))}
      </div>

      {range === "today" && (
        <section aria-label="Today" className="flex flex-col gap-3">
          {heading(`Today · ${dayLabel(today)}`, `${counts.today} ${counts.today === 1 ? "delivery" : "deliveries"}`)}
          {runs.map(runRow)}
          {loose.map(looseRow)}
          {counts.today === 0 && <Muted>Nothing is coming today.</Muted>}
        </section>
      )}

      {range === "upcoming" && (
        <section aria-label="Upcoming" className="flex flex-col gap-3">
          {heading("Upcoming", "planned after 16:00")}
          {days.map((d) => (
            <Row
              key={d.date}
              time={{ label: "Window", value: windowOpen }}
              title={dayLabel(d.date)}
              badge={orderCount(d.orders.length)}
              line={d.orders.map((o) => `${o.orderRef} ${temperatureLabel(o.temperature)}${o.redeliveryOf ? " (make-up)" : ""}`).join(" · ")}
              status={d.status}
              actions={[d.orders.length === 1 ? { label: "View order", onClick: () => onOpen(d.orders[0]!.orderId) } : { label: "View orders", onClick: onOrders }]}
            />
          ))}
          {days.length === 0 && <Muted>No orders after today yet.</Muted>}
        </section>
      )}

      {range === "past" && (
        <section aria-label="Past 7 days" className="flex flex-col gap-3">
          {heading("Past 7 days", past.data ? `${pastRuns.length} ${pastRuns.length === 1 ? "delivery" : "deliveries"} · ${pastOpen} open ${pastOpen === 1 ? "issue" : "issues"}` : "")}
          {past.error && <Notice tone="danger" title="Could not load the past week">{past.error.message}</Notice>}
          {pastRuns.map(pastRow)}
          {past.loading && !past.data && <Muted>Loading the past week…</Muted>}
          {past.data && pastRuns.length === 0 && <Muted>Nothing was delivered in the past week.</Muted>}
        </section>
      )}

      {open && (
        <MakeUpDrawer
          gateway={gateway}
          order={open}
          issue={issueBehind(open, issues)}
          record={deliveries.find((d) => d.orderId === open.orderId) ?? null}
          outlet={outlet}
          onClose={() => setMakeUp(null)}
        />
      )}
    </div>
  );
}
