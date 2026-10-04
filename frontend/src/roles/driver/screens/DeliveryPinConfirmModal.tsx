"use client";

import { useEffect, useRef, useState } from "react";
import { cx } from "@shared/ui";

// The store's one-time handover PIN (R-RCP-09, issue #117). The store manager
// reads it from their screen and the driver types it here; the server checks
// it. It is evidence that the two met, never a gate: the delivery is already
// recorded, so Skip is always there and no answer holds the driver up.

export type HandoverAnswer =
  | { outcome: "VERIFIED" | "ALREADY_CONFIRMED" | "WRONG" | "LOCKED" | "EXPIRED"; attemptsLeft: number }
  | { error: string };

export type DeliveryPinConfirmModalProps = {
  isOpen: boolean;
  /** Skip, or done: the driver moves on either way. */
  onClose: () => void;
  onVerify: (pin: string) => Promise<HandoverAnswer>;
  isNight?: boolean;
  stopName: string;
  online: boolean;
};

const MESSAGE = {
  WRONG: (left: number) => `That PIN is not right. ${left} ${left === 1 ? "try" : "tries"} left.`,
  LOCKED: "Too many wrong tries. The store manager can issue a new PIN; the delivery is recorded either way.",
  EXPIRED: "This PIN has expired. The store manager can issue a new one; the delivery is recorded either way.",
};

export default function DeliveryPinConfirmModal({
  isOpen,
  onClose,
  onVerify,
  isNight = false,
  stopName,
  online,
}: DeliveryPinConfirmModalProps): React.JSX.Element | null {
  const [pin, setPin] = useState("");
  const [step, setStep] = useState<"pin" | "confirmed">("pin");
  const [checking, setChecking] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [finished, setFinished] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    setPin("");
    setStep("pin");
    setMessage(null);
    setFinished(false);
    window.setTimeout(() => input.current?.focus(), 50);
  }, [isOpen]);

  useEffect(() => {
    if (step !== "confirmed") return;
    const timer = window.setTimeout(onClose, 2200);
    return () => window.clearTimeout(timer);
  }, [step, onClose]);

  if (!isOpen) return null;

  const verify = async () => {
    if (pin.length !== 4 || checking) return;
    setChecking(true);
    setMessage(null);
    const answer = await onVerify(pin);
    setChecking(false);
    if ("error" in answer) return setMessage(answer.error);
    if (answer.outcome === "VERIFIED" || answer.outcome === "ALREADY_CONFIRMED") return setStep("confirmed");
    setPin("");
    if (answer.outcome === "WRONG") return setMessage(MESSAGE.WRONG(answer.attemptsLeft));
    setFinished(true);
    setMessage(MESSAGE[answer.outcome]);
  };

  const card = cx(
    "relative z-10 w-full max-w-[343px] rounded-[38px] px-6 flex flex-col items-center shadow-[0px_5px_20px_rgba(0,0,0,0.09)] transition-colors select-none animate-fade-in",
    isNight ? "bg-[#292929] text-white" : "bg-white text-black",
  );
  const primary = cx(
    "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-sm disabled:opacity-50",
    isNight ? "bg-[#00BF6A] text-black" : "bg-[#031B08] text-white",
  );

  return (
    <div role="dialog" aria-modal="true" aria-label="Store manager PIN" className="absolute inset-0 z-50 flex items-center justify-center p-6 animate-fade-in">
      <div className="absolute inset-0 bg-black/35 backdrop-blur-[7px]" onClick={onClose} />

      {step === "pin" && (
        <div onClick={(e) => e.stopPropagation()} className={cx(card, "pt-8 pb-7 gap-[18px]")}>
          <h3 className="text-[20px] font-medium leading-[25px] text-center">Enter store manager PIN</h3>
          <p className={cx("text-[14px] font-light text-center", isNight ? "text-[#A9A9A9]" : "text-[#6B7280]")}>
            {stopName} · the PIN is on the store manager's screen
          </p>

          <label className="relative flex items-center gap-3" aria-label="PIN">
            {[0, 1, 2, 3].map((idx) => (
              <span
                key={idx}
                aria-hidden="true"
                className={cx(
                  "w-[54px] h-[54px] rounded-[18px] flex items-center justify-center border",
                  isNight ? "bg-[#1f1f1f] border-[#444444]" : "bg-[#E7F3F2] border-[#B7F2ED]",
                )}
              >
                {pin.length > idx && <span className={cx("w-3 h-3 rounded-full", isNight ? "bg-white" : "bg-black")} />}
              </span>
            ))}
            <input
              ref={input}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 4))}
              onKeyDown={(e) => e.key === "Enter" && void verify()}
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={4}
              disabled={finished || !online}
              aria-label="Store manager PIN"
              className="absolute inset-0 opacity-0"
            />
          </label>

          {!online && (
            <p role="status" className="text-[14px] text-center">
              No signal: the PIN can be checked once you are back online. The delivery is already recorded.
            </p>
          )}
          {message && (
            <p role="alert" className={cx("text-[14px] text-center", isNight ? "text-[#FF8A8E]" : "text-[#C62828]")}>
              {message}
            </p>
          )}

          {!finished && online && (
            <button type="button" onClick={() => void verify()} disabled={pin.length !== 4 || checking} className={primary}>
              {checking ? "Checking…" : "Confirm"}
            </button>
          )}
          <button type="button" onClick={onClose} className="text-[16px] underline">
            {finished || !online ? "Continue" : "Skip"}
          </button>
        </div>
      )}

      {step === "confirmed" && (
        <div onClick={(e) => e.stopPropagation()} className={cx(card, "h-[266px] pt-[38px] pb-6 text-center")}>
          <div className="w-[56px] h-[56px] rounded-full bg-white shadow-[0px_12.7273px_50.9091px_rgba(0,0,0,0.09)] flex items-center justify-center shrink-0">
            <svg width="25" height="25" viewBox="0 0 24 24" fill="none" stroke="#09B824" strokeWidth="3.4" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="20 6 9 17 4 12" />
            </svg>
          </div>
          <h3 className="text-[22px] font-medium leading-[28px] mt-[18px]">Handover confirmed</h3>
          <p className="text-[15px] font-light leading-[19px] mt-[6px]">{stopName}</p>
          <p className={cx("text-[13px] font-light leading-[16px] mt-[6px]", isNight ? "text-[#A9A9A9]" : "text-[#6B7280]")}>
            The store manager's PIN matched
          </p>
        </div>
      )}
    </div>
  );
}
