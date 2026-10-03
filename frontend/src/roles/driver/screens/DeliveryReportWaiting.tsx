"use client";
import { useState, useEffect } from "react";
import { cx } from "@shared/ui";
import type { RouteStop } from "../data/stopView.ts";

export type DeliveryReportWaitingProps = {
  /** Whether this phone is in step with the server, in words. */
  syncLabel?: string;
  onBack: () => void;
  /** The run sheet's stops (issue #117). */
  stops: RouteStop[];
  /** Opens the delivery form for this stop: counts, outcome, photo and signature. */
  onConfirm?: (stop: RouteStop) => void;
  /** Opens the problem report, which sends to dispatch or records the stop as not delivered. */
  onProblem?: () => void;
  isNight?: boolean;
  onToggleTheme?: () => void;
  stopIndex?: number;
};

export default function DeliveryReportWaiting({
  syncLabel = "Connecting",
  onBack,
  onConfirm,
  onProblem,
  stops,
  isNight = false,
  onToggleTheme,
  stopIndex = 0,
}: DeliveryReportWaitingProps): React.JSX.Element {
  const [viewState, setViewState] = useState<"waiting" | "report">("waiting");

  const safeIndex = Math.min(Math.max(0, stopIndex), Math.max(0, stops.length - 1));
  const currentStop: RouteStop | undefined = stops[safeIndex];
  const deliveredItems = currentStop?.deliveredItems ?? [];
  const expectedUnits = currentStop?.expectedUnits ?? 0;


  const handleConfirmDelivery = () => {
    if (currentStop) onConfirm?.(currentStop);
  };

  if (!currentStop) return <></>;

  return (
    <>
      {/* 8-tick spinner rotation animation & scrollbar hiding */}
      <style>{`
        @keyframes waypointSpinnerSpin {
          0%   { transform: rotate(0deg); }
          100% { transform: rotate(360deg); }
        }
        .waypoint-spinner {
          animation: waypointSpinnerSpin 1.2s steps(8, end) infinite;
        }
        .hide-scrollbar {
          -ms-overflow-style: none;
          scrollbar-width: none;
        }
        .hide-scrollbar::-webkit-scrollbar {
          display: none;
          width: 0;
          height: 0;
        }
      `}</style>

      <div
        className={cx(
          "relative mx-auto flex h-full max-h-full w-full flex-col font-go select-none transition-colors overflow-hidden short:h-auto short:min-h-full short:max-h-none short:overflow-visible",
          isNight ? "bg-[#161616] text-white" : "bg-[#E7F3F2] text-black"
        )}
      >
        {/* =================================================================== */}
        {/* VIEW 1: WAITING FOR STORE CONFIRMATION                             */}
        {/* =================================================================== */}
        {viewState === "waiting" && (
          <div className="relative flex flex-col h-full w-full justify-between animate-fade-in">
            {/* Top Header: Back + sync pill + Theme toggle */}
            <div className="pt-[20px] px-[25px] sm:px-[33px] flex items-center justify-between shrink-0 z-20">
              <button
                type="button"
                onClick={onBack}
                className={cx(
                  "flex items-center gap-[9px] text-[20px] font-medium leading-[25px] h-[30px] transition-opacity active:opacity-70",
                  isNight ? "text-white" : "text-black"
                )}
                aria-label="Go back"
              >
                <svg width="9" height="14" viewBox="0 0 9 14" fill="none">
                  <path
                    d="M7.75 1L1.25 7L7.75 13"
                    stroke="currentColor"
                    strokeWidth="2.2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
                <span>Back</span>
              </button>

              {/* Right controls */}
              <div className="flex items-center gap-4">
                {/* Sync Pill (Figma: w:125px, h:43px, radius:25px) */}
                <div
                  className={cx(
                    "w-[125px] h-[43px] rounded-[25px] flex items-center justify-center transition-colors",
                    isNight
                      ? "bg-[#292929] text-white shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
                      : "bg-white text-black shadow-[0px_5px_20px_7px_rgba(0,0,0,0.02)]"
                  )}
                >
                  <span className="text-[16px] font-medium leading-[20px] tracking-tight">
                    {syncLabel}
                  </span>
                </div>

                {/* Theme toggle */}
                <button
                  type="button"
                  onClick={onToggleTheme}
                  className={cx(
                    "size-[43px] rounded-[25px] flex items-center justify-center transition-all active:scale-95",
                    isNight
                      ? "bg-[#292929] text-white shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
                      : "bg-white text-black shadow-[0px_5px_20px_2px_rgba(0,0,0,0.09)]"
                  )}
                  aria-label="Toggle theme"
                >
                  {isNight ? (
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
                      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
                    </svg>
                  ) : (
                    <svg
                      width="22"
                      height="22"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <circle cx="12" cy="12" r="5" />
                      <line x1="12" y1="1" x2="12" y2="3" />
                      <line x1="12" y1="21" x2="12" y2="23" />
                      <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
                      <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
                      <line x1="1" y1="12" x2="3" y2="12" />
                      <line x1="21" y1="12" x2="23" y2="12" />
                      <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
                      <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
                    </svg>
                  )}
                </button>
              </div>
            </div>

            {/* Destination Title Section */}
            <div className="mt-[24px] px-[40px] shrink-0">
              <span
                className={cx(
                  "text-[15px] font-light leading-[19px]",
                  isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                )}
              >
                Stop {currentStop.stopNumber} · Delivery
              </span>
              <h1
                className={cx(
                  "text-[40px] font-medium leading-[50px] tracking-tight mt-1",
                  isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                )}
              >
                {currentStop.name}
              </h1>
            </div>

            {/* Center Status Card: Waiting for store confirmation */}
            {/* Figma: top:427px, left:40px, right:40px, h:147px, gap:14px */}
            <div
              className="px-[40px] flex flex-col items-center text-center gap-3.5 shrink-0 my-auto"
            >
              {/* 8-tick Circular Spinner */}
              <div className="w-[40px] h-[40px] relative waypoint-spinner">
                <svg viewBox="0 0 40 40" className="w-full h-full" fill="none">
                  {[
                    { rot: 0, op: 1.0 },
                    { rot: 45, op: 0.18 },
                    { rot: 90, op: 0.24 },
                    { rot: 135, op: 0.32 },
                    { rot: 180, op: 0.42 },
                    { rot: 225, op: 0.55 },
                    { rot: 270, op: 0.7 },
                    { rot: 315, op: 0.85 },
                  ].map(({ rot, op }, i) => (
                    <rect
                      key={i}
                      x="18.25"
                      y="3"
                      width="3.5"
                      height="8"
                      rx="1.75"
                      fill={isNight ? "#00BF6A" : "#0E766D"}
                      opacity={op}
                      transform={`rotate(${rot} 20 20)`}
                    />
                  ))}
                </svg>
              </div>

              <div className="flex flex-col gap-1 items-center max-w-[313px]">
                <h2
                  className={cx(
                    "text-[20px] font-medium leading-[25px]",
                    isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                  )}
                >
                  At the stop
                </h2>
                <p
                  className={cx(
                    "text-[14px] font-light leading-[18px]",
                    isNight ? "text-[#A9A9A9]" : "text-[#6B7280]"
                  )}
                >
                  Count the units with the store manager, then open the delivery report. The store confirms its receipt on its own screen afterwards.
                </p>
              </div>
            </div>

            {/* Bottom Action: Report problem button */}
            {/* Figma: left:49px, right:49px, bottom:34px, h:64px, radius:22px */}
            <div className="px-[49px] pb-[34px] shrink-0 flex flex-col gap-3">
              <button
                type="button"
                onClick={() => setViewState("report")}
                className={cx(
                  "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
                  isNight ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90" : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
                )}
              >
                Open delivery report
              </button>
              <button
                type="button"
                onClick={() => onProblem?.()}
                className={cx(
                  "w-full h-[64px] rounded-[22px] text-[18px] font-medium leading-[23px] flex items-center justify-center transition-all active:scale-[0.99]",
                  isNight
                    ? "bg-[#3A3A3A] text-white hover:bg-[#444444]"
                    : "bg-[#B7F2ED] text-black hover:bg-[#a8eae4]"
                )}
              >
                Report problem
              </button>
            </div>
          </div>
        )}

        {/* =================================================================== */}
        {/* VIEW 2: DELIVERY REPORT (CONFIRMATION READY)                       */}
        {/* =================================================================== */}
        {viewState === "report" && (
          <div className="relative flex flex-col h-full w-full animate-fade-in overflow-hidden short:h-auto short:min-h-full short:max-h-none short:overflow-visible">
            {/* Top Navigation Bar */}
            <div className="pt-[20px] px-[25px] sm:px-[33px] flex items-center justify-between shrink-0 z-20">
              <button
                type="button"
                onClick={() => setViewState("waiting")}
                className={cx(
                  "flex items-center gap-[9px] text-[20px] font-medium leading-[25px] h-[30px] transition-opacity active:opacity-70",
                  isNight ? "text-white" : "text-black"
                )}
                aria-label="Back to waiting"
              >
                <svg width="9" height="14" viewBox="0 0 9 14" fill="none">
                  <path
                    d="M7.75 1L1.25 7L7.75 13"
                    stroke="currentColor"
                    strokeWidth="2.2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
                <span>Back</span>
              </button>

              <div className="flex items-center gap-4">
                {/* Sync Pill (w:125px, h:43px, radius:25px) */}
                <div
                  className={cx(
                    "w-[125px] h-[43px] rounded-[25px] flex items-center justify-center transition-colors",
                    isNight
                      ? "bg-[#292929] text-white shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
                      : "bg-white text-black shadow-[0px_5px_20px_7px_rgba(0,0,0,0.02)]"
                  )}
                >
                  <span className="text-[16px] font-medium leading-[20px] tracking-tight">
                    {syncLabel}
                  </span>
                </div>

                {/* Theme toggle */}
                <button
                  type="button"
                  onClick={onToggleTheme}
                  className={cx(
                    "size-[43px] rounded-[25px] flex items-center justify-center transition-all active:scale-95",
                    isNight
                      ? "bg-[#292929] text-white shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
                      : "bg-white text-black shadow-[0px_5px_20px_2px_rgba(0,0,0,0.09)]"
                  )}
                  aria-label="Toggle theme"
                >
                  {isNight ? (
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
                      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
                    </svg>
                  ) : (
                    <svg
                      width="22"
                      height="22"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    >
                      <circle cx="12" cy="12" r="5" />
                      <line x1="12" y1="1" x2="12" y2="3" />
                      <line x1="12" y1="21" x2="12" y2="23" />
                      <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
                      <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
                      <line x1="1" y1="12" x2="3" y2="12" />
                      <line x1="21" y1="12" x2="23" y2="12" />
                      <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
                      <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
                    </svg>
                  )}
                </button>
              </div>
            </div>

            {/* Scrollable container for Delivery Report content without visible scrollbar */}
            <div className="flex-1 overflow-y-auto overscroll-contain px-[25px] pt-[20px] pb-4 short:flex-none short:overflow-visible scroll-smooth no-scrollbar hide-scrollbar [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
              {/* Destination Header */}
              <div className="px-[14px]">
                <span
                  className={cx(
                    "text-[15px] font-light leading-[19px] block",
                    isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                  )}
                >
                  Stop {currentStop.stopNumber} · Delivery
                </span>
                <h1
                  className={cx(
                    "text-[40px] font-medium leading-[50px] tracking-tight mt-1",
                    isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                  )}
                >
                  {currentStop.name}
                </h1>
                <span
                  className={cx(
                    "text-[15px] font-light leading-[19px] block mt-[16px]",
                    isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                  )}
                >
                  Delivery report
                </span>
              </div>

              {/* CARD 1: Expected vs Received (Figma: Rectangle 1, height:130px, radius:25px) */}
              <div
                className={cx(
                  "w-full h-[130px] rounded-[25px] mt-[16px] flex items-center justify-between px-6 transition-colors",
                  isNight
                    ? "bg-[#292929] shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
                    : "bg-white shadow-[0px_5px_20px_rgba(0,0,0,0.02)]"
                )}
              >
                {/* Left Half: Expected */}
                <div className="flex-1 flex flex-col items-center justify-center">
                  <span
                    className={cx(
                      "text-[23px] font-medium leading-[29px] text-center",
                      isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                    )}
                  >
                    Expected
                  </span>
                  <div className="flex items-baseline justify-center gap-1.5 mt-0.5">
                    <span
                      className={cx(
                        "text-[48px] font-medium leading-[60px]",
                        isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                      )}
                    >
                      {expectedUnits}
                    </span>
                    <span
                      className={cx(
                        "text-[15px] font-light leading-[19px]",
                        isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                      )}
                    >
                      Units
                    </span>
                  </div>
                </div>

                {/* Center Divider: Line 9 (w:1px, h:94px) */}
                <div
                  className={cx(
                    "w-[1px] h-[94px] shrink-0",
                    isNight ? "bg-white/20" : "bg-black"
                  )}
                />

                {/* Right Half: Received */}
                <div className="flex-1 flex flex-col items-center justify-center">
                  <span
                    className={cx(
                      "text-[23px] font-medium leading-[29px] text-center",
                      isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                    )}
                  >
                    Products
                  </span>
                  <div className="flex items-baseline justify-center gap-1.5 mt-0.5">
                    <span
                      className={cx(
                        "text-[48px] font-medium leading-[60px]",
                        isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                      )}
                    >
                      {deliveredItems.length}
                    </span>
                    <span
                      className={cx(
                        "text-[15px] font-light leading-[19px]",
                        isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                      )}
                    >
                      Lines
                    </span>
                  </div>
                </div>
              </div>

              {/* CARD 2: Delivered items list */}
              {/* Figma: Delivered items, radius:25px, padding: 20px 13px 12px */}
              <div
                className={cx(
                  "w-full rounded-[25px] mt-[16px] px-[16px] pt-[20px] pb-[16px] flex flex-col transition-colors",
                  isNight
                    ? "bg-[#292929] shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
                    : "bg-white shadow-[0px_5px_20px_rgba(0,0,0,0.02)]"
                )}
              >
                {/* Header row */}
                <div className="flex items-center justify-between px-[5px] pb-[14px]">
                  <span
                    className={cx(
                      "text-[20px] font-medium leading-[25px]",
                      isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                    )}
                  >
                    Delivered items
                  </span>
                  <span
                    className={cx(
                      "text-[15px] font-light leading-[19px]",
                      isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
                    )}
                  >
                    {expectedUnits} units
                  </span>
                </div>

                {/* Columns title row */}
                <div className="flex items-center justify-between px-[5px] pb-[8px]">
                  <span
                    className={cx(
                      "text-[15px] font-light leading-[19px]",
                      isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                    )}
                  >
                    Unit
                  </span>
                  <span
                    className={cx(
                      "text-[15px] font-light leading-[19px]",
                      isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                    )}
                  >
                    Qty
                  </span>
                </div>

                {/* Top Divider */}
                <div
                  className={cx(
                    "w-full h-[1px]",
                    isNight ? "bg-white/30" : "bg-[#B9C2C2]"
                  )}
                />

                {/* Item Rows */}
                {deliveredItems.map((item, idx) => (
                  <div key={item.code} className="flex flex-col">
                    <div className="flex items-center justify-between px-[5px] py-[10px]">
                      {/* Item unit identifier & name */}
                      <div className="flex flex-col gap-[2px]">
                        <span
                          className={cx(
                            "text-[15px] font-semibold leading-[19px]",
                            isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                          )}
                        >
                          {item.code}
                        </span>
                        <span
                          className={cx(
                            "text-[13px] font-light leading-[16px]",
                            isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
                          )}
                        >
                          {item.category}
                        </span>
                      </div>

                      {/* Quantity */}
                      <span
                        className={cx(
                          "text-[15px] font-semibold leading-[19px]",
                          isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                        )}
                      >
                        {item.qty}
                      </span>
                    </div>

                    {/* Divider between items */}
                    {idx < deliveredItems.length - 1 && (
                      <div
                        className={cx(
                          "w-full h-[1px]",
                          isNight ? "bg-white/30" : "bg-[#B9C2C2]"
                        )}
                      />
                    )}
                  </div>
                ))}
              </div>

              {/* Scroll Spacer to let the user scroll cleanly past the floating bottom button & gradient */}
              <div className="h-[170px] shrink-0" />
            </div>

            {/* Bottom Fade Gradient (height: 160px) */}
            <div
              className={cx(
                "pointer-events-none absolute bottom-0 left-0 right-0 h-[160px] z-20 transition-colors",
                isNight
                  ? "bg-gradient-to-b from-transparent via-[#161616]/80 to-[#161616]"
                  : "bg-gradient-to-b from-transparent via-[#E7F3F2]/80 to-[#E7F3F2]"
              )}
            />

            {/* Floating Confirm Button (Figma Component 5: h:64px, left:49px, right:49px, bottom:34px, radius:22px) */}
            <div className="absolute bottom-[34px] left-[49px] right-[49px] z-30">
              <button
                type="button"
                onClick={handleConfirmDelivery}
                className={cx(
                  "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-lg",
                  isNight
                    ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90"
                    : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
                )}
              >
                Record delivery
              </button>
            </div>
          </div>
        )}



      </div>
    </>
  );
}
