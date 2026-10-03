"use client";

import { useState } from "react";
import type { OrderView, PlacementView, PlanView, VehicleView } from "@shared/domain/types";
import { PrimaryButton, SecondaryButton } from "@shared/ui";
import { hhmm, ruleLabel } from "@shared/wording";
import type { TripLoad } from "../data/plan.ts";
import { usePlacements, useSequencePreview } from "../data/usePlanReads.ts";
import CheckList from "./CheckList.tsx";
import type { PlanActions } from "./planActions.ts";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";
import Refusal from "./Refusal.tsx";

// Figma "Plan · Edit trip": reorder a trip's stops, take an order off it, or move
// an order to another trip, with the checks updating as the dispatcher changes
// it. The new stop order is timed and judged by the server before it is sent, so
// an order that would make a stop late shows the rule and cannot be saved.

type Panel = { kind: "take" | "move"; orderId: string } | null;

function move<T>(list: T[], from: number, to: number): T[] {
  const next = [...list];
  const [item] = next.splice(from, 1);
  next.splice(to, 0, item!);
  return next;
}

export default function EditTrip({
  plan,
  load,
  orders,
  fleet,
  actions,
  onDone,
}: {
  plan: PlanView;
  load: TripLoad;
  orders: Map<string, OrderView>;
  fleet: VehicleView[];
  actions: PlanActions;
  onDone: () => void;
}): React.JSX.Element {
  const { trip } = load;
  const original = trip.stops.map((stop) => stop.orderId);
  const [sequence, setSequence] = useState(original);
  const [reason, setReason] = useState("");
  const [panel, setPanel] = useState<Panel>(null);
  const [panelReason, setPanelReason] = useState("");
  const [target, setTarget] = useState<string | null>(null);

  const changed = sequence.some((id, index) => id !== original[index]);
  const preview = useSequencePreview(changed ? trip.tripId : null, changed ? sequence : null, plan.planId);
  const failed = preview.data?.checks.find((check) => !check.passed) ?? null;
  const places = usePlacements(plan.planId, panel?.kind === "move" ? [panel.orderId] : []);
  const fits = (places.data?.[panel?.orderId ?? ""] ?? []).filter((place) => place.feasible && !(place.vehicleId === trip.vehicleId && place.tripNumber === trip.tripNumber));
  const chosen = fits.find((place) => `${place.vehicleId}/${place.tripNumber}` === target) ?? null;
  const vehicleKind = (place: PlacementView) => fleet.find((v) => v.vehicleId === place.vehicleId)?.refrigerated ? "refrigerated" : "ambient";

  return (
    <div className="flex flex-col gap-3">
      <ol aria-label="Stops, reorder them here" className="flex flex-col gap-1.5">
        {sequence.map((orderId, index) => {
          const stop = trip.stops.find((s) => s.orderId === orderId)!;
          const order = orders.get(orderId);
          return (
            <li key={orderId} className="flex items-center gap-2 rounded-go-card bg-go-subtle px-3 py-2">
              <span className="w-5 shrink-0 text-[13px] font-medium text-go-secondary">{index + 1}</span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[13px] font-medium text-go-ink">{`${stop.outletId}${order ? ` · ${order.orderRef}` : ""}`}</span>
                <span className="block text-xs text-go-secondary">{`${hhmm(stop.windowOpen)}-${hhmm(stop.windowClose)}`}</span>
              </span>
              <button type="button" aria-label={`Move ${stop.outletId} earlier`} disabled={index === 0} onClick={() => setSequence(move(sequence, index, index - 1))} className="rounded-full bg-go-card px-2 py-1 text-xs font-medium disabled:opacity-40">
                Up
              </button>
              <button type="button" aria-label={`Move ${stop.outletId} later`} disabled={index === sequence.length - 1} onClick={() => setSequence(move(sequence, index, index + 1))} className="rounded-full bg-go-card px-2 py-1 text-xs font-medium disabled:opacity-40">
                Down
              </button>
              <button type="button" onClick={() => setPanel({ kind: "take", orderId })} className="text-xs font-medium text-go-teal">
                Take off
              </button>
              <button type="button" onClick={() => setPanel({ kind: "move", orderId })} className="text-xs font-medium text-go-teal">
                Move
              </button>
            </li>
          );
        })}
      </ol>

      {changed && (
        <div className="flex flex-col gap-2 rounded-go-card bg-go-subtle p-3">
          {preview.error && <Refusal error={preview.error} what="the check of this stop order" />}
          {preview.data && (
            <p role="status" className={`text-[13px] font-medium ${preview.data.feasible ? "text-go-success" : "text-go-danger-strong"}`}>
              {preview.data.feasible ? "Every stop is still on time." : `This order does not work: ${failed ? ruleLabel(failed.ruleId) : "a rule refuses it"}.`}
            </p>
          )}
          {preview.data && !preview.data.feasible && failed && <p className="text-xs text-go-ink">{failed.reason}</p>}
          {preview.data && <CheckList checks={preview.data.checks} />}
          <ReasonPicker label="Why change the stop order?" value={reason} onChange={setReason} />
          <PrimaryButton
            disabled={actions.busy || !preview.data?.feasible || !reasonReady(reason)}
            onClick={() => void actions.reorder(trip.tripId, sequence, reason.trim()).then((ok) => ok && onDone())}
          >
            Save stop order
          </PrimaryButton>
        </div>
      )}

      {panel?.kind === "take" && (
        <div className="flex flex-col gap-2 rounded-go-card bg-go-subtle p-3">
          <ReasonPicker label={`Why is ${orders.get(panel.orderId)?.orderRef ?? "this order"} taken off?`} value={panelReason} onChange={setPanelReason} />
          <div className="flex gap-2">
            <PrimaryButton disabled={actions.busy || !reasonReady(panelReason)} onClick={() => void actions.defer(panel.orderId, panelReason.trim()).then((ok) => ok && onDone())}>
              Take it off the trip
            </PrimaryButton>
            <SecondaryButton onClick={() => setPanel(null)}>Cancel</SecondaryButton>
          </div>
        </div>
      )}

      {panel?.kind === "move" && (
        <div className="flex flex-col gap-2 rounded-go-card bg-go-subtle p-3">
          <p className="text-[13px] font-medium text-go-ink">{`Move ${orders.get(panel.orderId)?.orderRef ?? "this order"} to`}</p>
          {places.error && <Refusal error={places.error} what="the places for this order" />}
          {places.loading && !places.data && <p className="text-[13px] text-go-secondary">Checking every vehicle…</p>}
          {places.data && fits.length === 0 && <p className="text-[13px] text-go-ink">It fits on no other trip.</p>}
          <div role="radiogroup" aria-label="Trips it can move to" className="flex flex-col gap-1.5">
            {fits.map((place) => (
              <button
                key={`${place.vehicleId}/${place.tripNumber}`}
                type="button"
                role="radio"
                aria-checked={target === `${place.vehicleId}/${place.tripNumber}`}
                onClick={() => setTarget(`${place.vehicleId}/${place.tripNumber}`)}
                className={`rounded-go-card border px-3 py-2 text-left text-[13px] ${target === `${place.vehicleId}/${place.tripNumber}` ? "border-go-teal bg-go-success-tint" : "border-go-rule"}`}
              >
                <span className="font-medium text-go-ink">{`${place.vehicleId} · Trip ${place.tripNumber}`}</span>
                <span className="block text-xs text-go-secondary">{`${vehicleKind(place)} · ${place.joins ? "joins the trip already planned" : "opens a new trip"}`}</span>
              </button>
            ))}
          </div>
          <ReasonPicker label="Why is it moving?" value={panelReason} onChange={setPanelReason} />
          <div className="flex gap-2">
            <PrimaryButton
              disabled={actions.busy || !chosen || !reasonReady(panelReason)}
              onClick={() =>
                chosen &&
                void actions
                  .place({ orderId: panel.orderId, vehicleId: chosen.vehicleId, tripNumber: chosen.tripNumber as 1 | 2, reason: panelReason.trim() })
                  .then((ok) => ok && onDone())
              }
            >
              Move the order
            </PrimaryButton>
            <SecondaryButton onClick={() => setPanel(null)}>Cancel</SecondaryButton>
          </div>
        </div>
      )}

      <SecondaryButton onClick={onDone}>Done</SecondaryButton>
    </div>
  );
}
