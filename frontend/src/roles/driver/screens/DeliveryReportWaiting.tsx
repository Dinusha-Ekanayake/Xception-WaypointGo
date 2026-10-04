"use client";
import { cx } from "@shared/ui";
import type { ReceiptAnswerView } from "@shared/domain/types";
import type { RouteStop } from "../data/stopView.ts";
import type { HandoverPhase } from "../data/handover.ts";
import { SlideToConfirm } from "../ui.tsx";

export type DeliveryReportWaitingProps = {
  /** Whether this phone is in step with the server, in words. */
  syncLabel?: string;
  onBack: () => void;
  /** The run sheet's stops (issue #117). */
  stops: RouteStop[];
  phase: HandoverPhase;
  /** The store's answer, once it is in. */
  answer?: ReceiptAnswerView | null;
  /** False when this phone cannot ask whether the store has answered. */
  reachable?: boolean;
  busy?: boolean;
  /** The stop has no photo or signature yet. */
  proofOwed?: boolean;
  /** Why the driver moved on, in words, once they did. */
  leftBecause?: string | null;
  /** Records the handover: the goods are with the store. */
  onHandOver?: () => void;
  onAddProof?: () => void;
  /** Asks why, then moves on before the store answered. */
  onContinue?: () => void;
  onEnterPin?: () => void;
  /** The driver does not agree with the store's report. */
  onDisagree?: () => void;
  onNext?: () => void;
  /** Opens the problem report, which sends to dispatch or records the stop as not delivered. */
  onProblem?: () => void;
  isNight?: boolean;
  onToggleTheme?: () => void;
  stopIndex?: number;
};

