"use client";

import { useState } from "react";
import type { PlacementView, PlanView, TripView } from "@shared/domain/types";
import { Pill, PrimaryButton, SecondaryButton, Switch } from "@shared/ui";
import { dayLabel, ruleLabel, temperatureLabel } from "@shared/wording";
import { STATUS, size } from "../data/orders.ts";
import { daysBetween, lastServedText, type DecisionRow } from "../data/planViews.ts";
import { useNextDelivery } from "../data/usePlanReads.ts";
import CheckList from "./CheckList.tsx";
import type { PlanActions } from "./planActions.ts";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";

// Figma "Plan · 1 Decide", the panel beside the list: the order, why it was not
// placed, where it could go, and the two things a dispatcher does with it that
// are not placing it: swap it with an order on a trip, or keep it deferred.
// Placing is select and place (PLAN.md decision 1): every candidate comes from
// the server with every rule's verdict, so nothing is offered that the override
// would refuse.

const key = (place: PlacementView) => `${place.vehicleId}/${place.tripNumber}`;

export default function DecisionPanel({
  plan,
  row,
  places,
  editable,
  actions,
  onSwap,
}: {
  plan: PlanView;
  row: DecisionRow;
  /** Every place the order could take, with every rule's verdict; null while they load. */
  places: PlacementView[] | null;
  editable: boolean;
  actions: PlanActions;
  onSwap: () => void;
}): React.JSX.Element {
  const { allocation, order } = row;
  const next = useNextDelivery(plan.serviceDate);
  const [chosen, setChosen] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [onlyFits, setOnlyFits] = useState(true);

  const open = row.state === "open";
  const all = places ?? [];
  const fits = all.filter((place) => place.feasible);
  const shown = onlyFits ? fits : all;
  const target = fits.find((place) => key(place) === chosen) ?? null;
  const days = allocation.lastServedOn ? daysBetween(allocation.lastServedOn, plan.serviceDate) : null;

  return (
    <section aria-label="Decision" className="flex w-full flex-col gap-3 rounded-[24px] bg-go-card p-5 shadow-go-card lg:max-w-[380px]">
      <div>
        <h2 className="text-[19px] font-medium text-go-ink">{order?.orderRef ?? allocation.orderId}</h2>
        {order && (
          <p className="text-xs text-go-secondary">
            {order.outletId} · {order.districtName} · {order.brandCode} · {temperatureLabel(order.temperature)} · {size(order)}
          </p>
        )}
        <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
          {order && order.deferralCount > 0 && <Pill tone="warning">{`Priority · deferred ${order.deferralCount}×`}</Pill>}
          {order && <Pill tone="muted">{STATUS[order.status].label}</Pill>}
          <Pill tone="muted">{lastServedText(allocation.lastServedOn, plan.serviceDate)}</Pill>
        </div>
      </div>

      {row.state === "placed" && (
        <div className="flex flex-col gap-2 rounded-go-card bg-go-success-tint px-3.5 py-3 text-[13px] text-go-ink">
          <p className="font-medium">{`Placed on ${row.placedOn ?? "a trip"} by hand`}</p>
          <Switch
            label="Lock"
            hint="stays here when you regenerate"
            checked={allocation.locked}
            disabled={!editable || actions.busy}
            onChange={(next) => void actions.lock(allocation.orderId, next)}
          />
        </div>
      )}

      {row.state === "kept" && (
        <div className="rounded-go-card bg-go-subtle px-3.5 py-3 text-[13px] text-go-ink">
          <p className="font-medium">Kept deferred</p>
          <p className="text-go-secondary">{allocation.reason}</p>
        </div>
      )}

      {open && (
        <div className="rounded-go-card bg-go-warning-tint px-3.5 py-3 text-[13px] text-go-warning-text">
          <p className="font-medium">
            Why it was not placed
            {allocation.bindingRule && <span title={allocation.bindingRule}>{` · ${ruleLabel(allocation.bindingRule)}`}</span>}
          </p>
          <p>{allocation.reason}</p>
        </div>
      )}
      {open && <CheckList checks={allocation.checks} />}

      {open && editable && (
        <>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-[15px] font-medium text-go-ink">Candidate trips</h3>
            <Switch label="Only trips it can fit" checked={onlyFits} onChange={setOnlyFits} />
          </div>
          {places === null && <p className="text-[13px] text-go-secondary">Checking every vehicle…</p>}
          {places !== null && fits.length === 0 && (
            <p className="text-[13px] text-go-ink">It fits on no trip of any available vehicle. Swap it with an order on a trip, or keep it deferred: it is offered first on the next plan.</p>
          )}
          <div role="radiogroup" aria-label="Trips this order could take" className="flex flex-col gap-2">
            {shown.map((place) => (
              <TripChoice key={key(place)} place={place} checked={chosen === key(place)} only={fits.length === 1 && place.feasible} onChoose={() => setChosen(key(place))} />
            ))}
          </div>
          <ReasonPicker label="Why are you deciding this by hand?" value={reason} onChange={setReason} />
          <div className="flex flex-wrap gap-2">
            <PrimaryButton
              disabled={actions.busy || !target || !reasonReady(reason)}
              onClick={() => target && void actions.place({ orderId: allocation.orderId, vehicleId: target.vehicleId, tripNumber: target.tripNumber as 1 | 2, reason: reason.trim() })}
            >
              {target ? `Place on ${target.vehicleId} trip ${target.tripNumber}` : "Place on a trip"}
            </PrimaryButton>
            <SecondaryButton disabled={actions.busy} onClick={onSwap}>
              Open swap window
            </SecondaryButton>
          </div>
        </>
      )}
      {!editable && open && <p className="text-[13px] text-go-secondary">This plan is read only. Revise it to decide this order.</p>}

      <div className="mt-auto flex flex-col gap-1 border-t border-go-rule pt-3 text-[13px]">
        <p className="flex justify-between text-go-secondary">
          If deferred · next delivery
          <span className="font-medium text-go-ink">{next.data ? `${dayLabel(next.data)} · first` : "…"}</span>
        </p>
        <p className="flex justify-between text-go-secondary">
          Days without delivery
          <span className="font-medium text-go-ink">{days === null ? "Never served" : days}</span>
        </p>
        {open && editable && (
          <PrimaryButton disabled={actions.busy || !reasonReady(reason)} onClick={() => void actions.keepDeferred([allocation.orderId], reason.trim())}>
            Keep deferred
          </PrimaryButton>
        )}
      </div>
    </section>
  );
}

