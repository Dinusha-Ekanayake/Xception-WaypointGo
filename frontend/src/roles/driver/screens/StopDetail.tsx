"use client";

import { useState } from "react";
import type { OutletView } from "@shared/domain/types";
import { clock, type Stop } from "../data/run.ts";
import { ActionButton, Banner, Panel, Tag } from "../ui.tsx";
import ProofCapture, { EMPTY_PROOF, proofMissing, type ProofDraft } from "./ProofCapture.tsx";

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
  const [proof, setProof] = useState<ProofDraft>(EMPTY_PROOF);
  const [problem, setProblem] = useState<string | null>(null);
  const handedOver = stop.outcome === "DELIVERED" || stop.outcome === "PARTIAL";
  const owed = handedOver && !stop.proofCaptured && stop.arrivedAt !== null;
  const rows: Array<[string, string]> = [
    ["Window", `${clock(stop.windowOpen)} to ${clock(stop.windowClose)}`],
    ["Arrived", stop.arrivedAt ? clock(stop.arrivedAt) : "Not recorded"],
    ["Recorded", stop.completedAt ? clock(stop.completedAt) : "Not recorded"],
    ["Proof of delivery", stop.proofCaptured ? (proofOnPhone > 0 ? `Recorded · ${proofOnPhone} still sending` : "Recorded") : handedOver ? "Still owed" : "None"],
  ];
  if ((stop.lateMinutes ?? 0) > 0) rows.splice(2, 0, ["Late by", `${stop.lateMinutes} min`]);
  return (
    <div className="flex flex-col gap-4 px-5 pb-8 pt-2">
      <div>
        <p className="text-[15px] text-go-ink">
          Stop {String(stop.sequence).padStart(2, "0")} of {String(total).padStart(2, "0")}
        </p>
        <h1 className="mt-2 text-[40px] font-medium leading-[1.05] text-go-ink">{stop.outletId}</h1>
        {outlet && <p className="mt-1 text-[15px] text-go-muted">{outlet.districtName} · {outlet.brandCode}</p>}
        <div className="mt-3">
          <StopState stop={stop} />
        </div>
      </div>
      {stop.outcome === "SKIPPED" && (
        <Banner tone="neutral" title="Dispatch replanned this stop">
          It is no longer on your run. There is nothing to deliver here today.
        </Banner>
      )}
      <Panel label="What was recorded">
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
        <Panel label="Add proof of delivery">
          <h2 className="mb-4 text-[19px] font-medium text-go-ink">Add proof of delivery</h2>
          <ProofCapture proof={proof} onChange={setProof} />
          {(problem || error) && (
            <div className="mt-4">
              <Banner tone="bad" title={problem ?? error ?? ""} live />
            </div>
          )}
          <ActionButton
            className="mt-4"
            disabled={busy}
            onClick={() => {
              const gap = proofMissing(proof);
              setProblem(gap);
              if (!gap) onProof(proof);
            }}
          >
            Save proof
          </ActionButton>
        </Panel>
      )}
    </div>
  );
}

export function StopState({ stop }: { stop: Stop }): React.JSX.Element {
  const waiting = stop.waiting ? " · on phone" : "";
  switch (stop.outcome) {
    case "DELIVERED":
      return <Tag tone="good">Delivered{waiting}</Tag>;
    case "PARTIAL":
      return <Tag tone="warn">Partial{waiting}</Tag>;
    case "FAILED":
      return <Tag tone="bad">Not delivered{waiting}</Tag>;
    case "SKIPPED":
      return <Tag>Replanned</Tag>;
    case "ARRIVED":
      return <Tag tone="warn">At the stop{waiting}</Tag>;
    default:
      return <Tag>{stop.startedAt ? `On the way${waiting}` : "To do"}</Tag>;
  }
}
