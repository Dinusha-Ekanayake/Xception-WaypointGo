"use client";

import { useMemo, useRef, useState } from "react";
import type { OrderView, PlanView, StopView, VehicleView } from "@shared/domain/types";
import { Segmented } from "@shared/ui";
import { hhmm } from "@shared/wording";
import { after } from "../data/plan.ts";
import { lastServedText } from "../data/planViews.ts";
import { size } from "../data/orders.ts";
import { checkChips, stopRows, tripTiles } from "../data/tripWindow.ts";
import { useStopOrderProposal, useSwapPreview } from "../data/usePlanReads.ts";
import { swapTrips } from "./DecisionPanel.tsx";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";
import Refusal from "./Refusal.tsx";
import TripWindow, { moveItem, sameOrder } from "./TripWindow.tsx";
import type { PlanActions } from "./planActions.ts";

// Figma "Plan · 1b Swap window": a deferred order takes the place of a stop on
// a trip it could ride (same brand, district and temperature). The dispatcher
// drags the stop that gives way to "Will be deferred"; the server then times the
// trip as it would run and judges every rule, and Accept stays off until every
// check passes. The suggestion is the stop that frees the most room, labelled
// as a suggestion: no model chose it. Once a stop gives way, the trip's stops
// can be reordered in the same window; the swap and the new order go to the
// server as one plan:Swap and are judged together. "AI order" is the slot for a
// proposed stop order (useStopOrderProposal), which says it is not available
// until a module serves one.