function TripChoice({ place, checked, only, onChoose }: { place: PlacementView; checked: boolean; only: boolean; onChoose: () => void }): React.JSX.Element {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={checked}
      disabled={!place.feasible}
      onClick={onChoose}
      className={`rounded-go-card border px-3.5 py-2.5 text-left disabled:cursor-not-allowed disabled:opacity-60 ${checked ? "border-go-teal bg-go-success-tint" : "border-go-rule"}`}
    >
      <span className="flex items-center gap-2">
        <span className="text-[14px] font-medium text-go-ink">{`${place.vehicleId} · Trip ${place.tripNumber}`}</span>
        {only && <Pill tone="danger">Only option</Pill>}
      </span>
      <span className="block text-xs text-go-secondary">
        {place.feasible ? (place.joins ? "Joins the trip already planned" : "Opens a new trip") : `${ruleLabel(place.bindingRule)}: ${place.reason}`}
      </span>
    </button>
  );
}

/** The trips a deferred order could swap onto: same brand, district and temperature, so a rule is not the first thing to refuse. */
export function swapTrips(plan: PlanView, order: { brandCode: string; districtName: string; temperature: string } | undefined): TripView[] {
  if (!order) return [];
  return plan.trips.filter((trip) => trip.brandCode === order.brandCode && trip.districtName === order.districtName && trip.temperature === order.temperature);
}
