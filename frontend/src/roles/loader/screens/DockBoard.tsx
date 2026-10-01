"use client";

import { useState } from "react";
import type { Resource } from "@shared/api/useResource";
import type { ReadyTripView, SessionStatus } from "@shared/domain/types";
import { Icon, Notice, cx } from "@shared/ui";
import { hhmm } from "../data/manifest.ts";
import { BigButton, StatusChip } from "../ui.tsx";

// Figma "01 Dock board" (phone, cards) and "02 Dock board" (tablet, a table):
// the trips ready to load at this depot today (R-LOD-04). The design's route,
// load and loader columns wait on those fields in ReadyTripView (#10).

type Filter = "all" | "todo" | "loading" | "done";
const FILTERS: Array<{ value: Filter; label: string; match: (s: SessionStatus) => boolean }> = [
  { value: "all", label: "All", match: () => true },
  { value: "todo", label: "To load", match: (s) => s === "NOT_STARTED" },
  { value: "loading", label: "Loading", match: (s) => s === "IN_PROGRESS" || s === "BLOCKED" || s === "READY" },
  { value: "done", label: "Released", match: (s) => s === "COMPLETED" },
];

export default function DockBoard({
  depot,
  trips,
  online,
  onOpen,
}: {
  depot: string;
  trips: Resource<ReadyTripView[]>;
  online: boolean;
  onOpen: (tripId: string) => void;
}): React.JSX.Element {
  const [filter, setFilter] = useState<Filter>("all");
  const [query, setQuery] = useState("");
  const all = [...(trips.data ?? [])].sort((a, b) => a.plannedDeparture.localeCompare(b.plannedDeparture));
  const match = FILTERS.find((f) => f.value === filter)!.match;
  const shown = all.filter((t) => match(t.status) && t.vehicleId.toLowerCase().includes(query.trim().toLowerCase()));

  const actionFor = (trip: ReadyTripView) => () => onOpen(trip.tripId);

  return (
    <div className="flex flex-col gap-4 px-5 pb-6 md:px-8 lg:px-[42px]">
      <div className="flex flex-col gap-4 pt-2 md:flex-row md:flex-wrap md:items-start md:justify-between md:gap-x-4 md:gap-y-3">
        <div className="flex flex-col gap-3.5">
          <h1 className="text-[30px] font-semibold text-black">Tonight&apos;s departures</h1>
          <span className="flex min-h-12 w-fit items-center gap-2 rounded-full bg-white px-3.5 shadow-[0_5px_20px_rgba(0,0,0,0.09)]">
            <Icon name="dock" />
            <span className="text-base font-medium">Depot {depot}</span>
          </span>
        </div>

        <div className="flex flex-col gap-3.5 md:items-end">
          <label className="flex h-12 w-full items-center gap-2.5 rounded-[16px] border border-[#dfe3e8] bg-white pr-3 pl-4 md:w-[420px]">
            <Icon name="search" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search vehicle"
              aria-label="Search vehicle"
              className="min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-go-muted"
            />
          </label>

          <div role="tablist" aria-label="Filter trips" className="-mx-5 flex gap-2 overflow-x-auto px-5 md:mx-0 md:px-0">
            {FILTERS.map((f) => (
              <button
                key={f.value}
                type="button"
                role="tab"
                aria-selected={filter === f.value}
                onClick={() => setFilter(f.value)}
                className={cx(
                  "shrink-0 rounded-full px-[18px] py-[11px] text-[15px] font-medium whitespace-nowrap max-md:px-2.5 max-md:text-[14px]",
                  filter === f.value ? "bg-go-mint text-black" : "bg-[#f1f3f5] text-black",
                )}
              >
                {f.label} ({all.filter((t) => f.match(t.status)).length})
              </button>
            ))}
          </div>
        </div>
      </div>

      {!online && (
        <Notice tone="warning" title="Offline: this list may be out of date" live>
          Checks you record are saved on this device and sent when the connection returns.
        </Notice>
      )}
      {trips.error && (
        <Notice
          tone="danger"
          title="Could not load today's trips"
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
        <p className="rounded-[24px] bg-white p-6 text-center text-[15px] text-go-muted">
          {all.length === 0 ? `No trips are ready to load at ${depot} yet. They appear here once the plan is published.` : "No trips match."}
        </p>
      )}

      {/* Phone: cards. */}
      <ul className="flex flex-col gap-3 md:hidden">
        {shown.map((trip) => (
          <li key={trip.tripId}>
            <TripCard trip={trip} onOpen={actionFor(trip)} />
          </li>
        ))}
      </ul>

      {/* Tablet and up: the trip table from "02 Dock board". */}
      {shown.length > 0 && (
        <div className="hidden rounded-[25px] bg-white px-4 pt-3.5 pb-2 shadow-[0_5px_20px_rgba(0,0,0,0.09)] md:block">
          <table className="w-full border-collapse text-left">
            <thead>
              <tr className="text-[14px] text-go-muted">
                <th scope="col" className="py-2 pl-4 font-normal">Vehicle</th>
                <th scope="col" className="py-2 font-normal">Departs</th>
                <th scope="col" className="py-2 font-normal">Status</th>
                <th scope="col" className="py-2 pr-3.5"><span className="sr-only">Action</span></th>
              </tr>
            </thead>
            <tbody>
              {shown.map((trip) => (
                <tr key={trip.tripId} className="border-b border-[#d3e3e2] last:border-b-0">
                  <td className="py-3 pl-4">
                    <span className="flex flex-col gap-0.5">
                      <span className="text-[18px] font-medium">{trip.vehicleId}</span>
                      <span className="text-[14px] text-go-muted">Trip {trip.tripNumber}</span>
                    </span>
                  </td>
                  <td className="py-3 text-[18px] font-medium">{hhmm(trip.plannedDeparture)}</td>
                  <td className="py-3">
                    <StatusChip status={trip.status} />
                  </td>
                  <td className="w-[172px] py-3 pr-3.5">
                    <TripAction trip={trip} onOpen={actionFor(trip)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function TripAction({ trip, onOpen }: { trip: ReadyTripView; onOpen: () => void }): React.JSX.Element {
  return trip.status === "NOT_STARTED" ? (
    <BigButton icon="hand" onClick={onOpen}>
      Take trip
    </BigButton>
  ) : trip.status === "COMPLETED" ? (
    <BigButton tone="plain" onClick={onOpen}>
      View
    </BigButton>
  ) : (
    <BigButton icon="arrow-right" onClick={onOpen}>
      Continue
    </BigButton>
  );
}

function TripCard({
  trip,
  onOpen,
}: {
  trip: ReadyTripView;
  onOpen: () => void;
}): React.JSX.Element {
  return (
    <article className="flex flex-col gap-3 rounded-[24px] bg-white p-4 drop-shadow-[0_5px_10px_rgba(0,0,0,0.09)]">
      <div className="flex items-start justify-between">
        <div className="flex flex-col gap-0.5">
          <span className="text-[18px] font-medium">{trip.vehicleId}</span>
          <span className="text-[13px] text-go-muted">Trip {trip.tripNumber}</span>
        </div>
        <div className="flex flex-col items-end">
          <span className="text-[24px] font-medium">{hhmm(trip.plannedDeparture)}</span>
          <span className="text-[13px] text-go-muted">Departs</span>
        </div>
      </div>
      <div className="flex justify-end">
        <StatusChip status={trip.status} />
      </div>
      <TripAction trip={trip} onOpen={onOpen} />
    </article>
  );
}
