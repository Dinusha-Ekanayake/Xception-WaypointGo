"use client";

import { useMemo, useState } from "react";
import type { OrderView, PlanView, VehicleView } from "@shared/domain/types";
import { Pill, cx } from "@shared/ui";
import { capacityLabel, typeLabel } from "../data/fleet.ts";
import { board, summarise, type TripLoad } from "../data/plan.ts";
import {
  NO_FILTER,
  addedByHand,
  brandsOf,
  filterBoard,
  freeLabel,
  lateRisk,
  lowLoad,
  riskLabel,
  riskTone,
  scoredByEstimate,
  stopRisks,
  tripMatches,
  tripRisks,
  type StopRisk,
  type BoardFilter as Filter,
} from "../data/planViews.ts";
import { usePredictions } from "../data/usePlanReads.ts";
import BoardFilter from "./BoardFilter.tsx";
import DeferredColumn from "./DeferredColumn.tsx";
import type { PlanActions } from "./planActions.ts";
import PlanTrip from "./PlanTrip.tsx";

// Figma "Plan · 2 View plan": what the day adds up to, the orders it leaves out,
// every vehicle's two trips side by side, and one trip opened beside them with
// its timeline. Late risk is read from the scoring of a published plan; a draft
// is never scored, and the card says so instead of showing a zero.

