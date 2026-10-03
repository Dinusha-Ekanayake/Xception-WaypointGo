"use client";

import { useMemo, useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { ConstraintResultView, InterchangePreview, OrderView, PlanView, VehicleView } from "@shared/domain/types";
import { Menu, Pill, PrimaryButton, SecondaryButton } from "@shared/ui";
import { hhmm, temperatureLabel } from "@shared/wording";
import { typeLabel } from "../data/fleet.ts";
import { after, type TripLoad } from "../data/plan.ts";
import { stopShare, tightLine } from "../data/planViews.ts";
import CheckList from "./CheckList.tsx";
import EditTrip from "./EditTrip.tsx";
import type { PlanActions } from "./planActions.ts";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";
import Refusal from "./Refusal.tsx";

// Figma "Plan · 2 View plan", the trip opened beside the board: where it leaves
// and when it is back, how full it is, its stops in order with the share of the
// load each takes, and what a dispatcher does to it: edit its stops, or move it
// whole to another vehicle (plan:Replan, which also works on the published plan,
// where it starts a revision).

export default function PlanTrip({
  plan,
  load,
  fleet,
  orders,
  editable,
  canReplan,
  actions,
}: {
  plan: PlanView;
  load: TripLoad;
  fleet: VehicleView[];
  orders: Map<string, OrderView>;
  editable: boolean;
  canReplan: boolean;
  actions: PlanActions;
}): React.JSX.Element {
  const { trip } = load;
  const vehicle = fleet.find((v) => v.vehicleId === trip.vehicleId);
  const [mode, setMode] = useState<"view" | "edit" | "move">("view");
  const [reason, setReason] = useState("");
  const [vehicleId, setVehicleId] = useState("");
  const others = fleet.filter((v) => v.vehicleId !== trip.vehicleId);
  const warn = tightLine(load.volumePercent, load.weightPercent);
  const checks = useMemo(() => tripChecks(plan, trip.tripId), [plan, trip.tripId]);

  const preview = useResource<InterchangePreview>(
    mode === "move" && vehicleId
      ? (signal) => request<InterchangePreview>(`/api/plans/preview/interchange?trip=${encodeURIComponent(trip.tripId)}&vehicle=${encodeURIComponent(vehicleId)}`, { signal })
      : null,
    `${plan.planId}:${trip.tripId}:${vehicleId}`,
  );
  const [showChecks, setShowChecks] = useState(false);

  return (
    <section aria-label={`${trip.vehicleId} trip ${trip.tripNumber}`} className="flex w-full flex-col rounded-[24px] bg-go-card shadow-go-card lg:max-h-[calc(100dvh-330px)] lg:max-w-[360px]">
      <div className="flex flex-1 flex-col gap-3 overflow-y-auto p-5 pb-3">
      <div>
        <h2 className="flex flex-wrap items-center gap-2 text-[19px] font-medium text-go-ink">
          {`${trip.vehicleId} Trip ${trip.tripNumber}`} <Pill tone="success">{trip.brandCode}</Pill> {trip.districtName}
        </h2>
        <p className="text-xs text-go-secondary">
          {`${vehicle ? `${typeLabel(vehicle)} · ` : ""}Depart ${plan.depotCode} ${hhmm(trip.plannedDeparture)} · back ${after(trip.plannedDeparture, trip.plannedMinutes)} · ${temperatureLabel(trip.temperature).toLowerCase()}`}
        </p>
      </div>

      <Bar label="Volume" percent={load.volumePercent} />
      <Bar label="Weight" percent={load.weightPercent} />
      <div className="flex items-center justify-between gap-2 text-xs">
        <span className="font-medium text-go-warning-text">{warn ? `! ${warn}` : ""}</span>
        <Menu
          label="More about this trip"
          align="right"
          className="rounded-full bg-go-surface px-3 py-1 text-xs font-medium text-go-ink"
          items={[{ id: "checks", label: showChecks ? "Hide trip checks" : "Trip checks", hint: "Why it is Tight or Low" }]}
          onSelect={() => setShowChecks((value) => !value)}
        >
          More
        </Menu>
      </div>
      {showChecks && (checks.length > 0 ? <CheckList checks={checks} title={`Trip checks · ${trip.vehicleId} trip ${trip.tripNumber}`} /> : <p className="text-[13px] text-go-secondary">No checks are recorded for this trip.</p>)}

      {mode === "edit" ? (
        <EditTrip plan={plan} load={load} orders={orders} fleet={fleet} actions={actions} onDone={() => setMode("view")} />
      ) : (
        <Timeline plan={plan} load={load} orders={orders} vehicle={vehicle} />
      )}

      {mode === "move" && (
        <div className="flex flex-col gap-2 rounded-go-card bg-go-subtle p-3">
          <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
            Move the whole trip to
            <select value={vehicleId} onChange={(event) => setVehicleId(event.target.value)} className="rounded-go-input border border-go-rule bg-white px-3 py-2.5 text-[14px] font-normal">
              <option value="">Choose a vehicle</option>
              {others.map((v) => (
                <option key={v.vehicleId} value={v.vehicleId}>
                  {`${v.vehicleId} · ${typeLabel(v)}`}
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
              <CheckList checks={preview.data.checks} />
            </>
          )}
          <ReasonPicker label="Why is it moving?" value={reason} onChange={setReason} />
          <div className="flex gap-2">
            <PrimaryButton
              disabled={actions.busy || !reasonReady(reason) || !vehicleId || preview.data?.feasible !== true}
              onClick={() => void actions.moveTrip(trip.tripId, vehicleId, reason.trim()).then((ok) => ok && setMode("view"))}
            >
              Move the trip
            </PrimaryButton>
            <SecondaryButton onClick={() => setMode("view")}>Cancel</SecondaryButton>
          </div>
        </div>
      )}

      </div>
      {mode === "view" && (
        <div className="flex flex-col gap-2 px-5 pt-2 pb-5">
          {editable && <PrimaryButton onClick={() => setMode("edit")}>Edit this trip</PrimaryButton>}
          {canReplan && <SecondaryButton onClick={() => setMode("move")}>Move to another vehicle</SecondaryButton>}
        </div>
      )}
    </section>
  );
}

/** The checks recorded for the orders of a trip, each rule once: the vehicle's day as it was judged when they were placed. */
export function tripChecks(plan: PlanView, tripId: string): ConstraintResultView[] {
  const seen = new Map<string, ConstraintResultView>();
  for (const allocation of plan.allocations) {
    if (allocation.tripId !== tripId) continue;
    for (const check of allocation.checks) {
      const key = `${check.ruleId}|${check.reason}`;
      if (!seen.has(key)) seen.set(key, check);
    }
  }
  return [...seen.values()];
}

/** Depart, each stop with its window and its share of the load, and back at the depot. */
function Timeline({ plan, load, orders, vehicle }: { plan: PlanView; load: TripLoad; orders: Map<string, OrderView>; vehicle: VehicleView | undefined }): React.JSX.Element {
  const { trip } = load;
  return (
    <ol aria-label="Stops in order" className="relative flex flex-col before:absolute before:top-4 before:bottom-4 before:left-[67px] before:w-0.5 before:bg-go-rule">
      <Row time={hhmm(trip.plannedDeparture)} dot="hollow" title={`Depart ${plan.depotCode}`} />
      {trip.stops.map((stop) => {
        const order = orders.get(stop.orderId);
        const share = stopShare(order, vehicle);
        return (
          <Row
            key={stop.orderId}
            time={hhmm(stop.plannedArrival)}
            dot="filled"
            title={`${stop.outletId}${order ? ` · ${order.districtName}` : ""}`}
            note={stop.windowOpen && stop.windowClose ? `${hhmm(stop.windowOpen)}-${hhmm(stop.windowClose)}` : "No window"}
            aside={share === null ? undefined : `${share}%`}
          />
        );
      })}
      <Row time={after(trip.plannedDeparture, trip.plannedMinutes)} dot="hollow" title={`Back at ${plan.depotCode}`} />
    </ol>
  );
}

function Row({ time, dot, title, note, aside }: { time: string; dot: "filled" | "hollow"; title: string; note?: string; aside?: string }): React.JSX.Element {
  return (
    <li className="flex items-start gap-3 py-1.5">
      <span className="w-11 shrink-0 text-[14px] font-medium tabular-nums text-go-ink">{time}</span>
      <span aria-hidden className={`relative z-10 mt-1.5 size-2.5 shrink-0 rounded-full border-2 border-go-teal ${dot === "filled" ? "bg-go-teal" : "bg-go-card"}`} />
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[14px] font-medium text-go-ink">{title}</span>
        {note && <span className="block text-xs text-go-secondary">{note}</span>}
      </span>
      {aside && <span className="shrink-0 rounded-full bg-go-surface px-2 py-0.5 text-[11px] text-go-secondary">{aside}</span>}
    </li>
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
