"use client";

import { useState } from "react";
import type { OrderView, PlanView, VehicleView } from "@shared/domain/types";
import { KpiCard, Pill } from "@shared/ui";
import { typeLabel } from "../data/fleet.ts";
import { board, summarise, type TripLoad } from "../data/plan.ts";
import PlanTrip, { type TripAction } from "./PlanTrip.tsx";

// Figma "Plan · 2 View plan": every vehicle's two trips side by side, and one
// trip opened beside them. The design's late-risk percentages wait on the
// predictor (#16) and are not shown.

export default function PlanBoard({
  plan,
  fleet,
  orders,
  editable,
  canReplan,
  busy,
  onAction,
}: {
  plan: PlanView;
  fleet: VehicleView[];
  orders: Map<string, OrderView>;
  /** A draft, online: orders can be taken off a trip. */
  editable: boolean;
  /** Online: a trip can be moved to another vehicle, on a draft or on the published plan. */
  canReplan: boolean;
  busy: boolean;
  onAction: (action: TripAction) => void;
}): React.JSX.Element {
  const rows = board(plan, fleet);
  const summary = summarise(plan, fleet);
  const [openId, setOpenId] = useState<string | null>(null);
  const loads = rows.flatMap((row) => row.trips).filter((load): load is TripLoad => load !== null);
  const open = loads.find((load) => load.trip.tripId === openId) ?? loads[0] ?? null;

  return (
    <>
      <div className="flex w-full gap-3.5 max-md:flex-col">
        <KpiCard label="Orders" value={`${summary.served} of ${summary.orders}`} note={`planned · ${summary.deferred} deferred${summary.unservable ? ` · ${summary.unservable} cannot be served` : ""}`} />
        <KpiCard label="Vehicles" value={summary.vehiclesUsed} note={`in use · ${summary.vehiclesIdle} idle`} />
        <KpiCard label="Trips" value={summary.trips} note={summary.tightTrips ? `${summary.tightTrips} over 90% full` : "none over 90% full"} valueClassName={summary.tightTrips ? "text-go-warning-text" : "text-go-ink"} />
      </div>

      <div className="flex min-h-0 w-full flex-1 gap-[18px] max-lg:flex-col">
        <section aria-label="Trips by vehicle" className="flex min-w-0 flex-1 flex-col rounded-[24px] bg-white px-5 pt-4 pb-3 shadow-go-card">
          {rows.length === 0 ? (
            <p className="py-8 text-center text-[13px] text-go-secondary">This plan has no trips: no order could be placed.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[560px] border-separate border-spacing-y-2 text-left">
                <thead>
                  <tr className="text-[11px] tracking-wide text-go-secondary uppercase">
                    <th scope="col" className="w-[150px] pb-1 font-medium">Vehicle</th>
                    <th scope="col" className="pb-1 font-medium">Trip 1</th>
                    <th scope="col" className="pb-1 font-medium">Trip 2</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.vehicleId} className="align-top">
                      <th scope="row" className="pr-2 text-left font-normal">
                        <span className="block text-[15px] font-medium text-go-ink">{row.vehicleId}</span>
                        <span className="block text-xs text-go-secondary">{row.vehicle ? typeLabel(row.vehicle) : "No longer available"}</span>
                      </th>
                      {row.trips.map((load, index) => (
                        <td key={index} className="pr-2">
                          {load ? (
                            <TripCell load={load} active={open?.trip.tripId === load.trip.tripId} onOpen={() => setOpenId(load.trip.tripId)} />
                          ) : (
                            <span className="flex min-h-[58px] items-center justify-center rounded-go-card border border-dashed border-go-rule text-xs text-go-secondary">Free</span>
                          )}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {open && (
          <PlanTrip
            key={open.trip.tripId}
            plan={plan}
            load={open}
            fleet={fleet}
            orders={orders}
            editable={editable}
            canReplan={canReplan}
            busy={busy}
            onAction={onAction}
          />
        )}
      </div>
    </>
  );
}

function TripCell({ load, active, onOpen }: { load: TripLoad; active: boolean; onOpen: () => void }): React.JSX.Element {
  const { trip } = load;
  return (
    <button
      type="button"
      aria-pressed={active}
      aria-label={`${trip.vehicleId} trip ${trip.tripNumber}: ${trip.brandCode} ${trip.districtName}, ${trip.stops.length} stops`}
      onClick={onOpen}
      className={`flex min-h-[58px] w-full flex-col gap-1 rounded-go-card border px-3 py-2 text-left ${active ? "border-go-teal bg-go-success-tint" : "border-go-rule"}`}
    >
      <span className="flex items-center gap-2">
        <Pill tone="success">{trip.brandCode}</Pill>
        <span className="truncate text-[14px] font-medium text-go-ink">{trip.districtName}</span>
        {load.tight && <Pill tone="warning">Tight</Pill>}
      </span>
      <span className="text-xs text-go-secondary">
        {trip.temperature === "chilled" ? "Chilled" : "Ambient"} · {trip.stops.length} {trip.stops.length === 1 ? "stop" : "stops"}
      </span>
    </button>
  );
}
