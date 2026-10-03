"use client";

import { useState, useRef, useEffect, useCallback } from "react";
import { cx } from "@shared/ui";

export type ReportProblemBottomSheetProps = {
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (reason: string) => void;
  isNight?: boolean;
  stopContext?: string;
};

const PROBLEM_REASONS = [
  "Running late",
  "Cannot unload here",
  "No one at the store",
  "Vehicle problem",
  "Goods damaged or short",
];

function SlideOptionRow({
  reason,
  isNight,
  onTrigger,
}: {
  reason: string;
  isNight: boolean;
  onTrigger: (reason: string) => void;
}): React.JSX.Element {
  const trackRef = useRef<HTMLDivElement>(null);
  const [sliderX, setSliderX] = useState<number>(0);
  const [isDragging, setIsDragging] = useState<boolean>(false);
  const [isSubmitted, setIsSubmitted] = useState<boolean>(false);
  const startXRef = useRef<number>(0);
  const currentDiffRef = useRef<number>(0);

  const getMaxSlide = useCallback(() => {
    if (!trackRef.current) return 220;
    // 48px circle + 6px left margin + 6px right margin = 60px
    return Math.max(80, trackRef.current.clientWidth - 48 - 12);
  }, []);

  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (isSubmitted) return;
    setIsDragging(true);
    startXRef.current = e.clientX - sliderX;
    currentDiffRef.current = sliderX;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  };

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (!isDragging || isSubmitted) return;
    const diff = e.clientX - startXRef.current;
    const maxSlide = getMaxSlide();
    const clamped = Math.max(0, Math.min(diff, maxSlide));
    currentDiffRef.current = clamped;
    setSliderX(clamped);
  };

  const handlePointerUp = () => {
    if (!isDragging || isSubmitted) return;
    setIsDragging(false);
    const maxSlide = getMaxSlide();
    if (currentDiffRef.current >= maxSlide * 0.55 || currentDiffRef.current > 120) {
      setIsSubmitted(true);
      setSliderX(maxSlide);
      onTrigger(reason);
    } else {
      setSliderX(0);
      currentDiffRef.current = 0;
    }
  };

  return (
    <div
      ref={trackRef}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerCancel={handlePointerUp}
      className={cx(
        "relative w-full h-[60px] rounded-[24px] flex items-center overflow-hidden touch-none select-none cursor-grab active:cursor-grabbing transition-colors",
        isNight
          ? "bg-[#121212] border border-white/[0.06]"
          : "bg-[#E7F3F2] border border-black/[0.03]"
      )}
    >
      {/* Background slide track highlight */}
      <div
        style={{
          width: `${sliderX + 54}px`,
          opacity: sliderX > 5 ? 1 : 0,
        }}
        className={cx(
          "absolute left-0 top-0 bottom-0 rounded-[24px] pointer-events-none transition-opacity duration-150",
          isNight ? "bg-[#00BF6A]/15" : "bg-[#031B08]/10"
        )}
      />

      {/* Draggable Circle with Right-Pointed Arrow */}
      <div
        style={{
          transform: `translateX(${sliderX}px)`,
          transition: isDragging ? "none" : "transform 0.28s cubic-bezier(0.25, 1, 0.5, 1)",
        }}
        className={cx(
          "absolute left-[6px] top-[6px] w-[48px] h-[48px] rounded-full flex items-center justify-center shadow-md shrink-0 z-10 transition-colors pointer-events-none",
          isNight
            ? "bg-[#00BF6A] text-black"
            : "bg-[#031B08] text-white"
        )}
        aria-label={`Slide to report ${reason}`}
      >
        {/* Right-pointed arrow icon inside circle */}
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.5"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <line x1="5" y1="12" x2="19" y2="12" />
          <polyline points="12 5 19 12 12 19" />
        </svg>
      </div>

      {/* Option Text (positioned after the circle) */}
      <span
        style={{
          opacity: Math.max(0.2, 1 - sliderX / 120),
        }}
        className={cx(
          "pl-[64px] pr-4 text-[17px] font-medium leading-[21px] pointer-events-none transition-opacity truncate",
          isNight ? "text-white" : "text-black"
        )}
      >
        {reason}
      </span>
    </div>
  );
}