export default function SwapWindow({
  plan,
  incoming,
  orders,
  fleet,
  actions,
  onClose,
}: {
  plan: PlanView;
  /** The deferred order that wants a place. */
  incoming: OrderView;
  orders: Map<string, OrderView>;
  fleet: VehicleView[];
  actions: PlanActions;
  onClose: () => void;
}): React.JSX.Element {
  const trips = useMemo(() => swapTrips(plan, incoming), [plan, incoming]);
  const [tripId, setTripId] = useState(trips[0]?.tripId ?? "");
  const trip = trips.find((t) => t.tripId === tripId) ?? trips[0] ?? null;
  const vehicle = fleet.find((v) => v.vehicleId === trip?.vehicleId);
  const [outId, setOutId] = useState<string | null>(null);
  const out = outId ? orders.get(outId) : undefined;
  const [reason, setReason] = useState("");
  /** The dispatcher's stop order after the swap; null leaves it to the timeline. */
  const [sequence, setSequence] = useState<string[] | null>(null);
  const preview = useSwapPreview(outId, incoming.orderId, plan.planId, sequence);
  const proposal = useStopOrderProposal(outId, incoming.orderId);
  const proposed = proposal.available ? proposal.proposal.data : null;
  // Every stop the previews have timed, so a reorder still draws while its own preview is on the way.
  const timed = useRef(new Map<string, StopView>());
  for (const stop of preview.data?.stops ?? []) timed.current.set(stop.orderId, stop);
  const allocation = plan.allocations.find((a) => a.orderId === incoming.orderId);

  const suggested = useMemo(() => {
    if (!trip) return null;
    const need = Number(incoming.volumeM3);
    const roomy = trip.stops
      .map((stop) => ({ stop, m3: Number(orders.get(stop.orderId)?.volumeM3 ?? 0) }))
      .filter((s) => s.m3 >= need)
      .sort((a, b) => b.m3 - a.m3);
    return roomy[0]?.stop ?? null;
  }, [trip, incoming, orders]);

  if (!trip) {
    return (
      <TripWindow
        label="Swap window"
        kicker={`Swap ${incoming.orderRef} · ${incoming.brandCode} ${incoming.outletId} ${incoming.districtName}`}
        title="No trip to swap onto"
        onClose={onClose}
        left={{ title: "Incoming", hint: "Deferred order to swap in", body: <Incoming order={incoming} lastServed={allocation ? lastServedText(allocation.lastServedOn, plan.serviceDate) : null} /> }}
        tiles={[]}
        depart={{ place: plan.depotCode, time: "-" }}
        back={{ time: "-" }}
        stops={[]}
        right={{ title: "Will be deferred", hint: "No trip of this plan carries the same brand, district and temperature, so there is nothing to trade with." }}
        chips={[]}
        footer="Nothing to swap"
        accept={{ label: "Accept changes", disabled: true, onClick: () => undefined }}
      />
    );
  }

  const order = sequence ?? (outId && preview.data ? preview.data.stops.map((stop) => stop.orderId) : null);
  const leave = () => {
    if ((outId !== null || sequence !== null) && !window.confirm("Discard this swap?")) return;
    onClose();
  };
  const showing: StopView[] = outId && order ? order.map((id) => timed.current.get(id)).filter((stop): stop is StopView => stop !== undefined) : trip.stops;
  const feasible = Boolean(outId && preview.data?.feasible);
  const chips = outId && preview.data ? checkChips(preview.data.checks) : [];
  const defaultReason = `Swapped for ${incoming.outletId} ${incoming.districtName}`;
  const undo = () => (setOutId(null), setSequence(null));
  const usingProposal = proposed !== null && sequence !== null && sameOrder(sequence, proposed.orderIds);
  const choose = (orderId: string) => {
    setOutId(orderId);
    setSequence(null);
    if (!reasonReady(reason)) setReason(defaultReason);
  };

  return (
    <TripWindow
      label="Swap window"
      kicker={`Swap ${incoming.orderRef} · ${incoming.brandCode} ${incoming.outletId} ${incoming.districtName}`}
      title={`${trip.vehicleId} Trip ${trip.tripNumber} · ${trip.brandCode} ${trip.districtName} · departs ${hhmm(trip.plannedDeparture)}`}
      onClose={leave}
      banner={
        <div className="flex flex-wrap items-center gap-4 rounded-go-input border border-go-teal/30 bg-go-success-tint px-4 py-3">
          <span className="text-[11px] font-semibold tracking-[0.12em] text-go-teal uppercase">{outId ? "Your swap" : "Suggested"}</span>
          <span className="min-w-0 flex-1 text-[14px] text-go-ink">
            {outId
              ? `${incoming.outletId} joins the trip, ${out?.outletId ?? "the stop"} is deferred`
              : suggested
                ? `Defer ${suggested.outletId}: it frees the most room for ${size(incoming)}`
                : "No single stop frees enough room; drag one to the right to see what the trip would be"}
          </span>
          {trips.length > 1 && (
            <Segmented
              label="Trip"
              value={trip.tripId}
              onChange={(id) => (setTripId(id), undo())}
              options={trips.map((t) => ({ value: t.tripId, label: `${t.vehicleId} T${t.tripNumber}` }))}
            />
          )}
          {outId ? (
            <button type="button" onClick={undo} className="rounded-full bg-go-card px-3.5 py-2 text-[13px] font-medium text-go-ink">
              Undo swap
            </button>
          ) : (
            suggested && (
              <button type="button" onClick={() => choose(suggested.orderId)} className="rounded-full bg-go-card px-3.5 py-2 text-[13px] font-medium text-go-ink">
                Use it
              </button>
            )
          )}
          {outId && (
            <div className="flex w-full flex-wrap items-center gap-3 border-t border-go-teal/20 pt-2.5">
              <span className="text-[11px] font-semibold tracking-[0.12em] text-go-teal uppercase">AI order</span>
              <span className="min-w-0 flex-1 text-[13px] text-go-ink">
                {proposed
                  ? `${proposed.summary}${proposed.minutesSaved ? ` · ${proposed.minutesSaved} min shorter` : ""}`
                  : proposal.available
                    ? proposal.proposal.loading
                      ? "Asking for a stop order…"
                      : "No stop order proposed for this trip"
                    : "Not available yet: no service proposes a stop order. Drag the stops to set your own."}
              </span>
              {sequence !== null && (
                <button type="button" onClick={() => setSequence(null)} className="rounded-full bg-go-card px-3.5 py-1.5 text-[13px] font-medium text-go-ink">
                  {usingProposal ? "Undo order" : "Default order"}
                </button>
              )}
              {!usingProposal && (
                <button
                  type="button"
                  disabled={!proposed}
                  onClick={() => proposed && setSequence(proposed.orderIds)}
                  className="rounded-full bg-go-card px-3.5 py-1.5 text-[13px] font-medium text-go-ink disabled:opacity-40"
                >
                  Use AI order
                </button>
              )}
            </div>
          )}
        </div>
      }
      left={{
        title: "Incoming",
        hint: outId ? "Deferred order being swapped in" : "Deferred order to swap in",
        body: outId ? (
          <p className="rounded-go-input bg-go-success-tint px-3 py-2.5 text-[13px] font-medium text-go-teal">{`✓ ${incoming.outletId} ${incoming.districtName} is on the trip`}</p>
        ) : (
          <Incoming order={incoming} lastServed={allocation ? lastServedText(allocation.lastServedOn, plan.serviceDate) : null} />
        ),
      }}
      tiles={tripTiles(showing, orders, vehicle, !outId || (Boolean(preview.data) && !preview.loading))}
      depart={{ place: `${plan.depotCode} depot`, time: hhmm(trip.plannedDeparture) }}
      back={{ time: after(trip.plannedDeparture, trip.plannedMinutes) }}
      stops={stopRows(plan, showing, orders, vehicle, outId ? incoming.orderId : null)}
      onDefer={outId ? undefined : choose}
      onReorder={outId && order ? (from, to) => setSequence(moveItem(order, from, to)) : undefined}
      right={{
        title: "Will be deferred",
        hint: `Moves to the next run, first on it. The reason goes to the store.`,
        body: out ? (
          <div className="flex flex-col gap-3">
            <div className="rounded-go-input border border-go-warning bg-go-warning-tint px-3 py-2.5">
              <p className="text-[14px] font-medium text-go-ink">{`${out.brandCode} ${out.outletId} ${out.districtName}`}</p>
              <p className="text-xs text-go-secondary">{`${out.orderRef} · ${size(out)}`}</p>
            </div>
            <ReasonPicker required label="Reason for the store" value={reason} onChange={setReason} />
          </div>
        ) : undefined,
      }}
      chips={chips}
      footer={
        preview.error ? (
          <Refusal error={preview.error} what="the check of this swap" />
        ) : !outId ? (
          "Accept unlocks when every check passes"
        ) : preview.loading && !preview.data ? (
          "Checking the trip…"
        ) : feasible && !reasonReady(reason) ? (
          <span className="text-go-warning-text">✓ Every check passes · add a reason to accept</span>
        ) : feasible ? (
          <span className="text-go-teal">{`✓ Every check passes${sequence ? " · your stop order goes with the swap" : ""} · stores are told at publish`}</span>
        ) : (
          <span className="text-go-danger-strong">A rule refuses this swap: see the checks</span>
        )
      }
      accept={{
        label: "Accept changes",
        disabled: actions.busy || !feasible || !reasonReady(reason),
        onClick: () =>
          outId &&
          void actions.swap(outId, incoming.orderId, reason.trim(), sequence ?? undefined).then((ok) => ok && onClose()),
      }}
    />
  );
}

function Incoming({ order, lastServed }: { order: OrderView; lastServed: string | null }): React.JSX.Element {
  return (
    <div className="flex flex-col gap-0.5 rounded-go-input border border-go-rule px-3 py-2.5">
      <p className="text-[14px] font-medium text-go-ink">{`${order.outletId} ${order.districtName}`}</p>
      <p className="text-xs text-go-secondary">{`${order.orderRef} · ${size(order)}`}</p>
      {lastServed && <p className="text-xs font-medium text-go-warning-text">{lastServed}</p>}
    </div>
  );
}
