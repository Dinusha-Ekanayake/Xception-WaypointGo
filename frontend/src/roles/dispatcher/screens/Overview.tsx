"use client";

import type { Resource } from "@shared/api/useResource";
import type { VehicleView } from "@shared/domain/types";
import { Card, CardHead, LinkAction, Pending, cx } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import FleetError from "./FleetError.tsx";
import { NotificationRows } from "../NotificationsPanel.tsx";
import { useDispatcherInbox } from "../inbox.tsx";
import Refusal from "./Refusal.tsx";
import { OrdersCard, SummaryCard } from "./OverviewCards.tsx";
import { summarise } from "../data/fleet.ts";
import { depotStamp, depotToday, greeting } from "../data/scope.ts";
import { useIssues, useOrders } from "../data/useDay.ts";
import type { ViewId } from "../navigation.ts";

// Figma "02 Overview": today's depot at a glance, every tile from the module
// that owns the fact: orders and deferrals from Ordering and Planning, the
// days behind from Ordering and Execution, issues from Issues, vehicles from
// reference data. What no module serves yet says so (decision D-D: no mock data).

export default function Overview({
  displayName,
  depots,
  scope,
  depotFilter,
  onDepotFilter,
  scopeLabel,
  fleet,
  online,
  onNavigate,
}: {
  displayName: string;
  depots: string[];
  /** Every depot in the session, for the Summary's depot menu, which is the sidebar's scope. */
  scope: string[];
  depotFilter: string;
  onDepotFilter: (filter: string) => void;
  scopeLabel: string;
  fleet: Resource<VehicleView[]>;
  online: boolean;
  onNavigate: (view: ViewId) => void;
}): React.JSX.Element {
  const summary = fleet.data ? summarise(fleet.data) : null;
  const today = depotToday();
  const orders = useOrders(depots, today);
  const issues = useIssues(depots);

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
          <OrdersCard depots={depots} orders={orders.data} error={orders.error} scopeLabel={scopeLabel} onNavigate={onNavigate} />
          <SummaryCard depots={depots} scope={scope} depotFilter={depotFilter} onDepotFilter={onDepotFilter} issues={issues.data} onNavigate={onNavigate} />
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
            <h3 className="text-[14px] font-medium text-go-ink">Workshop vehicles</h3>
            <Pending what="which vehicles are in the workshop and when they come back" waitingOn="The counts above are vehicles available today. Vehicles in the workshop are not listed yet." />
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
