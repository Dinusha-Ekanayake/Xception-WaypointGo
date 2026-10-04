"use client";

import type { ReadyTripView } from "@shared/domain/types";
import { IDLE_RELEASE_MINUTES, hhmm } from "../data/manifest.ts";
import { BigButton, StatusChip, TempBadge } from "../ui.tsx";
import { useT } from "../i18n.tsx";

// The trips on the dock board, two ways. A phone gets Figma 08's cards; a
// tablet, desk or terminal gets the table from Figma 07, 09 and 10 (02 Dock
// board). Both read the same trip and offer the same one action.

export type Who = "free" | "mine" | "other";

type RowProps = {
  trip: ReadyTripView;
  who: Who;
  online: boolean;
  /** All docks are on the board, so each trip says which dock it loads at. */
  showDock: boolean;
  /** This trip is being opened or just closed: its vehicle moves to the load sheet's truck card. */
  moving?: boolean;
  onOpen: () => void;
};

// One shared element name, given only to the trip in motion (shared/ui/transition.ts).
const MOVING = { viewTransitionName: "vt-trip" };

/** The one thing a loader can do with a trip: continue, take, view, or nothing while someone else holds it (R-LOD-11). */
function TripAction({ trip, who, online, onOpen }: Omit<RowProps, "showDock">): React.JSX.Element {
  const tr = useT();
  if (trip.status === "COMPLETED") {
    return <BigButton tone="plain" onClick={onOpen}>{tr("View")}</BigButton>;
  }
  if (who === "mine") {
    return <BigButton icon="arrow-right" onClick={onOpen}>{tr("Continue")}</BigButton>;
  }
  if (who === "other") {
    return <BigButton tone="muted" icon="lock" disabled>{tr("In use")}</BigButton>;
  }
  return online ? (
    <BigButton icon="hand" onClick={onOpen}>{tr("Take trip")}</BigButton>
  ) : (
    <BigButton tone="muted" disabled>{tr("Needs connection")}</BigButton>
  );
}

function tripLine(trip: ReadyTripView, showDock: boolean, tr: ReturnType<typeof useT>): string {
  const n = tr("Trip {n} of {m}", { n: trip.tripNumber, m: trip.tripsForVehicle });
  return showDock ? `${n} · ${trip.dockCode}` : n;
}

const stops = (trip: ReadyTripView, tr: ReturnType<typeof useT>) =>
  tr(trip.stopCount === 1 ? "{n} stop" : "{n} stops", { n: trip.stopCount });

export function TripCard({ trip, who, online, showDock, moving, onOpen }: RowProps): React.JSX.Element {
  const tr = useT();
  return (
    <article className="flex flex-col gap-3 rounded-[24px] bg-go-card p-4 drop-shadow-[0_5px_10px_rgba(0,0,0,0.09)]">
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-0.5" style={moving ? MOVING : undefined}>
          <span className="flex items-center gap-2">
            <span className="text-[18px] font-medium">{trip.vehicleId}</span>
            <TempBadge temperature={trip.temperature} />
          </span>
          <span className="text-[13px] text-go-muted">{tripLine(trip, showDock, tr)}</span>
        </div>
        <div className="flex flex-col items-end">
          <span className="text-[24px] font-medium">{hhmm(trip.plannedDeparture)}</span>
          <span className="text-[13px] text-go-muted">{tr(trip.status === "COMPLETED" ? "Departed" : "Departs")}</span>
        </div>
      </div>
      <div className="flex items-end justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <span className="text-[18px] font-medium">{trip.districtName}</span>
          <span className="text-[13px] text-go-muted">
            {trip.brandCode} · {stops(trip, tr)}
            {trip.holder && who === "other" && ` · ${trip.holder.name}`}
            {trip.holder && who === "free" && ` · ${tr("idle {n} min, free to take", { n: IDLE_RELEASE_MINUTES })}`}
          </span>
        </div>
        <StatusChip status={trip.status} />
      </div>
      <TripAction trip={trip} who={who} online={online} onOpen={onOpen} />
    </article>
  );
}

