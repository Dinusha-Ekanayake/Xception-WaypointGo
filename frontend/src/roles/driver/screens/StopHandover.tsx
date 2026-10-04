"use client";

import { useState } from "react";
import { Sheet } from "@shared/ui";
import type { StoreAnswerWaiverReason } from "@shared/domain/types";
import type { Stop } from "../data/run.ts";
import type { RouteStop } from "../data/stopView.ts";
import { useStoreAnswer } from "../data/storeAnswer.ts";
import { LEAVE_REASONS, handoverPhase, leftBecause } from "../data/handover.ts";
import DeliveryReportWaiting from "./DeliveryReportWaiting.tsx";
import { SlideRow } from "./Sheets.tsx";

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
  onEnterPin,
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
  /** Opens the store manager's PIN; a correct one moves the run on from here. */
  onEnterPin: () => void;
  onNext: () => void;
  onProblem: () => void;
}): React.JSX.Element {
  const handedOver = stop.outcome === "DELIVERED" || stop.outcome === "PARTIAL";
  const store = useStoreAnswer(stop.orderId, handedOver && !stop.storeAnswerWaived, online);
  const answer = store.state === "answered" ? store.answer : null;
  // Accepted on an earlier visit: a correct PIN entered now moves straight on, so it is never seen here.
  const accepted = answer?.handover.status === "CONFIRMED";
  const phase = handoverPhase(stop, answer !== null, accepted);

  const [asking, setAsking] = useState<"continue" | "disagree" | null>(null);
  const [detail, setDetail] = useState("");

  const leave = async (reason: StoreAnswerWaiverReason): Promise<boolean> => {
    const ok = await onMoveOn(reason, detail);
    if (ok) {
      setAsking(null);
      setDetail("");
    }
    return ok;
  };

  return (
    <>
      <DeliveryReportWaiting
        stops={stops}
        stopIndex={stopIndex}
        syncLabel={syncLabel}
        // Leaving a handed-over stop is a decision (R-EXE-26): Back asks why, the same as Continue.
        // At the door, once accepted or once the reason is given, Back just goes back.
        onBack={phase === "waiting" || phase === "answered" ? () => setAsking("continue") : onBack}
        phase={phase}
        answer={answer}
        reachable={store.state !== "unreachable"}
        busy={busy}
        leftBecause={leftBecause(stop.storeAnswerWaived)}
        onHandOver={() => void onHandOver()}
        onContinue={() => setAsking("continue")}
        onEnterPin={onEnterPin}
        onDisagree={() => setAsking("disagree")}
        onNext={onNext}
        onProblem={onProblem}
        isNight={isNight}
        onToggleTheme={onToggleTheme}
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
                : "The store can still answer after you leave. Dispatch sees why you moved on. Slide a row right to choose it."}
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
            // As Report a problem: a reason is slid across, so a stray tap never sends one.
            <div className="flex flex-col gap-2.5">
              {LEAVE_REASONS.map((option) => (
                <SlideRow
                  key={option.reason}
                  label={option.label}
                  disabled={busy}
                  onSend={async () => {
                    if (option.reason !== "disagree") return leave(option.reason);
                    setAsking("disagree");
                    return true;
                  }}
                />
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
