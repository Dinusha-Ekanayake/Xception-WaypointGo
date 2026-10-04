"use client";

import { useMemo, useState } from "react";
import type { OrderView, PlanView, VehicleView } from "@shared/domain/types";
import { Menu } from "@shared/ui";
import { hhmm, ruleLabel } from "@shared/wording";
import { typeLabel } from "../data/fleet.ts";
import { size } from "../data/orders.ts";
import { after, type TripLoad } from "../data/plan.ts";
import { lastServedText } from "../data/planViews.ts";
import { checkChips, stopRows, tripTiles } from "../data/tripWindow.ts";
import { useInterchange, usePlacements, useSequencePreview } from "../data/usePlanReads.ts";
import { tripChecks } from "./PlanTrip.tsx";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";
import Refusal from "./Refusal.tsx";
import TripWindow, { moveItem, sameOrder } from "./TripWindow.tsx";
import type { PlanActions } from "./planActions.ts";

// Figma "Plan · Edit trip": the trip's stops in order with the deferred orders
// beside them. A stop is dragged to a new place, dragged right to defer it, or
// a deferred order is added; the server times and judges the trip before
// anything is sent. Each change is one command on the draft, so the window
// holds one change at a time: accept or undo it before the next. Moving the
// whole trip to another vehicle also lives here, and is the only change a
// published plan takes (it starts a revision).

type Change =
  | { kind: "order"; sequence: string[] }
  | { kind: "defer"; orderId: string }
  | { kind: "add"; orderId: string }
  | { kind: "move"; vehicleId: string };

const MAX_DEFERRED = 8;

