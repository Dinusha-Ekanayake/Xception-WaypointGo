"use client";

import { useState, useEffect, useCallback } from "react";
import { cx } from "@shared/ui";
import { VoiceMessagePlayer } from "../ui.tsx";

export type RouteChangedBottomSheetProps = {
  isOpen: boolean;
  onClose: () => void;
  onViewRoute?: () => void;
  isNight?: boolean;
  sender?: string;
  time?: string;
};

export default function RouteChangedBottomSheet({
  isOpen,
  onClose,
  onViewRoute,
  isNight = false,
  sender = "Sent by dispatch",
  time = "Just now",
}: RouteChangedBottomSheetProps): React.JSX.Element | null {
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
      }, 250);
      return () => clearTimeout(timer);
    }
  }, [isOpen]);

  const handleClose = useCallback(() => {
    if (typeof window !== "undefined" && "speechSynthesis" in window) {
      window.speechSynthesis.cancel();
    }
    setIsVisible(false);
    setTimeout(() => {
      onClose();
    }, 250);
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

  // Opening the route does not wait for the sheet to slide away.
  const handleViewRoute = () => {
    setIsVisible(false);
    onClose();
    if (onViewRoute) {
      onViewRoute();
    }
  };

  return (
    <div
      role={isVisible ? "dialog" : undefined}
      aria-label={isVisible ? "Run sheet changed" : undefined}
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
          "absolute inset-0 bg-black/35 backdrop-blur-[6px] transition-opacity duration-[250ms] ease-out",
          isVisible ? "opacity-100" : "opacity-0 pointer-events-none"
        )}
        onClick={handleClose}
      />

      {/* Sheet (Figma: auto layout, p: 20px 20px 40px, gap: 12px, h: 318px, rounded: 40px 41px 0px 0px) */}
      <div
        onClick={(e) => e.stopPropagation()}
        className={cx(
          "relative z-10 w-full rounded-t-[40px] px-5 pt-5 pb-9 flex flex-col items-center gap-3 font-go select-none shadow-2xl",
          "transition-transform duration-[250ms] ease-[cubic-bezier(0.32,0.72,0,1)] will-change-transform",
          isVisible ? "translate-y-0" : "translate-y-full",
          isNight ? "bg-[#292929] text-white" : "bg-white text-black"
        )}
      >
        {/* Message Card (Figma: w: 353px, h: 158px, p: 18px, gap: 6px, rounded: 22px) */}
        <div
          className={cx(
            "w-full rounded-[22px] p-[18px] flex flex-col gap-1.5 transition-colors",
            isNight ? "bg-[#121212]" : "bg-[#E7F3F2]"
          )}
        >
          {/* Meta row: Dot + "Run sheet changed" + "Just now" */}
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
                Run sheet changed
              </span>
            </div>
            <span
              className={cx(
                "text-[13px] font-normal leading-[16px]",
                isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
              )}
            >
              {time}
            </span>
          </div>

          {/* Title: "Your route has been changed" */}
          <h3
            className={cx(
              "text-[24px] font-medium leading-[30px] tracking-tight mt-0.5",
              isNight ? "text-white" : "text-black"
            )}
          >
            Your route has been changed
          </h3>

          {/* Body */}
          <p
            className={cx(
              "text-[15px] font-normal leading-[21px]",
              isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
            )}
          >
            Stops were added or reordered. Check the new run sheet before you continue.
          </p>

          {/* From sender */}
          <span
            className={cx(
              "text-[13px] font-normal leading-[16px] mt-0.5",
              isNight ? "text-white" : "text-black"
            )}
          >
            {sender}
          </span>
          <VoiceMessagePlayer
            duration="0:07"
            text="Your route has been changed. Stops were added or reordered. Check the new run sheet before you continue."
            isNight={isNight}
            className="pt-2"
          />
        </div>

        {/* View route Button (Figma: w: 295px, h: 64px, rounded: 22px) */}
        <button
          type="button"
          onClick={handleViewRoute}
          className={cx(
            "w-full max-w-[295px] h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-sm mt-1",
            isNight
              ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90"
              : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
          )}
        >
          View run sheet
        </button>
      </div>
    </div>
  );
}
