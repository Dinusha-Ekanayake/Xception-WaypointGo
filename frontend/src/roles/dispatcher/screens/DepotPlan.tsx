"use client";

import { useEffect, useMemo, useState } from "react";
import { PlanCommandKind, type GenerationJobView, type OrderView } from "@shared/domain/types";
import { Notice, PrimaryButton, SecondaryButton, useToast } from "@shared/ui";
import { clock, dayLabel } from "@shared/wording";
import { useFleet } from "../data/fleet.ts";
import { costNote, improvementNote, publishedEditOpen, summarise } from "../data/plan.ts";
import { depotHolding, depotStates, idsIn, mergeWorking, type DepotWorking } from "../data/planRouting.ts";
import { decidedCount, decisionRows } from "../data/planViews.ts";
import { depotToday } from "../data/scope.ts";
import { useCommand } from "../data/useCommand.ts";
import { progressLabel, useGenerations } from "../data/useGeneration.ts";
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
import Refusal, { refusalText } from "./Refusal.tsx";

// One plan view for the depots in view: their trips and orders together, so
// "Both" is one board and picking a depot in the header narrows it. Each depot
// keeps its own plan for the day: a command about an order or a trip goes to
// the plan that holds it, with that plan's version (PLAN.md decision 4); a
// command about the whole plan (generate, save, publish, revise) goes to each
// depot's plan in turn, and each answer is said. Saved plans and Compare work
// on one depot's plan, so they ask for one depot in view.

type Body = { planVersion: number; served: number; deferred: number; unservable: number; partial: boolean };

