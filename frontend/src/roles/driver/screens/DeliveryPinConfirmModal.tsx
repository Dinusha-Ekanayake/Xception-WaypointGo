"use client";

import { useState, useEffect } from "react";
import { cx } from "@shared/ui";

export type DeliveryPinConfirmModalProps = {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: () => void;
  isNight?: boolean;
  stopName?: string;
};

export default function DeliveryPinConfirmModal({
  isOpen,
  onClose,
  onSuccess,
  isNight = false,
  stopName = "Stop 01 • Peradeniya",
}: DeliveryPinConfirmModalProps): React.JSX.Element | null {
  const [step, setStep] = useState<"pin" | "confirmed">("pin");
  const [pin, setPin] = useState<string>("4821"); // Prefilled 4-digit PIN

  // Reset to PIN step whenever reopened
  useEffect(() => {
    if (isOpen) {
      setStep("pin");
    }
  }, [isOpen]);

  // When confirmed, wait 2.2 seconds and complete
  useEffect(() => {
    if (step === "confirmed") {
      const timer = setTimeout(() => {
        onSuccess();
      }, 2200);
      return () => clearTimeout(timer);
    }
  }, [step, onSuccess]);

  if (!isOpen) return null;

  const handleConfirmPin = () => {
    setStep("confirmed");
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      className="absolute inset-0 z-50 flex items-center justify-center p-6 animate-fade-in"
    >
      {/* Blur scrim: background: rgba(0, 0, 0, 0.35); backdrop-filter: blur(7px); */}
      <div
        className="absolute inset-0 bg-black/35 backdrop-blur-[7px] transition-opacity"
        onClick={() => {
          if (step === "confirmed") {
            onSuccess();
          } else {
            onClose();
          }
        }}
      />

      {/* STEP 1: Enter Store Manager PIN Popup */}
      {step === "pin" && (
        <div
          onClick={(e) => e.stopPropagation()}
          className={cx(
            "relative z-10 w-full max-w-[343px] rounded-[38px] pt-8 pb-7 px-6 flex flex-col items-center gap-[22px] shadow-[0px_5px_20px_rgba(0,0,0,0.09)] transition-colors select-none animate-fade-in",
            isNight ? "bg-[#292929] text-white" : "bg-white text-black"
          )}
        >
          {/* Title */}
          <h3
            className={cx(
              "text-[20px] font-medium leading-[25px] text-center",
              isNight ? "text-white" : "text-black"
            )}
          >
            Enter Store Manager PIN
          </h3>

          {/* 4 PIN Digit Dots / Boxes */}
          <div className="flex items-center gap-3">
            {[0, 1, 2, 3].map((idx) => {
              const isFilled = pin.length > idx;
              return (
                <div
                  key={idx}
                  className={cx(
                    "w-[54px] h-[54px] rounded-[18px] flex items-center justify-center transition-all border",
                    isNight
                      ? "bg-[#1f1f1f] border-[#444444]"
                      : "bg-[#E7F3F2] border-[#B7F2ED]"
                  )}
                >
                  {isFilled && (
                    <span
                      className={cx(
                        "w-3 h-3 rounded-full transition-transform scale-100",
                        isNight ? "bg-white" : "bg-black"
                      )}
                    />
                  )}
                </div>
              );
            })}
          </div>

          {/* Confirm Button */}
          <button
            type="button"
            onClick={handleConfirmPin}
            className={cx(
              "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
              isNight
                ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90"
                : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
            )}
          >
            Confirm
          </button>
        </div>
      )}

      {/* STEP 2: Delivery Confirmed Success Card (Figma: PIN popup: Delivery confirmed) */}
      {step === "confirmed" && (
        <div
          onClick={(e) => e.stopPropagation()}
          className={cx(
            "relative z-10 w-full max-w-[343px] h-[266px] rounded-[38px] pt-[38px] pb-6 px-6 flex flex-col items-center text-center shadow-[0px_5px_20px_rgba(0,0,0,0.09)] transition-colors select-none animate-fade-in",
            isNight ? "bg-[#292929] text-white" : "bg-white text-black"
          )}
        >
          {/* Success Check Badge (56px white circle, 0px 12.7273px 50.9091px rgba(0,0,0,0.09)) */}
          <div className="w-[56px] h-[56px] rounded-full bg-white shadow-[0px_12.7273px_50.9091px_rgba(0,0,0,0.09)] flex items-center justify-center shrink-0">
            <svg
              width="25"
              height="25"
              viewBox="0 0 24 24"
              fill="none"
              stroke="#09B824"
              strokeWidth="3.4"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <polyline points="20 6 9 17 4 12" />
            </svg>
          </div>

          {/* Delivery confirmed */}
          <h3
            className={cx(
              "text-[22px] font-medium leading-[28px] mt-[18px]",
              isNight ? "text-white" : "text-black"
            )}
          >
            Delivery confirmed
          </h3>

          {/* Stop Name */}
          <p
            className={cx(
              "text-[15px] font-light leading-[19px] mt-[6px]",
              isNight ? "text-white" : "text-black"
            )}
          >
            {stopName}
          </p>

          {/* Proof saved • syncing to dispatch */}
          <p
            className={cx(
              "text-[13px] font-light leading-[16px] mt-[6px]",
              isNight ? "text-[#A9A9A9]" : "text-[#6B7280]"
            )}
          >
            Proof saved • syncing to dispatch
          </p>
        </div>
      )}
    </div>
  );
}
