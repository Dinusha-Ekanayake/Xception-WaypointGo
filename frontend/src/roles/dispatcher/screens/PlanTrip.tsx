"use client";

import { useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { InterchangePreview, OrderView, PlanView, VehicleView } from "@shared/domain/types";
import { Pill, PrimaryButton, SecondaryButton } from "@shared/ui";
import { typeLabel } from "../data/fleet.ts";
import { after, hhmm, type TripLoad } from "../data/plan.ts";
import { Checks } from "./PlanDecide.tsx";
import Refusal from "./Refusal.tsx";

// One trip, opened: its load against the vehicle, its stops in order with their
// windows, and the two things a dispatcher does to it. Taking an order off
// (plan:Defer) needs a draft; moving the trip whole to another vehicle
// (plan:Replan) also works on the published plan, where it starts a revision.

export type TripAction =
  | { kind: "defer"; orderId: string; reason: string }
  | { kind: "replan"; tripId: string; vehicleId: string; reason: string };

export default function PlanTrip({
  plan,
  load,
  fleet,
  orders,
  editable,
  canReplan,
  busy,
  onAction,
}: {
  plan: PlanView;
  load: TripLoad;
  fleet: VehicleView[];
  orders: Map<string, OrderView>;
  editable: boolean;
  canReplan: boolean;
  busy: boolean;
  onAction: (action: TripAction) => void;
}): React.JSX.Element {
  const { trip } = load;
  const [mode, setMode] = useState<{ kind: "defer"; orderId: string } | { kind: "replan" } | null>(null);
  const [reason, setReason] = useState("");
  const [vehicleId, setVehicleId] = useState("");
  const others = fleet.filter((vehicle) => vehicle.vehicleId !== trip.vehicleId);

  const preview = useResource<InterchangePreview>(
    mode?.kind === "replan" && vehicleId
      ? (signal) => request<InterchangePreview>(`/api/plans/preview/interchange?trip=${encodeURIComponent(trip.tripId)}&vehicle=${encodeURIComponent(vehicleId)}`, { signal })
      : null,
    `${plan.planId}:${trip.tripId}:${vehicleId}`,
  );

  const start = (next: typeof mode) => {
    setMode(next);
    setReason("");
  };

  return (
    <section aria-label={`${trip.vehicleId} trip ${trip.tripNumber}`} className="flex w-full flex-col gap-3 rounded-[24px] bg-white p-5 shadow-go-card lg:max-w-[360px]">
      <div>
        <h2 className="flex flex-wrap items-center gap-2 text-[19px] font-medium text-go-ink">
          {trip.vehicleId} Trip {trip.tripNumber} <Pill tone="success">{trip.brandCode}</Pill> {trip.districtName}
        </h2>
        <p className="text-xs text-go-secondary">
          Departs {hhmm(trip.plannedDeparture)} · back {after(trip.plannedDeparture, trip.plannedMinutes)} · {trip.temperature}
        </p>
      </div>

      <Bar label="Volume" percent={load.volumePercent} />
      <Bar label="Weight" percent={load.weightPercent} />

      <ol className="flex flex-col">
        {trip.stops.map((stop) => {
          const order = orders.get(stop.orderId);
          return (
            <li key={stop.orderId} className="flex items-start gap-3 border-t border-go-rule py-2">
              <span className="w-11 shrink-0 text-[14px] font-medium tabular-nums text-go-ink">{hhmm(stop.plannedArrival)}</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[14px] font-medium text-go-ink">
                  {stop.outletId}
                  {order ? ` · ${order.orderRef}` : ""}
                </span>
                <span className="block text-xs text-go-secondary">
                  Window {hhmm(stop.windowOpen)} to {hhmm(stop.windowClose)}
                </span>
              </span>
              {editable && (
                <button type="button" onClick={() => start({ kind: "defer", orderId: stop.orderId })} className="shrink-0 text-xs font-medium text-go-teal">
                  Take off
                </button>
              )}
            </li>
          );
        })}
      </ol>

      {mode?.kind === "defer" && (
        <div className="flex flex-col gap-2 rounded-go-card bg-go-subtle p-3">
          <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
            Why is {orders.get(mode.orderId)?.orderRef ?? "this order"} deferred?
            <input value={reason} maxLength={300} onChange={(event) => setReason(event.target.value)} className="rounded-go-input border border-go-rule bg-white px-3 py-2.5 text-[14px] font-normal outline-none focus:border-go-teal" />
          </label>
          <div className="flex gap-2">
            <PrimaryButton disabled={busy || !reason.trim()} onClick={() => onAction({ kind: "defer", orderId: mode.orderId, reason: reason.trim() })}>
              Defer the order
            </PrimaryButton>
            <SecondaryButton onClick={() => setMode(null)}>Cancel</SecondaryButton>
          </div>
        </div>
      )}

      {mode?.kind === "replan" && (
        <div className="flex flex-col gap-2 rounded-go-card bg-go-subtle p-3">
          <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
            Move the whole trip to
            <select value={vehicleId} onChange={(event) => setVehicleId(event.target.value)} className="rounded-go-input border border-go-rule bg-white px-3 py-2.5 text-[14px] font-normal">
              <option value="">Choose a vehicle</option>
              {others.map((vehicle) => (
                <option key={vehicle.vehicleId} value={vehicle.vehicleId}>
                  {vehicle.vehicleId} · {typeLabel(vehicle)}
                </option>
              ))}
            </select>
          </label>
          {preview.error && <Refusal error={preview.error} what="the check for this vehicle" />}
          {preview.data && vehicleId && (
            <>
              <p role="status" className={`text-[13px] font-medium ${preview.data.feasible ? "text-go-success" : "text-go-danger-strong"}`}>
                {preview.data.feasible ? `${vehicleId} can take this trip.` : `${vehicleId} cannot take this trip.`}
              </p>
              <Checks checks={preview.data.checks} />
            </>
          )}
          <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
            Why is it moving?
            <input value={reason} maxLength={300} onChange={(event) => setReason(event.target.value)} className="rounded-go-input border border-go-rule bg-white px-3 py-2.5 text-[14px] font-normal outline-none focus:border-go-teal" />
          </label>
          <div className="flex gap-2">
            <PrimaryButton
              disabled={busy || !reason.trim() || !vehicleId || preview.data?.feasible !== true}
              onClick={() => onAction({ kind: "replan", tripId: trip.tripId, vehicleId, reason: reason.trim() })}
            >
              Move the trip
            </PrimaryButton>
            <SecondaryButton onClick={() => setMode(null)}>Cancel</SecondaryButton>
          </div>
        </div>
      )}

      {canReplan && mode === null && <SecondaryButton onClick={() => start({ kind: "replan" })}>Move to another vehicle</SecondaryButton>}
    </section>
  );
}

function Bar({ label, percent }: { label: string; percent: number | null }): React.JSX.Element {
  const shown = percent === null ? 0 : Math.min(100, percent);
  const tight = (percent ?? 0) >= 90;
  return (
    <div className="flex items-center gap-3 text-xs text-go-secondary">
      <span className="w-14">{label}</span>
      <span
        role="meter"
        aria-label={`${label} used`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent ?? undefined}
        aria-valuetext={percent === null ? "capacity unknown" : `${percent}%`}
        className="h-1.5 flex-1 overflow-hidden rounded-full bg-go-surface"
      >
        <span className={`block h-full rounded-full ${tight ? "bg-go-warning" : "bg-go-teal"}`} style={{ width: `${shown}%` }} />
      </span>
      <span className={`w-10 text-right font-medium tabular-nums ${tight ? "text-go-warning-text" : "text-go-ink"}`}>{percent === null ? "n/a" : `${percent}%`}</span>
    </div>
  );
}
