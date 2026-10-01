"use client";

import { useEffect, useState } from "react";
import type { ItemView, OutletView, ReadyTripView, ReleaseTrip } from "@shared/domain/types";
import { Icon, Notice } from "@shared/ui";
import type { LoadingGateway } from "../data/gateway.ts";
import { clockTime, hhmm, kg, loadedTotals, m3, progress, untilDeparture } from "../data/manifest.ts";
import { useTrip, type Line, type Outcome } from "../data/useTrip.ts";
import { Bar, BigButton, Ring, TempBadge } from "../ui.tsx";
import IssueSheet from "./IssueSheet.tsx";
import { HandBack, OutOfSequence, Released, Toast, type ToastMessage } from "./LoadFeedback.tsx";
import ManifestList from "./ManifestList.tsx";
import ReleaseSheet from "./ReleaseSheet.tsx";

// Figma "02 Load sheet". Container: the trip hook, the sheets and the notices.

export default function LoadSheet({
  gateway,
  trip,
  outlets,
  online,
  waiting,
  onQueued,
  onSynced,
  onBack,
  actingUserId,
}: {
  gateway: LoadingGateway;
  trip: ReadyTripView;
  outlets: Map<string, OutletView>;
  online: boolean;
  waiting: number;
  onQueued: () => void;
  onSynced: (at: Date | null) => void;
  /** Back to the dock board, after a release or from the top bar. */
  onBack: () => void;
  actingUserId: string;
}): React.JSX.Element {
  const t = useTrip(gateway, trip.tripId, online, waiting, onQueued, actingUserId);
  const [issueFor, setIssueFor] = useState<{ line: Line; item: ItemView | null } | null | undefined>(undefined);
  const [handingBack, setHandingBack] = useState(false);
  const [toast, setToast] = useState<ToastMessage | null>(null);
  const [sequence, setSequence] = useState<{ line: Line; item: ItemView; firstStop: number } | null>(null);
  const [justReleased, setJustReleased] = useState(false);
  const [releasing, setReleasing] = useState(false);
  const [blockedBy, setBlockedBy] = useState<string | null>(null);
  const m = t.manifest.data;

  useEffect(() => onSynced(t.manifest.loadedAt), [t.manifest.loadedAt, onSynced]);

  if (!m) {
    return t.manifest.error ? (
      <div className="px-5">
        <Notice tone="danger" title="Could not load this trip's load sheet">
          {t.manifest.error.message}
        </Notice>
      </div>
    ) : (
      <p className="py-10 text-center text-[15px] text-go-muted">Loading the load sheet…</p>
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

  const release = async (checklist: Omit<ReleaseTrip, "tripId">) => {
    const outcome: Outcome = await t.release(checklist);
    if (outcome.ok) {
      setReleasing(false);
      setJustReleased(true);
    }
    else setBlockedBy(outcome.error.message);
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
    const outcome = await t.check(line, item, to);
    if (outcome.ok && to === "LOADED") {
      setToast({
        title: `${item.productId} loaded`,
        detail: outcome.queued ? "Saved on this device; sends when you're back online." : undefined,
        undo: () => void t.check(line, { ...item, status: "LOADED" }, "PENDING"),
      });
    }
  };

  if (justReleased) {
    const reported = t.lines.filter((l) => l.items.some((i) => i.status !== "LOADED" && i.status !== "PENDING")).length;
    return (
      <Released
        vehicleId={m.vehicleId}
        summary={`${m.brandCode} · ${new Set(t.lines.map((l) => l.stopSequence)).size} stops · ${t.lines.length} orders${reported ? ` · ${reported} with items reported` : ""}`}
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
              Got it
            </button>
          }
        >
          {t.lines.filter((l) => l.recheck).length} orders moved and must be checked again. They are marked below; earlier checks on them no longer count.
        </Notice>
      )}
      {t.error && (
        <Notice
          tone="danger"
          live
          title={t.error.message}
          action={
            <button type="button" onClick={t.clearError} className="min-h-12 shrink-0 px-2 text-[13px] font-medium text-go-teal">
              Dismiss
            </button>
          }
        />
      )}

      {/* Landscape tablet: truck summary pinned left, load list beside it. */}
      <div className="flex flex-col gap-6 lg:grid lg:grid-cols-[440px_minmax(0,1fr)] lg:items-start">
      <section aria-label="Truck" className="flex flex-col gap-4 rounded-[31px] bg-white px-[22px] py-5 shadow-[0_5px_20px_rgba(0,0,0,0.09)] lg:sticky lg:top-[132px]">
        <div className="flex items-center gap-2.5">
          <span className="rounded-full bg-black px-3.5 py-1.5 text-[15px] font-medium text-white">
            {status === "COMPLETED" ? "Released" : status === "NOT_STARTED" ? "Not started" : status === "READY" ? "Ready to release" : "Loading"}
          </span>
          <TempBadge temperature={m.temperature} />
          <span className="flex-1" />
          <span className="text-right text-[13px] text-go-muted md:hidden">
            {m.vehicleId} · Trip {m.tripNumber}
            <br />
            Departs {hhmm(m.plannedDeparture)}
          </span>
        </div>
        <div className="flex justify-center py-2">
          <Ring percent={p.percent} />
        </div>
        <Bar label="Volume" value={`${m3(totals.volume)} / ${m3(volumeCap)}`} share={volumeCap ? totals.volume / volumeCap : 0} />
        <Bar label="Weight" value={`${kg(totals.weight)} / ${kg(weightCap)}`} share={weightCap ? totals.weight / weightCap : 0} />
        {editable && (
          <div className="flex items-center gap-2 rounded-[16px] bg-[#e7f3f2] py-2 pr-2 pl-4">
            <Icon name="lock" />
            <span className="flex-1 text-[14px] font-medium text-go-success">
              Locked to you{m.holder ? ` since ${clockTime(m.holder.since)}` : ""}
            </span>
            <button
              type="button"
              onClick={() => setHandingBack(true)}
              className="min-h-12 rounded-full bg-white px-4 text-[14px] font-medium text-black"
            >
              Hand back
            </button>
          </div>
        )}
        <div className="flex flex-col gap-1">
          <p className="text-[28px] font-semibold">
            {p.checked} of {p.total} orders loaded
          </p>
          <p className="text-[15px] font-medium text-go-success">
            {[left > 0 ? `${left} left` : "All loaded", untilDeparture(m.plannedDeparture)].filter(Boolean).join(" · ")}
          </p>
          {p.flagged > 0 && <p className="text-[15px] font-medium text-go-danger-strong">{p.flagged} reported to the dispatcher</p>}
        </div>

        {status === "NOT_STARTED" && (
          <BigButton size="l" onClick={() => void t.start()} disabled={t.busy}>
            Start loading
          </BigButton>
        )}
        {editable && (
          <div className="flex flex-wrap gap-3">
            <BigButton tone="danger" size="l" fit onClick={() => setIssueFor(null)}>
              Report issue
            </BigButton>
            <BigButton
              tone={left === 0 && t.planChangedFrom === null ? "mint" : "muted"}
              size="l"
              fit
              onClick={() => {
                setBlockedBy(
                  waiting > 0
                    ? "Some checks are still saved only on this phone. Release once they are sent."
                    : t.planChangedFrom !== null
                      ? "The plan changed. Confirm the change and recheck the marked orders first."
                      : null,
                );
                setReleasing(true);
              }}
            >
              {left === 0 ? "Release vehicle" : `Release · ${left} left`}
            </BigButton>
          </div>
        )}
        {status === "COMPLETED" && (
          <Notice tone="info" title="Vehicle released">
            The driver can depart. Reports made here stay with the trip.
          </Notice>
        )}
        {gateway.revisePlan && editable && (
          <button
            type="button"
            onClick={() => {
              gateway.revisePlan!(trip.tripId);
              t.manifest.refresh();
            }}
            className="min-h-12 text-[13px] text-go-warning-text underline"
          >
            Sample data: publish a plan change
          </button>
        )}
      </section>

      <ManifestList
        lines={t.lines}
        outlets={outlets}
        editable={editable}
        onToggle={(line, item) =>
          item ? void tick(line, item) : void t.check(line, null, line.status === "LOADED" ? "PENDING" : "LOADED")
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
              setToast({ title: "Issue saved on this device", detail: "Sends to the dispatcher when you're back online. Keep loading." });
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
          onAnyway={() => {
            const { line, item } = sequence;
            setSequence(null);
            void tick(line, item, true);
          }}
          onClose={() => setSequence(null)}
        />
      )}
      {toast && <Toast message={toast} onDone={() => setToast(null)} />}
      {handingBack && (
        <HandBack
          vehicleId={m.vehicleId}
          checked={p.checked}
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
