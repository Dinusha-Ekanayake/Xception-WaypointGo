"use client";

import { useState } from "react";
import { Notice, Pill, PrimaryButton, SecondaryButton } from "@shared/ui";
import type { Decision, PlanSummary, Working } from "../data/plan.ts";
import { dayLabel } from "../data/scope.ts";
import { clock } from "@shared/wording";

// Figma "Plan · 3 Publish". Publishing is a deliberate second step, and the
// server's gate decides: if the demand, the reference data or a rule changed
// since the draft was made, it refuses and names every reason (PLN-07, PLN-14).
// A published plan is immutable; changing it starts a revision.

export default function PlanPublish({
  state,
  depot,
  date,
  summary,
  decisions,
  online,
  busy,
  reviseReason,
  onReviseReason,
  onPublish,
  onRevise,
}: {
  state: Exclude<Working, { stage: "none" }>;
  depot: string;
  date: string;
  summary: PlanSummary;
  decisions: Decision[];
  online: boolean;
  busy: boolean;
  reviseReason: string;
  onReviseReason: (reason: string) => void;
  onPublish: () => void;
  onRevise: () => void;
}): React.JSX.Element {
  const [confirming, setConfirming] = useState(false);
  const deferred = decisions.filter((decision) => decision.allocation.decision === "DEFERRED");
  const unservable = decisions.filter((decision) => decision.allocation.decision === "UNSERVABLE");

  return (
    <section aria-label="Publish" className="flex w-full max-w-[820px] flex-col gap-4 rounded-[24px] bg-white p-6 shadow-go-card">
      <div>
        <h2 className="text-[19px] font-medium text-go-ink">
          {state.stage === "published" ? "This plan is published" : state.revises ? "Publish this revision" : "Publish this plan"}
        </h2>
        <p className="text-[13px] text-go-secondary">
          {depot} · {dayLabel(date)} · version {state.plan.planVersion} · {summary.served} of {summary.orders} orders on {summary.trips} trips, {summary.vehiclesUsed} vehicles
        </p>
      </div>

      {decisions.length > 0 && (
        <div className="flex flex-col gap-2">
          <h3 className="text-[15px] font-medium text-go-ink">
            {state.stage === "published" ? "Not delivered by this plan" : "Publishing leaves these undelivered"}
          </h3>
          <ul className="flex flex-col">
            {[...deferred, ...unservable].map(({ allocation, order }) => (
              <li key={allocation.orderId} className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-go-rule py-2 text-[13px] text-go-ink">
                <span className="min-w-[110px] font-medium">{order?.orderRef ?? allocation.orderId}</span>
                <span className="min-w-[140px] flex-1 text-go-secondary">{order ? `${order.outletId} · ${order.districtName}` : ""}</span>
                <Pill tone={allocation.decision === "UNSERVABLE" ? "danger" : "warning"}>
                  {allocation.decision === "UNSERVABLE" ? "Cannot be served" : "Deferred"} · {allocation.bindingRule}
                </Pill>
                <span className="w-full text-xs text-go-secondary">{allocation.reason}</span>
              </li>
            ))}
          </ul>
          {state.stage === "draft" && deferred.length > 0 && <p className="text-xs text-go-secondary">A deferred order is offered first on the next plan, and its store manager is told.</p>}
        </div>
      )}

      {state.stage === "draft" &&
        (confirming ? (
          <div className="flex flex-wrap items-center gap-2 rounded-go-card bg-go-success-tint p-3">
            <p className="min-w-[220px] flex-1 text-[13px] font-medium text-go-ink">
              {state.revises ? "Send this change to the loaders and drivers?" : `Publish for ${depot} on ${dayLabel(date)}? It cannot be edited afterwards, only revised.`}
            </p>
            <PrimaryButton disabled={!online || busy} onClick={onPublish}>
              {busy ? "Publishing…" : "Confirm publish"}
            </PrimaryButton>
            <SecondaryButton onClick={() => setConfirming(false)}>Cancel</SecondaryButton>
          </div>
        ) : (
          <div>
            <PrimaryButton disabled={!online || busy} onClick={() => setConfirming(true)}>
              {state.revises ? "Publish revision" : "Publish plan"}
            </PrimaryButton>
          </div>
        ))}

      {state.stage === "published" && (
        <>
          <Notice tone="info" title={`Published${state.plan.publishedAt ? ` at ${clock(new Date(state.plan.publishedAt))}` : ""}. Loaders and drivers work from this version.`}>
            It cannot be edited. To change it, start a revision: it becomes a draft, and nothing changes on the dock or the road until that draft is published.
          </Notice>
          <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
            Why is the plan being revised?
            <input
              value={reviseReason}
              maxLength={300}
              onChange={(event) => onReviseReason(event.target.value)}
              placeholder="For example: a vehicle broke down, new orders were confirmed"
              className="rounded-go-input border border-go-rule px-3 py-2.5 text-[14px] font-normal outline-none focus:border-go-teal"
            />
          </label>
          <div>
            <SecondaryButton disabled={!online || busy || !reviseReason.trim()} onClick={onRevise}>
              Start a revision
            </SecondaryButton>
          </div>
        </>
      )}
    </section>
  );
}
