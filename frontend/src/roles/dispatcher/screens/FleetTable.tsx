"use client";

import { useState } from "react";
import type { FuelView, VehicleView } from "@shared/domain/types";
import { FilterTabs, Icon, Pending, Pill, cx } from "@shared/ui";
import { capacityLabel, typeLabel } from "../data/fleet.ts";

// The fleet table from Figma "06 Vehicles": All, On the road, At depot and
// Workshop. On the road comes from Execution's run sheets; reference data lists
// only vehicles available on the day, so the Workshop tab says what it waits on
// rather than showing an empty list as if none were there. Trips are the day's
// published plan; the fuel bar is the week's planned use against the quota.

export type RoadStatus = "road" | "depot";

type Tab = "all" | RoadStatus | "workshop";

const COLUMNS = "grid grid-cols-[80px_124px_146px_100px_44px_minmax(160px,1fr)] items-center gap-2.5 px-0.5";

export default function FleetTable({
  fleet,
  status,
  trips,
  fuel,
  onOpen,
}: {
  fleet: VehicleView[];
  /** Where each vehicle is now; missing while Execution has not answered. */
  status: Record<string, RoadStatus> | null;
  /** Trips on the day's published plan, by vehicle. */
  trips: Record<string, number> | null;
  fuel: Record<string, FuelView> | null;
  onOpen: (vehicleId: string) => void;
}): React.JSX.Element {
  const [tab, setTab] = useState<Tab>("all");
  const [query, setQuery] = useState("");
  const where = (v: VehicleView): RoadStatus => status?.[v.vehicleId] ?? "depot";

  const needle = query.trim().toUpperCase();
  const rows = fleet.filter((v) => (tab === "all" || (tab !== "workshop" && where(v) === tab)) && (!needle || v.vehicleId.includes(needle)));

  const count = (t: RoadStatus) => fleet.filter((v) => where(v) === t).length;

  return (
    <div className="flex min-w-0 flex-col overflow-x-auto">
      <div className="flex min-w-[690px] items-center gap-2 pb-2.5">
        <FilterTabs
          label="Filter vehicles"
          value={tab}
          onChange={setTab}
          options={[
            { value: "all", label: `All (${fleet.length})` },
            { value: "road", label: `On the road (${status ? count("road") : "…"})` },
            { value: "depot", label: `At depot (${status ? count("depot") : "…"})` },
            { value: "workshop", label: "Workshop" },
          ]}
        />
        <div className="flex-1" />
        <label className="flex items-center gap-1.5 rounded-full bg-go-surface px-3 py-[7px]">
          <Icon name="search" />
          <input
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Find vehicle"
            aria-label="Find vehicle"
            className="w-28 bg-transparent text-xs text-go-ink outline-none placeholder:text-go-placeholder"
          />
        </label>
      </div>

      {tab === "workshop" ? (
        <Pending what="the vehicles in the workshop" waitingOn="a fleet status read in reference data" />
      ) : (
        <div role="table" aria-label="Available vehicles" className="min-w-[690px]">
          <div role="row" className={`${COLUMNS} border-b border-go-rule py-2 text-[13px] text-go-secondary`}>
            <span role="columnheader">Vehicle</span>
            <span role="columnheader">Type</span>
            <span role="columnheader">Capacity</span>
            <span role="columnheader">Status</span>
            <span role="columnheader">Trips</span>
            <span role="columnheader">Fuel quota · this week</span>
          </div>
          {rows.map((vehicle) => {
            const f = fuel?.[vehicle.vehicleId];
            const quota = f ? Number(f.quotaLitres) : 0;
            const used = f && quota > 0 ? Math.round((Number(f.usedLitres) / quota) * 100) : null;
            const onRoad = where(vehicle) === "road";
            return (
              <button
                key={vehicle.vehicleId}
                type="button"
                role="row"
                onClick={() => onOpen(vehicle.vehicleId)}
                className={`${COLUMNS} w-full border-b border-go-rule py-[9px] text-left text-[13px] text-go-ink hover:bg-go-subtle`}
              >
                <span role="cell" className="font-semibold">
                  {vehicle.vehicleId}
                </span>
                <span role="cell">
                  <Pill tone={vehicle.refrigerated ? "info" : "muted"} icon={vehicle.refrigerated ? "snowflake" : undefined}>
                    {typeLabel(vehicle)}
                  </Pill>
                </span>
                <span role="cell" className="whitespace-nowrap">
                  {capacityLabel(vehicle)}
                </span>
                <span role="cell">
                  <Pill tone={onRoad ? "success" : "neutral"}>{status ? (onRoad ? "On the road" : "At depot") : "Available"}</Pill>
                </span>
                <span role="cell" className="tabular-nums">
                  {trips ? (trips[vehicle.vehicleId] ?? 0) : "…"}
                </span>
                <span role="cell" className="flex items-center gap-2">
                  <span className="h-1.5 w-20 overflow-hidden rounded-full bg-go-surface">
                    <span className={cx("block h-full rounded-full", (used ?? 0) > 90 ? "bg-go-warning" : "bg-go-teal")} style={{ width: `${Math.min(100, used ?? 0)}%` }} />
                  </span>
                  <span className={cx("text-xs tabular-nums", (used ?? 0) > 90 ? "text-go-warning-text" : "text-go-secondary")}>{used === null ? "-" : `${used}%`}</span>
                </span>
              </button>
            );
          })}
          {rows.length === 0 && (
            <p className="py-6 text-center text-[13px] text-go-secondary">{fleet.length === 0 ? "No vehicles are available today." : "No vehicle matches this filter."}</p>
          )}
          <p className="flex items-center gap-2 pt-2 text-xs text-go-secondary">
            <span aria-hidden className="h-1.5 w-4 rounded-full bg-go-teal" /> Planned this week, published plans only
          </p>
        </div>
      )}
    </div>
  );
}
