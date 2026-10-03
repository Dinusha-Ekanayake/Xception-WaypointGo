"use client";

import { useState, useEffect, useCallback } from "react";
import { cx } from "@shared/ui";

export type SignOutConfirmBottomSheetProps = {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  isNight?: boolean;
};

export default function SignOutConfirmBottomSheet({
  isOpen,
  onClose,
  onConfirm,
  isNight = false,
}: SignOutConfirmBottomSheetProps): React.JSX.Element | null {
  const [isRendered, setIsRendered] = useState(isOpen);
  const [isVisible, setIsVisible] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setIsRendered(true);
      const raf = requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          setIsVisible(true);
        });
      });
      return () => cancelAnimationFrame(raf);
    } else {
      setIsVisible(false);
      const timer = setTimeout(() => {
        setIsRendered(false);
      }, 300);
      return () => clearTimeout(timer);
    }
  }, [isOpen]);

  const handleClose = useCallback(() => {
    setIsVisible(false);
    setTimeout(() => {
      onClose();
    }, 300);
  }, [onClose]);

  // Close on Escape key press
  useEffect(() => {
    if (!isOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        handleClose();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [isOpen, handleClose]);

  if (!isRendered) return null;

  const handleConfirmSignOut = () => {
    setIsVisible(false);
    setTimeout(() => {
      onClose();
      onConfirm();
    }, 300);
  };

  return (
    <div
      role={isVisible ? "dialog" : undefined}
      aria-modal={isVisible ? "true" : undefined}
      aria-hidden={!isVisible}
      data-state={isVisible ? "open" : "closed"}
      className={cx(
        "absolute inset-0 z-50 flex flex-col justify-end overflow-hidden",
        !isVisible && "pointer-events-none"
      )}
    >
      {/* Scrim (Figma: background: rgba(0, 0, 0, 0.25); backdrop-filter: blur(6px);) */}
      <div
        className={cx(
          "absolute inset-0 bg-black/35 backdrop-blur-[6px] transition-opacity duration-300 ease-out",
          isVisible ? "opacity-100" : "opacity-0 pointer-events-none"
        )}
        onClick={handleClose}
      />

      {/* Sheet (Figma: auto layout, p: 20px 20px 40px, gap: 12px, h: 372px, rounded: 40px 41px 0px 0px) */}
      <div
        onClick={(e) => e.stopPropagation()}
        className={cx(
          "relative z-10 w-full rounded-t-[40px] px-5 pt-5 pb-9 flex flex-col items-center gap-3 font-go select-none shadow-2xl",
          "transition-transform duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] will-change-transform",
          isVisible ? "translate-y-0" : "translate-y-full",
          isNight ? "bg-[#292929] text-white" : "bg-white text-black"
        )}
      >
        {/* Message Card (Figma: w: 353px, h: 136px, p: 18px, gap: 6px, rounded: 22px) */}
        <div
          className={cx(
            "w-full rounded-[22px] p-[18px] flex flex-col gap-1.5 transition-colors",
            isNight ? "bg-[#121212]" : "bg-[#E7F3F2]"
          )}
        >
          {/* Meta row: Dot + "Sign out" + "Now" */}
          <div className="flex items-center justify-between w-full">
            <div className="flex items-center gap-1.5">
              <span
                className={cx(
                  "w-2 h-2 rounded-full",
                  isNight ? "bg-[#00BF6A]" : "bg-[#0E766D]"
                )}
              />
              <span
                className={cx(
                  "text-[13px] font-medium leading-[16px]",
                  isNight ? "text-[#00BF6A]" : "text-[#0E766D]"
                )}
              >
                Sign out
              </span>
            </div>
            <span
              className={cx(
                "text-[13px] font-normal leading-[16px]",
                isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
              )}
            >
              Now
            </span>
          </div>

          {/* Title: "Sign out of GO?" */}
          <h3
            className={cx(
              "text-[24px] font-medium leading-[30px] tracking-tight mt-0.5",
              isNight ? "text-white" : "text-black"
            )}
          >
            Sign out of GO?
          </h3>

          {/* Body */}
          <p
            className={cx(
              "text-[15px] font-normal leading-[21px]",
              isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
            )}
          >
            You’ll need your staff ID and password to sign in again. Your work is saved and synced.
          </p>
        </div>

        {/* Action Buttons: Sign out + Cancel */}
        <div className="w-full flex flex-col items-center gap-3 mt-[20px]">
          {/* Sign out button (Figma: w: 295px, h: 64px, rounded: 22px) */}
          <button
            type="button"
            onClick={onConfirm}
            className={cx(
              "w-full max-w-[295px] h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
              isNight
                ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90"
                : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
            )}
          >
            Sign out
          </button>

          {/* Cancel button (Figma: w: 295px, h: 64px, rounded: 22px, border: 1px solid) */}
          <button
            type="button"
            onClick={onClose}
            className={cx(
              "w-full max-w-[295px] h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] border bg-transparent",
              isNight
                ? "border-[#A9A9A9] text-white hover:bg-white/5"
                : "border-[#6B6B6B] text-black hover:bg-black/5"
            )}
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
