"use client";

import { useState } from "react";
import type { AllocationView, OrderView, PlanView, VehicleView } from "@shared/domain/types";
import { Icon, PrimaryButton, SecondaryButton, Sheet } from "@shared/ui";
import { dayLabel, ruleLabel } from "@shared/wording";
import { size } from "../data/orders.ts";
import { lastServedText } from "../data/planViews.ts";
import { useNextDelivery, usePlacements } from "../data/usePlanReads.ts";
import { swapTrips } from "./DecisionPanel.tsx";
import SwapWindow from "./SwapWindow.tsx";
import type { PlanActions } from "./planActions.ts";

// Figma "Overlay · Deferred order": a deferred card on the plan board opens the
// order's facts and the best way out: a place the server says it fits, else a
// trip it could swap onto. Swap opens the swap window, Keep deferred records the
// rule as the reason, and Open in Decide goes to the full decision.

export default function DeferredOrderCard({
  plan,
  allocation,
  order,
  orders,
  fleet,
  editable,
  actions,
  onDecide,
  onClose,
}: {
  plan: PlanView;
  allocation: AllocationView;
  order: OrderView;
  orders: Map<string, OrderView>;
  fleet: VehicleView[];
  editable: boolean;
  actions: PlanActions;
  onDecide: () => void;
  onClose: () => void;
}): React.JSX.Element {
  const [swapping, setSwapping] = useState(false);
  const next = useNextDelivery(plan.serviceDate);
  const places = usePlacements(plan.planId, editable ? [order.orderId] : []);
  const fit = (places.data?.[order.orderId] ?? []).find((place) => place.feasible) ?? null;
  const swap = swapTrips(plan, order)[0] ?? null;
  const kept = allocation.source === "KEPT" || allocation.source === "MANUAL_DEFER";
  const tooBig = allocation.decision === "UNSERVABLE";
  const best = fit
    ? `Place on ${fit.vehicleId} Trip ${fit.tripNumber} · ${fit.joins ? "joins the trip" : "opens a new trip"}`
    : swap
      ? `Swap onto ${swap.vehicleId} Trip ${swap.tripNumber} · one of its stops would wait`
      : tooBig
        ? "Too big for any vehicle: split it or ask the store for a new order"
        : places.loading
          ? "Checking every vehicle…"
          : "No trip can take it today";

  if (swapping) return <SwapWindow plan={plan} incoming={order} orders={orders} fleet={fleet} actions={actions} onClose={onClose} />;

  return (
    <Sheet label={`Deferred order ${order.orderRef}`} onClose={onClose}>
      <header className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-semibold tracking-[0.08em] text-go-warning-text uppercase">{tooBig ? "Cannot be served" : kept ? "Kept deferred" : "Deferred · needs a decision"}</p>
          <h2 className="text-[22px] font-medium text-go-ink">{order.orderRef}</h2>
          <p className="text-[13px] text-go-secondary">{`${order.brandCode} ${order.outletId} ${order.districtName}`}</p>
        </div>
        <button type="button" onClick={onClose} aria-label="Close" className="rounded-full p-2 hover:bg-go-subtle">
          <Icon name="close" />
        </button>
      </header>
      <dl className="grid grid-cols-2 gap-2">
        <Fact label="Size" value={size(order)} />
        <Fact label="Next delivery" value={next.data ? `${dayLabel(next.data)} · first` : "-"} />
        <Fact label="Last served" value={lastServedText(allocation.lastServedOn, plan.serviceDate)} warn />
        <Fact label="Reason" value={tooBig ? "Too big for any vehicle" : ruleLabel(allocation.bindingRule)} />
      </dl>
      {editable && (
        <div className="rounded-go-input bg-go-success-tint px-3 py-2.5">
          <p className="text-[11px] font-semibold tracking-[0.08em] text-go-teal uppercase">Best option</p>
          <p className="text-[13px] text-go-ink">{best}</p>
        </div>
      )}
      <div className="-mx-5 flex flex-wrap gap-2 border-t border-go-rule px-5 pt-4 md:-mx-7 md:px-7">
        {editable && swap && <PrimaryButton onClick={() => setSwapping(true)}>Swap…</PrimaryButton>}
        {editable && !kept && !tooBig && (
          <SecondaryButton
            disabled={actions.busy}
            onClick={() => void actions.keepDeferred([order.orderId], `Kept deferred: ${ruleLabel(allocation.bindingRule)}`).then((ok) => ok && onClose())}
          >
            Keep deferred
          </SecondaryButton>
        )}
        <button type="button" onClick={onDecide} className="rounded-full bg-go-surface px-4 py-2.5 text-sm font-medium text-go-ink">
          Open in Decide
        </button>
      </div>
    </Sheet>
  );
}

function Fact({ label, value, warn = false }: { label: string; value: string; warn?: boolean }): React.JSX.Element {
  return (
    <div className={`rounded-go-input px-3 py-2 ${warn ? "bg-go-warning-tint" : "bg-go-surface"}`}>
      <dt className="text-[10px] font-semibold tracking-[0.06em] text-go-secondary uppercase">{label}</dt>
      <dd className={`text-[14px] font-medium ${warn ? "text-go-warning-text" : "text-go-ink"}`}>{value}</dd>
    </div>
  );
}