/** "21.4 / 26.4": the planned load against the vehicle, or the load alone from a server without capacities. */
function loadText(trip: ReadyTripView): string {
  const load = Number(trip.volumeM3).toFixed(1);
  return trip.volumeCapM3 ? `${load} / ${Number(trip.volumeCapM3).toFixed(1)}` : load;
}

function LoaderCell({ trip, who }: { trip: ReadyTripView; who: Who }): React.JSX.Element {
  const tr = useT();
  if (!trip.holder) return <span className="text-go-muted">-</span>;
  return (
    <span className="flex flex-col">
      <span className="text-[16px] text-go-ink">{who === "mine" ? tr("You") : trip.holder.name}</span>
      <span className="text-[13px] text-go-muted">
        {who === "free" ? tr("idle {n} min, free to take", { n: IDLE_RELEASE_MINUTES }) : trip.holder.employeeCode}
      </span>
    </span>
  );
}

export function TripTable({ trips, who, online, showDock, moving, onOpen }: {
  trips: ReadyTripView[];
  who: (trip: ReadyTripView) => Who;
  online: boolean;
  showDock: boolean;
  moving?: string | null;
  onOpen: (tripId: string) => void;
}): React.JSX.Element {
  const tr = useT();
  const head = "px-3 pb-3 text-left text-[14px] font-normal text-go-muted";
  return (
    <div className="rounded-[24px] bg-go-card px-3 pt-4 pb-1 shadow-go-card lg:px-4">
      <table className="w-full border-collapse">
        <caption className="sr-only">{tr("Tonight's departures")}</caption>
        <thead>
          <tr>
            <th scope="col" className={head}>{tr("Vehicle")}</th>
            <th scope="col" className={head}>{tr("Route")}</th>
            <th scope="col" className={head}>{tr("Departs")}</th>
            <th scope="col" className={`${head} hidden lg:table-cell`}>{tr("Load (m³)")}</th>
            <th scope="col" className={`${head} hidden lg:table-cell`}>{tr("Loader")}</th>
            <th scope="col" className={head}>{tr("Status")}</th>
            <th scope="col" className={head}><span className="sr-only">{tr("Action")}</span></th>
          </tr>
        </thead>
        <tbody>
          {trips.map((trip) => {
            const w = who(trip);
            const done = trip.status === "COMPLETED";
            return (
              <tr key={trip.tripId} className="border-t border-go-divider align-middle">
                <td className="px-3 py-3">
                  <span className="flex flex-wrap items-center gap-2" style={trip.tripId === moving ? MOVING : undefined}>
                    <span className={done ? "text-[18px] font-medium text-go-muted" : "text-[18px] font-medium"}>{trip.vehicleId}</span>
                    <TempBadge temperature={trip.temperature} />
                  </span>
                  <span className="text-[13px] text-go-muted">{tripLine(trip, showDock, tr)}</span>
                </td>
                <td className="px-3 py-3">
                  <span className="flex flex-col lg:flex-row lg:items-baseline lg:gap-2">
                    <span className="text-[16px] font-medium">{trip.districtName}</span>
                    <span className="text-[14px] text-go-muted">{stops(trip, tr)}</span>
                  </span>
                  <span className="text-[14px] text-go-muted">{trip.brandCode}</span>
                </td>
                <td className="px-3 py-3 text-[18px] font-medium tabular-nums">{hhmm(trip.plannedDeparture)}</td>
                <td className="hidden px-3 py-3 text-[15px] tabular-nums lg:table-cell">{loadText(trip)}</td>
                <td className="hidden px-3 py-3 lg:table-cell"><LoaderCell trip={trip} who={w} /></td>
                <td className="px-3 py-3"><StatusChip status={trip.status} /></td>
                <td className="w-[136px] px-3 py-3">
                  <TripAction trip={trip} who={w} online={online} onOpen={() => onOpen(trip.tripId)} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
