"use client";

import { useState } from "react";
import { FailureReasons, type FailureReason, type OutletView } from "@shared/domain/types";
import { canDeliver, clock, missing, withDispatcher, type RecordDraft, type Stop } from "../data/run.ts";
import { ActionButton, Banner, Field, OutlineButton, Panel, PinnedAction, input } from "../ui.tsx";
import ProofCapture, { EMPTY_PROOF, proofMissing, type ProofDraft } from "./ProofCapture.tsx";
import { useT } from "../i18n.tsx";

// Figma "Delivery report": expected against delivered, then confirm. In the
// design the store confirms the count on the driver's phone with a PIN; the
// system records the driver's own outcome and proof here, and the store
// confirms separately in its own screens (R-RCP-07), so neither record
// overwrites the other.

/** Drawn translated; tests/driver-strings.test.ts checks each has its row. */
export const REASON_LABEL: Record<FailureReason, string> = {
  outlet_closed: "No one at the outlet",
  refused: "The outlet refused the goods",
  mall_window_closed: "The mall's delivery window has closed",
  access_blocked: "Cannot unload here",
  vehicle_breakdown: "Vehicle broke down",
  goods_damaged: "Goods damaged",
  other: "Something else",
};

/** `damaged`: some units were damaged and the rest handed over; a quiet goods problem goes with it (R-ISS-16). */
export type Report = { record: RecordDraft; proof: ProofDraft | null; damaged?: boolean };

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
  const t = useT();
  // R-EXE-32: a stop the driver reported is the dispatcher's to end; the goods may still be handed over.
  const reported = withDispatcher(stop);
  const deliverable = stop.outcome === "ARRIVED" && canDeliver(stop);
  // Owner's decision of 2026-10-21: goods damaged is a partial delivery of what is usable,
  // with a quiet goods problem; only nothing usable is not delivered (as before).
  const damagedMode = startFailed === "goods_damaged" && deliverable;
  // Not delivered is the driver's choice, or the only outcome a stop not reached (or a late mall) can take.
  const [choseFailed, setChoseFailed] = useState(startFailed !== null && !damagedMode);
  const failed = choseFailed || !deliverable;
  const [units, setUnits] = useState(damagedMode ? Math.max(1, stop.itemCount - 1) : stop.itemCount);
  const [reason, setReason] = useState<string>(
    damagedMode ? "Goods damaged" : startFailed ?? (stop.mallOutlet && !deliverable && stop.outcome === "ARRIVED" ? "mall_window_closed" : ""),
  );
  const [note, setNote] = useState(damagedMode ? "Damaged units kept on the vehicle" : "");
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
    onConfirm({ record: draft, proof: wantsProof && stop.outcome === "ARRIVED" ? proof : null, damaged: damagedMode && outcome === "PARTIAL" });
  };

  // Which visible field a gap belongs to, so it is shown there instead of only in
  // the banner; a gap about the stop as a whole (arrival, the mall's window, the
  // partial range) has no single field and stays in the banner below.
  const REASON_GAPS = ["Choose why the delivery could not be made.", "Say why the delivery was partial.", "This stop is late. Say why."];
  const NOTE_GAPS = ["Say what happened to the goods.", "Say what happened to the units not delivered."];
  // The gaps are compared in English and drawn in the driver's language.
  const reasonError = problem && REASON_GAPS.includes(problem) ? t(problem) : undefined;
  const noteError = problem && NOTE_GAPS.includes(problem) ? t(problem) : undefined;
  const bannerProblem = problem && !reasonError && !noteError ? t(problem) : null;

  return (
    <div className="flex flex-col gap-4 px-5 pb-8 pt-2">
      <div>
        <p className="text-[15px] text-go-ink">
          {t("Stop {n} of {total} · Delivery report", { n: String(stop.sequence).padStart(2, "0"), total: String(total).padStart(2, "0") })}
        </p>
        <h1 className="mt-2 text-[40px] font-medium leading-[1.05] text-go-ink">{stop.outletId}</h1>
        {outlet && <p className="mt-1 text-[15px] text-go-muted">{outlet.districtName} · {outlet.brandCode}</p>}
      </div>

      {damagedMode && !failed && (
        <Banner tone="warn" title={t("Goods damaged")}>
          {t("Set the units handed over in good condition. The rest stay on the vehicle, and a goods problem is recorded for the end of the day. Nothing usable? Tap Could not deliver.")}
        </Banner>
      )}

      {reported && (
        <Banner tone="warn" title={t(stop.reportedProblem === "CANNOT_UNLOAD" ? "You reported: cannot unload here" : "You reported: store closed")}>
          {t("The dispatcher decides: they take this store off your trip, or tell you to wait. If the store opens, deliver as usual.")}
        </Banner>
      )}

      {stop.outcome === "ARRIVED" && waited > 0 && (
        <Banner tone="neutral" title={t("Arrived {n} min before the window opens at {time}", { n: waited, time: clock(stop.windowOpen) })}>
          {t("Wait for the window. The wait is recorded separately from the delivery.")}
        </Banner>
      )}
      {late > 0 && (
        <Banner tone={stop.mallOutlet ? "bad" : "warn"} title={t("Arrived {n} min after the window closed at {time}", { n: late, time: clock(stop.windowClose) })}>
          {t(
            stop.mallOutlet
              ? "A mall takes no deliveries outside its window. Record this stop as not delivered."
              : timingUncertain
                ? "The time will be confirmed when this phone is back online."
                : "The goods are still delivered. Say why the stop was late.",
          )}
        </Banner>
      )}

      {!failed && (
        <Panel label={t("Units")}>
          <div className="grid grid-cols-2 divide-x divide-go-rule text-center">
            <div className="px-2">
              <p className="text-[22px] font-medium text-go-ink">{t("Expected")}</p>
              <p className="text-[44px] font-semibold leading-tight tabular-nums text-go-ink">
                {stop.itemCount} <span className="text-[14px] font-normal">{t("units")}</span>
              </p>
            </div>
            <div className="px-2">
              <p className="text-[22px] font-medium text-go-ink">{t("Delivered")}</p>
              <p className="text-[44px] font-semibold leading-tight tabular-nums text-go-ink" aria-live="polite">
                {units} <span className="text-[14px] font-normal">{t("units")}</span>
              </p>
            </div>
          </div>
          <div className="mt-4 flex items-center gap-3">
            <button
              type="button"
              aria-label={t("One unit fewer delivered")}
              disabled={units <= 1}
              onClick={() => setUnits((n) => Math.max(1, n - 1))}
              className="flex size-14 items-center justify-center rounded-full bg-go-surface text-[28px] text-go-ink disabled:opacity-40"
            >
              −
            </button>
            <p className="flex-1 text-center text-[14px] text-go-muted">{t("Change only if some units were not handed over.")}</p>
            <button
              type="button"
              aria-label={t("One unit more delivered")}
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
        <Panel label={t("Not delivered")}>
          <Field label={t("Why could it not be delivered?")} error={reasonError} errorId="reason-error">
            <select
              className={input}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              aria-invalid={reasonError ? true : undefined}
              aria-describedby={reasonError ? "reason-error" : undefined}
            >
              <option value="" disabled>
                {t("Choose a reason")}
              </option>
              {/* A closed store or a stop that cannot unload is reported to the dispatcher instead (R-EXE-32). */}
              {FailureReasons.filter((code) => code !== "outlet_closed" && code !== "access_blocked").map((code) => (
                <option key={code} value={code}>
                  {t(REASON_LABEL[code])}
                </option>
              ))}
            </select>
          </Field>
        </Panel>
      )}

      {(failed || outcome === "PARTIAL" || (late > 0 && !timingUncertain)) && (
        <Panel label={t("Details")}>
          <div className="flex flex-col gap-4">
            {!failed && (
              <Field label={t(outcome === "PARTIAL" ? "Why were some units not delivered?" : "Why was the stop late?")} error={reasonError} errorId="reason-error">
                <input
                  className={input}
                  value={reason}
                  maxLength={500}
                  onChange={(event) => setReason(event.target.value)}
                  aria-invalid={reasonError ? true : undefined}
                  aria-describedby={reasonError ? "reason-error" : undefined}
                />
              </Field>
            )}
            {(failed || outcome === "PARTIAL") && (
              <Field
                label={t("What happened to the goods not delivered?")}
                hint={t("There is no returns process. This note is the record.")}
                error={noteError}
                errorId="note-error"
              >
                <input
                  className={input}
                  value={note}
                  maxLength={500}
                  placeholder={t("For example: kept on the vehicle, returning to the depot")}
                  onChange={(event) => setNote(event.target.value)}
                  aria-invalid={noteError ? true : undefined}
                  aria-describedby={noteError ? "note-error" : undefined}
                />
              </Field>
            )}
          </div>
        </Panel>
      )}

      {stop.outcome === "ARRIVED" && (
        <Panel label={t("Proof of delivery")}>
          <h2 className="mb-4 text-[19px] font-medium text-go-ink">{t(failed ? "Evidence (optional)" : "Proof of delivery")}</h2>
          <ProofCapture proof={proof} onChange={setProof} />
        </Panel>
      )}

      {(bannerProblem || error) && <Banner tone="bad" title={bannerProblem ?? error ?? ""} live />}

      {!(reported && failed) && (
        <PinnedAction on="canvas">
          <ActionButton busy={busy} onClick={confirm}>
            {t(busy ? "Sending…" : failed ? "Record as not delivered" : "Confirm")}
          </ActionButton>
        </PinnedAction>
      )}
      {deliverable && !reported && (
        <OutlineButton
          onClick={() => {
            setChoseFailed((value) => !value);
            setReason(damagedMode && !choseFailed ? "goods_damaged" : damagedMode ? "Goods damaged" : "");
            setProblem(null);
          }}
        >
          {t(failed ? "Back to the delivery" : "Could not deliver")}
        </OutlineButton>
      )}
    </div>
  );
}
