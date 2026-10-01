"use client";

import { useState } from "react";
import type { Resource } from "@shared/api/useResource";
import type { ReadyTripView } from "@shared/domain/types";
import { Icon, Notice, cx } from "@shared/ui";
import { IDLE_RELEASE_MINUTES, hhmm, holdLapsed } from "../data/manifest.ts";
import { BigButton, StatusChip, TempBadge } from "../ui.tsx";

// Figma "01 Dock board", "06 Change dock", "E3 offline" and "E4 no trips here":
// tonight's departures at the loader's depot, filtered by the dock they stand at.
// One loader per trip, until release or hand back (R-LOD-11), so a trip someone
// else holds shows "In use" and cannot be opened for loading.

type Filter = "all" | "available" | "mine" | "inUse";
type Who = (trip: ReadyTripView) => "free" | "mine" | "other";

const FILTERS: Array<{ value: Filter; label: string; match: (t: ReadyTripView, who: Who) => boolean }> = [
  { value: "all", label: "All", match: () => true },
  { value: "available", label: "Available", match: (t, who) => t.status !== "COMPLETED" && who(t) === "free" },
  { value: "mine", label: "Mine", match: (t, who) => who(t) === "mine" },
  { value: "inUse", label: "In use", match: (t, who) => who(t) === "other" },
];

