"use client";

import { useMemo, useState } from "react";
import type { ConstraintResultView, OrderView, PlanView, VehicleView } from "@shared/domain/types";
import { Icon, Pill, Popover, PrimaryButton } from "@shared/ui";
import { checkLabel, hhmm, temperatureLabel } from "@shared/wording";
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
          {`${vehicle ? `${typeLabel(vehicle)} · ` : ""}Depart ${(vehicle?.depotCode ?? plan.depotCode)} ${hhmm(trip.plannedDeparture)} · back ${after(trip.plannedDeparture, trip.plannedMinutes)} · ${temperatureLabel(trip.temperature).toLowerCase()}`}
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
              {`${check.passed ? "✓" : "!"} ${checkLabel(check.ruleId, check.passed)}`}
            </p>
          ))}
        </Popover>
      </div>

      <Timeline plan={plan} load={load} orders={orders} vehicle={vehicle} risks={risks} onLock={editable ? (orderId, held) => void actions.lock(orderId, held) : undefined} />
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

/**
 * Depart, each stop with its window and its share of the load, and back at the
 * depot. Each row draws its own piece of the line, so the dots sit on it
 * whatever a row's height. A stop can be locked to this vehicle: a regenerate
 * keeps a locked order where it is (R-PLN-34).
 */
function Timeline({
  plan,
  load,
  orders,
  vehicle,
  risks,
  onLock,
}: {
  plan: PlanView;
  load: TripLoad;
  orders: Map<string, OrderView>;
  vehicle: VehicleView | undefined;
  risks: Map<string, StopRisk> | null;
  /** Present on an open draft: locks or unlocks a stop's order. */
  onLock?: (orderId: string, locked: boolean) => void;
}): React.JSX.Element {
  const { trip } = load;
  const locked = new Set(plan.allocations.filter((a) => a.locked).map((a) => a.orderId));
  const back = after(trip.plannedDeparture, trip.plannedMinutes);
  const lastStop = trip.stops[trip.stops.length - 1];
  return (
    <ol aria-label="Stops in order" className="flex flex-col">
      <Row first time={hhmm(trip.plannedDeparture)} dot="hollow" title={`Depart ${(vehicle?.depotCode ?? plan.depotCode)}`} />
      {trip.stops.map((stop) => {
        const order = orders.get(stop.orderId);
        const share = stopShare(order, vehicle);
        const held = locked.has(stop.orderId);
        return (
          <Row
            key={stop.orderId}
            time={hhmm(stop.plannedArrival)}
            dot="filled"
            title={`${stop.outletId}${order ? ` · ${order.districtName}` : ""}`}
            note={stop.windowOpen && stop.windowClose ? `${hhmm(stop.windowOpen)}-${hhmm(stop.windowClose)}` : "No window"}
            aside={share === null ? undefined : `${share}%`}
            risk={risks?.get(stop.orderId)}
            lock={onLock || held ? { held, onToggle: onLock ? () => onLock(stop.orderId, !held) : undefined, name: stop.outletId } : undefined}
          />
        );
      })}
      <Row
        last
        time={back}
        dot="hollow"
        title={`Back at ${(vehicle?.depotCode ?? plan.depotCode)}`}
        note={lastStop && back < hhmm(lastStop.plannedArrival) ? "Earlier than the last stop: these times need a fresh check" : undefined}
      />
    </ol>
  );
}

function Row({
  time,
  dot,
  title,
  note,
  aside,
  risk,
  lock,
  first = false,
  last = false,
}: {
  time: string;
  dot: "filled" | "hollow";
  title: string;
  note?: string;
  aside?: string;
  risk?: StopRisk;
  lock?: { held: boolean; onToggle?: () => void; name: string };
  first?: boolean;
  last?: boolean;
}): React.JSX.Element {
  return (
    <li className="flex items-stretch gap-3">
      <span className="w-11 shrink-0 py-1.5 text-[14px] leading-5 font-medium tabular-nums text-go-ink">{time}</span>
      <span aria-hidden className="relative flex w-3 shrink-0 justify-center">
        <span className={`absolute w-0.5 bg-go-rule ${first ? "top-[13px]" : "top-0"} ${last ? "h-[13px]" : "bottom-0"}`} />
        <span className={`relative z-10 mt-[8px] size-2.5 rounded-full border-2 border-go-teal ${dot === "filled" ? "bg-go-teal" : "bg-go-card"}`} />
      </span>
      <span className="min-w-0 flex-1 py-1.5">
        <span className="block truncate text-[14px] leading-5 font-medium text-go-ink">{title}</span>
        {note && <span className="block text-xs text-go-secondary">{note}</span>}
      </span>
      <span className="flex shrink-0 items-start gap-1.5 py-1.5">
        {risk && (
          <span title="Chance this stop arrives after its window">
            <Pill tone={RISK_PILL[riskTone(risk.percent)]}>{riskLabel(risk)}</Pill>
          </span>
        )}
        {aside && (
          <span className="rounded-full bg-go-surface px-2 py-0.5 text-[11px] text-go-secondary" title="Share of the vehicle's load">
            {aside}
          </span>
        )}
        {lock &&
          (lock.onToggle ? (
            <button
              type="button"
              aria-pressed={lock.held}
              aria-label={lock.held ? `Unlock ${lock.name}` : `Lock ${lock.name} to this vehicle`}
              title={lock.held ? "Locked: a regenerate keeps it here" : "Lock to this vehicle"}
              onClick={lock.onToggle}
              className={`flex size-6 items-center justify-center rounded-full ${lock.held ? "bg-go-ink" : "bg-go-surface opacity-60 hover:opacity-100"}`}
            >
              <span className={lock.held ? "invert" : ""}>
                <Icon name="lock" />
              </span>
            </button>
          ) : (
            lock.held && (
              <span title="Locked: a regenerate keeps it here" className="flex size-6 items-center justify-center rounded-full bg-go-surface">
                <Icon name="lock" label="Locked" />
              </span>
            )
          ))}
      </span>
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
