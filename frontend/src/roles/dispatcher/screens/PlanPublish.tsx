"use client";

import { useState } from "react";
import type { OrderView, PlanView } from "@shared/domain/types";
import { PrimaryButton, SecondaryButton, cx } from "@shared/ui";
import { clock, dayLabel, ruleLabel } from "@shared/wording";
import { leftOutLine, type PlanSummary, type Working } from "../data/plan.ts";
import { daysBetween, publishBlocker, type DecisionRow } from "../data/planViews.ts";
import { useComparison } from "../data/usePlanReads.ts";
import PublishAudience from "./PublishAudience.tsx";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";

// Figma "Plan · 3 Publish" (3a blocked, 3b ready, 3c published, 3d send
// update). Publishing is a deliberate second step with two gates: every order
// the engine deferred needs a dispatcher's decision first, and the server's
// gate then decides: if the demand, the reference data or a rule changed since
// the draft was made, it refuses and names every reason (PLN-07, PLN-14). A
// published plan is immutable; changing it starts a revision, and sending that
// revision tells only the drivers and stores it changes (R-NOT-12). The step
// bar holds the button; this is what it will do.

const WAITING_DAYS = 3;

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
  confirming,
  onConfirming,
  revising,
  onRevising,
  final,
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
  /** The step bar's Publish was pressed: ask once more. */
  confirming: boolean;
  onConfirming: (confirming: boolean) => void;
  /** The step bar's Edit plan was pressed on a published plan. */
  revising: boolean;
  onRevising: (revising: boolean) => void;
  /** Past 16:00 on the service day: the published plan is final (R-PLN-43). */
  final: boolean;
  reviseReason: string;
  onReviseReason: (reason: string) => void;
  onPublish: () => void;
  onRevise: () => void;
  /** Jumps back to the Decide step at an order. */
  onDecide: (orderId: string) => void;
}): React.JSX.Element {
  const [showAll, setShowAll] = useState(false);
  const blocker = publishBlocker(rows);
  const blocked = state.stage === "draft" && blocker.open > 0;
  const revises = state.stage === "draft" ? state.revises : null;
  const left = plan.allocations.filter((a) => a.decision !== "SERVED");
  const deferred = left.filter((a) => a.decision === "DEFERRED");
  const byHand = plan.allocations.filter((a) => a.source !== "ENGINE").length;
  const waiting = deferred.filter((a) => a.lastServedOn !== null && daysBetween(a.lastServedOn, plan.serviceDate) + 1 >= WAITING_DAYS);
  const changes = useComparison(revises ? revises.planId : null, revises ? plan.planId : null);
  const told = changes.data ? changes.data.changedTrips.length : null;
  const published = state.stage === "published";
  const outletCount = new Set(deferred.map((a) => orders.get(a.orderId)?.outletId).filter(Boolean)).size;

  const headline = published
    ? `Published at ${state.plan.publishedAt ? clock(new Date(state.plan.publishedAt)) : "-"}`
    : blocked
      ? "Publishing is blocked"
      : revises
        ? told === 0
          ? "Nothing to send"
          : "Ready to send the update"
        : "Ready to publish. Every decision is made.";
  const subline = published
    ? "Sent · you can still edit and publish again"
    : blocked
      ? `${blocker.open} ${blocker.open === 1 ? "order still needs" : "orders still need"} a decision`
      : "The server checks every rule again when you publish";

  return (
    <div className="flex w-full gap-[18px] max-lg:flex-col">
      <section aria-label="Publish" className="flex min-w-0 flex-1 flex-col gap-3 rounded-go-panel bg-go-card p-5">
        <div className="flex items-center gap-3">
          <span
            aria-hidden
            className={cx("flex size-11 shrink-0 items-center justify-center rounded-full text-[15px] font-semibold", blocked ? "bg-go-danger-tint text-go-danger-strong" : "bg-go-success-tint text-go-teal")}
          >
            {blocked ? "!" : "✓"}
          </span>
          <div className="min-w-0">
            <h2 className="text-[22px] font-medium text-go-ink">{headline}</h2>
            <p className="text-[13px] text-go-secondary">{subline}</p>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-3">
          <Stat value={`${summary.served} of ${summary.orders}`} note="orders planned" />
          <Stat value={`${summary.trips} ${summary.trips === 1 ? "trip" : "trips"}`} note={`on ${summary.vehiclesUsed} ${summary.vehiclesUsed === 1 ? "vehicle" : "vehicles"}`} />
          <Stat value={`${left.length}`} note={left.length ? `${leftOutLine(summary).split(" · ").slice(1).join(" · ")} · first on the next run` : "every order on a trip"} />
        </div>

        <ul className="flex flex-col">
          <Row
            tone={blocked ? "danger" : "ok"}
            title={blocked ? `${blocker.open} ${blocker.open === 1 ? "order needs" : "orders need"} a decision` : "Every order has a decision"}
            note={`${leftOutLine(summary)} · ${byHand} your ${byHand === 1 ? "change" : "changes"}`}
            action={
              blocked && blocker.first
                ? { label: "Decide now", onClick: () => onDecide(blocker.first!.allocation.orderId) }
                : left.length > 0
                  ? { label: showAll ? "Hide" : "Show all", onClick: () => setShowAll((v) => !v) }
                  : undefined
            }
          />
          {showAll && (
            <li className="flex flex-col pb-2 pl-11">
              {left.map((allocation) => {
                const order = orders.get(allocation.orderId);
                return (
                  <p key={allocation.orderId} className="flex flex-wrap gap-x-3 border-t border-go-rule py-1.5 text-xs text-go-ink">
                    <span className="w-[100px] font-medium">{order?.orderRef ?? allocation.orderId}</span>
                    <span className="flex-1 text-go-secondary">{order ? `${order.outletId} · ${order.districtName}` : ""}</span>
                    <span className={allocation.decision === "UNSERVABLE" ? "text-go-danger-strong" : "text-go-warning-text"}>
                      {`${allocation.decision === "UNSERVABLE" ? "Cannot be served" : "Deferred"} · ${ruleLabel(allocation.bindingRule)}`}
                    </span>
                  </p>
                );
              })}
            </li>
          )}
          {waiting.length > 0 && (
            <Row
              tone="danger"
              title={`${waiting.length} ${waiting.length === 1 ? "outlet reaches" : "outlets reach"} ${WAITING_DAYS} days without a delivery`}
              note={`${waiting.map((a) => orders.get(a.orderId)?.outletId ?? "").filter(Boolean).join(", ")} · the store manager is told the reason`}
              action={!published ? { label: "Review", onClick: () => onDecide(waiting[0]!.orderId) } : undefined}
            />
          )}
          {revises && (
            <Row
              tone="warning"
              title={told === null ? "Checking what changes…" : told === 0 ? "No trip changes" : `${told} ${told === 1 ? "change is" : "changes are"} not sent yet`}
              note={
                changes.data && told
                  ? `Sending tells the drivers of ${told} ${told === 1 ? "trip" : "trips"}${changes.data.affectedOutlets.length ? ` and ${changes.data.affectedOutlets.length} ${changes.data.affectedOutlets.length === 1 ? "outlet" : "outlets"}` : ""}. Everyone else keeps the plan they have.`
                  : "Nobody on the road or at a store is told about an unchanged trip."
              }
            />
          )}
        </ul>

        <div className="flex flex-col gap-2">
          <h3 className="text-[15px] font-medium text-go-ink">Send to</h3>
          <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-3">
            <Audience label={`Loaders · dock board`} />
            <Audience label={`${summary.vehiclesUsed} ${summary.vehiclesUsed === 1 ? "driver" : "drivers"}`} />
            <Audience label={`${outletCount} ${outletCount === 1 ? "store manager" : "store managers"}`} />
          </div>
          <p className="text-xs text-go-secondary">Checked again on publish · later publishes send only changes</p>
        </div>

        {confirming && state.stage === "draft" && (
          <div className="flex flex-wrap items-center gap-2 rounded-go-card bg-go-success-tint p-3">
            <p className="min-w-[220px] flex-1 text-[13px] font-medium text-go-ink">
              {revises ? "Send this change to the loaders and drivers?" : `Publish for ${depot} on ${dayLabel(date)}? It cannot be edited afterwards, only revised.`}
            </p>
            <PrimaryButton disabled={!online || blocked} busy={busy} onClick={onPublish}>
              {busy ? "Sending…" : revises ? "Send update" : "Confirm publish"}
            </PrimaryButton>
            <SecondaryButton onClick={() => onConfirming(false)}>Cancel</SecondaryButton>
          </div>
        )}

        {published && !revising && (
          <div className="flex flex-wrap items-center gap-2 border-t border-go-rule pt-4">
            <SecondaryButton disabled={!online || final} onClick={() => onRevising(true)}>
              Edit plan
            </SecondaryButton>
            <SecondaryButton onClick={() => (window.location.hash = "/live")}>Watch the run</SecondaryButton>
            <p className="text-xs text-go-secondary">
              {final
                ? "This plan is final: changes closed at 16:00 on its day."
                : "Editing starts a revision until 16:00 on the plan's day; drivers and stores keep this plan until the update is sent."}
            </p>
          </div>
        )}

        {published && revising && !final && (
          <div className="flex flex-col gap-2 rounded-go-card bg-go-surface p-3">
            <p className="text-[13px] text-go-ink">
              A published plan is not edited. A revision becomes a draft, and nothing changes on the dock or the road until it is sent.
            </p>
            <ReasonPicker label="Why is the plan being revised?" value={reviseReason} onChange={onReviseReason} placeholder="For example: a vehicle broke down, new orders were confirmed" />
            <div className="flex gap-2">
              <PrimaryButton disabled={!online || !reasonReady(reviseReason)} busy={busy} onClick={onRevise}>
                {busy ? "Sending…" : "Start a revision"}
              </PrimaryButton>
              <SecondaryButton onClick={() => onRevising(false)}>Cancel</SecondaryButton>
            </div>
          </div>
        )}
      </section>

      <PublishAudience plan={plan} orders={orders} changes={revises ? (changes.data ?? null) : null} />
    </div>
  );
}

