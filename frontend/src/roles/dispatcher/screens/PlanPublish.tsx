"use client";

import { useState } from "react";
import type { OrderView, PlanView } from "@shared/domain/types";
import { Notice, Pill, PrimaryButton, SecondaryButton } from "@shared/ui";
import { clock, dayLabel, ruleLabel } from "@shared/wording";
import type { PlanSummary, Working } from "../data/plan.ts";
import type { DecisionRow } from "../data/planViews.ts";
import { publishBlocker } from "../data/planViews.ts";
import { useComparison } from "../data/usePlanReads.ts";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";

// Figma "Plan · 3 Publish". Publishing is a deliberate second step, and two
// things stand in front of it. Every order the engine deferred needs a
// dispatcher's decision first (3a), and the server's gate then decides: if the
// demand, the reference data or a rule changed since the draft was made, it
// refuses and names every reason (PLN-07, PLN-14). A published plan is
// immutable; changing it starts a revision, and sending that revision tells
// only the drivers and stores it changes (R-NOT-12).

export default function PlanPublish({
  state,
  plan,
  orders,
  depot,
  date,
  summary,
  rows,
  online,
  busy,
  reviseReason,
  onReviseReason,
  onPublish,
  onRevise,
  onDecide,
}: {
  state: Exclude<Working, { stage: "none" }>;
  plan: PlanView;
  orders: Map<string, OrderView>;
  depot: string;
  date: string;
  summary: PlanSummary;
  rows: DecisionRow[];
  online: boolean;
  busy: boolean;
  reviseReason: string;
  onReviseReason: (reason: string) => void;
  onPublish: () => void;
  onRevise: () => void;
  /** Jumps back to the Decide step at the first order still waiting. */
  onDecide: (orderId: string) => void;
}): React.JSX.Element {
  const [confirming, setConfirming] = useState(false);
  const blocker = publishBlocker(rows);
  const blocked = state.stage === "draft" && blocker.open > 0;
  const revises = state.stage === "draft" ? state.revises : null;
  const left = plan.allocations.filter((a) => a.decision !== "SERVED");
  const changes = useComparison(revises ? revises.planId : null, revises ? plan.planId : null);
  const told = changes.data ? changes.data.changedTrips.length : null;

  return (
    <section aria-label="Publish" className="flex w-full max-w-[820px] flex-col gap-4 rounded-[24px] bg-go-card p-6 shadow-go-card">
      <div>
        <h2 className="text-[19px] font-medium text-go-ink">
          {state.stage === "published" ? "This plan is published" : revises ? "Send this update" : "Publish this plan"}
        </h2>
        <p className="text-[13px] text-go-secondary">
          {`${depot} · ${dayLabel(date)} · version ${plan.planVersion} · ${summary.served} of ${summary.orders} orders on ${summary.trips} trips, ${summary.vehiclesUsed} vehicles`}
        </p>
      </div>

      {blocked && (
        <Notice
          tone="danger"
          title={`Publishing is blocked: ${blocker.open} ${blocker.open === 1 ? "order still needs" : "orders still need"} a decision`}
          action={
            blocker.first && (
              <SecondaryButton onClick={() => onDecide(blocker.first!.allocation.orderId)}>
                Decide now
              </SecondaryButton>
            )
          }
        >
          Place each one, swap it with an order on a trip, or keep it deferred and say why.
        </Notice>
      )}

      {revises && (
        <div className="flex flex-col gap-2 rounded-go-card bg-go-warning-tint px-4 py-3 text-[13px] text-go-ink">
          <p className="font-medium text-go-warning-text">
            {told === null ? "Checking what changes…" : told === 0 ? "No trip changes: nobody on the road or at a store is told." : `${told} ${told === 1 ? "change is" : "changes are"} not sent yet`}
          </p>
          {changes.data && told !== null && told > 0 && (
            <p>
              {`Sending tells the drivers of ${told} ${told === 1 ? "trip" : "trips"}`}
              {changes.data.affectedOutlets.length > 0 ? ` and ${changes.data.affectedOutlets.length} ${changes.data.affectedOutlets.length === 1 ? "outlet" : "outlets"}` : ""}
              {". Everyone else keeps the plan they have."}
            </p>
          )}
        </div>
      )}

      {left.length > 0 && (
        <div className="flex flex-col gap-2">
          <h3 className="text-[15px] font-medium text-go-ink">{state.stage === "published" ? "Not delivered by this plan" : "Publishing leaves these undelivered"}</h3>
          <ul className="flex flex-col">
            {left.map((allocation) => {
              const order = orders.get(allocation.orderId);
              return (
                <li key={allocation.orderId} className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-go-rule py-2 text-[13px] text-go-ink">
                  <span className="min-w-[110px] font-medium">{order?.orderRef ?? allocation.orderId}</span>
                  <span className="min-w-[140px] flex-1 text-go-secondary">{order ? `${order.outletId} · ${order.districtName}` : ""}</span>
                  <Pill tone={allocation.decision === "UNSERVABLE" ? "danger" : "warning"}>
                    {`${allocation.decision === "UNSERVABLE" ? "Cannot be served" : "Deferred"} · ${ruleLabel(allocation.bindingRule)}`}
                  </Pill>
                  <span className="w-full text-xs text-go-secondary">{allocation.reason}</span>
                </li>
              );
            })}
          </ul>
          {state.stage === "draft" && left.some((a) => a.decision === "DEFERRED") && (
            <p className="text-xs text-go-secondary">A deferred order is offered first on the next plan, and its store is told.</p>
          )}
        </div>
      )}

      {state.stage === "draft" &&
        (confirming ? (
          <div className="flex flex-wrap items-center gap-2 rounded-go-card bg-go-success-tint p-3">
            <p className="min-w-[220px] flex-1 text-[13px] font-medium text-go-ink">
              {revises ? "Send this change to the loaders and drivers?" : `Publish for ${depot} on ${dayLabel(date)}? It cannot be edited afterwards, only revised.`}
            </p>
            <PrimaryButton disabled={!online || busy || blocked} onClick={onPublish}>
              {busy ? "Sending…" : revises ? "Send update" : "Confirm publish"}
            </PrimaryButton>
            <SecondaryButton onClick={() => setConfirming(false)}>Cancel</SecondaryButton>
          </div>
        ) : (
          <div>
            <PrimaryButton disabled={!online || busy || blocked} onClick={() => setConfirming(true)}>
              {revises ? "Send update" : "Publish plan"}
            </PrimaryButton>
          </div>
        ))}

      {state.stage === "published" && (
        <>
          <Notice tone="info" title={`Published${state.plan.publishedAt ? ` at ${clock(new Date(state.plan.publishedAt))}` : ""}. Loaders and drivers work from this version.`}>
            It cannot be edited. To change it, start a revision: it becomes a draft, and nothing changes on the dock or the road until that draft is sent.
          </Notice>
          <ReasonPicker
            label="Why is the plan being revised?"
            value={reviseReason}
            onChange={onReviseReason}
            placeholder="For example: a vehicle broke down, new orders were confirmed"
          />
          <div>
            <SecondaryButton disabled={!online || busy || !reasonReady(reviseReason)} onClick={onRevise}>
              Start a revision
            </SecondaryButton>
          </div>
        </>
      )}
    </section>
  );
}
