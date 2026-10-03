"use client";

import { useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { ConstraintResultView, PlacementView, PlanView } from "@shared/domain/types";
import { Pill, PrimaryButton } from "@shared/ui";
import { STATUS, size } from "../data/orders.ts";
import type { Decision } from "../data/plan.ts";
import Refusal from "./Refusal.tsx";

// Figma "Plan · 1 Decide": the orders the engine did not place, each with the
// rule that stopped it, and where the dispatcher could still put it. Placing is
// select and place (PLAN.md decision 1): every candidate comes from the server
// with every rule's verdict, so nothing is offered that the override would refuse.

export type Place = { orderId: string; vehicleId: string; tripNumber: 1 | 2; reason: string };

export default function PlanDecide({
  plan,
  decisions,
  editable,
  busy,
  onPlace,
}: {
  plan: PlanView;
  decisions: Decision[];
  /** A draft, online. A published plan is read only until it is revised. */
  editable: boolean;
  busy: boolean;
  onPlace: (place: Place) => void;
}): React.JSX.Element {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = decisions.find((decision) => decision.allocation.orderId === selectedId) ?? decisions[0] ?? null;

  if (decisions.length === 0) {
    return (
      <section aria-label="Decisions" className="rounded-[24px] bg-white px-6 py-8 text-center shadow-go-card">
        <h2 className="text-[19px] font-medium text-go-ink">Every order is on a trip</h2>
        <p className="mt-1 text-[13px] text-go-secondary">Nothing was deferred. Check the trips, then publish.</p>
      </section>
    );
  }

  return (
    <div className="flex min-h-0 w-full flex-1 gap-[18px] max-lg:flex-col">
      <section aria-label="Orders needing a decision" className="flex min-w-0 flex-1 flex-col gap-2 rounded-[24px] bg-white p-4 shadow-go-card">
        <h2 className="px-1 pb-1 text-[19px] font-medium text-go-ink">
          {decisions.length} {decisions.length === 1 ? "order was" : "orders were"} not placed
        </h2>
        <ul className="flex flex-col gap-2">
          {decisions.map(({ allocation, order }) => {
            const active = selected?.allocation.orderId === allocation.orderId;
            return (
              <li key={allocation.orderId}>
                <button
                  type="button"
                  aria-pressed={active}
                  onClick={() => setSelectedId(allocation.orderId)}
                  className={`flex w-full flex-wrap items-center gap-x-3 gap-y-1 rounded-go-card px-4 py-3 text-left ${active ? "bg-go-success-tint" : "bg-go-subtle"}`}
                >
                  <span className="min-w-[180px] flex-1">
                    <span className="block text-[15px] font-medium text-go-ink">{order?.orderRef ?? allocation.orderId}</span>
                    {order && (
                      <span className="block text-xs text-go-secondary">
                        {order.outletId} · {order.districtName} · {order.brandCode} · {size(order)} · {order.temperature}
                      </span>
                    )}
                  </span>
                  {order && order.deferralCount > 0 && <span className="text-xs font-medium text-go-warning-text">Deferred {order.deferralCount}× before</span>}
                  <Pill tone={allocation.decision === "UNSERVABLE" ? "danger" : "warning"}>
                    {allocation.decision === "UNSERVABLE" ? "Cannot be served" : "Deferred"} · {allocation.bindingRule ?? "no rule"}
                  </Pill>
                </button>
              </li>
            );
          })}
        </ul>
      </section>

      {selected && <DecisionPanel key={`${plan.planId}:${selected.allocation.orderId}`} plan={plan} decision={selected} editable={editable} busy={busy} onPlace={onPlace} />}
    </div>
  );
}

function DecisionPanel({
  plan,
  decision,
  editable,
  busy,
  onPlace,
}: {
  plan: PlanView;
  decision: Decision;
  editable: boolean;
  busy: boolean;
  onPlace: (place: Place) => void;
}): React.JSX.Element {
  const { allocation, order } = decision;
  const placeable = editable && allocation.decision === "DEFERRED";
  const places = useResource<PlacementView[]>(
    placeable ? (signal) => request<PlacementView[]>(`/api/plans/preview/placements?order=${encodeURIComponent(allocation.orderId)}`, { signal }) : null,
    `${plan.planId}:${allocation.orderId}`,
  );
  const [chosen, setChosen] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  const [showRefused, setShowRefused] = useState(false);

  const all = places.data ?? [];
  const fits = all.filter((place) => place.feasible);
  const refused = all.filter((place) => !place.feasible);
  const key = (place: PlacementView) => `${place.vehicleId}/${place.tripNumber}`;
  const target = fits.find((place) => key(place) === chosen) ?? null;

  return (
    <section aria-label="Decision" className="flex w-full flex-col gap-3 rounded-[24px] bg-white p-5 shadow-go-card lg:max-w-[380px]">
      <div>
        <h2 className="text-[19px] font-medium text-go-ink">{order?.orderRef ?? allocation.orderId}</h2>
        {order && (
          <p className="text-xs text-go-secondary">
            {order.outletId} · {order.districtName} · {order.brandCode} · {order.temperature} · {size(order)}
          </p>
        )}
        {order && <p className="mt-1 text-xs text-go-secondary">Order status: {STATUS[order.status].label}</p>}
      </div>

      <div className="rounded-go-card bg-go-warning-tint px-3.5 py-3 text-[13px] text-go-warning-text">
        <p className="font-medium">
          Why it was not placed <code className="ml-1 text-[11px]">{allocation.bindingRule}</code>
        </p>
        <p>{allocation.reason}</p>
      </div>
      <Checks checks={allocation.checks} />

      {allocation.decision === "UNSERVABLE" && (
        <p className="text-[13px] text-go-ink">No vehicle of this depot can carry this order, so it cannot be placed by hand. The store manager is told it cannot be served.</p>
      )}

      {placeable && (
        <>
          <h3 className="text-[15px] font-medium text-go-ink">Where it could go</h3>
          {places.error && <Refusal error={places.error} what="the places for this order" />}
          {places.loading && !places.data && <p className="text-[13px] text-go-secondary">Checking every vehicle…</p>}
          {places.data && fits.length === 0 && <p className="text-[13px] text-go-ink">It fits on no trip of any available vehicle. It stays deferred and is offered first on the next plan.</p>}
          <div role="radiogroup" aria-label="Trips this order fits on" className="flex flex-col gap-2">
            {fits.map((place) => (
              <button
                key={key(place)}
                type="button"
                role="radio"
                aria-checked={chosen === key(place)}
                onClick={() => setChosen(key(place))}
                className={`rounded-go-card border px-3.5 py-2.5 text-left ${chosen === key(place) ? "border-go-teal bg-go-success-tint" : "border-go-rule"}`}
              >
                <span className="block text-[14px] font-medium text-go-ink">
                  {place.vehicleId} · Trip {place.tripNumber}
                </span>
                <span className="block text-xs text-go-secondary">{place.joins ? "Joins the trip already planned" : "Opens a new trip"}</span>
              </button>
            ))}
          </div>
          {refused.length > 0 && (
            <button type="button" onClick={() => setShowRefused((value) => !value)} aria-expanded={showRefused} className="self-start text-[13px] font-medium text-go-teal">
              {showRefused ? "Hide" : "Show"} {refused.length} {refused.length === 1 ? "place" : "places"} it does not fit
            </button>
          )}
          {showRefused && (
            <ul className="flex flex-col gap-1.5">
              {refused.map((place) => (
                <li key={key(place)} className="rounded-go-card bg-go-subtle px-3.5 py-2 text-xs text-go-secondary">
                  <span className="font-medium text-go-ink">
                    {place.vehicleId} · Trip {place.tripNumber}
                  </span>{" "}
                  <code>{place.bindingRule}</code> {place.reason}
                </li>
              ))}
            </ul>
          )}
          {target && (
            <>
              <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
                Why are you placing it by hand?
                <input
                  value={reason}
                  maxLength={300}
                  onChange={(event) => setReason(event.target.value)}
                  placeholder="Recorded with the plan"
                  className="rounded-go-input border border-go-rule px-3 py-2.5 text-[14px] font-normal outline-none focus:border-go-teal"
                />
              </label>
              <PrimaryButton
                disabled={busy || reason.trim().length === 0}
                onClick={() => onPlace({ orderId: allocation.orderId, vehicleId: target.vehicleId, tripNumber: target.tripNumber, reason: reason.trim() })}
              >
                Place on {target.vehicleId} trip {target.tripNumber}
              </PrimaryButton>
            </>
          )}
        </>
      )}
      {!editable && allocation.decision === "DEFERRED" && (
        <p className="text-[13px] text-go-secondary">This plan is read only. Revise it to place this order.</p>
      )}
    </section>
  );
}

/** Every rule the engine evaluated for this order, on expand (PLAN.md decision 2). */
export function Checks({ checks }: { checks: ConstraintResultView[] }): React.JSX.Element | null {
  if (checks.length === 0) return null;
  return (
    <details className="rounded-go-card bg-go-subtle px-3.5 py-2.5 text-[13px] text-go-ink">
      <summary className="cursor-pointer font-medium">All {checks.length} checks</summary>
      <ul className="mt-2 flex flex-col gap-1.5">
        {checks.map((check, index) => (
          <li key={`${check.ruleId}-${index}`} className="flex gap-2 text-xs">
            <span className={`shrink-0 font-medium ${check.passed ? "text-go-success" : "text-go-danger-strong"}`}>{check.passed ? "Passed" : "Failed"}</span>
            <code className="shrink-0">{check.ruleId}</code>
            <span className="text-go-secondary">
              {check.reason}
              {check.slack !== null && ` · slack ${check.slack}`}
            </span>
          </li>
        ))}
      </ul>
    </details>
  );
}