function Stat({ value, note }: { value: string; note: string }): React.JSX.Element {
  return (
    <div className="flex flex-col rounded-go-card bg-go-surface px-4 py-3">
      <span className="text-[20px] font-medium text-go-ink">{value}</span>
      <span className="text-xs text-go-secondary">{note}</span>
    </div>
  );
}

function Row({
  tone,
  title,
  note,
  action,
}: {
  tone: "ok" | "warning" | "danger";
  title: string;
  note: string;
  action?: { label: string; onClick: () => void };
}): React.JSX.Element {
  return (
    <li className="flex items-center gap-3 border-t border-go-rule py-3.5">
      <span
        aria-hidden
        className={cx(
          "flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold",
          tone === "ok" ? "bg-go-success-tint text-go-teal" : tone === "warning" ? "bg-go-warning-tint text-go-warning-text" : "bg-go-danger-tint text-go-danger-strong",
        )}
      >
        {tone === "ok" ? "✓" : "!"}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-[14px] font-medium text-go-ink">{title}</span>
        <span className="block text-xs text-go-secondary">{note}</span>
      </span>
      {action && (
        <button type="button" onClick={action.onClick} className="shrink-0 text-[13px] font-medium text-go-teal">
          {`${action.label} ›`}
        </button>
      )}
    </li>
  );
}

function Audience({ label }: { label: string }): React.JSX.Element {
  return (
    <p className="flex items-center gap-2.5 rounded-go-input bg-go-success-tint px-3 py-2.5 text-[13px] font-medium text-go-ink">
      <span aria-hidden className="flex size-4 items-center justify-center rounded-[4px] bg-go-ink text-[10px] text-go-card">
        ✓
      </span>
      {label}
    </p>
  );
}
