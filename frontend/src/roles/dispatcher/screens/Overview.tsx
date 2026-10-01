"use client";

import type { Resource } from "@shared/api/useResource";
import type { VehicleView } from "@shared/domain/types";
import { Card, CardHead, LinkAction, Notice, Pending, cx } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import FleetError from "./FleetError.tsx";
import { summarise } from "../data/fleet.ts";
import { depotStamp, greeting } from "../data/scope.ts";
import type { ViewId } from "../navigation.ts";

// Figma "02 Overview": today's depot at a glance. Vehicles is live from
// reference data. Orders, the delivery summary and notifications name the
// module they wait on (decision D-D: no mock data).

export default function Overview({
  displayName,
  scopeLabel,
  fleet,
  online,
  onNavigate,
}: {
  displayName: string;
  scopeLabel: string;
  fleet: Resource<VehicleView[]>;
  online: boolean;
  onNavigate: (view: ViewId) => void;
}): React.JSX.Element {
  const summary = fleet.data ? summarise(fleet.data) : null;

  return (
    <>
      <PageHeader
        title={`${greeting()}, ${displayName}`}
        subtitle={`${depotStamp()} · ${scopeLabel}`}
        online={online}
        lastSyncedAt={fleet.loadedAt}
      />

      <div className="flex min-h-0 w-full flex-1 gap-5 max-lg:flex-col">
        <div className="flex min-w-0 flex-1 flex-col gap-5">
          <Card label="Orders">
            <CardHead
              title="Orders"
              meta={`Today · ${scopeLabel}`}
              action={<LinkAction onClick={() => onNavigate("orders")}>Open orders</LinkAction>}
            />
            <Pending what="Today's confirmed and deferred orders" waitingOn="the Ordering module (#8)" />
          </Card>

          <Card label="Summary" className="flex-1">
            <CardHead title="Summary" meta="Orders, on-time rate, issues and trips per day" />
            <Pending what="The delivery summary" waitingOn="Execution (#12) and Issues (#13)" />
          </Card>
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

          <Card label="Recent notifications" className="flex-1">
            <CardHead title="Recent notifications" meta="Messages sent to you" />
            <Pending what="Your notifications" waitingOn="the Notification module (#14)" />
          </Card>
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
        {value === undefined ? "…" : value === null ? "—" : value}
      </p>
      <p className="text-[11px] text-go-secondary">{label}</p>
    </div>
  );
}
