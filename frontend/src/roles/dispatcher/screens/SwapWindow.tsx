"use client";

import { useMemo, useState } from "react";
import type { OrderView, PlanView } from "@shared/domain/types";
import { Notice, Pill, PrimaryButton, SecondaryButton, Sheet } from "@shared/ui";
import { hhmm, ruleLabel } from "@shared/wording";
import { size } from "../data/orders.ts";
import { useSwapPreview } from "../data/usePlanReads.ts";
import CheckList from "./CheckList.tsx";
import { swapTrips } from "./DecisionPanel.tsx";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";
import Refusal from "./Refusal.tsx";
import type { PlanActions } from "./planActions.ts";

// Figma "Plan · 1b Swap window": a deferred order takes the place of an order on
// a trip, and the window shows the trip as it would run before anything is sent.
// Every candidate is an order on a trip the deferred order could ride (same
// brand, district and temperature); the server says whether the vehicle's whole
// day still passes, so a swap that breaks a rule cannot be approved.

export default function SwapWindow({
  plan,
  incoming,
  orders,
  actions,
  onClose,
}: {
  plan: PlanView;
  /** The deferred order that wants a place. */
  incoming: OrderView;
  orders: Map<string, OrderView>;
  actions: PlanActions;
  onClose: () => void;
}): React.JSX.Element {
  const candidates = useMemo(
    () =>
      swapTrips(plan, incoming).flatMap((trip) =>
        trip.stops.map((stop) => ({ trip, stop, order: orders.get(stop.orderId) })),
      ),
    [plan, incoming, orders],
  );
  const [outId, setOutId] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const preview = useSwapPreview(outId, incoming.orderId, plan.planId);
  const chosen = candidates.find((candidate) => candidate.stop.orderId === outId) ?? null;
  const failed = preview.data?.checks.find((check) => !check.passed) ?? null;

  return (
    <Sheet label="Swap window" onClose={onClose}>
      <div>
        <h2 className="text-[19px] font-medium text-go-ink">{`Swap in ${incoming.orderRef}`}</h2>
        <p className="text-xs text-go-secondary">{`${incoming.outletId} · ${incoming.districtName} · ${size(incoming)}. Pick the order it takes the place of.`}</p>
      </div>

      {candidates.length === 0 ? (
        <Notice tone="neutral" title="No trip to swap onto">
          No trip of this plan carries the same brand, district and temperature, so there is nothing to trade with.
        </Notice>
      ) : (
        <ul aria-label="Orders it could take the place of" className="flex max-h-56 flex-col gap-1.5 overflow-y-auto">
          {candidates.map(({ trip, stop, order }) => (
            <li key={stop.orderId}>
              <button
                type="button"
                aria-pressed={outId === stop.orderId}
                onClick={() => setOutId(stop.orderId)}
                className={`flex w-full items-center gap-2 rounded-go-card border px-3.5 py-2 text-left ${outId === stop.orderId ? "border-go-teal bg-go-success-tint" : "border-go-rule"}`}
              >
                <span className="min-w-0 flex-1">
                  <span className="block text-[14px] font-medium text-go-ink">{order?.orderRef ?? stop.orderId}</span>
                  <span className="block text-xs text-go-secondary">{`${stop.outletId}${order ? ` · ${size(order)}` : ""}`}</span>
                </span>
                <Pill tone="muted">{`${trip.vehicleId} Trip ${trip.tripNumber}`}</Pill>
              </button>
            </li>
          ))}
        </ul>
      )}

      {preview.error && <Refusal error={preview.error} what="the check of this swap" />}
      {chosen && preview.data && (
        <div className="flex flex-col gap-2 rounded-go-card bg-go-subtle p-3.5">
          <p role="status" className={`text-[13px] font-medium ${preview.data.feasible ? "text-go-success" : "text-go-danger-strong"}`}>
            {preview.data.feasible
              ? `${preview.data.vehicleId} Trip ${preview.data.tripNumber} can take it.`
              : `It cannot swap in: ${failed ? ruleLabel(failed.ruleId) : "a rule refuses it"}.`}
          </p>
          <ol aria-label="The trip after the swap" className="flex flex-col text-[13px] text-go-ink">
            {preview.data.stops.map((stop) => (
              <li key={stop.orderId} className="flex gap-3 border-t border-go-rule py-1.5 first:border-t-0">
                <span className="w-11 shrink-0 font-medium tabular-nums">{hhmm(stop.plannedArrival)}</span>
                <span className={stop.orderId === incoming.orderId ? "font-medium text-go-teal" : ""}>
                  {`${stop.outletId}${orders.get(stop.orderId) ? ` · ${orders.get(stop.orderId)!.orderRef}` : ""}`}
                </span>
              </li>
            ))}
          </ol>
          <CheckList checks={preview.data.checks} />
        </div>
      )}

      <ReasonPicker label="Why are you swapping them?" value={reason} onChange={setReason} />
      <div className="flex flex-wrap gap-2">
        <PrimaryButton
          disabled={actions.busy || !outId || !preview.data?.feasible || !reasonReady(reason)}
          onClick={() => outId && void actions.swap(outId, incoming.orderId, reason.trim()).then((ok) => ok && onClose())}
        >
          Approve swap
        </PrimaryButton>
        <SecondaryButton onClick={onClose}>Cancel</SecondaryButton>
      </div>
    </Sheet>
  );
}
