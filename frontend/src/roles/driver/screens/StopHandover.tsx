"use client";

import { useState } from "react";
import { Sheet } from "@shared/ui";
import type { StoreAnswerWaiverReason } from "@shared/domain/types";
import type { Stop } from "../data/run.ts";
import type { RouteStop } from "../data/stopView.ts";
import { useStoreAnswer } from "../data/storeAnswer.ts";
import DeliveryPinConfirmModal, { type HandoverAnswer } from "./DeliveryPinConfirmModal.tsx";
import { LEAVE_REASONS, handoverPhase, leftBecause } from "../data/handover.ts";
import DeliveryReportWaiting from "./DeliveryReportWaiting.tsx";

// Issue #21, store-led handover. At the door the driver hands over; the store
// checks the load and answers; the driver sees the store's report and accepts it
// with the store's PIN, or says why they move on (never a gate, R-RCP-09). This
// container decides the phase and holds the PIN and the reason; the view is
// DeliveryReportWaiting, the Figma "waiting" and "delivery report" screens.

export default function StopHandover({
  stop,
  stops,
  stopIndex,
  syncLabel,
  online,
  busy,
  isNight,
  onBack,
  onToggleTheme,
  onHandOver,
  onMoveOn,
  onVerifyPin,
  onAddProof,
  onNext,
  onProblem,
}: {
  stop: Stop;
  stops: RouteStop[];
  stopIndex: number;
  syncLabel: string;
  online: boolean;
  busy: boolean;
  isNight: boolean;
  onBack: () => void;
  onToggleTheme: () => void;
  onHandOver: () => Promise<boolean>;
  onMoveOn: (reason: StoreAnswerWaiverReason, detail: string) => Promise<boolean>;
  onVerifyPin: (pin: string) => Promise<HandoverAnswer>;
  onAddProof: () => void;
  onNext: () => void;
  onProblem: () => void;
}): React.JSX.Element {
  const handedOver = stop.outcome === "DELIVERED" || stop.outcome === "PARTIAL";
  const [acceptedHere, setAcceptedHere] = useState(false);
  const store = useStoreAnswer(stop.orderId, handedOver && !stop.storeAnswerWaived, online);
  const answer = store.state === "answered" ? store.answer : null;
  const accepted = acceptedHere || answer?.handover.status === "CONFIRMED";
  const phase = handoverPhase(stop, answer !== null, accepted);

  const [pinOpen, setPinOpen] = useState(false);
  const [asking, setAsking] = useState<"continue" | "disagree" | null>(null);
  const [detail, setDetail] = useState("");

  const verify = async (pin: string): Promise<HandoverAnswer> => {
    const result = await onVerifyPin(pin);
    if ("outcome" in result && (result.outcome === "VERIFIED" || result.outcome === "ALREADY_CONFIRMED")) {
      setAcceptedHere(true);
      store.refresh();
    }
    return result;
  };

  const leave = async (reason: StoreAnswerWaiverReason) => {
    const ok = await onMoveOn(reason, detail);
    if (ok) {
      setAsking(null);
      setDetail("");
    }
  };

  return (
    <>
      <DeliveryReportWaiting
        stops={stops}
        stopIndex={stopIndex}
        syncLabel={syncLabel}
        onBack={onBack}
        phase={phase}
        answer={answer}
        reachable={store.state !== "unreachable"}
        busy={busy}
        proofOwed={handedOver && !stop.proofCaptured}
        leftBecause={leftBecause(stop.storeAnswerWaived)}
        onHandOver={() => void onHandOver()}
        onAddProof={onAddProof}
        onContinue={() => setAsking("continue")}
        onEnterPin={() => setPinOpen(true)}
        onDisagree={() => setAsking("disagree")}
        onNext={onNext}
        onProblem={onProblem}
        isNight={isNight}
        onToggleTheme={onToggleTheme}
      />
      <DeliveryPinConfirmModal
        isOpen={pinOpen}
        onClose={() => setPinOpen(false)}
        onVerify={verify}
        isNight={isNight}
        stopName={stop.outletId}
        online={online}
      />
      {asking !== null && (
        <Sheet
          label={asking === "disagree" ? "Disagree with the store" : "Why are you moving on?"}
          onClose={() => {
            setAsking(null);
            setDetail("");
          }}
          placement="frame"
          dismissible={asking !== "disagree"}
        >
          <div>
            <h2 className="text-[24px] font-medium text-go-ink">{asking === "disagree" ? "Disagree with the store" : "Why are you moving on?"}</h2>
            <p className="mt-1 text-[15px] text-go-muted">
              {asking === "disagree"
                ? "Dispatch gets an issue to sort it out. The stop stays delivered and you carry on."
                : "The store can still answer after you leave. Dispatch sees why you moved on."}
            </p>
          </div>
          {asking === "disagree" ? (
            <>
              <label className="flex flex-col gap-1.5 text-[15px] text-go-ink">
                <span className="font-medium">What is wrong? (optional)</span>
                <textarea
                  value={detail}
                  maxLength={300}
                  onChange={(event) => setDetail(event.target.value)}
                  rows={3}
                  className="rounded-[18px] bg-go-surface p-3 text-[16px] text-go-ink outline-none focus-visible:ring-2 focus-visible:ring-go-signal"
                />
              </label>
              <button
                type="button"
                disabled={busy}
                onClick={() => void leave("disagree")}
                className="flex h-[64px] w-full items-center justify-center rounded-[22px] bg-go-action text-[20px] font-medium text-go-on-action active:scale-[0.99] disabled:opacity-60"
              >
                Raise issue and move on
              </button>
            </>
          ) : (
            <div className="flex flex-col gap-2.5">
              {LEAVE_REASONS.map((option) => (
                <button
                  key={option.reason}
                  type="button"
                  disabled={busy}
                  onClick={() => (option.reason === "disagree" ? setAsking("disagree") : void leave(option.reason))}
                  className="flex min-h-[60px] w-full items-center rounded-full bg-go-surface px-5 text-left text-[17px] font-medium text-go-ink active:scale-[0.99] disabled:opacity-60"
                >
                  {option.label}
                </button>
              ))}
            </div>
          )}
          <button
            type="button"
            onClick={() => {
              setAsking(null);
              setDetail("");
            }}
            className="flex h-[64px] w-full max-w-[295px] items-center justify-center self-center rounded-[22px] border border-go-muted bg-transparent text-[20px] font-medium text-go-ink active:scale-[0.99]"
          >
            Cancel
          </button>
        </Sheet>
      )}
    </>
  );
}
