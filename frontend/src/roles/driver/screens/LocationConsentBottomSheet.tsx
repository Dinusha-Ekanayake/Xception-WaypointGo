"use client";

import { useState, useEffect, useCallback } from "react";
import { cx } from "@shared/ui";

export type LocationConsentBottomSheetProps = {
  isOpen: boolean;
  /** "Not now". Tapping outside the sheet or pressing Escape means the same. */
  onDecline: () => void;
  onAllow: () => void;
  isNight?: boolean;
};

/**
 * Asks to share the truck's position while the run is open, in the sign-out
 * sheet's shape: it rises from the bottom of the phone over a blurred scrim.
 * A question left unanswered would ask again, so leaving it is a "Not now";
 * "Turn on" brings the question back later.
 */
export default function LocationConsentBottomSheet({
  isOpen,
  onDecline,
  onAllow,
  isNight = false,
}: LocationConsentBottomSheetProps): React.JSX.Element | null {
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
    }
    setIsVisible(false);
    const timer = setTimeout(() => setIsRendered(false), 250);
    return () => clearTimeout(timer);
  }, [isOpen]);

  const decline = useCallback(() => {
    if (isOpen) onDecline();
  }, [isOpen, onDecline]);

  useEffect(() => {
    if (!isOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        decline();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [isOpen, decline]);

  if (!isRendered) return null;

  return (
    <div
      role={isVisible ? "dialog" : undefined}
      aria-label={isVisible ? "Share your location" : undefined}
      aria-modal={isVisible ? "true" : undefined}
      aria-hidden={!isVisible}
      data-state={isVisible ? "open" : "closed"}
      data-full-frame
      className={cx("absolute inset-0 z-50 flex flex-col justify-end overflow-hidden", !isVisible && "pointer-events-none")}
    >
      <div
        className={cx(
          "absolute inset-0 bg-black/35 backdrop-blur-[6px] transition-opacity duration-[250ms] ease-out",
          isVisible ? "opacity-100" : "opacity-0 pointer-events-none"
        )}
        onClick={decline}
      />

      <div
        onClick={(e) => e.stopPropagation()}
        className={cx(
          "relative z-10 flex w-full flex-col items-center gap-3 rounded-t-[40px] px-5 pb-9 pt-5 font-go shadow-2xl select-none",
          "transition-transform duration-[250ms] ease-[cubic-bezier(0.32,0.72,0,1)] will-change-transform",
          isVisible ? "translate-y-0" : "translate-y-full",
          isNight ? "bg-[#292929] text-white" : "bg-white text-black"
        )}
      >
        <div className={cx("flex w-full flex-col gap-1.5 rounded-[22px] p-[18px]", isNight ? "bg-[#121212]" : "bg-[#E7F3F2]")}>
          <div className="flex w-full items-center justify-between">
            <div className="flex items-center gap-1.5">
              <span className={cx("h-2 w-2 rounded-full", isNight ? "bg-[#00BF6A]" : "bg-[#0E766D]")} />
              <span className={cx("text-[13px] font-medium leading-[16px]", isNight ? "text-[#00BF6A]" : "text-[#0E766D]")}>Location</span>
            </div>
            <span className={cx("text-[13px] font-normal leading-[16px]", isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]")}>Now</span>
          </div>
          <h3 className={cx("mt-0.5 text-[24px] font-medium leading-[30px] tracking-tight", isNight ? "text-white" : "text-black")}>
            Share your location while the run is open?
          </h3>
          <p className={cx("text-[15px] font-normal leading-[21px]", isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]")}>
            So the dispatcher and the store can see where the truck is. Only while your run is open.
          </p>
        </div>

        <div className="mt-[20px] flex w-full flex-col items-center gap-3">
          <button
            type="button"
            onClick={onAllow}
            className={cx(
              "flex h-[64px] w-full max-w-[295px] items-center justify-center rounded-[22px] text-[20px] font-medium leading-[25px] shadow-sm transition-all active:scale-[0.99]",
              isNight ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90" : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
            )}
          >
            Share location
          </button>
          <button
            type="button"
            onClick={onDecline}
            className={cx(
              "flex h-[64px] w-full max-w-[295px] items-center justify-center rounded-[22px] border bg-transparent text-[20px] font-medium leading-[25px] transition-all active:scale-[0.99]",
              isNight ? "border-[#A9A9A9] text-white hover:bg-white/5" : "border-[#6B6B6B] text-black hover:bg-black/5"
            )}
          >
            Not now
          </button>
        </div>
      </div>
    </div>
  );
}
