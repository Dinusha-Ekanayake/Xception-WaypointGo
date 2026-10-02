"use client";

import { useMemo, useState } from "react";
import { PlanCommandKind, type OrderView, type PlanView } from "@shared/domain/types";
import { Notice, PrimaryButton, SecondaryButton, Segmented, formatClock } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import { useFleet } from "../data/fleet.ts";
import { improvementNote, openDecisions, summarise, working } from "../data/plan.ts";
import { formatDay } from "../data/scope.ts";
import { useCommand } from "../data/useCommand.ts";
import { useOrders, usePlans } from "../data/useDay.ts";
import DayPicker from "./DayTools.tsx";
import { Retry } from "./Orders.tsx";
import PlanBoard from "./PlanBoard.tsx";
import PlanDecide, { type Place } from "./PlanDecide.tsx";
import PlanPublish from "./PlanPublish.tsx";
import type { TripAction } from "./PlanTrip.tsx";
import Refusal from "./Refusal.tsx";

// Figma "Plan": decide, view plan, publish. A plan is one depot's day, so the
// screen works on one depot. It holds no draft id: every edit replaces the
// draft with its next version, so the draft is read by depot and day and each
// command names the version it saw (PLAN.md decision 4).

type Tab = "decide" | "view" | "publish";
type Body = { planVersion: number; served: number; deferred: number; unservable: number; partial: boolean };

