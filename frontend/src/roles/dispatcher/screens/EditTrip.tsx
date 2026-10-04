"use client";

import { useMemo, useRef, useState } from "react";
import type { OrderView, PlanView, StopView, VehicleView } from "@shared/domain/types";
import { Menu } from "@shared/ui";
import { hhmm } from "@shared/wording";
import { typeLabel } from "../data/fleet.ts";
import { size } from "../data/orders.ts";
import { after, type TripLoad } from "../data/plan.ts";
import { lastServedText } from "../data/planViews.ts";
import { checkChips, stopRows, tripTiles } from "../data/tripWindow.ts";
import { useInterchange, useTripEditPreview } from "../data/usePlanReads.ts";
import { tripChecks } from "./PlanTrip.tsx";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";
import Refusal from "./Refusal.tsx";
import TripWindow, { draggableOrder, moveItem, sameOrder } from "./TripWindow.tsx";
import type { PlanActions } from "./planActions.ts";

// Figma "Plan · Edit trip": what the trip carries and in what order, changed by
// hand. Drag a deferred order onto the trip (or click Add), drag stops into a
// new order, drag a stop to the right to take it off, or remove the whole trip.
// Any number of changes make one draft: the server times the trip and judges
// the vehicle's whole day as it stands, and Accept unlocks only when every rule
// passes (plan:EditTrip, R-PLN-42). Moving the whole trip to another vehicle is
// its own change (plan:Replan) and the only one a published plan takes.

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
  /** A draft, online: the trip's orders and their order can change. */
  editable: boolean;
  /** Online: the trip can move to another vehicle. */
  canReplan: boolean;
  actions: PlanActions;
  onClose: () => void;
}): React.JSX.Element {
  const { trip } = load;
  const vehicle = fleet.find((v) => v.vehicleId === trip.vehicleId);
  const original = useMemo(() => trip.stops.map((stop) => stop.orderId), [trip.stops]);
  /** The orders the trip should carry, in order: the dispatcher's working copy. */
  const [ids, setIds] = useState<string[]>(original);
  const [moveTo, setMoveTo] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  const changed = !sameOrder(ids, original);
  const preview = useTripEditPreview(changed && moveTo === null ? trip.tripId : null, changed ? ids : null, plan.planId);
  const interchange = useInterchange(plan.planId, trip.tripId, moveTo);
  // Every stop a preview has timed, so a change still draws while its own preview is on the way.
  const timed = useRef(new Map<string, StopView>(trip.stops.map((stop) => [stop.orderId, stop])));
  for (const stop of preview.data?.stops ?? []) timed.current.set(stop.orderId, stop);

  const added = ids.filter((id) => !original.includes(id));
  const removed = original.filter((id) => !ids.includes(id));
  const stops: StopView[] = ids.map(
    (id, index) =>
      timed.current.get(id) ?? {
        sequence: index + 1, orderId: id, outletId: orders.get(id)?.outletId ?? "", plannedArrival: "", windowOpen: "", windowClose: "", serviceMinutes: "0",
      },
  );
  const candidates = useMemo(
    () =>
      plan.allocations
        .filter((a) => a.decision !== "SERVED")
        .map((a) => ({ allocation: a, order: orders.get(a.orderId) }))
        .filter((c): c is { allocation: (typeof plan.allocations)[number]; order: OrderView } => c.order !== undefined),
    [plan.allocations, orders],
  );
  const waiting = candidates.filter((c) => !ids.includes(c.order.orderId));
  const others = fleet.filter((v) => v.vehicleId !== trip.vehicleId);
  const canEdit = editable && moveTo === null;

  const insert = (orderId: string, index: number) =>
    setIds((list) => {
      const without = list.filter((id) => id !== orderId);
      return [...without.slice(0, index), orderId, ...without.slice(index)];
    });
  const takeOff = (orderId: string) => setIds((list) => list.filter((id) => id !== orderId));
  const putBack = (orderId: string) => setIds((list) => (list.includes(orderId) ? list : [...list, orderId]));

  const checks = moveTo !== null ? interchange.data?.checks : changed ? preview.data?.checks : tripChecks(plan, trip.tripId);
  const ready = moveTo !== null ? interchange.data?.feasible === true : changed && preview.data?.feasible === true && !preview.loading;
  const summary = [
    added.length ? `${added.length} added` : null,
    removed.length ? `${removed.length} taken off` : null,
    !added.length && !removed.length && changed ? "new stop order" : null,
    ids.length === 0 ? "the trip is removed" : null,
  ]
    .filter(Boolean)
    .join(" · ");

  /** Closing with changes not sent asks first: a drag-and-drop session is easy to lose to Escape. */
  const leave = () => {
    if ((changed || moveTo !== null) && !window.confirm("Discard your changes to this trip?")) return;
    onClose();
  };

  const apply = async () => {
    const why = reason.trim();
    const ok = moveTo !== null ? await actions.moveTrip(trip.tripId, moveTo, why) : await actions.editTrip(trip.tripId, ids, why);
    if (ok) onClose();
  };

  return (
    <TripWindow
      label="Edit trip"
      kicker={`Edit trip · ${plan.depotCode}`}
      title={`${trip.vehicleId} Trip ${trip.tripNumber} · ${trip.brandCode} ${trip.districtName} · departs ${hhmm(trip.plannedDeparture)}`}
      onClose={leave}
      banner={
        <div className="flex flex-wrap items-center gap-3 rounded-go-input bg-go-surface px-4 py-2.5 text-[13px] text-go-ink">
          <span className="min-w-0 flex-1">
            {moveTo !== null
              ? `Moving the whole trip to ${moveTo}`
              : editable
                ? "Drag deferred orders onto the trip, drag stops to reorder, drag a stop right to take it off. Accept when every check passes."
                : "A published plan changes only by moving the whole trip."}
          </span>
          {canEdit && (
            <button
              type="button"
              disabled={ids.length === 0}
              onClick={() => setIds([])}
              className="rounded-full bg-go-danger-tint px-3.5 py-1.5 text-[13px] font-medium text-go-danger-strong disabled:opacity-40"
            >
              Remove trip
            </button>
          )}
          {canReplan && (
            <Menu
              label="Move the trip to"
              align="right"
              disabled={changed}
              items={others.map((v) => ({ id: v.vehicleId, label: v.vehicleId, hint: typeLabel(v), selected: moveTo === v.vehicleId }))}
              onSelect={setMoveTo}
              className="flex items-center gap-1.5 rounded-full bg-go-card px-3.5 py-1.5 text-[13px] font-medium"
              chevron
            >
              Move trip to
            </Menu>
          )}
          {(changed || moveTo !== null) && (
            <button type="button" onClick={() => (setIds(original), setMoveTo(null))} className="text-[13px] font-medium text-go-teal">
              Undo all
            </button>
          )}
        </div>
      }
      left={{
        title: "Deferred orders",
        hint: editable ? "Drag onto the trip or click Add" : "Placing orders needs a draft",
        body: (
          <ul aria-label="Deferred orders" className="flex flex-col gap-2 overflow-y-auto">
            {waiting.length === 0 && <li className="text-[13px] text-go-secondary">No order is waiting.</li>}
            {waiting.map(({ allocation, order }) => (
              <li
                key={order.orderId}
                {...(canEdit ? draggableOrder(order.orderId) : {})}
                className={`flex flex-col gap-0.5 rounded-go-input border border-go-rule bg-go-card px-3 py-2 ${canEdit ? "cursor-grab active:cursor-grabbing" : ""}`}
              >
                <span className="flex items-center gap-2">
                  {canEdit && <span aria-hidden className="text-go-secondary">⠿</span>}
                  <span className="min-w-0 flex-1 truncate text-[13px] font-medium text-go-ink">{`${order.outletId} ${order.districtName}`}</span>
                  {canEdit && (
                    <button
                      type="button"
                      aria-label={`Add ${order.outletId} to the trip`}
                      onClick={() => insert(order.orderId, ids.length)}
                      className="rounded-full bg-go-ink px-2 py-0.5 text-[11px] font-medium text-go-card"
                    >
                      + Add
                    </button>
                  )}
                </span>
                <span className="text-xs text-go-secondary">{`${order.orderRef} · ${size(order)}`}</span>
                <span className="text-xs text-go-warning-text">{lastServedText(allocation.lastServedOn, plan.serviceDate)}</span>
              </li>
            ))}
          </ul>
        ),
      }}
      tiles={tripTiles(stops, orders, vehicle, !changed || (Boolean(preview.data) && !preview.loading))}
      depart={{ place: `${plan.depotCode} depot`, time: hhmm(trip.plannedDeparture) }}
      back={{ time: changed ? "…" : after(trip.plannedDeparture, trip.plannedMinutes) }}
      stops={stopRows(plan, stops, orders, vehicle).map((row) => (added.includes(row.orderId) ? { ...row, tag: "NEW · added by you" } : row))}
      onReorder={canEdit ? (from, to) => setIds((list) => moveItem(list, from, to)) : undefined}
      onDefer={canEdit ? takeOff : undefined}
      onInsert={canEdit ? insert : undefined}
      right={{
        title: "Will be deferred",
        hint: "Moves to the next run, first on it. The reason goes to the store.",
        body:
          changed || moveTo !== null ? (
            <div className="flex flex-col gap-2">
              {removed.map((id) => {
                const order = orders.get(id);
                return (
                  <div key={id} className="rounded-go-input border border-go-warning bg-go-warning-tint px-3 py-2">
                    <p className="text-[14px] font-medium text-go-ink">{order ? `${order.outletId} ${order.districtName}` : id}</p>
                    {order && <p className="text-xs text-go-secondary">{`${order.orderRef} · ${size(order)}`}</p>}
                    <button type="button" onClick={() => putBack(id)} className="mt-1 text-xs font-medium text-go-teal">
                      Put it back
                    </button>
                  </div>
                );
              })}
              {canEdit && (
                <p className="rounded-go-input border border-dashed border-go-warning/60 px-3 py-3 text-center text-[13px] text-go-warning-text">Drag a stop here to defer it</p>
              )}
              <ReasonPicker required label="Why this change?" value={reason} onChange={setReason} />
            </div>
          ) : undefined,
      }}
      chips={checkChips(checks ?? [])}
      footer={
        preview.error ? (
          <Refusal error={preview.error} what="the check of this trip" />
        ) : interchange.error ? (
          <Refusal error={interchange.error} what="the check for this vehicle" />
        ) : !changed && moveTo === null ? (
          "No changes yet"
        ) : (moveTo !== null ? interchange.loading && !interchange.data : preview.loading) ? (
          "Checking the trip…"
        ) : ready && !reasonReady(reason) ? (
          <span className="text-go-warning-text">{`✓ Every check passes · add a reason to accept`}</span>
        ) : ready ? (
          <span className="text-go-teal">{`✓ Every check passes · ${moveTo !== null ? `trip moves to ${moveTo}` : summary}`}</span>
        ) : (
          <span className="text-go-danger-strong">{`${summary || `trip moves to ${moveTo}`}: a rule refuses it, see the checks`}</span>
        )
      }
      accept={{ label: "Accept changes", disabled: actions.busy || !ready || !reasonReady(reason), onClick: () => void apply() }}
    />
  );
}