export default function PlanBoard({
  plan,
  fleet,
  orders,
  editable,
  canReplan,
  published,
  actions,
  onOpenDecision,
}: {
  plan: PlanView;
  fleet: VehicleView[];
  orders: Map<string, OrderView>;
  /** A draft, online: orders can be taken off a trip and its stops reordered. */
  editable: boolean;
  /** Online: a trip can be moved to another vehicle, on a draft or on the published plan. */
  canReplan: boolean;
  /** The plan is the published one, so it has been scored. */
  published: boolean;
  actions: PlanActions;
  onOpenDecision: (orderId: string) => void;
}): React.JSX.Element {
  const rows = board(plan, fleet);
  const summary = summarise(plan, fleet);
  const low = lowLoad(rows);
  const [filter, setFilter] = useState<Filter>(NO_FILTER);
  const [openId, setOpenId] = useState<string | null>(null);
  const predictions = usePredictions(published ? plan.planId : null);
  const risk = predictions.data ? lateRisk(plan, predictions.data) : null;
  // Per trip and per stop (#119): the board tags risky trips, the open trip tags every stop.
  const trips = useMemo(() => (predictions.data ? tripRisks(predictions.data) : null), [predictions.data]);
  const stops = useMemo(() => (predictions.data ? stopRisks(predictions.data) : null), [predictions.data]);
  const estimate = predictions.data ? scoredByEstimate(predictions.data) : false;
  const shown = useMemo(() => filterBoard(rows, filter), [rows, filter]);
  const loads = rows.flatMap((row) => row.trips).filter((load): load is TripLoad => load !== null);
  const open = loads.find((load) => load.trip.tripId === openId) ?? loads[0] ?? null;

  return (
    <>
      <div className="flex w-full gap-3.5 max-lg:flex-wrap">
        <Kpi label="Orders" tag={summary.deferred > 0 ? `${summary.deferred} deferred` : undefined} tone="danger" value={summary.served} note={`planned of ${summary.orders}${summary.unservable ? ` · ${summary.unservable} cannot be served` : ""}`} />
        <Kpi label="Vehicles" value={summary.vehiclesUsed} note={`in use · ${summary.vehiclesIdle} idle`} />
        <Kpi
          label="Low-load trips"
          tag="under 70% full"
          tone="warning"
          value={low.trips}
          note={low.trips > 0 ? `${low.trips === 1 ? "trip" : "trips"} · ${low.spareM3} m³ spare` : "trips · none to merge"}
        />
        <Kpi
          label="Late risk"
          tag={risk ? "over 35% chance" : undefined}
          tone="danger"
          value={risk ? `${risk.high} high` : "Not scored"}
          note={risk ? `${risk.low} low${estimate ? " · estimated" : ""}` : published ? (predictions.loading ? "Reading the scoring…" : "The time predictor has not scored this plan") : "A draft is scored once it is published"}
        />
      </div>

      <div className="flex min-h-0 w-full flex-1 gap-[18px] max-lg:flex-col">
        <DeferredColumn plan={plan} orders={orders} onOpen={onOpenDecision} />

        <section aria-label="Trips by vehicle" className="flex min-w-0 flex-1 flex-col rounded-[24px] bg-go-card px-5 pt-4 pb-3 shadow-go-card">
          <BoardFilter filter={filter} brands={brandsOf(plan)} onChange={setFilter} />
          {shown.length === 0 ? (
            <p className="py-8 text-center text-[13px] text-go-secondary">
              {rows.length === 0 ? "This plan has no trips: no order could be placed." : "No trip matches this filter."}
            </p>
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
                  {shown.map((row) => (
                    <tr key={row.vehicleId} className="align-top">
                      <th scope="row" className="pr-2 text-left font-normal">
                        <span className="block text-[15px] font-medium text-go-ink">{row.vehicleId}</span>
                        <span className="block text-xs text-go-secondary">
                          {row.vehicle ? `${typeLabel(row.vehicle)} · ${capacityLabel(row.vehicle).split(" · ")[0]}` : "No longer available"}
                        </span>
                      </th>
                      {row.trips.map((load, index) => (
                        <td key={index} className="pr-2">
                          {load ? (
                            <TripCell
                              load={load}
                              added={addedByHand(plan, load.trip)}
                              risk={trips?.get(load.trip.tripId)}
                              dim={!tripMatches(load.trip, filter)}
                              active={open?.trip.tripId === load.trip.tripId}
                              onOpen={() => setOpenId(load.trip.tripId)}
                            />
                          ) : (
                            <span className="flex min-h-[58px] items-center justify-center rounded-go-card border border-dashed border-go-rule text-xs text-go-secondary">
                              {freeLabel(row.vehicle)}
                            </span>
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
          <PlanTrip key={open.trip.tripId} plan={plan} load={open} fleet={fleet} orders={orders} editable={editable} canReplan={canReplan} actions={actions} risks={stops} />
        )}
      </div>
    </>
  );
}

/** A KPI card as the plan is drawn: the label with a coloured tag at the right, a big number, a line under it. */
function Kpi({ label, value, note, tag, tone = "danger" }: { label: string; value: React.ReactNode; note: string; tag?: string; tone?: "danger" | "warning" }): React.JSX.Element {
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-0.5 rounded-go-card-l bg-go-card px-[18px] py-3.5 shadow-go-card max-lg:min-w-[calc(50%-7px)]">
      <p className="flex flex-wrap items-center justify-between gap-x-2 text-xs text-go-secondary">
        <span className="truncate">{label}</span>
        {tag && <span className={cx("shrink-0 font-medium", tone === "danger" ? "text-go-danger-strong" : "text-go-warning-text")}>{tag}</span>}
      </p>
      <p className="truncate text-2xl font-medium text-go-ink">{value}</p>
      <p className="truncate text-xs text-go-secondary">{note}</p>
    </div>
  );
}

function TripCell({ load, added, dim, active, onOpen, risk }: { load: TripLoad; added: boolean; dim: boolean; active: boolean; onOpen: () => void; risk?: StopRisk }): React.JSX.Element {
  const { trip } = load;
  const chilled = trip.temperature === "chilled";
  return (
    <button
      type="button"
      aria-pressed={active}
      aria-label={`${trip.vehicleId} trip ${trip.tripNumber}: ${trip.brandCode} ${trip.districtName}, ${trip.stops.length} stops`}
      onClick={onOpen}
      className={`flex min-h-[58px] w-full flex-col gap-1 rounded-go-card border px-3 py-2 text-left ${dim ? "opacity-40" : ""} ${active ? "border-go-teal bg-go-success-tint" : "border-go-rule"}`}
    >
      <span className="flex items-center gap-2">
        <Pill tone="success">{trip.brandCode}</Pill>
        <span className="min-w-0 flex-1 truncate text-[14px] font-medium text-go-ink">{trip.districtName}</span>
        {added && <Pill tone="success">Added</Pill>}
        {load.tight && <Pill tone="warning">Tight</Pill>}
        {risk && riskTone(risk.percent) !== "low" && (
          <Pill tone={riskTone(risk.percent) === "high" ? "danger" : "warning"}>{riskLabel(risk, "Late risk")}</Pill>
        )}
      </span>
      <span className="flex items-center gap-1.5 text-xs text-go-secondary">
        <span aria-hidden className={`size-2 rounded-full ${chilled ? "bg-go-info" : "bg-go-placeholder"}`} />
        {chilled ? "Chilled" : "Ambient"} · {trip.stops.length} {trip.stops.length === 1 ? "stop" : "stops"}
      </span>
    </button>
  );
}