export default function Plan({
  depots,
  date,
  onDate,
  online,
}: {
  /** The depots in view; with more than one the screen asks which to plan. */
  depots: string[];
  date: string;
  onDate: (date: string) => void;
  online: boolean;
}): React.JSX.Element {
  const [chosen, setChosen] = useState<string | null>(null);
  const depot = chosen && depots.includes(chosen) ? chosen : depots[0]!;
  const one = useMemo(() => [depot], [depot]);
  const plans = usePlans(one, date);
  const orders = useOrders(one, date);
  const fleet = useFleet(one, date);
  const { busy, run } = useCommand();
  const [tab, setTab] = useState<Tab>("decide");
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<{ error: Error; what: string } | null>(null);
  const [reviseReason, setReviseReason] = useState("");

  const current = plans.data?.[0] ?? null;
  const state = working(current?.published ?? null, current?.draft ?? null);
  const plan: PlanView | null = state.stage === "none" ? null : state.plan;
  const byId = useMemo(() => new Map<string, OrderView>((orders.data ?? []).map((order) => [order.orderId, order])), [orders.data]);
  const vehicles = fleet.data ?? [];
  const decisions = plan ? openDecisions(plan, byId) : [];
  const summary = plan ? summarise(plan, vehicles) : null;
  const editable = online && state.stage === "draft";
  const toPlan = (orders.data ?? []).filter((order) => order.status === "CONFIRMED" || order.status === "DEFERRED").length;

  /** Sends one command, then reads the day again whatever the answer: a refusal often means the plan moved. */
  const send = async (what: string, kind: string, payload: unknown, version: number | null, done: (body: Body) => string) => {
    setNotice(null);
    setFailure(null);
    const sent = await run<Body>(kind, payload, version);
    if (sent.ok) setNotice(done(sent.result));
    else setFailure({ error: sent.error, what });
    plans.refresh();
    orders.refresh();
    return sent.ok;
  };

  const counts = (body: Body) =>
    `${body.served} placed, ${body.deferred} deferred${body.unservable ? `, ${body.unservable} cannot be served` : ""}${body.partial ? ". The engine ran out of time, so the rest were deferred" : ""}`;

  const generate = () =>
    send("generating the plan", PlanCommandKind.generate, { depotCode: depot, serviceDate: date }, null, (body) => `Draft version ${body.planVersion}: ${counts(body)}.`).then((ok) => ok && setTab("decide"));

  const place = (target: Place) =>
    plan && send("placing the order", PlanCommandKind.override, { planId: plan.planId, ...target }, plan.rowVersion, (body) => `Placed on ${target.vehicleId} trip ${target.tripNumber}. Draft version ${body.planVersion}: ${counts(body)}.`);

  const act = (action: TripAction) => {
    if (!plan) return;
    if (action.kind === "defer") {
      return send("deferring the order", PlanCommandKind.defer, { planId: plan.planId, orderId: action.orderId, reason: action.reason }, plan.rowVersion, (body) => `Order deferred. Draft version ${body.planVersion}: ${counts(body)}.`);
    }
    return send(
      "moving the trip",
      PlanCommandKind.replan,
      { planId: plan.planId, tripId: action.tripId, replacementVehicleId: action.vehicleId, reason: action.reason },
      plan.rowVersion,
      (body) => `Trip moved to ${action.vehicleId}. Draft version ${body.planVersion}: ${counts(body)}. Publish it to send the change.`,
    );
  };

  const publish = () =>
    plan && send("publishing the plan", PlanCommandKind.publish, { planId: plan.planId }, plan.rowVersion, () => `Plan for ${depot} on ${formatDay(date)} is published. Loaders and drivers now work from it.`).then((ok) => ok && setTab("view"));

  const revise = () =>
    plan &&
    send("revising the plan", PlanCommandKind.revise, { planId: plan.planId, reason: reviseReason.trim() }, plan.rowVersion, (body) => `Revision draft version ${body.planVersion}: ${counts(body)}. Nothing changes for loaders or drivers until it is published.`).then(
      (ok) => ok && (setReviseReason(""), setTab("decide")),
    );

  const stage =
    state.stage === "none" ? "No plan yet" : state.stage === "draft" ? `Draft version ${state.plan.planVersion}${state.revises ? " · revises the published plan" : ""}` : `Published · version ${state.plan.planVersion}`;
  const error = plans.error ?? orders.error ?? fleet.error;
  const improvement = improvementNote(plan?.improvement ?? null);

  return (
    <>
      <PageHeader
        title={`Plan ${formatDay(date)}`}
        subtitle={`${depot} · ${plans.data ? stage : "Loading"}`}
        online={online}
        lastSyncedAt={plans.loadedAt}
        tools={
          <>
            {depots.length > 1 && <Segmented label="Depot to plan" value={depot} onChange={setChosen} options={depots.map((code) => ({ value: code, label: code }))} />}
            <DayPicker date={date} onDate={onDate} />
          </>
        }
      />

      {error && <Refusal error={error} what="the plan" action={<Retry onClick={() => (plans.refresh(), orders.refresh(), fleet.refresh())} />} />}
      {failure && <Refusal error={failure.error} what={failure.what} />}
      {notice && <Notice tone="info" title={notice} live />}
      {improvement && (
        <Notice tone="info" title={improvement.title}>
          {improvement.detail}
        </Notice>
      )}
      {plan?.plannedWithoutPredictor && (
        <Notice tone="neutral" title="Planned on the booklet's travel and service times">
          The time predictor is not running, so no learned times were used.
        </Notice>
      )}

      {plans.data && state.stage === "none" && (
        <section aria-label="No plan" className="flex w-full max-w-[760px] flex-col items-start gap-3 rounded-[24px] bg-white p-6 shadow-go-card">
          <h2 className="text-[19px] font-medium text-go-ink">No plan for {depot} on {formatDay(date)}</h2>
          <p className="text-[13px] text-go-secondary">
            {orders.data ? `${toPlan} ${toPlan === 1 ? "order is" : "orders are"} confirmed and waiting to be planned.` : "Counting the orders…"} Generating places every order it can and names the rule
            that stopped each one it could not.
          </p>
          <PrimaryButton disabled={!online || busy} onClick={() => void generate()}>
            {busy ? "Generating…" : "Generate draft"}
          </PrimaryButton>
        </section>
      )}

      {state.stage !== "none" && plan && summary && (
        <>
          <div className="flex w-full flex-wrap items-center gap-2 rounded-[24px] bg-white p-2 shadow-go-card">
            <div role="tablist" aria-label="Plan steps" className="flex flex-1 flex-wrap gap-1">
              <Step id="decide" tab={tab} onTab={setTab} title="Decide" note={decisions.length ? `${decisions.length} not placed` : "All placed"} />
              <Step id="view" tab={tab} onTab={setTab} title="View plan" note={`${summary.trips} ${summary.trips === 1 ? "trip" : "trips"} · ${summary.tightTrips} tight`} />
              <Step
                id="publish"
                tab={tab}
                onTab={setTab}
                title="Publish"
                note={state.stage === "published" ? `Published${state.plan.publishedAt ? ` ${formatClock(new Date(state.plan.publishedAt))}` : ""}` : "Not published yet"}
              />
            </div>
            {state.stage === "draft" && (
              <SecondaryButton
                disabled={!online || busy || state.revises !== null}
                title={state.revises ? "A revision is changed by hand, not regenerated" : undefined}
                onClick={() => window.confirm("Regenerate the draft? Orders placed or deferred by hand in this draft are decided again by the engine.") && void generate()}
              >
                Regenerate
              </SecondaryButton>
            )}
          </div>

          {tab === "decide" && <PlanDecide plan={plan} decisions={decisions} editable={editable} busy={busy} onPlace={(target) => void place(target)} />}
          {tab === "view" && <PlanBoard plan={plan} fleet={vehicles} orders={byId} editable={editable} canReplan={online} busy={busy} onAction={(action) => void act(action)} />}
          {tab === "publish" && (
            <PlanPublish
              state={state}
              depot={depot}
              date={date}
              summary={summary}
              decisions={decisions}
              online={online}
              busy={busy}
              reviseReason={reviseReason}
              onReviseReason={setReviseReason}
              onPublish={() => void publish()}
              onRevise={() => void revise()}
            />
          )}
        </>
      )}
      {!plans.data && !plans.error && <p className="py-8 text-center text-[13px] text-go-secondary">Loading the plan…</p>}
    </>
  );
}

function Step({ id, tab, onTab, title, note }: { id: Tab; tab: Tab; onTab: (tab: Tab) => void; title: string; note: string }): React.JSX.Element {
  const selected = id === tab;
  return (
    <button
      type="button"
      role="tab"
      aria-selected={selected}
      onClick={() => onTab(id)}
      className={`flex min-w-[150px] flex-1 flex-col rounded-go-card px-4 py-2 text-left ${selected ? "bg-go-success-tint" : ""}`}
    >
      <span className="text-[15px] font-medium text-go-ink">{title}</span>
      <span className="text-xs text-go-secondary">{note}</span>
    </button>
  );
}