export default function DeliveryReportWaiting({
  syncLabel = "Connecting",
  onBack,
  phase,
  answer = null,
  reachable = true,
  busy = false,
  proofOwed = false,
  leftBecause = null,
  onHandOver,
  onAddProof,
  onContinue,
  onEnterPin,
  onDisagree,
  onNext,
  onProblem,
  stops,
  isNight = false,
  onToggleTheme,
  stopIndex = 0,
}: DeliveryReportWaitingProps): React.JSX.Element {
  // The store's report replaces the waiting view as soon as it is in.
  const viewState = phase === "answered" || phase === "accepted" ? "report" : "waiting";

  const safeIndex = Math.min(Math.max(0, stopIndex), Math.max(0, stops.length - 1));
  const currentStop: RouteStop | undefined = stops[safeIndex];
  const lines = answer?.lines ?? [];
  const expectedUnits = answer ? lines.reduce((sum, line) => sum + line.expectedQuantity, 0) : (currentStop?.expectedUnits ?? 0);
  // A line the store left blank was received as expected.
  const receivedUnits = lines.reduce((sum, line) => sum + (line.receivedQuantity ?? line.expectedQuantity), 0);
  // The catalogue is reconstructed from order totals: a product is never shown as a real SKU.
  const deliveredItems = lines.map((line) => ({
    code: line.productId,
    category: "Inferred product",
    qty: line.receivedQuantity ?? line.expectedQuantity,
    expected: line.expectedQuantity,
  }));
  const handover = answer?.handover ?? null;
  const pinClosed = handover !== null && (handover.status === "LOCKED" || handover.status === "EXPIRED");
  const verdict =
    answer?.status === "CONFIRMED"
      ? "All received"
      : answer?.status === "PARTIAL"
        ? "Part received"
        : answer?.status === "DISPUTED"
          ? "Disputed by the store"
          : "The store's report";

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
              {/* 8-tick Circular Spinner, while the store is checking */}
              <div className={cx("w-[40px] h-[40px] relative waypoint-spinner", phase !== "waiting" && "hidden")}>
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
                  {phase === "arrived" ? "At the stop" : phase === "left" ? "You moved on" : "Waiting for store confirmation"}
                </h2>
                <p
                  className={cx(
                    "text-[14px] font-light leading-[18px]",
                    isNight ? "text-[#A9A9A9]" : "text-[#6B7280]"
                  )}
                >
                  {phase === "arrived"
                    ? "Unload the goods, then slide Hand over. The store checks what arrived and their report appears here."
                    : phase === "left"
                      ? `${leftBecause ?? "You moved on"}. The store can still answer, and dispatch sees why you left.`
                      : reachable
                        ? "The store is checking the load. Their report appears here as soon as they send it."
                        : "This phone cannot reach Waypoint, so the store's report cannot be shown yet. You can carry on and say why."}
                </p>
              </div>
            </div>

            {/* Bottom Action: Report problem button */}
            {/* Figma: left:49px, right:49px, bottom:34px, h:64px, radius:22px */}
            <div className="px-[49px] pb-[34px] shrink-0 flex flex-col gap-3">
              {phase === "arrived" && (
                <SlideToConfirm label="Hand over" doneLabel="Handed over" done={busy} isNight={isNight} onConfirm={() => onHandOver?.()} />
              )}
              {phase === "waiting" && proofOwed && (
                <button
                  type="button"
                  onClick={() => onAddProof?.()}
                  className={cx(
                    "w-full h-[56px] rounded-full text-[17px] font-medium flex items-center justify-center transition-all active:scale-[0.99]",
                    isNight ? "bg-[#3A3A3A] text-white" : "bg-[#B7F2ED] text-black"
                  )}
                >
                  Add photo or signature
                </button>
              )}
              {phase === "waiting" && (
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => onContinue?.()}
                  className={cx(
                    "w-full h-[64px] rounded-[22px] border text-[20px] font-medium leading-[25px] flex items-center justify-center bg-transparent transition-all active:scale-[0.99] disabled:opacity-60",
                    isNight ? "border-[#A9A9A9] text-white" : "border-[#6B6B6B] text-black"
                  )}
                >
                  Continue to next stop
                </button>
              )}
              {phase === "left" && (
                <button
                  type="button"
                  onClick={() => onNext?.()}
                  className={cx(
                    "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
                    isNight ? "bg-[#00BF6A] text-black" : "bg-[#031B08] text-white"
                  )}
                >
                  Next stop
                </button>
              )}
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
                onClick={onBack}
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
                  {verdict}
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
                    Received
                  </span>
                  <div className="flex items-baseline justify-center gap-1.5 mt-0.5">
                    <span
                      className={cx(
                        "text-[48px] font-medium leading-[60px]",
                        isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                      )}
                    >
                      {receivedUnits}
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
                    Received items
                  </span>
                  <span
                    className={cx(
                      "text-[15px] font-light leading-[19px]",
                      isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
                    )}
                  >
                    {receivedUnits} of {expectedUnits} units
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

                      {/* Quantity: what the store received, against what was sent when it differs */}
                      <span
                        className={cx(
                          "text-[15px] font-semibold leading-[19px]",
                          item.qty < item.expected ? "text-[#E5484D]" : isNight ? "text-[#FFFFFF]" : "text-[#000000]"
                        )}
                      >
                        {item.qty < item.expected ? `${item.qty} of ${item.expected}` : item.qty}
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

              {/* The store's own words, when they gave any */}
              {answer?.note && (
                <div
                  className={cx(
                    "w-full rounded-[25px] mt-[16px] px-[21px] py-[16px] transition-colors",
                    isNight ? "bg-[#292929]" : "bg-white"
                  )}
                >
                  <span className={cx("text-[13px] font-light leading-[16px] block", isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]")}>
                    The store's note
                  </span>
                  <p className={cx("mt-1 text-[15px] leading-[20px]", isNight ? "text-white" : "text-black")}>{answer.note}</p>
                </div>
              )}

              {/* Scroll Spacer to let the user scroll cleanly past the floating buttons & gradient */}
              <div className="h-[220px] shrink-0" />
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

            {/* Floating actions (Figma Component 5: h:64px, left:49px, right:49px, bottom:34px, radius:22px) */}
            <div className="absolute bottom-[34px] left-[49px] right-[49px] z-30 flex flex-col gap-3">
              {phase === "accepted" ? (
                <>
                  <p role="status" className={cx("text-center text-[15px] font-medium", isNight ? "text-[#00BF6A]" : "text-[#0E766D]")}>
                    Accepted with the store's PIN
                  </p>
                  <button
                    type="button"
                    onClick={() => onNext?.()}
                    className={cx(
                      "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-lg",
                      isNight ? "bg-[#00BF6A] text-black" : "bg-[#031B08] text-white"
                    )}
                  >
                    Next stop
                  </button>
                </>
              ) : (
                <>
                  {pinClosed ? (
                    <p role="status" className={cx("text-center text-[14px]", isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]")}>
                      {handover?.status === "LOCKED" ? "The PIN is locked after too many tries." : "The PIN has expired."} Ask the store for a new one, or carry on.
                    </p>
                  ) : (
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => onEnterPin?.()}
                      className={cx(
                        "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-lg disabled:opacity-60",
                        isNight ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90" : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
                      )}
                    >
                      Enter PIN to accept
                    </button>
                  )}
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => (pinClosed ? onContinue?.() : onDisagree?.())}
                    className={cx(
                      "w-full h-[56px] rounded-[22px] border text-[18px] font-medium flex items-center justify-center bg-transparent transition-all active:scale-[0.99] disabled:opacity-60",
                      isNight ? "border-[#A9A9A9] text-white" : "border-[#6B6B6B] text-black"
                    )}
                  >
                    {pinClosed ? "Continue to next stop" : "I disagree"}
                  </button>
                </>
              )}
            </div>
          </div>
        )}



      </div>
    </>
  );
}
