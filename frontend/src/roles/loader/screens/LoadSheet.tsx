"use client";

import { useEffect, useState } from "react";
import type { OutletView, ReadyTripView } from "@shared/domain/types";
import { Notice } from "@shared/ui";
import type { LoadingGateway } from "../data/gateway.ts";
import { hhmm, kg, loadedTotals, m3, progress, tripTemperature } from "../data/manifest.ts";
import { useTrip, type Line, type Outcome } from "../data/useTrip.ts";
import { Bar, BigButton, Ring, TempBadge } from "../ui.tsx";
import IssueSheet from "./IssueSheet.tsx";
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
}: {
  gateway: LoadingGateway;
  trip: ReadyTripView;
  outlets: Map<string, OutletView>;
  online: boolean;
  waiting: number;
  onQueued: () => void;
  onSynced: (at: Date | null) => void;
}): React.JSX.Element {
  const t = useTrip(gateway, trip.tripId, online, waiting, onQueued);
  const [issueFor, setIssueFor] = useState<Line | null | undefined>(undefined);
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
  const editable = status === "IN_PROGRESS" || status === "BLOCKED";
  const left = p.total - p.checked;

  const release = async () => {
    const outcome: Outcome = await t.release();
    if (outcome.ok) setReleasing(false);
    else setBlockedBy(outcome.error.message);
  };

  return (
    <div className="flex flex-col gap-4 px-5 pb-8">
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
      <div className="flex flex-col gap-4 lg:grid lg:grid-cols-[400px_minmax(0,1fr)] lg:items-start lg:gap-6">
      <section aria-label="Truck" className="flex flex-col gap-4 lg:sticky lg:top-4 rounded-[31px] bg-white px-[22px] py-5 shadow-[0_5px_20px_rgba(0,0,0,0.09)]">
        <div className="flex items-center gap-2.5">
          <span className="rounded-full bg-black px-3.5 py-1.5 text-[15px] font-medium text-white">
            {status === "COMPLETED" ? "Released" : status === "NOT_STARTED" ? "Not started" : "Loading"}
          </span>
          <TempBadge temperature={tripTemperature(t.lines)} />
          <span className="flex-1" />
          <span className="text-right text-[13px] text-go-muted">
            {m.vehicleId} · Trip {m.tripNumber}
            <br />
            Departs {hhmm(trip.plannedDeparture)}
          </span>
        </div>
        <div className="flex justify-center py-2">
          <Ring percent={p.percent} />
        </div>
        <Bar label="Volume loaded" value={`${m3(totals.volume)} / ${m3(totals.volumeAll)}`} share={totals.volumeAll ? totals.volume / totals.volumeAll : 0} />
        <Bar label="Weight loaded" value={`${kg(totals.weight)} / ${kg(totals.weightAll)}`} share={totals.weightAll ? totals.weight / totals.weightAll : 0} />
        <div className="flex flex-col gap-1">
          <p className="text-[28px] font-semibold">
            {p.checked} of {p.total} orders checked
          </p>
          {p.flagged > 0 && <p className="text-[15px] font-medium text-go-danger-strong">{p.flagged} reported to the dispatcher</p>}
        </div>

        {status === "NOT_STARTED" && (
          <BigButton size="l" onClick={() => void t.start()} disabled={t.busy}>
            Start loading
          </BigButton>
        )}
        {editable && (
          <div className="flex flex-col gap-3">
            <BigButton tone="danger" size="l" onClick={() => setIssueFor(null)}>
              Report issue
            </BigButton>
            <BigButton
              tone={left === 0 && t.planChangedFrom === null ? "ink" : "muted"}
              size="l"
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
            <button type="button" onClick={() => window.confirm("Hand this trip back? Your checks stay, and another loader can take it.") && void t.handBack()} className="min-h-12 text-[14px] font-medium text-go-muted underline">
              Hand back this trip
            </button>
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
        onToggle={(line) => void t.check(line, line.status === "LOADED" ? "PENDING" : "LOADED")}
        onReport={(line) => setIssueFor(line)}
      />
      </div>

      {issueFor !== undefined && (
        <IssueSheet
          lines={t.lines}
          initial={issueFor}
          outlets={outlets}
          busy={t.busy}
          onSend={async (payload) => (await t.flag(payload)).ok}
          onClose={() => setIssueFor(undefined)}
        />
      )}
      {releasing && (
        <ReleaseSheet
          lines={t.lines}
          outlets={outlets}
          blockedBy={blockedBy}
          busy={t.busy}
          onRelease={() => void release()}
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
