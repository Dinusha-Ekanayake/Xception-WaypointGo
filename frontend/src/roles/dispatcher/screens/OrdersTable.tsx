"use client";

import type { OrderView, RunSheetStopView } from "@shared/domain/types";
import { Pill, cx } from "@shared/ui";
import { clock, dayLabel, hhmm } from "@shared/wording";
import { STATUS, size } from "../data/orders.ts";
import { seriesColour } from "./ForecastChart.tsx";

// Figma "03 Orders" table: order, outlet with its brand's colour, type, size,
// vehicle and trip, status, and a last column that depends on the tab: the
// expected arrival or the time it was done (Current), the time it was placed (Upcoming), or
// the day it was delivered (Past). Upcoming and Past group the rows by day.

export type LastColumn = "eta" | "placed" | "done";

export type OrderLine = {
  order: OrderView;
  ride: string | undefined;
  stop: RunSheetStopView | undefined;
  issues: number;
};

const COLUMNS = "grid grid-cols-[120px_minmax(170px,1.4fr)_84px_140px_110px_minmax(170px,1fr)_112px] items-center gap-3 px-1";

export default function OrdersTable({
  groups,
  last,
  selected,
  onSelect,
}: {
  /** One group per day; a single unnamed group for Current. */
  groups: Array<{ title: string | null; lines: OrderLine[] }>;
  last: LastColumn;
  selected: string | null;
  onSelect: (orderId: string) => void;
}): React.JSX.Element {
  const heading = last === "eta" ? "Expected / done" : last === "placed" ? "Placed" : "Delivered";
  return (
    <div className="overflow-x-auto">
      <div role="table" aria-label="Orders due" className="min-w-[900px]">
        <div role="row" className={`${COLUMNS} border-b border-go-rule pb-2 text-[13px] text-go-secondary`}>
          {["Order", "Outlet", "Type", "Size", "Vehicle · trip", "Status", heading].map((label) => (
            <span key={label} role="columnheader" className="whitespace-nowrap">
              {label}
            </span>
          ))}
        </div>
        {groups.map((group) => (
          <div key={group.title ?? "all"} role="rowgroup">
            {group.title && (
              <div role="row" className="px-1 pt-3 pb-1">
                <span role="rowheader" className="text-xs font-medium text-go-teal">
                  {group.title}
                </span>
              </div>
            )}
            {group.lines.map((line) => (
              <Row key={line.order.orderId} line={line} last={last} active={selected === line.order.orderId} onSelect={() => onSelect(line.order.orderId)} />
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}

function Row({ line, last, active, onSelect }: { line: OrderLine; last: LastColumn; active: boolean; onSelect: () => void }): React.JSX.Element {
  const { order, ride, stop, issues } = line;
  const state = STATUS[order.status];
  const late = stop && order.status === "IN_TRANSIT" && (stop.lateMinutes ?? 0) > 0;
  const detail = [late ? "at risk" : null, issues > 0 ? `${issues} ${issues === 1 ? "issue" : "issues"}` : null].filter(Boolean).join(" · ");
  const tone = issues > 0 ? "danger" : late ? "warning" : state.tone;
  const lastText =
    last === "placed"
      ? clock(order.placedAt)
      : last === "done"
        ? stop?.completedAt
          ? `${dayLabel(order.deliveryDate).split(" ").slice(0, 2).join(" ")} ${clock(stop.completedAt)}`
          : "-"
        : stop?.completedAt
          ? clock(stop.completedAt)
          : stop?.expectedArrival
            ? `Expected ${clock(stop.expectedArrival)}`
            : stop
              ? `Planned ${hhmm(stop.plannedArrival)}`
              : "-";
  return (
    <button
      type="button"
      role="row"
      aria-pressed={active}
      onClick={onSelect}
      className={cx(`${COLUMNS} min-h-[43px] w-full border-b border-go-rule py-2 text-left text-[13px] text-go-ink`, active ? "bg-go-success-tint" : "hover:bg-go-subtle")}
    >
      <span role="cell" className="font-medium">
        {order.orderRef}
      </span>
      <span role="cell" className="flex min-w-0 items-center gap-2">
        <span aria-hidden className="size-2 shrink-0 rounded-full" style={{ background: seriesColour(`${order.brandCode}-ambient`) }} />
        <span className="truncate">{`${order.outletId} · ${order.districtName}`}</span>
        <span className="sr-only">{order.brandCode}</span>
      </span>
      <span role="cell">
        <Pill tone={order.temperature === "chilled" ? "info" : "muted"}>{order.temperature === "chilled" ? "Chilled" : "Ambient"}</Pill>
      </span>
      <span role="cell" className="tabular-nums">
        {size(order)}
      </span>
      <span role="cell" className={ride ? "" : "text-go-secondary"}>
        {ride ?? "-"}
      </span>
      <span role="cell" className="flex flex-wrap items-center gap-1.5">
        <Pill tone={tone}>{detail ? `${state.label} · ${detail}` : state.label}</Pill>
        {order.deferralCount > 0 && <span className="text-xs text-go-warning-text">{`deferred ${order.deferralCount}×`}</span>}
        {order.dateRolled && <span className="text-xs text-go-secondary">{`moved from ${dayLabel(order.requestedDate)}`}</span>}
      </span>
      <span role="cell" className="tabular-nums">
        {lastText}
      </span>
    </button>
  );
}
