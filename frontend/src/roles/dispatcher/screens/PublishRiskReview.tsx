"use client";

import { useState } from "react";
import type { RecordRiskReview, RiskStopView } from "@shared/domain/types";
import { friendlyError } from "@shared/api/problem";
import { Notice, PrimaryButton, SecondaryButton } from "@shared/ui";
import { RECORD_RISK_REVIEW, riskSummary, riskWhy, useRiskReview } from "../data/riskReview.ts";
import { useCommand } from "../data/useCommand.ts";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";
import Row from "./PublishRow.tsx";

// Roadmap T1-01, Publish step: the stops likely to arrive after their window,
// for the dispatcher to keep (with a reason) or to swap (with a reason, then
// change the plan in Decide). Labelled an estimate: the figures are the
// deterministic estimate until the trust gates pass. It never blocks Publish.

export default function PublishRiskReview({
  planId,
  online,
  onDecide,
}: {
  planId: string;
  online: boolean;
  onDecide: (orderId: string) => void;
}): React.JSX.Element | null {
  const report = useRiskReview(planId);
  const [open, setOpen] = useState(false);
  if (report.error && !report.data) {
    return <Row tone="warning" title="Late risk could not be checked" note="Publishing is not blocked · try again later" />;
  }
  const data = report.data;
  if (!data || !data.enabled || data.stops.length === 0) return null;
  const unreviewed = data.stops.some((s) => s.review === null);
  return (
    <>
      <Row
        tone={unreviewed ? "danger" : "ok"}
        title={riskSummary(data.stops)}
        note={`${data.thresholdPercent}% chance or more of arriving after the window${data.estimate ? " · estimate: learning" : ""} · publishing is not blocked`}
        action={{ label: open ? "Hide" : "Review", onClick: () => setOpen((v) => !v) }}
      />
      {open && (
        <li aria-label="Stops likely to be late" className="flex flex-col pb-2 pl-11">
          {data.stops.map((stop) => (
            <RiskStop key={stop.orderId} planId={planId} stop={stop} online={online} onDecide={onDecide} onRecorded={report.refresh} />
          ))}
        </li>
      )}
    </>
  );
}

function RiskStop({
  planId,
  stop,
  online,
  onDecide,
  onRecorded,
}: {
  planId: string;
  stop: RiskStopView;
  online: boolean;
  onDecide: (orderId: string) => void;
  onRecorded: () => void;
}): React.JSX.Element {
  const { busy, run } = useCommand();
  const [choosing, setChoosing] = useState<"KEEP" | "SWAP" | null>(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const record = async () => {
    if (!choosing || !reasonReady(reason)) return;
    const payload: RecordRiskReview = { planId, orderId: stop.orderId, choice: choosing, reason: reason.trim() };
    const sent = await run(RECORD_RISK_REVIEW, payload, null);
    if (!sent.ok) {
      setError(friendlyError(sent.error));
      return;
    }
    const swap = choosing === "SWAP";
    setChoosing(null);
    setReason("");
    setError(null);
    onRecorded();
    if (swap) onDecide(stop.orderId);
  };

  return (
    <div data-testid="risk-stop" className="flex flex-col gap-2 border-t border-go-rule py-2 text-xs text-go-ink">
      <p className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="w-[100px] font-medium">{stop.outletId}</span>
        <span className="flex-1 text-go-secondary">{`${stop.vehicleId} · ${riskWhy(stop)}`}</span>
        <span className="font-medium text-go-danger-strong">{`${stop.percent}%`}</span>
      </p>
      {stop.review && choosing === null && (
        <p className="text-go-secondary">
          <span className="font-medium text-go-ink">{stop.review.choice === "KEEP" ? "Kept" : "To swap"}</span>
          {` · ${stop.review.reason}`}
        </p>
      )}
      {choosing === null ? (
        <div className="flex gap-2">
          <SecondaryButton disabled={!online} onClick={() => setChoosing("KEEP")}>
            Keep
          </SecondaryButton>
          <SecondaryButton disabled={!online} onClick={() => setChoosing("SWAP")}>
            Swap
          </SecondaryButton>
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          <ReasonPicker
            label={choosing === "KEEP" ? "Why keep it as planned?" : "Why swap it?"}
            value={reason}
            onChange={setReason}
            required
          />
          {error && <Notice tone="danger" live title={error} />}
          <div className="flex gap-2">
            <PrimaryButton busy={busy} disabled={!online || !reasonReady(reason)} onClick={() => void record()}>
              {choosing === "KEEP" ? "Keep this stop" : "Record and open in Decide"}
            </PrimaryButton>
            <SecondaryButton onClick={() => setChoosing(null)}>Cancel</SecondaryButton>
          </div>
        </div>
      )}
    </div>
  );
}
