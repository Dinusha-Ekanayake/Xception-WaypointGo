"use client";

import { useEffect, useMemo, useState } from "react";
import { PlanCommandKind, type GenerationJobView, type OrderView, type PlanView } from "@shared/domain/types";
import { Notice, PrimaryButton, SecondaryButton } from "@shared/ui";
import { clock, dayLabel } from "@shared/wording";
import PageHeader from "../PageHeader.tsx";
import { useFleet } from "../data/fleet.ts";
import { costNote, improvementNote, summarise, working } from "../data/plan.ts";
import { decidedCount, decisionRows } from "../data/planViews.ts";
import { useCommand } from "../data/useCommand.ts";
import { progressLabel, useGeneration } from "../data/useGeneration.ts";
import { useNextOrderDay, useOrders, usePlans, waitingToPlan } from "../data/useDay.ts";
import { useSnapshot, useSnapshots } from "../data/usePlanReads.ts";
import { Retry } from "./Orders.tsx";
import PlanBoard from "./PlanBoard.tsx";
import PlanCompare from "./PlanCompare.tsx";
import PlanDecide from "./PlanDecide.tsx";
import PlanPublish from "./PlanPublish.tsx";
import PlanSteps, { type Tab } from "./PlanSteps.tsx";
import PlanTools from "./PlanTools.tsx";
import type { Place, PlanActions } from "./planActions.ts";
import Refusal from "./Refusal.tsx";

