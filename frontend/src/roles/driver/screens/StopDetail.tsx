"use client";

import { useState } from "react";
import type { OutletView } from "@shared/domain/types";
import { clock, type Stop } from "../data/run.ts";
import { ActionButton, Banner, Panel, PinnedAction, Tag } from "../ui.tsx";
import ProofCapture, { EMPTY_PROOF, proofMissing, type ProofDraft } from "./ProofCapture.tsx";
import { useT } from "../i18n.tsx";

/**
 * A stop that already has its outcome: what was recorded, and whether its proof
 * has reached the server. Proof still owed can be added here, because proof
 * sits beside the outcome and may follow it (R-EXE-01).
 */
export default function StopDetail({
  stop,
  outlet,
  total,
  proofOnPhone,
  busy,
  error,
  onProof,
}: {
  stop: Stop;
  outlet: OutletView | undefined;
  total: number;
  /** Photo and signature files for this stop still waiting on this phone. */
  proofOnPhone: number;
  busy: boolean;
  error: string | null;
  onProof: (proof: ProofDraft) => void;
}): React.JSX.Element {
  const t = useT();
  const [proof, setProof] = useState<ProofDraft>(EMPTY_PROOF);
  const [problem, setProblem] = useState<string | null>(null);
  const handedOver = stop.outcome === "DELIVERED" || stop.outcome === "PARTIAL";
  const owed = handedOver && !stop.proofCaptured && stop.arrivedAt !== null;
  const rows: Array<[string, string]> = [
    [t("Window"), t("{open} to {close}", { open: clock(stop.windowOpen), close: clock(stop.windowClose) })],
    [t("Arrived"), stop.arrivedAt ? clock(stop.arrivedAt) : t("Not recorded")],
    [t("Recorded"), stop.completedAt ? clock(stop.completedAt) : t("Not recorded")],
    [
      t("Proof of delivery"),
      stop.proofCaptured
        ? proofOnPhone > 0
          ? t("Recorded · {n} still sending", { n: proofOnPhone })
          : t("Recorded")
        : t(handedOver ? "Still owed" : "None"),
    ],
  ];
  if ((stop.lateMinutes ?? 0) > 0) rows.splice(2, 0, [t("Late by"), t("{n} min", { n: stop.lateMinutes! })]);
  return (
    <div className="flex flex-col gap-4 px-5 pb-8 pt-2">
      {/* The tapped stop row on the route moves into this header (shared element, UX polish 4). */}
      <div style={{ viewTransitionName: "vt-stop" }}>
        <p className="text-[15px] text-go-ink">
          {t("Stop {n} of {total}", { n: String(stop.sequence).padStart(2, "0"), total: String(total).padStart(2, "0") })}
        </p>
        <h1 className="mt-2 text-[40px] font-medium leading-[1.05] text-go-ink">{stop.outletId}</h1>
        {outlet && <p className="mt-1 text-[15px] text-go-muted">{outlet.districtName} · {outlet.brandCode}</p>}
        <div className="mt-3">
          <StopState stop={stop} />
        </div>
      </div>
      {stop.outcome === "SKIPPED" && (
        <Banner tone="neutral" title={t("Dispatch replanned this stop")}>
          {t("It is no longer on your run. There is nothing to deliver here today.")}
        </Banner>
      )}
      <Panel label={t("What was recorded")}>
        <dl className="flex flex-col">
          {rows.map(([label, value], index) => (
            <div key={label} className={`flex min-h-12 items-center justify-between gap-4 text-[16px] ${index > 0 ? "border-t border-go-rule" : ""}`}>
              <dt className="text-go-muted">{label}</dt>
              <dd className="text-right font-medium text-go-ink">{value}</dd>
            </div>
          ))}
        </dl>
      </Panel>
      {owed && (
        <Panel label={t("Add proof of delivery")}>
          <h2 className="mb-4 text-[19px] font-medium text-go-ink">{t("Add proof of delivery")}</h2>
          <ProofCapture proof={proof} onChange={setProof} />
          {(problem || error) && (
            <div className="mt-4">
              <Banner tone="bad" title={t(problem ?? error ?? "")} live />
            </div>
          )}
          <PinnedAction on="card" className="mt-4">
            <ActionButton
              busy={busy}
              onClick={() => {
                const gap = proofMissing(proof, t);
                setProblem(gap);
                if (!gap) onProof(proof);
              }}
            >
              {t(busy ? "Sending…" : "Save proof")}
            </ActionButton>
          </PinnedAction>
        </Panel>
      )}
    </div>
  );
}

export function StopState({ stop }: { stop: Stop }): React.JSX.Element {
  const t = useT();
  const on = (state: string) => (stop.waiting ? t("{state} · on phone", { state }) : state);
  switch (stop.outcome) {
    case "DELIVERED":
      return <Tag tone="good">{on(t("Delivered"))}</Tag>;
    case "PARTIAL":
      return <Tag tone="warn">{on(t("Partial"))}</Tag>;
    case "FAILED":
      return <Tag tone="bad">{on(t("Not delivered"))}</Tag>;
    case "SKIPPED":
      return <Tag>{t("Replanned")}</Tag>;
    case "ARRIVED":
      return <Tag tone="warn">{on(t("At the stop"))}</Tag>;
    default:
      return <Tag>{stop.startedAt ? on(t("On the way")) : t("To do")}</Tag>;
  }
}
