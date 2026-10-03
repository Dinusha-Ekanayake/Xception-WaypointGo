"use client";

import { useMemo, useState } from "react";
import type { VehicleView } from "@shared/domain/types";
import { FilterTabs, Icon, Pill } from "@shared/ui";
import { capacityLabel, litres, typeLabel } from "../data/fleet.ts";

// The fleet table from Figma "06 Vehicles". The design's On the road / At depot
// tabs need Execution, so the tabs split what reference data knows instead:
// temperature and vehicle type. Rows open the vehicle drawer.

type Tab = "all" | "refrigerated" | "ambient" | "vans";

const MATCH: Record<Tab, (v: VehicleView) => boolean> = {
  all: () => true,
  refrigerated: (v) => v.refrigerated,
  ambient: (v) => !v.refrigerated,
  vans: (v) => v.van,
};

const COLUMNS = "grid grid-cols-[86px_116px_132px_minmax(90px,1fr)_96px_110px] items-center gap-2.5 px-0.5";

export default function FleetTable({
  fleet,
  onOpen,
}: {
  fleet: VehicleView[];
  onOpen: (vehicleId: string) => void;
}): React.JSX.Element {
  const [tab, setTab] = useState<Tab>("all");
  const [query, setQuery] = useState("");

  const rows = useMemo(() => {
    const needle = query.trim().toUpperCase();
    return fleet.filter((v) => MATCH[tab](v) && (!needle || v.vehicleId.includes(needle)));
  }, [fleet, tab, query]);

  const count = (t: Tab) => fleet.filter(MATCH[t]).length;

  return (
    <div className="flex min-w-0 flex-col overflow-x-auto">
      <div className="flex min-w-[690px] items-center gap-2 pb-2.5">
        <FilterTabs
          label="Filter vehicles"
          value={tab}
          onChange={setTab}
          options={[
            { value: "all", label: `All (${count("all")})` },
            { value: "refrigerated", label: `Refrigerated (${count("refrigerated")})` },
            { value: "ambient", label: `Ambient (${count("ambient")})` },
            { value: "vans", label: `Vans (${count("vans")})` },
          ]}
        />
        <div className="flex-1" />
        <label className="flex items-center gap-1.5 rounded-go-card-s bg-go-surface px-3 py-[7px]">
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

      <div role="table" aria-label="Available vehicles" className="min-w-[690px]">
        <div role="row" className={`${COLUMNS} border-b border-go-rule py-2 text-xs font-medium text-go-secondary`}>
          <span role="columnheader">Vehicle</span>
          <span role="columnheader">Type</span>
          <span role="columnheader">Capacity</span>
          <span role="columnheader">Status</span>
          <span role="columnheader">Depot</span>
          <span role="columnheader">Fuel quota · week</span>
        </div>
        {rows.map((vehicle) => (
          <button
            key={vehicle.vehicleId}
            type="button"
            role="row"
            onClick={() => onOpen(vehicle.vehicleId)}
            className={`${COLUMNS} w-full border-b border-go-rule py-[9px] text-left text-[13px] text-go-ink hover:bg-go-subtle`}
          >
            <span role="cell" className="font-semibold">{vehicle.vehicleId}</span>
            <span role="cell">
              <Pill tone={vehicle.refrigerated ? "info" : "muted"} icon={vehicle.refrigerated ? "snowflake" : undefined}>
                {typeLabel(vehicle)}
              </Pill>
            </span>
            <span role="cell" className="whitespace-nowrap">{capacityLabel(vehicle)}</span>
            <span role="cell">
              <Pill tone="success">Available</Pill>
            </span>
            <span role="cell">{vehicle.depotCode}</span>
            <span role="cell" className="whitespace-nowrap">{litres(vehicle.weeklyFuelQuotaL)}</span>
          </button>
        ))}
        {rows.length === 0 && (
          <p className="py-6 text-center text-[13px] text-go-secondary">
            {fleet.length === 0 ? "No vehicles are available on this day." : "No vehicle matches this filter."}
          </p>
        )}
      </div>
    </div>
  );
}
