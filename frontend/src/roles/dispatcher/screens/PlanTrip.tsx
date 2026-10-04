"use client";

import { useMemo, useState } from "react";
import type { ConstraintResultView, OrderView, PlanView, VehicleView } from "@shared/domain/types";
import { Pill, Popover, PrimaryButton } from "@shared/ui";
import { hhmm, ruleLabel, temperatureLabel } from "@shared/wording";
import { typeLabel } from "../data/fleet.ts";
import { after, type TripLoad } from "../data/plan.ts";
import { riskLabel, riskTone, stopShare, tightLine, type StopRisk } from "../data/planViews.ts";
import EditTrip from "./EditTrip.tsx";
import type { PlanActions } from "./planActions.ts";

// Figma "Plan · 2 View plan", the trip opened beside the board: where it leaves
// and when it is back, how full it is, its stops in order with the share of the
// load each takes, and "Edit this trip", which opens the trip window: reorder,
// defer, add, or move it whole to another vehicle (plan:Replan, which also
// works on the published plan, where it starts a revision). "More" opens the
// trip's rule checks as Figma's dark card.

export default function PlanTrip({
  plan,
  load,
  fleet,
  orders,
  editable,
  canReplan,
  actions,
  risks = null,
}: {
  plan: PlanView;
  load: TripLoad;
  fleet: VehicleView[];
  orders: Map<string, OrderView>;
  editable: boolean;
  canReplan: boolean;
  /** Each stop's late risk, keyed by order, once the published plan is scored (#119). */
  risks?: Map<string, StopRisk> | null;
  actions: PlanActions;
}): React.JSX.Element {
  const { trip } = load;
  const vehicle = fleet.find((v) => v.vehicleId === trip.vehicleId);
  const [editing, setEditing] = useState(false);
  const warn = tightLine(load.volumePercent, load.weightPercent);
  const checks = useMemo(() => tripChecks(plan, trip.tripId), [plan, trip.tripId]);

  return (
    <section aria-label={`${trip.vehicleId} trip ${trip.tripNumber}`} className="flex w-full flex-col rounded-go-panel bg-go-card lg:max-h-[calc(100dvh-330px)] lg:max-w-[340px]">
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
        <Popover
          label={`Rule checks · ${trip.vehicleId} Trip ${trip.tripNumber}`}
          tone="dark"
          align="right"
          trigger="More"
          className="rounded-full bg-go-surface px-3 py-1 text-xs font-medium text-go-ink"
          panelClassName="flex w-[240px] flex-col gap-1 text-xs"
        >
          <p className="pb-0.5 font-semibold">{`Rule checks · ${trip.vehicleId} Trip ${trip.tripNumber}`}</p>
          {checks.length === 0 && <p className="text-white/70">No checks are recorded for this trip.</p>}
          {checks.map((check, index) => (
            <p key={`${check.ruleId}-${index}`} title={check.reason} className={check.passed ? "" : "text-go-warning"}>
              {`${check.passed ? "✓" : "!"} ${ruleLabel(check.ruleId)}`}
            </p>
          ))}
        </Popover>
      </div>

      <Timeline plan={plan} load={load} orders={orders} vehicle={vehicle} risks={risks} />
      </div>
      {(editable || canReplan) && (
        <div className="flex flex-col px-5 pt-2 pb-5">
          <PrimaryButton onClick={() => setEditing(true)}>Edit this trip</PrimaryButton>
        </div>
      )}
      {editing && (
        <EditTrip plan={plan} load={load} orders={orders} fleet={fleet} editable={editable} canReplan={canReplan} actions={actions} onClose={() => setEditing(false)} />
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
function Timeline({ plan, load, orders, vehicle, risks }: { plan: PlanView; load: TripLoad; orders: Map<string, OrderView>; vehicle: VehicleView | undefined; risks: Map<string, StopRisk> | null }): React.JSX.Element {
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
            risk={risks?.get(stop.orderId)}
          />
        );
      })}
      <Row time={after(trip.plannedDeparture, trip.plannedMinutes)} dot="hollow" title={`Back at ${plan.depotCode}`} />
    </ol>
  );
}

function Row({ time, dot, title, note, aside, risk }: { time: string; dot: "filled" | "hollow"; title: string; note?: string; aside?: string; risk?: StopRisk }): React.JSX.Element {
  return (
    <li className="flex items-start gap-3 py-1.5">
      <span className="w-11 shrink-0 text-[14px] font-medium tabular-nums text-go-ink">{time}</span>
      <span aria-hidden className={`relative z-10 mt-1.5 size-2.5 shrink-0 rounded-full border-2 border-go-teal ${dot === "filled" ? "bg-go-teal" : "bg-go-card"}`} />
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[14px] font-medium text-go-ink">{title}</span>
        {note && <span className="block text-xs text-go-secondary">{note}</span>}
      </span>
      {risk && (
        <span className="shrink-0" title="Chance this stop arrives after its window">
          <Pill tone={RISK_PILL[riskTone(risk.percent)]}>{riskLabel(risk)}</Pill>
        </span>
      )}
      {aside && <span className="shrink-0 rounded-full bg-go-surface px-2 py-0.5 text-[11px] text-go-secondary" title="Share of the vehicle's load">{aside}</span>}
    </li>
  );
}

/** Late risk reads amber from 20% and red from 35%; below that it is quiet. */
const RISK_PILL = { low: "muted", watch: "warning", high: "danger" } as const;

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
