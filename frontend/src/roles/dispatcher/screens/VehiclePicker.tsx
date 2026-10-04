"use client";

import { useMemo, useState } from "react";
import type { PlanView, VehicleView } from "@shared/domain/types";
import { Icon, Popover, cx } from "@shared/ui";
import { capacityLabel, typeLabel } from "../data/fleet.ts";

// Choosing the vehicle a whole trip moves to: a small pop-up with a search and
// a few filters, each vehicle with its type, capacity and how many trips it
// already has in this plan. The server still judges the move (the interchange
// preview) before anything is sent; this only helps find the vehicle.

type Filter = "all" | "free" | "refrigerated" | "ambient" | "van";

const FILTERS: Array<{ id: Filter; label: string }> = [
  { id: "all", label: "All" },
  { id: "free", label: "Free" },
  { id: "refrigerated", label: "Refrigerated" },
  { id: "ambient", label: "Ambient" },
  { id: "van", label: "Van" },
];

export default function VehiclePicker({
  plan,
  fleet,
  current,
  chosen,
  disabled = false,
  onChoose,
}: {
  plan: PlanView;
  fleet: VehicleView[];
  /** The trip's own vehicle, left out of the list. */
  current: string;
  chosen: string | null;
  disabled?: boolean;
  onChoose: (vehicleId: string) => void;
}): React.JSX.Element {
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<Filter>("all");
  const tripsOf = useMemo(() => {
    const count = new Map<string, number>();
    for (const trip of plan.trips) count.set(trip.vehicleId, (count.get(trip.vehicleId) ?? 0) + 1);
    return count;
  }, [plan.trips]);

  const needle = query.trim().toUpperCase();
  const shown = fleet
    .filter((v) => v.vehicleId !== current)
    .filter((v) => !needle || v.vehicleId.includes(needle) || v.depotCode.toUpperCase().includes(needle))
    .filter((v) =>
      filter === "free" ? (tripsOf.get(v.vehicleId) ?? 0) === 0
      : filter === "refrigerated" ? v.refrigerated
      : filter === "ambient" ? !v.refrigerated
      : filter === "van" ? v.van
      : true,
    )
    .sort((a, b) => (tripsOf.get(a.vehicleId) ?? 0) - (tripsOf.get(b.vehicleId) ?? 0) || a.vehicleId.localeCompare(b.vehicleId));

  return (
    <Popover
      label="Move the trip to"
      align="right"
      trigger={
        <span className="flex items-center gap-1.5">
          {chosen ? `Move to ${chosen}` : "Move trip to"}
          <Icon name="chevron-down" />
        </span>
      }
      className={cx("flex items-center rounded-full bg-go-card px-3.5 py-1.5 text-[13px] font-medium text-go-ink", disabled && "pointer-events-none opacity-40")}
      panelClassName="flex w-[340px] flex-col gap-2 p-3"
    >
      {(close) => (
        <>
          <label className="flex items-center gap-2 rounded-full bg-go-surface px-3 py-2">
            <Icon name="search" />
            <input
              autoFocus
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Find vehicle"
              aria-label="Find vehicle"
              className="w-full bg-transparent text-[13px] text-go-ink outline-none placeholder:text-go-placeholder"
            />
          </label>
          <div role="group" aria-label="Vehicle filter" className="flex flex-wrap gap-1">
            {FILTERS.map((f) => (
              <button
                key={f.id}
                type="button"
                aria-pressed={filter === f.id}
                onClick={() => setFilter(f.id)}
                className={cx("rounded-full px-2.5 py-1 text-xs font-medium", filter === f.id ? "bg-go-ink text-go-card" : "bg-go-surface text-go-ink")}
              >
                {f.label}
              </button>
            ))}
          </div>
          <ul aria-label="Vehicles" className="flex max-h-[280px] flex-col gap-1 overflow-y-auto">
            {shown.length === 0 && <li className="px-2 py-3 text-center text-[13px] text-go-secondary">No vehicle matches.</li>}
            {shown.map((v) => {
              const trips = tripsOf.get(v.vehicleId) ?? 0;
              return (
                <li key={v.vehicleId}>
                  <button
                    type="button"
                    aria-pressed={chosen === v.vehicleId}
                    onClick={() => (onChoose(v.vehicleId), close())}
                    className={cx("flex w-full items-center gap-3 rounded-go-input px-3 py-2 text-left", chosen === v.vehicleId ? "bg-go-success-tint" : "hover:bg-go-subtle")}
                  >
                    <span className="min-w-0 flex-1">
                      <span className="block text-[14px] font-medium text-go-ink">{v.vehicleId}</span>
                      <span className="block truncate text-xs text-go-secondary">{`${typeLabel(v)} · ${capacityLabel(v)} · ${v.depotCode}`}</span>
                    </span>
                    <span className={cx("shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium", trips === 0 ? "bg-go-success-tint text-go-teal" : trips >= 2 ? "bg-go-warning-tint text-go-warning-text" : "bg-go-surface text-go-secondary")}>
                      {trips === 0 ? "Free" : `${trips} ${trips === 1 ? "trip" : "trips"}`}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </>
      )}
    </Popover>
  );
}
