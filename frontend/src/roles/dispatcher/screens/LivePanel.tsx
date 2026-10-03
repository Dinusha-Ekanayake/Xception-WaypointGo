"use client";

import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { DeliveryRecordView } from "@shared/domain/types";
import { cx } from "@shared/ui";
import { depotClock, etaOf, limits, round1, runTitle, silentMinutes, type Run } from "../data/liveDesk.ts";
import { useFuel } from "../data/useDay.ts";
import { Action, NOT_AVAILABLE_NOTE, STATUS } from "./LiveParts.tsx";

// Figma "05 Live · map, selected" (189:21358 at risk, 189:21746 offline): the
// vehicle's state, its trip from the depot to the stop ahead, its limits and
// what to do next. Messages to the store and the driver are drawn as designed
// but disabled until the backend can send them.

/** The driver on the vehicle's current delivery, as Execution names them. */
export function useDriver(run: Run | null): { name: string | null } {
  const stop = run ? (run.day.current ?? run.day.stops[run.day.stops.length - 1]) : null;
  const res = useResource(
    stop ? (signal: AbortSignal) => request<DeliveryRecordView>(`/api/execution/deliveries/${encodeURIComponent(stop.deliveryId)}`, { signal }) : null,
    `delivery|${stop?.deliveryId ?? ""}`,
  );
  const driver = res.data?.driver ?? null;
  return { name: driver ? `${driver.displayName}${driver.employeeCode ? ` (${driver.employeeCode})` : ""}` : null };
}

export function driverLine(run: Run, driver: string | null): string {
  return [driver, run.trip ? `Trip ${run.trip.tripNumber} of ${run.trip.tripsForVehicle}` : null, run.trip?.releasedAt ? `left ${depotClock(run.trip.releasedAt)}` : null]
    .filter(Boolean)
    .join(" · ");
}