export default function DepotPlan({
  depots,
  date,
  onDate,
  online,
}: {
  depots: string[];
  date: string;
  onDate: (date: string) => void;
  online: boolean;
}): React.JSX.Element {
  const toast = useToast();
  const single = depots.length === 1;
  const plans = usePlans(depots, date);
  const orders = useOrders(depots, date);
  const fleet = useFleet(depots, date);
  const saved = useSnapshots(depots[0] ?? "", date);
  const { busy, run } = useCommand();
  const [tab, setTab] = useState<Tab>("decide");
  const [focusId, setFocusId] = useState<string | null>(null);
  const [viewing, setViewing] = useState<string | null>(null);
  const [reviseReason, setReviseReason] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [revising, setRevising] = useState(false);
  const snapshot = useSnapshot(single ? viewing : null);
  const generation = useGenerations(depots, date);
  const planning = progressLabel(generation.following);

  const items: DepotWorking[] = useMemo(() => depotStates(plans.data ?? []), [plans.data]);
  const state = useMemo(() => mergeWorking(items), [items]);
  const live = state.stage === "none" ? null : state.plan;
  // Once the day's plan is published, it is the only plan: the candidates saved
  // before it and Compare go away, and it takes changes only until 16:00 that
  // day (R-PLN-43).
  const settled = state.stage === "published" || (state.stage === "draft" && state.revises !== null);
  const final = state.stage === "published" && !publishedEditOpen(state.plan.serviceDate);
  useEffect(() => {
    if (!settled) return;
    setViewing(null);
    setTab((current) => (current === "compare" ? "view" : current));
  }, [settled]);
  const plan = viewing && snapshot.data ? snapshot.data.plan : live;
  const byId = useMemo(() => new Map<string, OrderView>((orders.data ?? []).map((order) => [order.orderId, order])), [orders.data]);
  const vehicles = fleet.data ?? [];
  const rows = plan ? decisionRows(plan, byId) : [];
  const { decided, total, open } = decidedCount(rows);
  const summary = plan ? summarise(plan, vehicles) : null;
  const editable = online && state.stage === "draft" && viewing === null;
  const unplanned = items.filter((i) => i.state.stage === "none").map((i) => i.depot);
  const waitingIn = (depot: string) => waitingToPlan((orders.data ?? []).filter((o) => o.depotCode === depot));
  const toPlan = unplanned.reduce((n, d) => n + waitingIn(d), 0);
  // Nothing waits: name the next day that has orders waiting (issue #114).
  const nextDay = useNextOrderDay(depots, date, Boolean(plans.data) && state.stage === "none" && Boolean(orders.data) && toPlan === 0);

  const refused = (error: Error, what: string) => toast({ tone: "error", ...refusalText(error, what) });
  const reread = () => (plans.refresh(), orders.refresh(), saved.refresh());

  /** Sends one command; reads the day again whatever the answer, as a refusal often means the plan moved. */
  const send = async (what: string, kind: string, payload: unknown, version: number | null, done: (body: Body) => string) => {
    const sent = await run<Body>(kind, payload, version);
    if (sent.ok) toast(asToast(done(sent.result)));
    else refused(sent.error, what);
    reread();
    return sent.ok;
  };

  const counts = (body: Body) =>
    `${body.served} placed, ${body.deferred} deferred${body.unservable ? `, ${body.unservable} cannot be served` : ""}${body.partial ? ". The engine ran out of time, so the rest were deferred" : ""}`;
  const draftNote = (body: Body) => `Draft version ${body.planVersion}: ${counts(body)}.`;
  const named = (depot: string, text: string) => (single ? text : `${depot}: ${text}`);

  /** A command about an order or a trip, sent to the plan that holds it. */
  const edit = (what: string, kind: string, payload: Record<string, unknown>, done: (body: Body) => string) => {
    const holder = idsIn(payload).map((id) => depotHolding(items, id)).find(Boolean) ?? (single ? items[0] : null);
    if (!holder || holder.state.stage === "none") {
      refused(new Error("Pick one depot above to do this: each depot has its own plan."), what);
      return Promise.resolve(false);
    }
    const target = holder.state.plan;
    return send(what, kind, { planId: target.planId, ...payload }, target.rowVersion, (b) => named(holder.depot, done(b)));
  };

  /** A command about the whole plan, sent to each depot's plan that it applies to. */
  const each = async (what: string, kind: string, applies: (i: DepotWorking) => boolean, payload: Record<string, unknown>, done: (depot: string, body: Body) => string) => {
    let ok = true;
    for (const item of items.filter(applies)) {
      if (item.state.stage === "none") continue;
      const p = item.state.plan;
      ok = (await send(what, kind, { planId: p.planId, ...payload }, p.rowVersion, (b) => named(item.depot, done(item.depot, b)))) && ok;
    }
    return ok;
  };

  const actions: PlanActions = {
    busy,
    place: (t: Place) => edit("placing the order", PlanCommandKind.override, { orderId: t.orderId, vehicleId: t.vehicleId, tripNumber: t.tripNumber, reason: t.reason }, (b) => `Placed on ${t.vehicleId} trip ${t.tripNumber}. ${draftNote(b)}`),
    defer: (orderId, reason) => edit("taking the order off", PlanCommandKind.defer, { orderId, reason }, (b) => `Order taken off its trip and deferred. ${draftNote(b)}`),
    swap: (outOrderId, inOrderId, reason, orderIds) =>
      edit("swapping the orders", PlanCommandKind.swap, { outOrderId, inOrderId, reason, ...(orderIds ? { orderIds } : {}) }, (b) => `Swap approved${orderIds ? " with your stop order" : ""}. ${draftNote(b)}`),
    keepDeferred: async (orderIds, reason) => {
      // The orders can belong to several depots: each plan keeps its own.
      let ok = true;
      for (const item of items) {
        if (item.state.stage === "none") continue;
        const own = orderIds.filter((id) => item.state.stage !== "none" && item.state.plan.allocations.some((a) => a.orderId === id));
        if (own.length === 0) continue;
        ok = (await edit("keeping the orders deferred", PlanCommandKind.keepDeferred, { orderIds: own, reason }, (b) => `Kept deferred. ${own.length === 1 ? "The store gets" : `${own.length} stores get`} the reason at publish. ${draftNote(b)}`)) && ok;
      }
      return ok;
    },
    lock: (orderId, locked) => edit(locked ? "locking the order" : "unlocking the order", locked ? PlanCommandKind.lock : PlanCommandKind.unlock, { orderId }, () => (locked ? "Locked. A regenerate keeps it on this vehicle." : "Unlocked.")),
    reorder: (tripId, orderIds, reason) => edit("changing the stop order", PlanCommandKind.reorderStops, { tripId, orderIds, reason }, (b) => `Stop order saved. ${draftNote(b)}`),
    editTrip: (tripId, orderIds, reason) =>
      edit("changing the trip", PlanCommandKind.editTrip, { tripId, orderIds, reason }, (b) => `${orderIds.length === 0 ? "Trip removed" : "Trip saved"}. ${draftNote(b)}`),
    moveTrip: (tripId, vehicleId, reason) =>
      edit("moving the trip", PlanCommandKind.replan, { tripId, replacementVehicleId: vehicleId, reason }, (b) => `Trip moved to ${vehicleId}. ${draftNote(b)} Send it to tell the drivers.`),
    contactStore: (orderId, message) => {
      const holder = depotHolding(items, orderId);
      return holder && holder.state.stage !== "none"
        ? send("telling the store", PlanCommandKind.contactStore, { planId: holder.state.plan.planId, orderId, message }, null, () => "The store manager has your message.")
        : Promise.resolve(false);
    },
  };

  /** Follows a queued generation to its end (R-PLN-41), then reads the day again. */
  const finish = async (depot: string, job: GenerationJobView, keepDecisions: boolean) => {
    try {
      const done = await generation.follow(depot, job);
      if (done.status === "DONE" && done.result) toast(asToast(named(depot, `${keepDecisions ? "Planned again, keeping your decisions. " : ""}${draftNote(done.result)}`)));
      else refused(new Error(done.error ?? "Planning stopped without a plan."), `generating the plan for ${depot}`);
    } catch (e) {
      refused(e instanceof Error ? e : new Error(String(e)), `generating the plan for ${depot}`);
    }
    reread();
    setViewing(null);
    setTab("decide");
  };
  /** Generates for the depots with no plan yet, or plans again those with a draft. */
  const generate = async (keepDecisions: boolean, only?: string[]) => {
    const targets = only ?? items.filter((i) => (keepDecisions ? i.state.stage === "draft" : i.state.stage === "none" || i.state.stage === "draft")).map((i) => i.depot);
    await Promise.all(
      targets.map(async (depot) => {
        const sent = await run<GenerationJobView>(PlanCommandKind.generate, { depotCode: depot, serviceDate: date, keepDecisions }, null);
        if (!sent.ok) {
          refused(sent.error, `generating the plan for ${depot}`);
          plans.refresh();
          return;
        }
        await finish(depot, sent.result, keepDecisions);
      }),
    );
  };
  // A generation already running for this day, from a reload or another dispatcher: follow it.
  const { resumed, clearResumed } = generation;
  useEffect(() => {
    if (resumed.length === 0) return;
    clearResumed();
    for (const r of resumed) void finish(r.depot, r.job, false);
  }, [resumed]);

  const saveSnapshot = () => each("saving the plan", PlanCommandKind.saveSnapshot, (i) => i.state.stage === "draft", {}, () => "Snapshot saved. Open it from the plan menu.");
  const restore = (snapshotId: string, label: string) =>
    edit("returning to the saved plan", PlanCommandKind.restoreSnapshot, { snapshotId }, (b) => `Returned to ${label}. ${draftNote(b)}`).then((ok) => ok && (setViewing(null), setTab("decide")));

  const publish = () =>
    each("publishing the plan", PlanCommandKind.publish, (i) => i.state.stage === "draft", {}, (depot) => {
      const revises = items.find((i) => i.depot === depot)?.state;
      return revises?.stage === "draft" && revises.revises
        ? `Update sent for ${dayLabel(date)}. Only the drivers and stores it changes are told.`
        : `Plan for ${dayLabel(date)} is published. Loaders and drivers now work from it.`;
    }).then((ok) => ok && (setConfirming(false), setTab("publish")));

  const revise = () =>
    each("revising the plan", PlanCommandKind.revise, (i) => i.state.stage === "published", { reason: reviseReason.trim() }, (_, b) => `Revision draft version ${b.planVersion}: ${counts(b)}. Nothing changes for loaders or drivers until it is sent.`).then(
      (ok) => ok && (setReviseReason(""), setRevising(false), setTab("decide")),
    );

  const stage = state.stage === "none" ? "No plan yet" : state.stage === "draft" ? (single ? `Draft version ${state.plan.planVersion}${state.revises ? " · revises the published plan" : ""}` : "Draft") : `${single ? `Published · version ${state.plan.planVersion}` : "Published"} · ${final ? "final" : "changes until 16:00"}`;
  const workingLabel = state.stage === "published" ? "Published plan" : "Working draft";
  const error = plans.error ?? orders.error ?? fleet.error;
  const improvement = improvementNote(plan?.improvement ?? null);
  const cost = viewing || !single ? null : costNote(plan?.cost);
  const viewingSaved = viewing !== null ? saved.data?.find((s) => s.snapshotId === viewing) : undefined;
  const decide = (orderId: string) => (setFocusId(orderId), setTab("decide"));
  const about =
    !viewing && plan
      ? [
          improvement ? `${improvement.title}. ${improvement.detail}` : null,
          cost && !cost.compare ? `${cost.title}. ${cost.detail}` : null,
          plan.plannedWithoutPredictor && live ? "Planned on the booklet's travel and service times: the time predictor is not running." : null,
        ].filter((line): line is string => line !== null)
      : [];
  const draft = state.stage === "draft";
  const toolsLine = summary && plan ? `saved ${savedWhen(plan.savedAt)} · ${stage}` : plans.data ? stage : "Loading";

  return (
    <section aria-label="Plan" className="flex w-full flex-col gap-4">
      <div className="flex w-full flex-wrap items-center gap-3">
        <p className="min-w-[200px] flex-1 text-[13px] text-go-secondary">{toolsLine}</p>
        {state.stage !== "none" && !settled && (
          <PlanTools
            draft={draft}
            single={single}
            workingLabel={workingLabel}
            snapshots={single ? (saved.data ?? []) : []}
            viewing={viewing}
            onView={setViewing}
            online={online}
            busy={busy || planning !== null}
            onSave={() => void saveSnapshot()}
            onRegenerate={(keep) => void generate(keep)}
            comparing={tab === "compare"}
            onCompare={() => setTab(tab === "compare" ? "view" : "compare")}
          />
        )}
      </div>

      {error && <Refusal error={error} what="the plan" action={<Retry onClick={() => (reread(), fleet.refresh())} />} />}
      {planning && <Notice tone="neutral" title={planning} live>The plan is being made in the background; it opens here when it is ready, even after a reload.</Notice>}
      {cost?.compare && live && !settled && tab !== "compare" && (
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
      {plans.data && unplanned.length > 0 && (
        <div className="flex w-full flex-wrap items-center gap-3 rounded-go-panel bg-go-card px-6 py-5">
          <div className="min-w-[240px] flex-1">
            <h3 className="text-[17px] font-medium text-go-ink">{`No plan yet for ${unplanned.join(" and ")} on ${dayLabel(date)}`}</h3>
            <p className="text-[13px] text-go-secondary">
              {orders.data ? unplanned.map((d) => `${d}: ${waitingIn(d)} ${waitingIn(d) === 1 ? "order waits" : "orders wait"}`).join(" · ") : "Counting the orders…"}. Generating places every
              order it can and names the rule that stopped each one it could not.
            </p>
          </div>
          {state.stage === "none" && toPlan === 0 && nextDay.data ? (
            <PrimaryButton onClick={() => onDate(nextDay.data!.date)}>{`Plan ${dayLabel(nextDay.data.date)} · ${nextDay.data.waiting} waiting`}</PrimaryButton>
          ) : (
            <PrimaryButton disabled={!online || planning !== null} busy={busy} onClick={() => void generate(false, unplanned)}>
              {planning ?? (busy ? "Generating…" : "Generate draft")}
            </PrimaryButton>
          )}
        </div>
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
            decideBlocks={draft && open > 0}
            published={state.stage === "published"}
            next={
              tab === "decide"
                ? { label: "Next: view plan", onClick: () => setTab("view") }
                : tab === "view" || tab === "compare"
                  ? { label: "Next: publish", onClick: () => setTab("publish") }
                  : draft
                    ? {
                        label: state.revises ? "Send update" : "Publish plan",
                        onClick: () => setConfirming(true),
                        disabled: !online || busy || open > 0,
                        hint: open > 0 ? `${open} ${open === 1 ? "order needs" : "orders need"} a decision in Decide` : !online ? "Offline: publishing waits for the connection" : undefined,
                      }
                    : { label: "Published", onClick: () => undefined, disabled: true }
            }
          />

          {tab === "decide" && <PlanDecide plan={plan} rows={rows} orders={byId} fleet={vehicles} editable={editable} actions={actions} focusId={focusId} />}
          {tab === "view" && (
            <PlanBoard about={about} plan={plan} fleet={vehicles} orders={byId} editable={editable} canReplan={online && viewing === null && !final} published={single && state.stage === "published" && viewing === null} actions={actions} onOpenDecision={decide} />
          )}
          {tab === "publish" && (
            <PlanPublish
              state={state}
              plan={live}
              orders={byId}
              depot={depots.join(" + ")}
              date={date}
              summary={summarise(live, vehicles)}
              rows={decisionRows(live, byId)}
              online={online}
              busy={busy}
              confirming={confirming}
              onConfirming={setConfirming}
              revising={revising}
              onRevising={setRevising}
              final={final}
              reviseReason={reviseReason}
              onReviseReason={setReviseReason}
              onPublish={() => void publish()}
              onRevise={() => void revise()}
              onDecide={decide}
            />
          )}
          {tab === "compare" && single && !settled && (
            <PlanCompare
              working={{ id: live.planId, version: live.planVersion }}
              snapshots={saved.data ?? []}
              orders={byId}
              editable={editable}
              busy={busy}
              onBack={() => setTab("view")}
              onSave={draft ? () => void saveSnapshot() : undefined}
              onUse={(snapshotId) => void restore(snapshotId, saved.data?.find((s) => s.snapshotId === snapshotId)?.label ?? "the saved plan")}
            />
          )}
        </>
      )}
      {!plans.data && !plans.error && <p className="py-8 text-center text-[13px] text-go-secondary">Loading the plan…</p>}
    </section>
  );
}

/** "16:41" today, "Sat 3 Oct 16:41" another day: a bare time from yesterday reads like one still to come. */
function savedWhen(at: string): string {
  const instant = new Date(at);
  const day = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo" }).format(instant);
  return day === depotToday() ? clock(instant) : `${dayLabel(day)} ${clock(instant)}`;
}

/** "Swapped. Draft version 2: 3 placed, 1 deferred." reads as a bold first sentence and the rest. */
function asToast(message: string): { title: string; detail?: string } {
  const at = message.indexOf(". ");
  return at < 0 ? { title: message } : { title: message.slice(0, at + 1), detail: message.slice(at + 2) };
}
