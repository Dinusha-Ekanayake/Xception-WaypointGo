"use client";

import { useState, useRef, useEffect, useCallback } from "react";
import { cx } from "@shared/ui";
import { ROUTE_STOPS, RouteStop } from "./routeData.ts";

export type DrivingModeScreenProps = {
  onExit: () => void;
  isNight?: boolean;
  stopIndex?: number;
  onToggleTheme?: () => void;
};

export default function DrivingModeScreen({
  onExit,
  isNight = false,
  stopIndex = 2, // Defaults to Kadugannawa as in Figma, or current stop
  onToggleTheme,
}: DrivingModeScreenProps): React.JSX.Element {
  const safeIndex = Math.min(Math.max(0, stopIndex), ROUTE_STOPS.length - 1);
  const activeStop: RouteStop = ROUTE_STOPS[safeIndex] ?? ROUTE_STOPS[2];

  // Slide to exit slider state
  const trackRef = useRef<HTMLDivElement>(null);
  const [sliderX, setSliderX] = useState<number>(0);
  const [isDragging, setIsDragging] = useState<boolean>(false);
  const [isUnlocked, setIsUnlocked] = useState<boolean>(false);

  // Maximum slide distance: track width minus knob width (56px) minus margins (12px)
  const getMaxSlide = useCallback(() => {
    if (!trackRef.current) return 260;
    return Math.max(100, trackRef.current.clientWidth - 56 - 12);
  }, []);

  const handlePointerDown = (e: React.PointerEvent) => {
    setIsDragging(true);
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!isDragging || isUnlocked) return;
    if (!trackRef.current) return;

    const trackRect = trackRef.current.getBoundingClientRect();
    const currentX = e.clientX - trackRect.left - 28; // Center knob
    const maxSlide = getMaxSlide();
    const clampedX = Math.max(0, Math.min(currentX, maxSlide));

    setSliderX(clampedX);

    // If dragged past 85% threshold, trigger unlock
    if (clampedX >= maxSlide * 0.85) {
      setIsUnlocked(true);
      setSliderX(maxSlide);
      setTimeout(() => {
        onExit();
      }, 250);
    }
  };

  const handlePointerUp = () => {
    if (!isDragging) return;
    setIsDragging(false);

    const maxSlide = getMaxSlide();
    if (sliderX < maxSlide * 0.85) {
      // Spring back to start
      setSliderX(0);
    }
  };


  return (
    <div
      className={cx(
        "relative mx-auto flex h-full max-h-full w-full flex-col font-go select-none transition-colors overflow-hidden justify-between",
        isNight ? "bg-[#000000] text-white" : "bg-[#FFFFFF] text-black"
      )}
    >
      {/* =================================================================== */}
      {/* TOP HEADER: Driving Mode indicator + Notification button          */}
      {/* =================================================================== */}
      <div className="w-full flex items-center justify-between px-6 pt-5 pb-3 shrink-0 z-20">
        {/* Driving • Controls Hidden */}
        <span
          className={cx(
            "text-[16px] font-medium leading-[20px] tracking-tight",
            isNight ? "text-white" : "text-black"
          )}
        >
          Driving • Controls Hidden
        </span>

        {/* Top Right: Notification Bell & Theme toggle */}
        <div className="flex items-center gap-2">
          {/* Bell Notifications Button */}
          <div className="relative">
            <div
              className={cx(
                "w-[43px] h-[43px] rounded-full flex items-center justify-center transition-all border shadow-[0_5px_20px_rgba(0,0,0,0.09)]",
                isNight
                  ? "bg-[#292929] border-[#383838] text-white hover:bg-[#333333]"
                  : "bg-white border-[#dfe7e6] text-black hover:bg-slate-50"
              )}
            >
              {/* Bell Outline Icon */}
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
                <path d="M13.73 21a2 2 0 0 1-3.46 0" />
              </svg>
            </div>

            {/* Notification Badge Count (Figma: 22px circle, bg #E5484D, count 3) */}
            <div
              className={cx(
                "absolute -top-1 -right-1 min-w-[20px] h-[20px] px-1 rounded-full bg-[#E5484D] flex items-center justify-center text-[12px] font-semibold text-white pointer-events-none",
                isNight ? "border-[2px] border-black" : "border-[2px] border-white"
              )}
            >
              3
            </div>
          </div>

          {onToggleTheme && (
            <button
              type="button"
              onClick={onToggleTheme}
              className={cx(
                "w-[43px] h-[43px] rounded-full flex items-center justify-center border shadow-[0_5px_20px_rgba(0,0,0,0.09)] active:scale-95 transition-all shrink-0",
                isNight
                  ? "bg-[#292929] border-[#383838] text-white hover:bg-[#333333]"
                  : "bg-white border-[#dfe7e6] text-black hover:bg-slate-50"
              )}
              title={isNight ? "Switch to day mode" : "Switch to night mode"}
              aria-label={isNight ? "Switch to day mode" : "Switch to night mode"}
            >
              {isNight ? (
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />
                </svg>
              ) : (
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="12" cy="12" r="4.5" />
                  <line x1="12" y1="2" x2="12" y2="4.5" />
                  <line x1="12" y1="19.5" x2="12" y2="22" />
                  <line x1="4.5" y1="12" x2="2" y2="12" />
                  <line x1="22" y1="12" x2="19.5" y2="12" />
                  <line x1="17.3" y1="6.7" x2="19" y2="5" />
                  <line x1="5" y1="19" x2="6.7" y2="17.3" />
                  <line x1="6.7" y1="6.7" x2="5" y2="5" />
                  <line x1="19" y1="19" x2="17.3" y2="17.3" />
                </svg>
              )}
            </button>
          )}
        </div>
      </div>

      {/* =================================================================== */}
      {/* CENTER CONTENT                                                      */}
      {/* =================================================================== */}
      <div className="flex-1 flex flex-col justify-between px-[33px] pt-[20px] pb-[16px]">
        {/* Component 1: Next Stop Card (Figma: height: 105px, radius: 25px) */}
        <div
          className={cx(
            "w-full h-[105px] rounded-[25px] px-[25px] py-[20px] flex items-center justify-between shrink-0 transition-colors",
            isNight
              ? "bg-black border border-[#525252]"
              : "bg-white shadow-[0px_5px_20px_7px_rgba(0,0,0,0.09)]"
          )}
        >
          {/* Left: Next + Stop number */}
          <div className="flex flex-col">
            <span
              className={cx(
                "text-[16px] font-medium leading-[20px]",
                isNight ? "text-white" : "text-black"
              )}
            >
              Next
            </span>
            <span
              className={cx(
                "text-[32px] font-medium leading-[40px] tracking-tight mt-0.5",
                isNight ? "text-white" : "text-black"
              )}
            >
              Stop {activeStop.stopNumber}
            </span>
          </div>

          {/* Right: Stop category / Fresh */}
          <span
            className={cx(
              "text-[32px] font-medium leading-[40px] tracking-tight text-right",
              isNight ? "text-white" : "text-black"
            )}
          >
            Fresh
          </span>
        </div>

        {/* Center: Destination Name & Giant ETA */}
        <div className="flex-1 flex flex-col items-center justify-center my-auto">
          {/* Destination Name (Figma: 48px, 300) */}
          <h1
            className={cx(
              "text-[48px] font-light leading-[60px] tracking-tight text-center",
              isNight ? "text-white" : "text-black"
            )}
          >
            {activeStop.name}
          </h1>

          {/* Component 2: Giant ETA Digits + Distance/Status */}
          {/* Figma: 76px, 500 digits + 16px, 500 status */}
          <div className="flex flex-col items-center mt-6">
            <span
              className={cx(
                "text-[76px] font-medium leading-[95px] tracking-tight text-center font-mono sm:font-go",
                isNight ? "text-white" : "text-black"
              )}
            >
              {activeStop.eta}
            </span>
            <span
              className={cx(
                "text-[16px] font-medium leading-[20px] tracking-tight text-center mt-1",
                isNight ? "text-white" : "text-black"
              )}
            >
              3.22 Km • {activeStop.windowStatus}
            </span>
          </div>
        </div>

        {/* =================================================================== */}
        {/* BOTTOM: "Slide to exit" Interactive Slider                          */}
        {/* =================================================================== */}
        {/* Figma: height:68px, radius:34px, bottom:49px */}
        <div
          ref={trackRef}
          onPointerDown={handlePointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={handlePointerUp}
          onPointerCancel={handlePointerUp}
          className={cx(
            "relative w-full h-[68px] rounded-[34px] flex items-center justify-center overflow-hidden touch-none select-none transition-colors",
            isNight
              ? "bg-[#1C1C1C] border border-[#525252]"
              : "bg-[#F0F0F0]"
          )}
        >
          {/* "Slide to exit" Center Label */}
          <span
            className={cx(
              "text-[18px] font-medium leading-[23px] pointer-events-none transition-opacity duration-200",
              isNight ? "text-white/50" : "text-black/50",
              sliderX > 40 && "opacity-20"
            )}
          >
            {isUnlocked ? "Unlocked" : "Slide to exit"}
          </span>

          {/* Draggable Knob (Figma: 56px x 56px, radius: 28px) */}
          <div
            style={{
              transform: `translateX(${sliderX}px)`,
              transition: isDragging ? "none" : "transform 0.25s cubic-bezier(0.25,1,0.5,1)",
            }}
            className={cx(
              "absolute left-[6px] top-[6px] w-[56px] h-[56px] rounded-full flex items-center justify-center shadow-md cursor-grab active:cursor-grabbing shrink-0 z-10",
              isNight ? "bg-white text-black" : "bg-black text-white"
            )}
          >
            {/* fa/angles-right icon */}
            <svg
              width="22"
              height="22"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="pointer-events-none"
            >
              <polyline points="13 17 18 12 13 7" />
              <polyline points="6 17 11 12 6 7" />
            </svg>
          </div>
        </div>
      </div>
    </div>
  );
}
