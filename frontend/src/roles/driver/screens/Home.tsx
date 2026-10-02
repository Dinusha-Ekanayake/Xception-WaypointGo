"use client";

import type { VehicleView } from "@shared/domain/types";
import { VehicleStatuses, type ReportedVehicleStatus } from "@shared/domain/types";
import { clock, isFinished, nextStop, summarize, type Stop } from "../data/run.ts";
import { ActionButton, Banner, Panel, SoftButton, Tag, input } from "../ui.tsx";

// Figma "Driver: Home", "Driver: Home (No vehicle)" and "Driver: Home: after
// run". The design's notification list is the inbox of #14 and is not shown
// until that exists; today's stops take its place.

const STATUS_LABEL: Record<ReportedVehicleStatus, string> = {
  available: "Available",
  on_trip: "On trip",
  at_workshop: "At workshop",
  fault: "Fault",
};

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? parts[parts.length - 1]![0] : "")).toUpperCase() || "D";
}

function vehicleKind(vehicle: VehicleView): string {
  if (vehicle.refrigerated) return "Refrigerated";
  return vehicle.van ? "Van" : "Truck";
}

export default function Home({
  displayName,
  depot,
  vehicle,
  stops,
  unavailable,
  online,
  vehicleStatus,
  onVehicleStatus,
  onOpenRun,
  onOpenStop,
  onProblem,
}: {
  displayName: string;
  depot: string;
  vehicle: VehicleView | null;
  stops: Stop[];
  /** The server could not be asked and this phone holds no copy of today. */
  unavailable: boolean;
  online: boolean;
  vehicleStatus: ReportedVehicleStatus | null;
  onVehicleStatus: (status: ReportedVehicleStatus) => void;
  onOpenRun: () => void;
  onOpenStop: (stop: Stop) => void;
  /** The road or the vehicle, when no stop is open. */
  onProblem: () => void;
}): React.JSX.Element {
  const summary = summarize(stops);
  const next = nextStop(stops);
  const started = stops.some((stop) => stop.startedAt !== null || isFinished(stop));
  return (
    <div className="flex flex-col gap-5 px-5 pb-8 pt-2">
      <Panel label="You">
        <div className="flex items-center gap-4">
          <span aria-hidden="true" className="flex size-14 shrink-0 items-center justify-center rounded-full bg-go-soft text-[19px] font-medium text-go-on-soft">
            {initials(displayName)}
          </span>
          <div className="min-w-0">
            <p className="truncate text-[26px] font-medium leading-tight text-go-ink">{displayName}</p>
            <p className="text-[14px] text-go-muted">Driver{depot ? ` · ${depot} depot` : ""}</p>
          </div>
        </div>

        {vehicle ? (
          <div className="mt-5 rounded-[22px] bg-go-surface p-5">
            <div className="flex flex-wrap items-end justify-between gap-2">
              <div>
                <p className="text-[13px] text-go-muted">Vehicle</p>
                <p className="whitespace-nowrap text-[30px] font-semibold leading-tight text-go-ink">{vehicle.vehicleId}</p>
              </div>
              <Tag>{vehicleKind(vehicle)}</Tag>
            </div>
            <label className="mt-4 flex flex-col gap-1.5 text-[14px] text-go-muted">
              Vehicle status
              <select
                className={input}
                value={vehicleStatus ?? ""}
                onChange={(event) => onVehicleStatus(event.target.value as ReportedVehicleStatus)}
              >
                <option value="" disabled>
                  Report the vehicle's status
                </option>
                {VehicleStatuses.filter((status) => status !== "fault").map((status) => (
                  <option key={status} value={status}>
                    {STATUS_LABEL[status]}
                  </option>
                ))}
              </select>
            </label>
          </div>
        ) : (
          !unavailable && (
            <div className="mt-5">
              <Banner tone="neutral" title="No vehicle assigned today">
                Dispatch assigns your vehicle. If you expected one, ask them.
              </Banner>
            </div>
          )
        )}

        {vehicle && stops.length > 0 && (
          <ActionButton className="mt-5" onClick={onOpenRun}>
            {next ? (started ? "Continue run" : "Start run") : "Run summary"}
          </ActionButton>
        )}
        {vehicle && (
          <SoftButton className="mt-3" onClick={onProblem}>
            Report problem
          </SoftButton>
        )}
      </Panel>

      {unavailable && (
        <Banner tone="warn" title={online ? "Waypoint is not answering" : "This phone is offline"} live>
          Today's run has not been downloaded to this phone yet. It appears here as soon as the connection is back.
        </Banner>
      )}

      {vehicle && !unavailable && stops.length === 0 && (
        <Panel label="Today">
          <p className="text-[24px] font-medium text-go-ink">No run planned</p>
          <p className="mt-1 text-[15px] text-go-muted">
            Nothing has left the dock for {vehicle.vehicleId} today. Your stops appear here when the loader releases the vehicle.
          </p>
        </Panel>
      )}

      {stops.length > 0 && (
        <section aria-label="Today's stops" className="flex flex-col gap-3">
          <div className="flex items-center justify-between px-1">
            <h2 className="text-[17px] font-medium text-go-ink">Today's stops</h2>
            <Tag tone={summary.finished === summary.total ? "good" : "neutral"}>
              {summary.finished} of {summary.total} done
            </Tag>
          </div>
          {stops.map((stop) => (
            <button
              key={stop.deliveryId}
              type="button"
              onClick={() => onOpenStop(stop)}
              className="flex min-h-16 items-center gap-3 rounded-[22px] bg-go-card px-5 py-3 text-left shadow-go-card"
            >
              <span className="w-7 shrink-0 text-[15px] tabular-nums text-go-muted">{String(stop.sequence).padStart(2, "0")}</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[17px] font-medium text-go-ink">{stop.outletId}</span>
                <span className="block text-[13px] text-go-muted">
                  Window {clock(stop.windowOpen)} to {clock(stop.windowClose)} · {stop.itemCount} units
                </span>
              </span>
              <StopState stop={stop} />
            </button>
          ))}
        </section>
      )}
    </div>
  );
}

export function StopState({ stop }: { stop: Stop }): React.JSX.Element {
  const waiting = stop.waiting ? " · on phone" : "";
  switch (stop.outcome) {
    case "DELIVERED":
      return <Tag tone="good">Delivered{waiting}</Tag>;
    case "PARTIAL":
      return <Tag tone="warn">Partial{waiting}</Tag>;
    case "FAILED":
      return <Tag tone="bad">Not delivered{waiting}</Tag>;
    case "SKIPPED":
      return <Tag>Replanned</Tag>;
    case "ARRIVED":
      return <Tag tone="warn">At the stop{waiting}</Tag>;
    default:
      return <Tag>{stop.startedAt ? `On the way${waiting}` : "To do"}</Tag>;
  }
}