// Figma "Plan": decide, view plan, publish, and compare. A plan is one depot's
// day, so the screen works on one depot. It holds no draft id: every edit
// replaces the draft with its next version, so the draft is read by depot and
// day and each command names the version it saw (PLAN.md decision 4). A saved
// plan can be looked at, read only, beside the working one; nothing sent from
// here ever names it.

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
  const saved = useSnapshots(depot, date);
  const { busy, run } = useCommand();
  const [tab, setTab] = useState<Tab>("decide");
  const [focusId, setFocusId] = useState<string | null>(null);
  const [viewing, setViewing] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<{ error: Error; what: string } | null>(null);
  const [reviseReason, setReviseReason] = useState("");
  const snapshot = useSnapshot(viewing);
  const generation = useGeneration(depot, date);
  const planning = progressLabel(generation.following);

  const current = plans.data?.[0] ?? null;
  const state = working(current?.published ?? null, current?.draft ?? null);
  const live: PlanView | null = state.stage === "none" ? null : state.plan;
  const plan: PlanView | null = viewing && snapshot.data ? snapshot.data.plan : live;
  const byId = useMemo(() => new Map<string, OrderView>((orders.data ?? []).map((order) => [order.orderId, order])), [orders.data]);
  const vehicles = fleet.data ?? [];
  const rows = plan ? decisionRows(plan, byId) : [];
  const { decided, total, open } = decidedCount(rows);
  const summary = plan ? summarise(plan, vehicles) : null;
  const editable = online && state.stage === "draft" && viewing === null;
  const toPlan = waitingToPlan(orders.data ?? []);
  // Nothing waits today: name the next day that has orders waiting (issue #114).
  const nextDay = useNextOrderDay(one, date, Boolean(plans.data) && state.stage === "none" && Boolean(orders.data) && toPlan === 0);

  /** Sends one command, then reads the day again whatever the answer: a refusal often means the plan moved. */
  const send = async (what: string, kind: string, payload: unknown, version: number | null, done: (body: Body) => string) => {
    setNotice(null);
    setFailure(null);
    const sent = await run<Body>(kind, payload, version);
    if (sent.ok) setNotice(done(sent.result));
    else setFailure({ error: sent.error, what });
    plans.refresh();
    orders.refresh();
    saved.refresh();
    return sent.ok;
  };

  const counts = (body: Body) =>
    `${body.served} placed, ${body.deferred} deferred${body.unservable ? `, ${body.unservable} cannot be served` : ""}${body.partial ? ". The engine ran out of time, so the rest were deferred" : ""}`;
  const draftNote = (body: Body) => `Draft version ${body.planVersion}: ${counts(body)}.`;
  const edit = (what: string, kind: string, payload: Record<string, unknown>, done: (body: Body) => string) =>
    live ? send(what, kind, { planId: live.planId, ...payload }, live.rowVersion, done) : Promise.resolve(false);

  const actions: PlanActions = {
    busy,
    place: (t: Place) => edit("placing the order", PlanCommandKind.override, { orderId: t.orderId, vehicleId: t.vehicleId, tripNumber: t.tripNumber, reason: t.reason }, (b) => `Placed on ${t.vehicleId} trip ${t.tripNumber}. ${draftNote(b)}`),
    defer: (orderId, reason) => edit("taking the order off", PlanCommandKind.defer, { orderId, reason }, (b) => `Order taken off its trip and deferred. ${draftNote(b)}`),
    swap: (outOrderId, inOrderId, reason) => edit("swapping the orders", PlanCommandKind.swap, { outOrderId, inOrderId, reason }, (b) => `Swapped. ${draftNote(b)}`),
    keepDeferred: (orderIds, reason) => edit("keeping the orders deferred", PlanCommandKind.keepDeferred, { orderIds, reason }, (b) => `${orderIds.length} ${orderIds.length === 1 ? "order kept" : "orders kept"} deferred. ${draftNote(b)}`),
    lock: (orderId, locked) => edit(locked ? "locking the order" : "unlocking the order", locked ? PlanCommandKind.lock : PlanCommandKind.unlock, { orderId }, () => (locked ? "Locked: a regenerate keeps it on its trip." : "Unlocked.")),
    reorder: (tripId, orderIds, reason) => edit("changing the stop order", PlanCommandKind.reorderStops, { tripId, orderIds, reason }, (b) => `Stop order saved. ${draftNote(b)}`),
    moveTrip: (tripId, vehicleId, reason) =>
      edit("moving the trip", PlanCommandKind.replan, { tripId, replacementVehicleId: vehicleId, reason }, (b) => `Trip moved to ${vehicleId}. ${draftNote(b)} Send it to tell the drivers.`),
    contactStore: (orderId, message) => (live ? send("telling the store", PlanCommandKind.contactStore, { planId: live.planId, orderId, message }, null, () => "The store manager has your message.") : Promise.resolve(false)),
  };

  /** Follows a queued generation to its end (R-PLN-41), then reads the day again. */
  const finish = async (job: GenerationJobView, keepDecisions: boolean) => {
    try {
      const done = await generation.follow(job);
      if (done.status === "DONE" && done.result) setNotice(`${keepDecisions ? "Planned again, keeping your decisions. " : ""}${draftNote(done.result)}`);
      else setFailure({ error: new Error(done.error ?? "Planning stopped without a plan."), what: "generating the plan" });
    } catch (e) {
      setFailure({ error: e instanceof Error ? e : new Error(String(e)), what: "generating the plan" });
    }
    plans.refresh();
    orders.refresh();
    saved.refresh();
    setViewing(null);
    setTab("decide");
  };
  const generate = async (keepDecisions: boolean) => {
    setNotice(null);
    setFailure(null);
    const sent = await run<GenerationJobView>(PlanCommandKind.generate, { depotCode: depot, serviceDate: date, keepDecisions }, null);
    if (!sent.ok) {
      setFailure({ error: sent.error, what: "generating the plan" });
      plans.refresh();
      return;
    }
    await finish(sent.result, keepDecisions);
  };
  // A generation already running for this day, from a reload or another dispatcher: follow it.
  const { resumed, clearResumed } = generation;
  useEffect(() => {
    if (!resumed) return;
    clearResumed();
    void finish(resumed, false);
  }, [resumed]);

  const saveSnapshot = () => edit("saving the plan", PlanCommandKind.saveSnapshot, {}, () => "Snapshot saved.");
  const restore = (snapshotId: string, label: string) =>
    edit("returning to the saved plan", PlanCommandKind.restoreSnapshot, { snapshotId }, (b) => `Returned to ${label}. ${draftNote(b)}`).then((ok) => ok && (setViewing(null), setTab("decide")));

  const publish = () =>
    live && send("publishing the plan", PlanCommandKind.publish, { planId: live.planId }, live.rowVersion, () => (state.stage === "draft" && state.revises ? `Update sent for ${depot} on ${dayLabel(date)}. Only the drivers and stores it changes are told.` : `Plan for ${depot} on ${dayLabel(date)} is published. Loaders and drivers now work from it.`)).then((ok) => ok && setTab("view"));

  const revise = () =>
    live &&
    send("revising the plan", PlanCommandKind.revise, { planId: live.planId, reason: reviseReason.trim() }, live.rowVersion, (b) => `Revision draft version ${b.planVersion}: ${counts(b)}. Nothing changes for loaders or drivers until it is sent.`).then((ok) => ok && (setReviseReason(""), setTab("decide")));

  const stage =
    state.stage === "none" ? "No plan yet" : state.stage === "draft" ? `Draft version ${state.plan.planVersion}${state.revises ? " · revises the published plan" : ""}` : `Published · version ${state.plan.planVersion}`;
  const workingLabel = state.stage === "published" ? "Published plan" : "Working draft";
  const error = plans.error ?? orders.error ?? fleet.error;
  const improvement = improvementNote(plan?.improvement ?? null);
  const cost = viewing ? null : costNote(plan?.cost);
  const viewingSaved = viewing !== null ? saved.data?.find((s) => s.snapshotId === viewing) : undefined;
  const decide = (orderId: string) => (setFocusId(orderId), setTab("decide"));

  return (
    <>
      <PageHeader
        title={`Plan ${dayLabel(date)}`}
        subtitle={
          summary && plan
            ? `${depot} · ${summary.served} of ${summary.orders} fit · saved ${clock(new Date(plan.savedAt))} · ${stage}`
            : `${depot} · ${plans.data ? stage : "Loading"}`
        }
        online={online}
        lastSyncedAt={plans.loadedAt}
        onSync={plans.refresh}
        syncing={plans.loading}
        quiet
        tools={
          <PlanTools
            depots={depots}
            depot={depot}
            onDepot={(code) => (setChosen(code), setViewing(null))}
            date={date}
            onDate={onDate}
            hasPlan={state.stage !== "none"}
            draft={state.stage === "draft"}
            workingLabel={workingLabel}
            snapshots={saved.data ?? []}
            viewing={viewing}
            onView={setViewing}
            online={online}
            busy={busy || planning !== null}
            onSave={() => void saveSnapshot()}
            onRegenerate={(keep) => void generate(keep)}
            onCompare={() => setTab("compare")}
          />
        }
      />

      {error && <Refusal error={error} what="the plan" action={<Retry onClick={() => (plans.refresh(), orders.refresh(), fleet.refresh())} />} />}
      {failure && <Refusal error={failure.error} what={failure.what} />}
      {notice && <Notice tone="info" title={notice} live />}
      {planning && <Notice tone="neutral" title={planning} live>The plan is being made in the background; it opens here when it is ready, even after a reload.</Notice>}
      {cost?.compare && live && tab !== "compare" && (
        <Notice tone="info" title={cost.title} action={<SecondaryButton onClick={() => setTab("compare")}>Compare with the rules plan</SecondaryButton>}>
          {cost.detail}
        </Notice>
      )}
      {viewing && (
        <Notice
          tone="neutral"
          title={`${viewingSaved?.label ?? "A saved plan"}: read only`}
          action={
            <span className="flex gap-2">
              {editable && viewingSaved && <PrimaryButton disabled={busy} onClick={() => void restore(viewingSaved.snapshotId, viewingSaved.label)}>Use this plan</PrimaryButton>}
              <SecondaryButton onClick={() => setViewing(null)}>{`Back to ${workingLabel.toLowerCase()}`}</SecondaryButton>
            </span>
          }
        >
          A saved plan is never edited. Using it returns the working draft to it and keeps what you decided there.
        </Notice>
      )}
      {tab === "view" && !viewing && plan && (
        <p className="text-xs text-go-secondary">
          {[
            improvement ? `${improvement.title}. ${improvement.detail}` : null,
            cost && !cost.compare ? `${cost.title}. ${cost.detail}` : null,
            !plan.improvement && plan.engine.includes("scarce") && plan.allocations.some((a) => a.source !== "ENGINE")
              ? "Refrigerated vehicles were not planned again: your decisions were kept, so the second pass that could move them did not run."
              : null,
            plan.plannedWithoutPredictor && live ? "Planned on the booklet's travel and service times: the time predictor is not running." : null,
          ]
            .filter(Boolean)
            .join(" ")}
        </p>
      )}

      {plans.data && state.stage === "none" && (
        <section aria-label="No plan" className="flex w-full max-w-[760px] flex-col items-start gap-3 rounded-[24px] bg-go-card p-6 shadow-go-card">
          <h2 className="text-[19px] font-medium text-go-ink">{`No plan for ${depot} on ${dayLabel(date)}`}</h2>
          <p className="text-[13px] text-go-secondary">
            {orders.data ? `${toPlan} ${toPlan === 1 ? "order is" : "orders are"} confirmed and waiting to be planned.` : "Counting the orders…"} Generating places every order it can and names the rule
            that stopped each one it could not.
          </p>
          {toPlan === 0 && nextDay.data ? (
            <>
              <p className="text-[13px] text-go-ink">
                {nextDay.data.waiting} {nextDay.data.waiting === 1 ? "order waits" : "orders wait"} for {dayLabel(nextDay.data.date)}.
              </p>
              <PrimaryButton onClick={() => onDate(nextDay.data!.date)}>Plan {dayLabel(nextDay.data.date)}</PrimaryButton>
            </>
          ) : (
            <PrimaryButton disabled={!online || busy || planning !== null} onClick={() => void generate(false)}>
              {planning ?? (busy ? "Generating…" : "Generate draft")}
            </PrimaryButton>
          )}
        </section>
      )}

      {state.stage !== "none" && plan && summary && live && (
        <>
          <PlanSteps
            tab={tab}
            onTab={setTab}
            decide={open > 0 ? `${open} need a decision` : total > 0 ? `Done · ${decided} of ${total}` : "All placed"}
            view={`${summary.trips} ${summary.trips === 1 ? "trip" : "trips"} · ${summary.tightTrips} tight`}
            publish={state.stage === "published" ? `Published${state.plan.publishedAt ? ` ${clock(new Date(state.plan.publishedAt))}` : ""}` : "Not published yet"}
            decideDone={open === 0}
            nextLabel={tab === "decide" ? "View plan" : tab === "view" ? (state.stage === "draft" && state.revises ? "Send update" : state.stage === "draft" ? "Publish" : null) : null}
          />

          {tab === "decide" && <PlanDecide plan={plan} rows={rows} orders={byId} fleet={vehicles} editable={editable} actions={actions} focusId={focusId} />}
          {tab === "view" && (
            <PlanBoard plan={plan} fleet={vehicles} orders={byId} editable={editable} canReplan={online && viewing === null} published={state.stage === "published" && viewing === null} actions={actions} onOpenDecision={decide} />
          )}
          {tab === "publish" && (
            <PlanPublish
              state={state}
              plan={live}
              orders={byId}
              depot={depot}
              date={date}
              summary={summarise(live, vehicles)}
              rows={decisionRows(live, byId)}
              online={online}
              busy={busy}
              reviseReason={reviseReason}
              onReviseReason={setReviseReason}
              onPublish={() => void publish()}
              onRevise={() => void revise()}
              onDecide={decide}
            />
          )}
          {tab === "compare" && (
            <PlanCompare
              working={{ id: live.planId, version: live.planVersion }}
              snapshots={saved.data ?? []}
              orders={byId}
              editable={editable}
              busy={busy}
              onUse={(snapshotId) => void restore(snapshotId, saved.data?.find((s) => s.snapshotId === snapshotId)?.label ?? "the saved plan")}
            />
          )}
        </>
      )}
      {!plans.data && !plans.error && <p className="py-8 text-center text-[13px] text-go-secondary">Loading the plan…</p>}
    </>
  );
}