export default function DockBoard({
  depot,
  meId,
  trips,
  online,
  onOpen,
}: {
  depot: string;
  /** The loader operating this device; a trip they hold is "Mine". */
  meId: string;
  trips: Resource<ReadyTripView[]>;
  online: boolean;
  onOpen: (tripId: string) => void;
}): React.JSX.Element {
  const [filter, setFilter] = useState<Filter>("all");
  const [dock, setDock] = useState<string>("");
  const [query, setQuery] = useState("");
  const all = [...(trips.data ?? [])].sort((a, b) => a.plannedDeparture.localeCompare(b.plannedDeparture));
  const docks = [...new Set(all.map((t) => t.dockCode))].sort();
  const who: Who = (t) =>
    t.holder === null ? "free" : t.holder.userId === meId ? "mine" : holdLapsed(t.holder) ? "free" : "other";
  const q = query.trim().toLowerCase();
  const atDock = all.filter((t) => dock === "" || t.dockCode === dock);
  const match = FILTERS.find((f) => f.value === filter)!.match;
  const shown = atDock.filter(
    (t) =>
      match(t, who) &&
      (q === "" || [t.vehicleId, t.districtName, t.holder?.name ?? "", t.holder?.employeeCode ?? ""].some((v) => v.toLowerCase().includes(q))),
  );

  return (
    <div className="flex flex-col gap-4 px-5 pb-6 md:px-8 lg:px-[42px]">
      <div className="flex flex-col gap-3.5 pt-2">
        <h1 className="text-[30px] font-semibold text-black">Tonight&apos;s departures</h1>
        <label className="flex min-h-12 w-fit items-center gap-2 rounded-full bg-white pr-2 pl-3.5 shadow-[0_5px_20px_rgba(0,0,0,0.09)]">
          <Icon name="dock" />
          <span className="sr-only">Dock</span>
          <select value={dock} onChange={(e) => setDock(e.target.value)} className="min-h-12 bg-transparent text-base font-medium outline-none">
            <option value="">All docks · {depot}</option>
            {docks.map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </label>
        <label className="flex h-12 w-full items-center gap-2.5 rounded-[16px] border border-[#dfe3e8] bg-white pr-3 pl-4 md:w-[420px]">
          <Icon name="search" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search vehicle, route or loader"
            aria-label="Search vehicle, route or loader"
            className="min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-go-muted"
          />
        </label>
        <div aria-label="Filter trips" className="-mx-5 flex gap-2 overflow-x-auto px-5 md:mx-0 md:px-0">
          {FILTERS.map((f) => (
            <button
              key={f.value}
              type="button"
              aria-pressed={filter === f.value}
              onClick={() => setFilter(f.value)}
              className={cx(
                "min-h-12 shrink-0 rounded-full px-[18px] text-[15px] font-medium whitespace-nowrap max-md:px-2.5 max-md:text-[14px]",
                filter === f.value ? "bg-go-mint text-black" : "bg-[#f1f3f5] text-black",
              )}
            >
              {f.label} ({atDock.filter((t) => f.match(t, who)).length})
            </button>
          ))}
        </div>
        <p className="text-[13px] text-go-muted">One loader per trip, until release or hand back.</p>
      </div>

      {!online && (
        <Notice tone="warning" title="Your trip works offline. Taking a new one needs a connection." live>
          Checks you record are saved on this device and sent when the connection returns.
        </Notice>
      )}
      {trips.error && (
        <Notice
          tone="danger"
          title="Could not load tonight's trips"
          action={
            <button type="button" onClick={trips.refresh} className="min-h-12 shrink-0 px-2 text-[13px] font-medium text-go-teal">
              Retry
            </button>
          }
        >
          {trips.error.message}
        </Notice>
      )}
      {!trips.data && !trips.error && <p className="py-8 text-center text-[15px] text-go-muted">Loading trips…</p>}
      {trips.data && shown.length === 0 && (
        <div className="flex flex-col items-center gap-3 rounded-[24px] bg-white p-6 text-center">
          <p className="text-[17px] font-medium">
            {all.length === 0 ? "No trips tonight yet" : dock !== "" && atDock.length === 0 ? `No trips at ${dock} right now` : "No trips match"}
          </p>
          <p className="text-[15px] text-go-muted">
            {all.length === 0 ? "They appear here once the plan is published." : "Try another dock or filter."}
          </p>
          {dock !== "" && (
            <BigButton tone="plain" onClick={() => setDock("")}>
              Show all docks
            </BigButton>
          )}
        </div>
      )}

      <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
        {shown.map((trip) => (
          <li key={trip.tripId}>
            <TripCard trip={trip} who={who(trip)} online={online} onOpen={() => onOpen(trip.tripId)} />
          </li>
        ))}
      </ul>
    </div>
  );
}

function TripCard({
  trip,
  who,
  online,
  onOpen,
}: {
  trip: ReadyTripView;
  who: "free" | "mine" | "other";
  online: boolean;
  onOpen: () => void;
}): React.JSX.Element {
  const released = trip.status === "COMPLETED";
  return (
    <article className="flex flex-col gap-3 rounded-[24px] bg-white p-4 drop-shadow-[0_5px_10px_rgba(0,0,0,0.09)]">
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <span className="flex items-center gap-2">
            <span className="text-[18px] font-medium">{trip.vehicleId}</span>
            <TempBadge temperature={trip.temperature} />
          </span>
          <span className="text-[13px] text-go-muted">
            Trip {trip.tripNumber} of {trip.tripsForVehicle} · {trip.dockCode}
          </span>
        </div>
        <div className="flex flex-col items-end">
          <span className="text-[24px] font-medium">{hhmm(trip.plannedDeparture)}</span>
          <span className="text-[13px] text-go-muted">{released ? "Departed" : "Departs"}</span>
        </div>
      </div>
      <div className="flex items-end justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <span className="text-[18px] font-medium">{trip.districtName}</span>
          <span className="text-[13px] text-go-muted">
            {trip.brandCode} · {trip.stopCount} {trip.stopCount === 1 ? "stop" : "stops"}
            {trip.holder && ` · ${who === "mine" ? "You" : trip.holder.name}${trip.holder.employeeCode ? ` · ${trip.holder.employeeCode}` : ""}`}
            {trip.holder && who === "free" && ` · idle ${IDLE_RELEASE_MINUTES} min, free to take`}
          </span>
        </div>
        <StatusChip status={trip.status} />
      </div>
      {released ? (
        <BigButton tone="plain" onClick={onOpen}>
          View
        </BigButton>
      ) : who === "mine" ? (
        <BigButton icon="arrow-right" onClick={onOpen}>
          Continue
        </BigButton>
      ) : who === "other" ? (
        <BigButton tone="muted" icon="lock" disabled>
          In use
        </BigButton>
      ) : online ? (
        <BigButton icon="hand" onClick={onOpen}>
          Take trip
        </BigButton>
      ) : (
        <BigButton tone="muted" disabled>
          Needs connection
        </BigButton>
      )}
    </article>
  );
}