export default function LivePanel({
  run,
  date,
  depotName,
  now,
  trailPoints,
  onBack,
  onOpenTrip,
}: {
  run: Run;
  date: string;
  depotName: string;
  now: Date;
  trailPoints: number | null;
  onBack: () => void;
  onOpenTrip: () => void;
}): React.JSX.Element {
  const { name } = useDriver(run);
  const fuel = useFuel(run.day.vehicleId, date);
  const next = run.day.current;
  const status = STATUS[run.status];
  const quiet = silentMinutes(run, now);
  const facts = limits(run, fuel.data ?? null, now);
  const load = facts.find((f) => f.label === "Load");
  const fuelUse = facts.find((f) => f.label === "Fuel quota");
  const stopsLeft = run.day.stops.length - run.day.done;

  const box =
    run.status === "offline"
      ? { tint: "bg-go-surface", head: `Offline${quiet !== null ? ` · ${quiet} min` : ""}`, line: `Last seen ${run.position ? depotClock(run.position.recordedAt) : "unknown"} · syncs when back online`, ink: "text-go-ink" }
      : run.status === "at-risk" || run.status === "late"
        ? { tint: "bg-go-warning-tint", head: `${status.label}${next ? ` · ${next.outletId}` : ""}`, line: next ? `Expected ${etaOf(next)} · window ${next.windowClose.slice(0, 5)}` : "", ink: "text-go-warning-text" }
        : run.status === "returning"
          ? { tint: "bg-go-info-tint", head: "Returning", line: `All ${run.day.stops.length} stops closed · back to ${depotName}`, ink: "text-go-info" }
          : { tint: "bg-go-success-tint", head: `On time${next ? ` · ${next.outletId}` : ""}`, line: next ? `Expected ${etaOf(next)} · window ${next.windowClose.slice(0, 5)}` : "", ink: "text-go-teal" };

  return (
    <div className="flex flex-col gap-3.5">
      <div className="flex items-center justify-between">
        <button type="button" onClick={onBack} className="flex items-center gap-2 text-[16px] text-go-ink">
          <span aria-hidden className="text-[20px] leading-none">‹</span> Back
        </button>
        <button type="button" onClick={onOpenTrip} className="rounded-full bg-go-surface px-3 py-1.5 text-[13px] font-medium text-go-ink">
          Open trip
        </button>
      </div>
      <div>
        <h2 className="text-[21px] leading-tight font-medium text-go-ink">{runTitle(run)}</h2>
        <p className="mt-1 text-[12.5px] text-go-secondary">{driverLine(run, name) || `${run.day.done} of ${run.day.stops.length} stops done`}</p>
      </div>

      <div className={cx("flex flex-col gap-1.5 rounded-2xl px-3.5 py-3", box.tint)}>
        <p className={cx("flex items-center gap-2 text-[14.5px] font-semibold", box.ink)}>
          {run.status !== "on-time" && run.status !== "returning" && <span aria-hidden>⚠</span>}
          {box.head}
        </p>
        {box.line && <p className="text-[12.5px] text-go-ink">{box.line}</p>}
        {run.status === "offline" && next && (
          <span className="w-fit rounded-full bg-white px-2 py-1 text-[11.5px] text-go-ink">Next: {next.outletId} · {etaOf(next)}</span>
        )}
      </div>

      <section aria-label="Trip" className="flex flex-col gap-2">
        <h3 className="text-[13.5px] font-medium text-go-ink">Trip</h3>
        <ol className="flex flex-col gap-2.5">
          <Step dot="bg-go-teal" title={depotName} sub={[run.trip?.releasedAt ? `Departed ${depotClock(run.trip.releasedAt)}` : "Released", run.trip ? `${round1(Number(run.trip.volumeM3))} m³ loaded` : null].filter(Boolean).join(" · ")} />
          {run.position && (
            <Step dot={run.status === "offline" ? "bg-[#a3acaa]" : "bg-[#c08a3e]"} title="On the road" sub={`${run.status === "offline" ? "Last seen" : "Last update"} ${depotClock(run.position.recordedAt)}`} />
          )}
          {next && (
            <Step
              ring={run.status === "on-time" ? "border-go-teal" : "border-go-danger"}
              title={`${next.outletId}${stopsLeft > 1 ? ` · then ${stopsLeft - 1} more` : ""}`}
              sub={`${next.mallOutlet ? "Mall" : "Street"} · window ${next.windowOpen.slice(0, 5)}-${next.windowClose.slice(0, 5)} · expected ${etaOf(next)}`}
            />
          )}
        </ol>
      </section>

      <div className="grid grid-cols-3 gap-2">
        <Tile label="Load" value={load ? load.value : run.trip ? `${round1(Number(run.trip.volumeM3))} m³` : "-"} />
        <Tile label="Stops left" value={`${stopsLeft} of ${run.day.stops.length}`} />
        <Tile label="Fuel quota" value={fuelUse ? fuelUse.value : fuel.loading ? "…" : "-"} />
      </div>

      {(run.status === "at-risk" || run.status === "late" || run.status === "offline") && next && (
        <section aria-label="Recommended" className="flex flex-col gap-2">
          <h3 className="text-[13.5px] font-medium text-go-ink">Recommended</h3>
          <div className="flex items-start justify-between gap-2 rounded-2xl bg-go-success-tint px-3.5 py-3">
            <span>
              <span className="block text-[14px] font-medium text-go-ink">{run.status === "offline" ? "Send voice note" : "Notify store"}</span>
              <span className="block text-[12px] text-go-secondary">{run.status === "offline" ? "Plays when signal returns" : `Send expected arrival ${etaOf(next)} to ${next.outletId}`}</span>
            </span>
            <span className="rounded-full bg-white px-2 py-0.5 text-[11px] font-medium text-go-teal">Best</span>
          </div>
          <div className="rounded-2xl bg-go-surface px-3.5 py-3">
            <span className="block text-[14px] font-medium text-go-ink">{run.status === "offline" ? "If the phone stays quiet" : "If the window is missed"}</span>
            <span className="block text-[12px] text-go-secondary">
              {run.status === "offline" ? `Call ${next.outletId} to confirm the delivery` : "Deferred automatically · missed window · first on the next delivery day"}
            </span>
          </div>
          <div className="mt-1 flex flex-wrap gap-2">
            {run.status === "offline" ? (
              <>
                <Action unavailable>Call store</Action>
                <Action primary unavailable>Send voice note</Action>
              </>
            ) : (
              <>
                <Action unavailable>Voice message</Action>
                <Action primary unavailable>Notify store</Action>
              </>
            )}
          </div>
          <p className="text-[11px] text-go-secondary">{NOT_AVAILABLE_NOTE}</p>
        </section>
      )}

      {!run.position && <p className="text-[12px] text-go-secondary">No live location · stops only</p>}
      {trailPoints !== null && <p className="text-[12px] text-go-secondary">Location trail · {trailPoints} points drawn on the map</p>}
    </div>
  );
}

function Step({ dot, ring, title, sub }: { dot?: string; ring?: string; title: string; sub: string }): React.JSX.Element {
  return (
    <li className="flex gap-3">
      <span aria-hidden className={cx("mt-1 size-3 shrink-0 rounded-full", dot, ring && cx("border-2 bg-white", ring))} />
      <span className="flex flex-col">
        <span className="text-[14px] text-go-ink">{title}</span>
        <span className="text-[12px] text-go-secondary">{sub}</span>
      </span>
    </li>
  );
}

function Tile({ label, value }: { label: string; value: string }): React.JSX.Element {
  return (
    <div className="flex flex-col gap-0.5 rounded-xl bg-go-subtle px-2.5 py-2">
      <span className="text-[11px] text-go-secondary">{label}</span>
      <span className="text-[13.5px] font-medium text-go-ink">{value}</span>
    </div>
  );
}
