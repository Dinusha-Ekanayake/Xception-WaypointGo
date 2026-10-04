"use client";

import { useState } from "react";
import type { IssueView, OrderView } from "@shared/domain/types";
import { Card, CardHead, Icon, LinkAction, Menu, cx } from "@shared/ui";
import { ruleLabel } from "@shared/wording";
import { RANGES, rangeDates, reported, summarise, type Range } from "../data/history.ts";
import { depotToday } from "../data/scope.ts";
import { useDeferrals, useHistory } from "../data/useDay.ts";
import type { ViewId } from "../navigation.ts";
import { depotOptions } from "../Sidebar.tsx";
import Refusal from "./Refusal.tsx";
import TripsChart from "./TripsChart.tsx";

// Figma "02 Overview", the two left cards. Orders: what is confirmed for today,
// what was deferred and why, and the deferred outlets that were skipped before
// and must go first (R-PLN-20); each tile opens the screen that acts on it.
// Summary: a range of days from Ordering and Execution, and the trips per day.

const TONE = {
  success: "bg-go-success-tint",
  warning: "bg-go-warning-tint",
  danger: "bg-go-danger-tint",
} as const;

function TintTile({
  tone,
  label,
  value,
  note,
  onClick,
}: {
  tone: keyof typeof TONE;
  label: string;
  value: string;
  note: React.ReactNode;
  onClick: () => void;
}): React.JSX.Element {
  return (
    <button type="button" onClick={onClick} className={cx("flex min-w-0 flex-1 flex-col gap-1 rounded-go-card px-3.5 py-3 text-left", TONE[tone])}>
      <span className="flex items-center justify-between gap-2 text-[13px] text-go-secondary">
        {label}
        <Icon name="chevron-right-muted" />
      </span>
      <span className="text-2xl font-medium text-go-ink">{value}</span>
      <span className="text-[11px] text-go-secondary">{note}</span>
    </button>
  );
}

export function OrdersCard({
  depots,
  orders,
  error,
  scopeLabel,
  onNavigate,
}: {
  depots: string[];
  orders: OrderView[] | null;
  error: Error | null;
  scopeLabel: string;
  onNavigate: (view: ViewId) => void;
}): React.JSX.Element {
  const deferrals = useDeferrals(depots, depotToday());
  const confirmed = orders ? orders.filter((o) => !["STOCK_UNKNOWN", "PARTIALLY_RESERVED", "CANCELLED"].includes(o.status)).length : null;
  const rows = deferrals.data ?? [];
  const byRule = new Map<string, number>();
  for (const row of rows) byRule.set(row.ruleId, (byRule.get(row.ruleId) ?? 0) + 1);
  const mustGo = rows.filter((row) => row.skipCount >= 2);

  return (
    <Card label="Orders">
      <CardHead title="Orders" meta={`Today · ${scopeLabel}`} action={<LinkAction onClick={() => onNavigate("orders")}>Open orders</LinkAction>} />
      {error && confirmed === null && <Refusal error={error} what="today's orders" />}
      {deferrals.error && !deferrals.data && <Refusal error={deferrals.error} what="today's deferred orders" />}
      <div className="flex gap-2.5 max-sm:flex-col">
        <TintTile tone="success" label="Confirmed today" value={confirmed === null ? "…" : `${confirmed}`} note="Confirmed for delivery today" onClick={() => onNavigate("orders")} />
        <TintTile
          tone="warning"
          label="Deferred today"
          value={deferrals.data ? `${rows.length}` : "…"}
          note={
            rows.length === 0
              ? "None left out of a published plan"
              : [...byRule.entries()].map(([rule, n]) => (
                  <span key={rule} className="block">{`${ruleLabel(rule)} · ${n}`}</span>
                ))
          }
          onClick={() => onNavigate("plan")}
        />
        <TintTile
          tone="danger"
          label="Must-deliver deferred"
          value={deferrals.data ? `${mustGo.length}` : "…"}
          note={mustGo.length ? `Skipped before · ${mustGo.map((r) => r.outletId).slice(0, 3).join(", ")}` : "No outlet skipped twice"}
          onClick={() => onNavigate("plan")}
        />
      </div>
    </Card>
  );
}

