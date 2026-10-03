"use client";

import { useState } from "react";
import { FailureReasons, type FailureReason, type OutletView } from "@shared/domain/types";
import { canDeliver, clock, missing, type RecordDraft, type Stop } from "../data/run.ts";
import { ActionButton, Banner, Field, OutlineButton, Panel, input } from "../ui.tsx";
import ProofCapture, { EMPTY_PROOF, proofMissing, type ProofDraft } from "./ProofCapture.tsx";

// Figma "Delivery report": expected against delivered, then confirm. In the
// design the store confirms the count on the driver's phone with a PIN; the
// system records the driver's own outcome and proof here, and the store
// confirms separately in its own screens (R-RCP-07), so neither record
// overwrites the other.

const REASON_LABEL: Record<FailureReason, string> = {
  outlet_closed: "No one at the outlet",
  refused: "The outlet refused the goods",
  mall_window_closed: "The mall's delivery window has closed",
  access_blocked: "Cannot unload here",
  vehicle_breakdown: "Vehicle broke down",
  goods_damaged: "Goods damaged",
  other: "Something else",
};

export type Report = { record: RecordDraft; proof: ProofDraft | null };

export default function DeliveryReport({
  stop,
  outlet,
  total,
  busy,
  timingUncertain,
  startFailed,
  error,
  onConfirm,
}: {
  stop: Stop;
  outlet: OutletView | undefined;
  total: number;
  busy: boolean;
  /** The arrival is still on this phone, so the server will time it when it is sent. */
  timingUncertain: boolean;
  /** Open on "not delivered" with this reason, from Report a problem. */
  startFailed: FailureReason | null;
  error: string | null;
  onConfirm: (report: Report) => void;
}): React.JSX.Element {
  const deliverable = stop.outcome === "ARRIVED" && canDeliver(stop);
  // Not delivered is the driver's choice, or the only outcome a stop not reached (or a late mall) can take.
  const [choseFailed, setChoseFailed] = useState(startFailed !== null);
  const failed = choseFailed || !deliverable;
  const [units, setUnits] = useState(stop.itemCount);
  const [reason, setReason] = useState<string>(startFailed ?? (stop.mallOutlet && !deliverable && stop.outcome === "ARRIVED" ? "mall_window_closed" : ""));
  const [note, setNote] = useState("");
  const [proof, setProof] = useState<ProofDraft>(EMPTY_PROOF);
  const [problem, setProblem] = useState<string | null>(null);

  const late = stop.lateMinutes ?? 0;
  const waited = stop.waitMinutes ?? 0;
  const outcome: RecordDraft["outcome"] = failed ? "FAILED" : units < stop.itemCount ? "PARTIAL" : "DELIVERED";
  const draft: RecordDraft = { outcome, deliveredUnits: units, reason, dispositionNote: note };
  // Proof is evidence of a handover. A stop where nothing was handed over may still have a photo, never must.
  const wantsProof = outcome !== "FAILED" || proof.signature !== null || proof.photo !== null || proof.fallbackReason.trim() !== "";

  const confirm = () => {
    const gap = missing(stop, draft, timingUncertain) ?? (outcome !== "FAILED" ? proofMissing(proof) : null);
    setProblem(gap);
    if (gap) return;
    onConfirm({ record: draft, proof: wantsProof && stop.outcome === "ARRIVED" ? proof : null });
  };

  return (
    <div className="flex flex-col gap-4 px-5 pb-8 pt-2">
      <div>
        <p className="text-[15px] text-go-ink">
          Stop {String(stop.sequence).padStart(2, "0")} of {String(total).padStart(2, "0")} · Delivery report
        </p>
        <h1 className="mt-2 text-[40px] font-medium leading-[1.05] text-go-ink">{stop.outletId}</h1>
        {outlet && <p className="mt-1 text-[15px] text-go-muted">{outlet.districtName} · {outlet.brandCode}</p>}
      </div>

      {stop.outcome === "ARRIVED" && waited > 0 && (
        <Banner tone="neutral" title={`Arrived ${waited} min before the window opens at ${clock(stop.windowOpen)}`}>
          Wait for the window. The wait is recorded separately from the delivery.
        </Banner>
      )}
      {late > 0 && (
        <Banner tone={stop.mallOutlet ? "bad" : "warn"} title={`Arrived ${late} min after the window closed at ${clock(stop.windowClose)}`}>
          {stop.mallOutlet
            ? "A mall takes no deliveries outside its window. Record this stop as not delivered."
            : timingUncertain
              ? "The time will be confirmed when this phone is back online."
              : "The goods are still delivered. Say why the stop was late."}
        </Banner>
      )}

      {!failed && (
        <Panel label="Units">
          <div className="grid grid-cols-2 divide-x divide-go-rule text-center">
            <div className="px-2">
              <p className="text-[22px] font-medium text-go-ink">Expected</p>
              <p className="text-[44px] font-semibold leading-tight tabular-nums text-go-ink">
                {stop.itemCount} <span className="text-[14px] font-normal">units</span>
              </p>
            </div>
            <div className="px-2">
              <p className="text-[22px] font-medium text-go-ink">Delivered</p>
              <p className="text-[44px] font-semibold leading-tight tabular-nums text-go-ink" aria-live="polite">
                {units} <span className="text-[14px] font-normal">units</span>
              </p>
            </div>
          </div>
          <div className="mt-4 flex items-center gap-3">
            <button
              type="button"
              aria-label="One unit fewer delivered"
              disabled={units <= 1}
              onClick={() => setUnits((n) => Math.max(1, n - 1))}
              className="flex size-14 items-center justify-center rounded-full bg-go-surface text-[28px] text-go-ink disabled:opacity-40"
            >
              −
            </button>
            <p className="flex-1 text-center text-[14px] text-go-muted">Change only if some units were not handed over.</p>
            <button
              type="button"
              aria-label="One unit more delivered"
              disabled={units >= stop.itemCount}
              onClick={() => setUnits((n) => Math.min(stop.itemCount, n + 1))}
              className="flex size-14 items-center justify-center rounded-full bg-go-surface text-[28px] text-go-ink disabled:opacity-40"
            >
              +
            </button>
          </div>
        </Panel>
      )}

      {failed && (
        <Panel label="Not delivered">
          <Field label="Why could it not be delivered?">
            <select className={input} value={reason} onChange={(event) => setReason(event.target.value)}>
              <option value="" disabled>
                Choose a reason
              </option>
              {FailureReasons.map((code) => (
                <option key={code} value={code}>
                  {REASON_LABEL[code]}
                </option>
              ))}
            </select>
          </Field>
        </Panel>
      )}

      {(failed || outcome === "PARTIAL" || (late > 0 && !timingUncertain)) && (
        <Panel label="Details">
          <div className="flex flex-col gap-4">
            {!failed && (
              <Field label={outcome === "PARTIAL" ? "Why were some units not delivered?" : "Why was the stop late?"}>
                <input className={input} value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} />
              </Field>
            )}
            {(failed || outcome === "PARTIAL") && (
              <Field label="What happened to the goods not delivered?" hint="There is no returns process. This note is the record.">
                <input
                  className={input}
                  value={note}
                  maxLength={500}
                  placeholder="For example: kept on the vehicle, returning to the depot"
                  onChange={(event) => setNote(event.target.value)}
                />
              </Field>
            )}
          </div>
        </Panel>
      )}

      {stop.outcome === "ARRIVED" && (
        <Panel label="Proof of delivery">
          <h2 className="mb-4 text-[19px] font-medium text-go-ink">{failed ? "Evidence (optional)" : "Proof of delivery"}</h2>
          <ProofCapture proof={proof} onChange={setProof} />
        </Panel>
      )}

      {(problem || error) && <Banner tone="bad" title={problem ?? error ?? ""} live />}

      <ActionButton disabled={busy} onClick={confirm}>
        {failed ? "Record as not delivered" : "Confirm"}
      </ActionButton>
      {deliverable && (
        <OutlineButton
          onClick={() => {
            setChoseFailed((value) => !value);
            setReason("");
            setProblem(null);
          }}
        >
          {failed ? "Back to the delivery" : "Could not deliver"}
        </OutlineButton>
      )}
    </div>
  );
}
