"use client";

import { useEffect, useRef, useState } from "react";
import type { ItemView, OutletView, ReadyTripView, ReleaseTrip } from "@shared/domain/types";
import { ApiError, friendlyError } from "@shared/api/problem";
import { Notice, SkeletonRows } from "@shared/ui";
import type { LoadingGateway } from "../data/gateway.ts";
import { loadedTotals, orderLabel, paceOf, progress } from "../data/manifest.ts";
import { useTrip, type Line, type Outcome } from "../data/useTrip.ts";
import IssueSheet from "./IssueSheet.tsx";
import { HandBack, OutOfSequence, Released, Toast, TripTaken, type ToastMessage } from "./LoadFeedback.tsx";
import ManifestList from "./ManifestList.tsx";
import TruckCard from "./TruckCard.tsx";
import ReleaseSheet from "./ReleaseSheet.tsx";
import TripMessages from "./TripMessages.tsx";
import { useT } from "../i18n.tsx";
import { businessNow } from "@shared/wording";

// Figma "02 Load sheet". Container: the trip hook, the sheets and the notices.

export default function LoadSheet({
  gateway,
  trip,
  outlets,
  online,
  waiting,
  onQueued,
  onSynced,
  refreshKey,
  onBack,
  actingUserId,
  accountId,
}: {
  gateway: LoadingGateway;
  trip: ReadyTripView;
  outlets: Map<string, OutletView>;
  online: boolean;
  waiting: number;
  onQueued: () => void;
  onSynced: (at: Date | null) => void;
  /** Bumped when the loader taps "Synced": read the manifest again. */
  refreshKey?: number;
  /** Back to the dock board, after a release or from the top bar. */
  onBack: () => void;
  actingUserId: string;
  /** The device's account: messages are kept on it with no signal. */
  accountId: string;
}): React.JSX.Element {
  const tr = useT();
  const t = useTrip(gateway, trip.tripId, online, waiting, onQueued, actingUserId);
  const [issueFor, setIssueFor] = useState<{ line: Line; item: ItemView | null } | null | undefined>(undefined);
  const [handingBack, setHandingBack] = useState(false);
  const [toast, setToast] = useState<ToastMessage | null>(null);
  const [sequence, setSequence] = useState<{ line: Line; item: ItemView; firstStop: number } | null>(null);
  const [justReleased, setJustReleased] = useState(false);
  const [releasing, setReleasing] = useState(false);
  const [blockedBy, setBlockedBy] = useState<string | null>(null);
  const [talking, setTalking] = useState(false);
  // Lines whose tick is on its way: a second tap on the same line is ignored until it answers.
  const ticking = useRef(new Set<string>());
  const m = t.manifest.data;

  useEffect(() => onSynced(t.manifest.loadedAt), [t.manifest.loadedAt, onSynced]);
  const { refresh: reload } = t.manifest;
  useEffect(() => {
    if (refreshKey) reload();
  }, [refreshKey, reload]);

  if (!m) {
    return t.manifest.error ? (
      <div className="px-5">
        <Notice tone="danger" title={tr("Could not load this trip's load sheet")} onRetry={reload} retryLabel={tr("Try again")}>
          {friendlyError(t.manifest.error)}
        </Notice>
      </div>
    ) : (
      <div className="px-5 py-4">
        <SkeletonRows label={tr("Loading the load sheet…")} />
      </div>
    );
  }

  const status = m.status;
  const p = progress(t.lines);
  const totals = loadedTotals(t.lines);
  // Bars compare the load with what the vehicle can carry, by order-level weight
  // and volume (AGENTS.md, capacity never reads product lines).
  const weightCap = Number(m.weightCapKg);
  const volumeCap = Number(m.volumeCapM3);
  const editable = status === "IN_PROGRESS" || status === "BLOCKED" || status === "READY";
  const left = p.total - p.checked;
  const pace = editable && m.holder ? paceOf(p.checked, p.total, m.holder.since, m.plannedDeparture) : null;
  // 04: the trip went to another loader first (R-LOD-11).
  const taken =
    t.error instanceof ApiError && t.error.status === 409 && t.error.problem.violations.some((v) => v.rule === "R-LOD-11")
    && m.holder !== null && m.holder.userId !== actingUserId;
  const nextUp = t.lines
    .flatMap((l) => l.items.filter((i) => i.status === "PENDING").map((i) => tr("{order} · item {n}", { order: orderLabel(l), n: i.lineNo })))
    [0] ?? null;

  const release = async (checklist: Omit<ReleaseTrip, "tripId">) => {
    const outcome: Outcome = await t.release(checklist);
    if (outcome.ok) {
      setReleasing(false);
      setJustReleased(true);
    }
    else setBlockedBy(outcome.error.message);
  };

  const once = async (key: string, work: () => Promise<unknown>) => {
    if (ticking.current.has(key)) return;
    ticking.current.add(key);
    try {
      await work();
    } finally {
      ticking.current.delete(key);
    }
  };

  /** Tick one item; a pending stop that loads earlier is warned about first (E6), then Undo is offered (E5). */
  const tick = async (line: Line, item: ItemView, force = false) => {
    if (item.status !== "LOADED" && !force) {
      const earlier = t.lines.filter((l) => l.stopSequence > line.stopSequence && l.items.some((i) => i.status === "PENDING"));
      if (earlier.length > 0) {
        setSequence({ line, item, firstStop: Math.max(...earlier.map((l) => l.stopSequence)) });
        return;
      }
    }
    const to = item.status === "LOADED" ? "PENDING" : "LOADED";
    await t.check(line, item, to);
  };

  if (justReleased) {
    const reported = t.lines.filter((l) => l.items.some((i) => i.status !== "LOADED" && i.status !== "PENDING")).length;
    return (
      <Released
        vehicleId={m.vehicleId}
        summary={[
          m.brandCode,
          tr("{n} stops", { n: new Set(t.lines.map((l) => l.stopSequence)).size }),
          tr("{n} orders", { n: t.lines.length }),
          reported > 0 && tr("{n} with items reported", { n: reported }),
        ].filter(Boolean).join(" · ")}
        onBack={onBack}
      />
    );
  }

  return (
    <div className="flex flex-col gap-4 px-5 pb-8 md:px-8 lg:px-10">
      {t.planChangedFrom !== null && (
        <Notice
          tone="warning"
          live
          title={`Plan changed: version ${t.planChangedFrom} → ${m.planVersion}`}
          action={
            <button type="button" onClick={t.acknowledgePlan} className="min-h-12 shrink-0 px-2 text-[13px] font-medium text-go-teal">
              {tr("Got it")}
            </button>
          }
        >
          {t.lines.filter((l) => l.recheck).length} orders moved and must be checked again. They are marked below; earlier checks on them no longer count.
        </Notice>
      )}
      {taken && <TripTaken vehicleId={m.vehicleId} onBack={onBack} />}
      {t.error && !taken && (
        <Notice
          tone="danger"
          live
          title={friendlyError(t.error)}
          action={
            <button type="button" onClick={t.clearError} className="min-h-12 shrink-0 px-2 text-[13px] font-medium text-go-teal">
              {tr("Dismiss")}
            </button>
          }
        />
      )}

      <div className="flex justify-end">
        <button type="button" onClick={() => setTalking(true)} className="min-h-11 rounded-full bg-go-card px-4 text-[14px] font-medium text-go-ink shadow-go-card">
          {tr("Messages")}
        </button>
      </div>

      {/* Landscape tablet: truck summary pinned left, load list beside it. */}
      <div className="flex flex-col gap-6 lg:grid lg:grid-cols-[440px_minmax(0,1fr)] lg:items-start">
      <TruckCard
        m={m}
        status={status}
        percent={p.percent}
        checked={p.checked}
        total={p.total}
        flagged={p.flagged}
        left={left}
        pace={pace}
        totals={totals}
        weightCap={weightCap}
        volumeCap={volumeCap}
        editable={editable}
        busy={t.busy}
        planChanged={t.planChangedFrom !== null}
        waiting={waiting}
        sample={gateway.revisePlan ? () => {
          gateway.revisePlan!(trip.tripId);
          t.manifest.refresh();
        } : undefined}
        onStart={() => void t.start()}
        onHandBack={() => setHandingBack(true)}
        onReport={() => setIssueFor(null)}
        onRelease={(blocked) => {
          setBlockedBy(blocked);
          setReleasing(true);
        }}
      />

      <ManifestList
        lines={t.lines}
        outlets={outlets}
        editable={editable}
        onToggle={(line, item) =>
          void once(`${line.orderId}:${item?.lineNo ?? "order"}`, () =>
            item ? tick(line, item) : t.check(line, null, line.status === "LOADED" ? "PENDING" : "LOADED"),
          )
        }
        onReport={(line, item) => setIssueFor({ line, item })}
      />
      </div>

      {issueFor !== undefined && (
        <IssueSheet
          lines={t.lines}
          initial={issueFor}
          outlets={outlets}
          busy={t.busy}
          onSend={async (payload) => {
            const outcome = await t.flag(payload);
            if (outcome.ok && outcome.queued) {
              setToast({
                kind: "saved",
                at: businessNow(),
                title: tr("Issue saved on this device"),
                detail: tr("Sends when you're back online. Keep loading."),
                note: tr("Sends to the dispatcher and the store manager"),
              });
            }
            return outcome.ok;
          }}
          onClose={() => setIssueFor(undefined)}
        />
      )}
      {sequence && (
        <OutOfSequence
          firstStop={sequence.firstStop}
          thisStop={sequence.line.stopSequence}
          loadsLast={sequence.line.stopSequence === Math.min(...t.lines.map((l) => l.stopSequence))}
          onAnyway={() => {
            const { line, item } = sequence;
            setSequence(null);
            void tick(line, item, true);
          }}
          onClose={() => setSequence(null)}
        />
      )}
      {toast && <Toast message={toast} onDone={() => setToast(null)} />}
      {talking && <TripMessages accountId={accountId} actingUserId={actingUserId} tripId={trip.tripId} vehicleId={m.vehicleId} online={online} onQueued={onQueued} onClose={() => setTalking(false)} />}
      {handingBack && (
        <HandBack
          vehicleId={m.vehicleId}
          checked={p.checked}
          nextUp={nextUp}
          busy={t.busy}
          onConfirm={async () => {
            if ((await t.handBack()).ok) setHandingBack(false);
          }}
          onClose={() => setHandingBack(false)}
        />
      )}
      {releasing && (
        <ReleaseSheet
          lines={t.lines}
          outlets={outlets}
          blockedBy={blockedBy}
          capacity={{ weight: totals.weight, weightCap, volume: totals.volume, volumeCap }}
          busy={t.busy}
          onRelease={(checklist) => void release(checklist)}
          onReport={() => {
            setReleasing(false);
            setIssueFor(null);
          }}
          onClose={() => {
            setReleasing(false);
            setBlockedBy(null);
          }}
        />
      )}
    </div>
  );
}