export default function EditTrip({
  plan,
  load,
  orders,
  fleet,
  editable,
  canReplan,
  actions,
  onClose,
}: {
  plan: PlanView;
  load: TripLoad;
  orders: Map<string, OrderView>;
  fleet: VehicleView[];
  /** A draft, online: stops can change. */
  editable: boolean;
  /** Online: the trip can move to another vehicle. */
  canReplan: boolean;
  actions: PlanActions;
  onClose: () => void;
}): React.JSX.Element {
  const { trip } = load;
  const vehicle = fleet.find((v) => v.vehicleId === trip.vehicleId);
  const original = useMemo(() => trip.stops.map((stop) => stop.orderId), [trip.stops]);
  const [change, setChange] = useState<Change | null>(null);
  const [reason, setReason] = useState("");

  const sequence = change?.kind === "order" ? change.sequence : original;
  const reordered = change?.kind === "order" && !sameOrder(change.sequence, original);
  const preview = useSequencePreview(reordered ? trip.tripId : null, reordered ? sequence : null, plan.planId);
  const interchange = useInterchange(plan.planId, trip.tripId, change?.kind === "move" ? change.vehicleId : null);

  const deferred = useMemo(
    () =>
      plan.allocations
        .filter((a) => a.decision === "DEFERRED")
        .map((a) => ({ allocation: a, order: orders.get(a.orderId) }))
        .filter((d): d is { allocation: typeof d.allocation; order: OrderView } => d.order !== undefined)
        .slice(0, MAX_DEFERRED),
    [plan.allocations, orders],
  );
  const places = usePlacements(editable ? plan.planId : "", editable ? deferred.map((d) => d.allocation.orderId) : []);
  const placeHere = (orderId: string) =>
    (places.data?.[orderId] ?? []).find((place) => place.vehicleId === trip.vehicleId && place.tripNumber === trip.tripNumber) ?? null;

  // The dispatcher's order, with the server's times for it once the preview has answered.
  const timed = new Map((reordered && preview.data ? preview.data.stops : []).map((stop) => [stop.orderId, stop]));
  const stopsNow = sequence.map((id) => timed.get(id) ?? trip.stops.find((s) => s.orderId === id)!).filter(Boolean);
  const shownStops = change?.kind === "defer" ? stopsNow.filter((s) => s.orderId !== change.orderId) : stopsNow;
  const chips = reordered && preview.data ? checkChips(preview.data.checks) : change?.kind === "move" && interchange.data ? checkChips(interchange.data.checks) : checkChips(tripChecks(plan, trip.tripId));

  const ready =
    change === null
      ? false
      : change.kind === "order"
        ? reordered && preview.data?.feasible === true
        : change.kind === "add"
          ? placeHere(change.orderId)?.feasible === true
          : change.kind === "move"
            ? interchange.data?.feasible === true
            : true;

  const apply = async () => {
    if (!change) return;
    const why = reason.trim();
    const ok =
      change.kind === "order"
        ? await actions.reorder(trip.tripId, change.sequence, why)
        : change.kind === "defer"
          ? await actions.defer(change.orderId, why)
          : change.kind === "add"
            ? await actions.place({ orderId: change.orderId, vehicleId: trip.vehicleId, tripNumber: trip.tripNumber, reason: why })
            : await actions.moveTrip(trip.tripId, change.vehicleId, why);
    if (ok) onClose();
  };

  const busyWithOther = (kind: Change["kind"]) => change !== null && change.kind !== kind;
  const deferredOrder = change?.kind === "defer" ? orders.get(change.orderId) : undefined;
  const others = fleet.filter((v) => v.vehicleId !== trip.vehicleId);

  return (
    <TripWindow
      label="Edit trip"
      kicker={`Edit trip · ${plan.depotCode}`}
      title={`${trip.vehicleId} Trip ${trip.tripNumber} · ${trip.brandCode} ${trip.districtName} · departs ${hhmm(trip.plannedDeparture)}`}
      onClose={onClose}
      banner={
        canReplan ? (
          <div className="flex flex-wrap items-center gap-3 rounded-go-input bg-go-surface px-4 py-2.5 text-[13px] text-go-ink">
            <span className="min-w-0 flex-1">
              {change?.kind === "move" ? `Moving the whole trip to ${change.vehicleId}` : "The whole trip can also move to another vehicle."}
            </span>
            <Menu
              label="Move the trip to"
              align="right"
              disabled={busyWithOther("move")}
              items={others.map((v) => ({ id: v.vehicleId, label: v.vehicleId, hint: typeLabel(v), selected: change?.kind === "move" && change.vehicleId === v.vehicleId }))}
              onSelect={(vehicleId) => setChange({ kind: "move", vehicleId })}
              className="rounded-full bg-go-card px-3.5 py-1.5 text-[13px] font-medium"
              chevron
            >
              Move trip to
            </Menu>
            {change?.kind === "move" && (
              <button type="button" onClick={() => setChange(null)} className="text-[13px] font-medium text-go-teal">
                Undo
              </button>
            )}
          </div>
        ) : undefined
      }
      left={{
        title: "Deferred orders",
        hint: editable ? "Drag onto the trip or click Add" : "A published plan changes only by moving the trip",
        body: (
          <ul aria-label="Deferred orders" className="flex flex-col gap-2 overflow-y-auto">
            {deferred.length === 0 && <li className="text-[13px] text-go-secondary">No order is deferred.</li>}
            {deferred.map(({ allocation, order }) => {
              const place = placeHere(order.orderId);
              const fits = place?.feasible === true;
              const why = place && !place.feasible ? place.bindingRule : null;
              const adding = change?.kind === "add" && change.orderId === order.orderId;
              return (
                <li key={order.orderId} className={`flex flex-col gap-0.5 rounded-go-input border px-3 py-2 ${adding ? "border-go-teal bg-go-success-tint" : "border-go-rule"}`}>
                  <span className="flex items-center gap-2">
                    <span className="min-w-0 flex-1 truncate text-[13px] font-medium text-go-ink">{`${order.outletId} ${order.districtName}`}</span>
                    {editable && (fits || adding) && (
                      <button
                        type="button"
                        disabled={busyWithOther("add")}
                        onClick={() => setChange(adding ? null : { kind: "add", orderId: order.orderId })}
                        className="rounded-full bg-go-ink px-2 py-0.5 text-[11px] font-medium text-go-card disabled:opacity-40"
                      >
                        {adding ? "Undo" : "+ Add"}
                      </button>
                    )}
                  </span>
                  <span className="text-xs text-go-secondary">{`${order.orderRef} · ${size(order)}`}</span>
                  <span className="text-xs text-go-warning-text">{lastServedText(allocation.lastServedOn, plan.serviceDate)}</span>
                  {editable && places.data && !fits && <span className="text-xs text-go-danger-strong">{`✕ ${why ? ruleLabel(why) : "Does not join this trip"}`}</span>}
                </li>
              );
            })}
          </ul>
        ),
      }}
      tiles={tripTiles(shownStops, orders, vehicle)}
      depart={{ place: `${plan.depotCode} depot`, time: hhmm(trip.plannedDeparture) }}
      back={{ time: after(trip.plannedDeparture, trip.plannedMinutes) }}
      stops={stopRows(plan, shownStops, orders, vehicle)}
      onReorder={editable && !busyWithOther("order") ? (from, to) => setChange({ kind: "order", sequence: moveItem(sequence, from, to) }) : undefined}
      onDefer={editable && change === null ? (orderId) => setChange({ kind: "defer", orderId }) : undefined}
      right={{
        title: "Will be deferred",
        hint: "Moves to the next run, first on it. The reason goes to the store.",
        body:
          change !== null ? (
            <div className="flex flex-col gap-3">
              {deferredOrder && (
                <div className="rounded-go-input border border-go-warning bg-go-warning-tint px-3 py-2.5">
                  <p className="text-[14px] font-medium text-go-ink">{`${deferredOrder.outletId} ${deferredOrder.districtName}`}</p>
                  <p className="text-xs text-go-secondary">{`${deferredOrder.orderRef} · ${size(deferredOrder)}`}</p>
                  <button type="button" onClick={() => setChange(null)} className="mt-1 text-xs font-medium text-go-teal">
                    Put it back
                  </button>
                </div>
              )}
              <ReasonPicker label="Why this change?" value={reason} onChange={setReason} />
            </div>
          ) : undefined,
      }}
      chips={chips}
      footer={
        preview.error ? (
          <Refusal error={preview.error} what="the check of this stop order" />
        ) : interchange.error ? (
          <Refusal error={interchange.error} what="the check for this vehicle" />
        ) : change === null ? (
          "No changes yet"
        ) : ready ? (
          <span className="text-go-teal">{`1 change · ${describe(change, orders)}`}</span>
        ) : change.kind === "order" && !reordered ? (
          "The stop order is back as it was"
        ) : (
          <span className="text-go-danger-strong">{`${describe(change, orders)}: a rule refuses it, see the checks`}</span>
        )
      }
      accept={{ label: "Accept changes", disabled: actions.busy || !ready || !reasonReady(reason), onClick: () => void apply() }}
    />
  );
}

function describe(change: Change, orders: Map<string, OrderView>): string {
  const name = (id: string) => orders.get(id)?.outletId ?? "the order";
  switch (change.kind) {
    case "order":
      return "new stop order";
    case "defer":
      return `${name(change.orderId)} deferred`;
    case "add":
      return `${name(change.orderId)} added`;
    case "move":
      return `trip moves to ${change.vehicleId}`;
  }
}
