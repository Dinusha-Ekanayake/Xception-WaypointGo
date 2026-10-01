"use client";

import type { OutletView } from "@shared/domain/types";
import { clock, isFinished, lateMinutes, type Stop } from "../data/run.ts";
import { ActionButton, Panel, SoftButton, Tag } from "../ui.tsx";

// Figma "Route: Next stop": the stops already done, then the one to drive to.
// The design's Map and Call buttons are left out: outlets have no coordinates
// (A-11) and the data holds no phone numbers.

function place(outlet: OutletView | undefined): string {
  return outlet ? `${outlet.districtName} · ${outlet.brandCode}` : "";
}

function access(outlet: OutletView | undefined): string | null {
  if (!outlet) return null;
  const dock = outlet.dockType.replaceAll("_", " ");
  if (outlet.parkingConstraint === "mall_dock") return `Mall dock. Deliveries are taken only inside the window.`;
  if (outlet.parkingConstraint === "van_only") return `${dock[0]!.toUpperCase()}${dock.slice(1)} · vans only.`;
  return `${dock[0]!.toUpperCase()}${dock.slice(1)}.`;
}

export default function Route({
  date,
  stops,
  next,
  outlets,
  busy,
  now,
  onArrived,
  onReport,
  onProblem,
}: {
  date: string;
  stops: Stop[];
  next: Stop;
  outlets: Record<string, OutletView>;
  busy: boolean;
  now: Date;
  onArrived: (stop: Stop) => void;
  /** The stop is already reached: open its delivery report. */
  onReport: (stop: Stop) => void;
  onProblem: (stop: Stop) => void;
}): React.JSX.Element {
  const done = stops.filter(isFinished);
  const outlet = outlets[next.outletId];
  const lateNow = next.outcome === "ARRIVED" ? next.lateMinutes ?? 0 : lateMinutes(date, next.windowClose, now);
  const eta = next.expectedArrival ? clock(next.expectedArrival) : clock(next.plannedArrival);
  return (
    <div className="flex flex-col gap-4 px-5 pb-8 pt-2">
      {done.length > 0 && (
        <ul aria-label="Stops done" className="flex flex-col">
          {done.map((stop) => (
            <li key={stop.deliveryId} className="flex min-h-10 items-center gap-3 border-t border-go-rule text-[15px] text-go-ink">
              <span className="w-6 tabular-nums text-go-muted">{String(stop.sequence).padStart(2, "0")}</span>
              <span className="flex-1 truncate">{stop.outletId}</span>
              <span
                aria-label={stop.outcome === "DELIVERED" ? "Delivered" : stop.outcome === "PARTIAL" ? "Partly delivered" : stop.outcome === "SKIPPED" ? "Replanned" : "Not delivered"}
                className={`size-5 rounded-full ${
                  stop.outcome === "DELIVERED" ? "bg-go-signal" : stop.outcome === "PARTIAL" ? "bg-go-warning" : stop.outcome === "SKIPPED" ? "bg-go-divider" : "bg-go-danger"
                }`}
              />
            </li>
          ))}
        </ul>
      )}

      <Panel label="Next stop">
        <div className="flex items-center justify-between gap-3">
          <p className="text-[15px] text-go-muted">
            Next · stop {String(next.sequence).padStart(2, "0")} of {String(stops.length).padStart(2, "0")}
          </p>
          {next.mallOutlet && <Tag tone="warn">Mall window</Tag>}
        </div>
        <h1 className="mt-1 text-[40px] font-medium leading-[1.05] text-go-ink">{next.outletId}</h1>
        {outlet && <p className="mt-1 text-[15px] text-go-ink">{place(outlet)}</p>}

        <div className="mt-5 grid grid-cols-2 gap-3">
          <div className="rounded-[22px] bg-go-surface px-3 py-4 text-center">
            <p className="text-[13px] text-go-ink">{next.expectedArrival ? "Expected" : "Planned"}</p>
            <p className="text-[38px] font-semibold leading-tight tabular-nums text-go-ink">{eta}</p>
            <p className="text-[13px] text-go-muted">Window opens {clock(next.windowOpen)}</p>
          </div>
          <div className="rounded-[22px] border border-go-rule px-3 py-4 text-center">
            <p className="text-[13px] text-go-ink">Window closes</p>
            <p className="text-[38px] font-semibold leading-tight tabular-nums text-go-ink">{clock(next.windowClose)}</p>
            <p className={`text-[13px] font-medium ${lateNow > 0 ? "text-go-danger-strong" : "text-go-success"}`}>
              {lateNow > 0 ? `${lateNow} min late` : "On time"}
            </p>
          </div>
        </div>

        <p className="mt-5 border-t border-go-rule pt-4 text-[15px] text-go-ink">{next.itemCount} units to deliver</p>

        <div className="mt-4 flex flex-col gap-3">
          <SoftButton onClick={() => onProblem(next)}>Report problem</SoftButton>
          {next.outcome === "ARRIVED" ? (
            <ActionButton onClick={() => onReport(next)}>Open delivery report</ActionButton>
          ) : (
            <ActionButton disabled={busy} onClick={() => onArrived(next)}>
              I've arrived
            </ActionButton>
          )}
        </div>
        {access(outlet) && <p className="mt-4 text-[15px] text-go-ink">{access(outlet)}</p>}
      </Panel>
    </div>
  );
}
