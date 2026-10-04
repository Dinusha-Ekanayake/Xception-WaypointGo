"use client";

import { useState } from "react";
import type { Resource } from "@shared/api/useResource";
import type { ReadyTripView } from "@shared/domain/types";
import { Icon, Notice, SkeletonRows, cx, usePersistentState } from "@shared/ui";
import { depotToday, holdLapsed } from "../data/manifest.ts";
import { addDays, dayLabel } from "../../../shared/wording/index.ts";
import { BigButton } from "../ui.tsx";
import { TruckIcon } from "../icons.tsx";
import DockPicker, { DOCK_KEY } from "./DockPicker.tsx";
import { TripCard, TripTable, type Who } from "./TripRows.tsx";
import { useT } from "../i18n.tsx";

// Figma "01 Dock board", "06 Change dock", "E3 offline" and "E4 no trips here":
// tonight's departures at the loader's depot, filtered by the dock they stand at.
// One loader per trip, until release or hand back (R-LOD-11), so a trip someone
// else holds shows "In use" and cannot be opened for loading.

type Filter = "all" | "available" | "mine" | "inUse";
type WhoOf = (trip: ReadyTripView) => Who;

const FILTERS: Array<{ value: Filter; label: string; match: (t: ReadyTripView, who: WhoOf) => boolean }> = [
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
  moving,
  date,
}: {
  depot: string;
  /** The loader operating this device; a trip they hold is "Mine". */
  meId: string;
  trips: Resource<ReadyTripView[]>;
  online: boolean;
  onOpen: (tripId: string) => void;
  /** The trip being opened or just closed, whose row moves to and from the load sheet. */
  moving?: string | null;
  /** The day these trips leave; named under the title when it is not today or tomorrow (UX plan U8). */
  date?: string;
}): React.JSX.Element {
  const tr = useT();
  const today = depotToday();
  const leavesLater = date !== undefined && date !== today && date !== addDays(today, 1);
  const [filter, setFilter] = usePersistentState<Filter>("loader:board:filter", "all");
  const [dock, setDockState] = useState<string>(() => {
    try {
      return window.localStorage.getItem(DOCK_KEY) ?? "";
    } catch {
      return "";
    }
  });
  const setDock = (next: string) => {
    setDockState(next);
    try {
      window.localStorage.setItem(DOCK_KEY, next);
    } catch {
      // Blocked storage: the dock lasts until reload.
    }
  };
  const [picking, setPicking] = useState(false);
  const [query, setQuery] = useState("");
  const all = [...(trips.data ?? [])].sort((a, b) => a.plannedDeparture.localeCompare(b.plannedDeparture));
  const docks = [...new Set(all.map((t) => t.dockCode))].sort();
  const who: WhoOf = (t) =>
    t.holder === null ? "free" : t.holder.userId === meId ? "mine" : holdLapsed(t.holder) ? "free" : "other";
  const q = query.trim().toLowerCase();
  const atDock = all.filter((t) => dock === "" || t.dockCode === dock);
  const match = FILTERS.find((f) => f.value === filter)!.match;
  const shown = atDock.filter(
    (t) =>
      match(t, who) &&
      (q === "" || [t.vehicleId, t.districtName, t.holder?.name ?? "", t.holder?.employeeCode ?? ""].some((v) => v.toLowerCase().includes(q))),
  );

  const emptyDock = dock !== "" && atDock.length === 0;
  const busyDocks = docks.filter((d) => d !== dock && all.some((t) => t.dockCode === d && t.status !== "COMPLETED")).slice(0, 2);

  return (
    <div className="flex flex-col gap-4 px-5 pb-6 md:px-8 lg:px-[42px]">
      {/* A phone stacks the controls (Figma 08). A landscape tablet, desk or terminal puts the
          search and filters beside the title and dock (Figma 07, 10). */}
      <div className="flex flex-col gap-3.5 pt-2 lg:grid lg:grid-cols-[auto_1fr] lg:items-center lg:gap-x-6">
        <div className="flex flex-col gap-0.5 lg:col-start-1 lg:row-start-1">
          <h1 className="text-[30px] font-semibold text-go-ink">{tr("Tonight's departures")}</h1>
          {leavesLater && date && <p className="text-[15px] text-go-muted">{tr("Departing {day}", { day: dayLabel(date) })}</p>}
        </div>
        <div className="lg:col-start-1 lg:row-start-2">
          <DockPicker
            docks={docks}
            dock={dock}
            allLabel={tr("All docks · {depot}", { depot })}
            open={picking}
            onOpen={setPicking}
            onPick={(d) => {
              setDock(d);
              setPicking(false);
            }}
          />
        </div>
        <label className="flex h-12 w-full items-center gap-2.5 rounded-[16px] border border-go-rule bg-go-card pr-3 pl-4 md:w-[420px] lg:col-start-2 lg:row-start-1 lg:justify-self-end">
          <Icon name="search" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={tr("Search vehicle, trip or loader")}
            aria-label={tr("Search vehicle, trip or loader")}
            className="min-w-0 flex-1 bg-transparent text-base outline-none placeholder:text-go-muted"
          />
        </label>
        <div aria-label="Filter trips" className="-mx-5 flex gap-2 overflow-x-auto px-5 md:mx-0 md:px-0 lg:col-start-2 lg:row-start-2 lg:justify-self-end">
          {FILTERS.map((f) => (
            <button
              key={f.value}
              type="button"
              aria-pressed={filter === f.value}
              onClick={() => setFilter(f.value)}
              className={cx(
                "min-h-12 shrink-0 rounded-full px-[18px] text-[15px] font-medium whitespace-nowrap min-[1700px]:text-[17px] max-md:px-2.5 max-md:text-[14px]",
                filter === f.value ? "bg-go-soft text-go-on-soft" : "bg-go-surface text-go-ink",
              )}
            >
              {tr(f.label)} ({atDock.filter((t) => f.match(t, who)).length})
            </button>
          ))}
        </div>
      </div>

      {!online && (
        <Notice tone="warning" title={tr("Your trip works offline. Taking a new one needs a connection.")} live>
          {tr("Checks you record are saved on this device and sent when the connection returns.")}
        </Notice>
      )}
      {trips.error && (
        <Notice
          tone="danger"
          title={tr("Could not load tonight's trips")}
          action={
            <button type="button" onClick={trips.refresh} className="min-h-12 shrink-0 px-2 text-[13px] font-medium text-go-teal">
              {tr("Retry")}
            </button>
          }
        >
          {trips.error.message}
        </Notice>
      )}
      {!trips.data && !trips.error && <SkeletonRows label={tr("Loading trips…")} />}
      {trips.data && shown.length === 0 && (
        <div className="flex flex-col items-center gap-3 rounded-[32px] bg-go-card px-6 py-12 text-center text-go-ink shadow-go-card">
          <span className="flex size-14 items-center justify-center rounded-full bg-go-surface text-go-muted"><TruckIcon /></span>
          <p className="text-[19px] font-medium">
            {all.length === 0 ? tr("No trips tonight yet") : emptyDock ? tr("No trips at {dock} right now", { dock }) : tr("No trips match")}
          </p>
          <p className="text-[15px] text-go-muted">
            {all.length === 0
              ? tr("They appear here once the plan is published.")
              : emptyDock && busyDocks.length > 0
                ? tr("Nothing here tonight. Try {docks}.", { docks: busyDocks.join(tr(" or ")) })
                : tr("Try another dock or filter.")}
          </p>
          {emptyDock && (
            <BigButton tone="ink" fit onClick={() => setPicking(true)}>
              {tr("Change dock")}
            </BigButton>
          )}
        </div>
      )}

      {shown.length > 0 && (
        <>
          <ul className="grid gap-3 md:hidden">
            {shown.map((trip) => (
              <li key={trip.tripId}>
                <TripCard trip={trip} who={who(trip)} online={online} showDock={dock === ""} moving={trip.tripId === moving} onOpen={() => onOpen(trip.tripId)} />
              </li>
            ))}
          </ul>
          <div className="hidden md:block">
            <TripTable trips={shown} who={who} online={online} showDock={dock === ""} moving={moving} onOpen={onOpen} />
          </div>
        </>
      )}
    </div>
  );
}