export function SummaryCard({
  depots,
  scope,
  depotFilter,
  onDepotFilter,
  issues,
  onNavigate,
}: {
  depots: string[];
  scope: string[];
  depotFilter: string;
  onDepotFilter: (filter: string) => void;
  issues: IssueView[] | null;
  onNavigate: (view: ViewId) => void;
}): React.JSX.Element {
  const [range, setRange] = useState<Range>("7d");
  const dates = rangeDates(range, depotToday());
  const history = useHistory(depots, dates);
  const sum = history.data ? summarise(history.data) : null;
  const told = issues ? reported(issues, dates[0]!) : null;
  const options = depotOptions(scope);
  const depotText = options.find((o) => o.value === depotFilter)?.label ?? "All";
  const rangeText = RANGES.find((r) => r.id === range)!.label;
  const avgTrips = sum && sum.points.length ? Math.round(sum.points.reduce((t, p) => t + p.trips, 0) / sum.points.length) : null;
  const field = "flex items-center gap-1.5 rounded-full bg-go-surface px-3.5 py-2 text-[14px] font-medium text-go-ink";

  return (
    <Card label="Summary" className="flex-1">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="mr-auto text-[17px] font-medium text-go-ink">Summary</h2>
        <Menu label="Time range" align="right" items={RANGES.map((r) => ({ id: r.id, label: r.label, selected: r.id === range }))} onSelect={(id) => setRange(id as Range)} className={field} chevron>
          {rangeText}
        </Menu>
      </div>
      {history.error && !sum && <Refusal error={history.error} what="the days behind" />}
      <div className="flex gap-2.5 max-sm:flex-col">
        <Tile label="Orders" value={sum ? `${sum.orders}` : "…"} note={sum ? `${sum.delivered} delivered · ${sum.deferred} deferred` : ""} />
        <Tile label="On time" value={!sum ? "…" : sum.onTime === null ? "-" : `${sum.onTime}%`} note={sum && sum.onTime === null ? "No delivered stop yet" : "of delivered stops"} />
        <Tile
          label="Issues reported"
          value={told ? `${told.total}` : "…"}
          note={told ? <button type="button" onClick={() => onNavigate("issues")} className="text-left">{`${told.missing} missing · ${told.damaged} damaged`}</button> : ""}
        />
      </div>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 pt-2 text-xs">
        <h3 className="mr-auto text-[14px] font-medium text-go-ink">{`Trips per day · ${rangeText.toLowerCase()}`}</h3>
        <span className="flex items-center gap-1.5 text-go-secondary">
          <span aria-hidden className="size-2.5 rounded-[2px] bg-go-mint" /> Trips
        </span>
        <span className="flex items-center gap-1.5 text-go-secondary">
          <span aria-hidden className="h-0.5 w-3 bg-go-teal" /> On time %
        </span>
        {sum && avgTrips !== null && avgTrips > 0 && <span className="font-medium text-go-ink">{`avg ${avgTrips} trips${sum.onTime === null ? "" : ` · ${sum.onTime}% on time`}`}</span>}
      </div>
      {sum && sum.points.some((p) => p.trips > 0) ? (
        <TripsChart points={sum.points} />
      ) : (
        <p className="py-10 text-center text-[13px] text-go-secondary">
          {!sum ? (history.loading ? "Reading the days…" : "") : `No trips in ${rangeText === "Today" ? "today's run" : `the ${rangeText.toLowerCase()}`}.`}
        </p>
      )}
    </Card>
  );
}

function Tile({ label, value, note }: { label: string; value: string; note: React.ReactNode }): React.JSX.Element {
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1 rounded-go-card bg-go-surface px-4 py-3.5">
      <p className="text-[13px] text-go-secondary">{label}</p>
      <p className="text-[30px] leading-tight font-medium text-go-ink">{value}</p>
      <div className="text-xs text-go-secondary">{note}</div>
    </div>
  );
}