export default function ReportProblemBottomSheet({
  isOpen,
  onClose,
  onSubmit,
  isNight = false,
  stopContext: _stopContext,
}: ReportProblemBottomSheetProps): React.JSX.Element | null {
  const [isRendered, setIsRendered] = useState(isOpen);
  const [isVisible, setIsVisible] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const [recordedSeconds, setRecordedSeconds] = useState(0);
  const timerRef = useRef<NodeJS.Timeout | null>(null);

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

  const handleSelectReason = (reason: string) => {
    setIsVisible(false);
    setTimeout(() => {
      onSubmit(reason);
      onClose();
    }, 280);
  };

  const handleToggleRecord = () => {
    if (!isRecording) {
      setIsRecording(true);
      setRecordedSeconds(1);
      timerRef.current = setInterval(() => {
        setRecordedSeconds((prev) => prev + 1);
      }, 1000);
    } else {
      if (timerRef.current) clearInterval(timerRef.current);
      setIsRecording(false);
      setIsVisible(false);
      setTimeout(() => {
        onSubmit(`Voice note recorded (${recordedSeconds}s)`);
        onClose();
      }, 300);
    }
  };

  if (!isRendered) return null;

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
          "absolute inset-0 bg-black/40 backdrop-blur-[6px] transition-opacity duration-300 ease-out",
          isVisible ? "opacity-100" : "opacity-0 pointer-events-none"
        )}
        onClick={handleClose}
      />

      {/* Sheet Container (Figma: height: 757px, border-radius: 40px 41px 0px 0px) */}
      <div
        onClick={(e) => e.stopPropagation()}
        className={cx(
          "relative z-10 w-full max-h-[92dvh] overflow-y-auto rounded-t-[40px] px-5 pt-5 pb-7 flex flex-col gap-4 font-go select-none shadow-2xl",
          "transition-transform duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] will-change-transform",
          isVisible ? "translate-y-0" : "translate-y-full",
          isNight ? "bg-[#292929] text-white" : "bg-white text-black"
        )}
      >
        {/* Drag Handle indicator */}
        <div className="w-12 h-1 rounded-full bg-neutral-400/40 mx-auto -mt-1 mb-1 shrink-0" />

        {/* Title Block */}
        <div className="flex flex-col gap-1.5 shrink-0">
          <h2
            className={cx(
              "text-[24px] font-medium leading-[30px]",
              isNight ? "text-white" : "text-black"
            )}
          >
            Report a problem
          </h2>
        </div>

        {/* Section label */}
        <span
          className={cx(
            "text-[13px] font-normal leading-[16px] shrink-0",
            isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
          )}
        >
          What is wrong? · slide circle right to send it
        </span>

        {/* Reason chips list (5 rows with draggable circle slider) */}
        <div className="flex flex-col gap-2.5 shrink-0">
          {PROBLEM_REASONS.map((reason) => (
            <SlideOptionRow
              key={reason}
              reason={reason}
              isNight={isNight}
              onTrigger={handleSelectReason}
            />
          ))}
        </div>

        {/* Voice Note Section */}
        <div className="flex flex-col gap-2 shrink-0">
          <span
            className={cx(
              "text-[13px] font-normal leading-[16px]",
              isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
            )}
          >
            None of the above? Record a voice note to explain
          </span>

          {/* Voice Detail Card (h: 100px, rounded: 60px, p: 18px) */}
          <div
            className={cx(
              "w-full h-[100px] rounded-[60px] p-[18px] flex items-center gap-3.5 transition-colors",
              isNight ? "bg-[#121212]" : "bg-[#E7F3F2]"
            )}
          >
            {/* Mic Button (w:64px, h:64px, rounded:32px) */}
            <button
              type="button"
              onClick={handleToggleRecord}
              className={cx(
                "w-[64px] h-[64px] rounded-[32px] flex items-center justify-center shrink-0 transition-transform active:scale-95 shadow-md",
                isRecording
                  ? "bg-red-500 text-white animate-pulse"
                  : isNight
                    ? "bg-[#00BF6A] text-black"
                    : "bg-[#031A0C] text-white"
              )}
              aria-label={isRecording ? "Stop recording" : "Record voice note"}
            >
              <svg width="22" height="26" viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z" />
                <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z" />
              </svg>
            </button>

            {/* Recorder Label */}
            <div className="flex flex-col gap-0.5">
              <span
                className={cx(
                  "text-[17px] font-medium leading-[21px]",
                  isNight ? "text-white" : "text-black"
                )}
              >
                {isRecording ? `Recording... (${recordedSeconds}s)` : "Tap to record"}
              </span>
              {isRecording && (
                <span className="text-[12px] text-red-500 font-medium">
                  Tap again to finish & send
                </span>
              )}
            </div>
          </div>
        </div>

        {/* Close Button (Figma: h: 64px, rounded: 22px) */}
        <div className="pt-2 shrink-0">
          <button
            type="button"
            onClick={handleClose}
            className={cx(
              "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
              isNight
                ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90"
                : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
            )}
          >
            Close
          </button>
        </div>
      </div>
    </div>
  );
}
