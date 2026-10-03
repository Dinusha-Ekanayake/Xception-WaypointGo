"use client";

import type { Resource } from "@shared/api/useResource";
import type { VehicleView } from "@shared/domain/types";
import { Card, CardHead, LinkAction, Notice, StatTile, cx } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import FleetError from "./FleetError.tsx";
import { NotificationRows } from "../NotificationsPanel.tsx";
import { useDispatcherInbox } from "../inbox.tsx";
import Refusal from "./Refusal.tsx";
import SkippedOutlets from "./SkippedOutlets.tsx";
import { summarise } from "../data/fleet.ts";
import { issueCounts } from "../data/issues.ts";
import { punctuality, totals, vehicleDay } from "../data/live.ts";
import { flow } from "../data/orders.ts";
import { depotStamp, depotToday, greeting } from "../data/scope.ts";
import { useIssues, useLive, useOrders } from "../data/useDay.ts";
import type { ViewId } from "../navigation.ts";

// Figma "02 Overview": today's depot at a glance, every tile from the module
// that owns the fact: orders from Ordering, the road from Execution and
// Loading, issues from Issues, vehicles from reference data. Notifications
// name the module they wait on (decision D-D: no mock data).

export default function Overview({
  displayName,
  userId,
  depots,
  scopeLabel,
  fleet,
  online,
  onNavigate,
}: {
  displayName: string;
  userId: string;
  depots: string[];
  scopeLabel: string;
  fleet: Resource<VehicleView[]>;
  online: boolean;
  onNavigate: (view: ViewId) => void;
}): React.JSX.Element {
  const summary = fleet.data ? summarise(fleet.data) : null;
  const today = depotToday();
  const orders = useOrders(depots, today);
  const live = useLive(depots, today);
  const issues = useIssues(depots);
  const day = orders.data ? flow(orders.data) : null;
  const road = live.data ? totals(live.data.sheets.map((sheet) => vehicleDay(sheet, new Date())), live.data.dock) : null;
  const served = live.data ? punctuality(live.data.sheets) : null;
  const open = issues.data ? issueCounts(issues.data, userId) : null;
  const show = (value: number | string | undefined) => (value === undefined ? "…" : value);

  return (
    <>
      <PageHeader
        title={`${greeting()}, ${displayName}`}
        subtitle={`${depotStamp()} · ${scopeLabel}`}
        online={online}
        lastSyncedAt={fleet.loadedAt}
        onSync={fleet.refresh}
        syncing={fleet.loading}
      />

      <div className="flex min-h-0 w-full flex-1 gap-5 max-lg:flex-col">
        <div className="flex min-w-0 flex-1 flex-col gap-5">
          <Card label="Orders">
            <CardHead
              title="Orders"
              meta={`Today · ${scopeLabel}`}
              action={<LinkAction onClick={() => onNavigate("orders")}>Open orders</LinkAction>}
            />
            {orders.error && !day ? (
              <Refusal error={orders.error} what="today's orders" />
            ) : (
              <div className="flex gap-2 max-sm:flex-wrap">
                <StatTile label="Due today" value={show(day?.due)} />
                <StatTile label="Planned" value={show(day?.planned)} />
                <StatTile label="Delivered" value={show(day?.delivered)} note={day ? `${day.confirmedByStore} confirmed by store` : undefined} />
                <StatTile label="Need attention" value={show(day?.attention)} valueClassName={day?.attention ? "text-go-warning-text" : "text-go-ink"} />
              </div>
            )}
          </Card>

          <Card label="Summary">
            <CardHead title="Summary" meta={`Today · ${scopeLabel}`} action={<LinkAction onClick={() => onNavigate("live")}>Open live</LinkAction>} />
            {live.error && !road && <Refusal error={live.error} what="the road today" />}
            {issues.error && !open && <Refusal error={issues.error} what="the issues" />}
            <div className="flex gap-2 max-sm:flex-wrap">
              <StatTile label="On the road" value={show(road?.onTheRoad)} note={road ? `${road.atDock} still at the dock` : undefined} />
              <StatTile label="Stops done" value={road ? `${road.stopsDone} of ${road.stops}` : "…"} />
              <StatTile
                label="On time"
                value={!served ? "…" : served.served === 0 ? "-" : `${Math.round((served.onTime / served.served) * 100)}%`}
                note={served ? `${served.onTime} of ${served.served} delivered stops` : undefined}
              />
              <StatTile
                label="Open issues"
                value={show(open?.open)}
                note={open ? <button type="button" onClick={() => onNavigate("issues")} className="text-go-teal">{open.urgent} high or critical</button> : undefined}
                valueClassName={open?.urgent ? "text-go-danger-strong" : "text-go-ink"}
              />
            </div>
          </Card>

          <SkippedOutlets depots={depots} date={today} onNavigate={onNavigate} />
        </div>

        <div className="flex min-w-[300px] flex-1 flex-col gap-5 lg:max-w-[380px]">
          <Card label="Vehicles">
            <CardHead
              title="Vehicles"
              meta={`Today · ${scopeLabel}`}
              action={<LinkAction onClick={() => onNavigate("vehicles")}>View more</LinkAction>}
            />
            {fleet.error && !summary ? (
              <FleetError error={fleet.error} onRetry={fleet.refresh} />
            ) : (
              <div className="flex gap-2">
                <FleetTile value={summary?.vans} label="Available vans" />
                <FleetTile value={summary?.nonVans} label="Available vehicles" />
                <FleetTile value={null} label="Workshop vehicles" warning />
              </div>
            )}
            <Notice tone="neutral" title="Workshop vehicles">
              Reference data serves only the vehicles available on a day, so the workshop list waits on a fleet
              status read.
            </Notice>
          </Card>

          <RecentNotifications onNavigate={onNavigate} />
        </div>
      </div>
    </>
  );
}

/** The small vehicle count tile: number first, label under it. `null` is not served yet. */
function FleetTile({ value, label, warning = false }: { value: number | null | undefined; label: string; warning?: boolean }): React.JSX.Element {
  return (
    <div className="flex h-[74px] min-w-0 flex-1 flex-col gap-1 rounded-go-card-s bg-go-surface px-3 py-2">
      <p className={cx("truncate text-xl font-medium", warning ? "text-go-warning-text" : "text-go-teal")}>
        {value === undefined ? "…" : value === null ? "-" : value}
      </p>
      <p className="text-[11px] text-go-secondary">{label}</p>
    </div>
  );
}

/** Figma 189:10739: the three newest, with "View all" opening the panel. */
function RecentNotifications({ onNavigate }: { onNavigate: (view: ViewId) => void }): React.JSX.Element {
  const ctx = useDispatcherInbox();
  const items = ctx?.inbox.items ?? [];
  return (
    <Card label="Recent notifications" className="flex-1">
      <CardHead
        title="Recent notifications"
        meta="Messages sent to you"
        action={ctx && <LinkAction onClick={() => ctx.setOpen(true)}>View all</LinkAction>}
      />
      {items.length === 0 ? (
        <p className="text-sm text-go-secondary">{ctx?.inbox.loading ? "Loading…" : "No notifications yet."}</p>
      ) : (
        <NotificationRows items={items} onNavigate={onNavigate} limit={3} />
      )}
    </Card>
  );
}
