"use client";

import { useState, useEffect, useCallback } from "react";
import { cx } from "@shared/ui";

export type ScanState = "scanning" | "too-dark" | "wrong-code" | "success";

export type GetVehicleCameraAccessProps = {
  onBack?: () => void;
  onScanSuccess?: (vehicleId: string) => void;
  onEnterManualId?: () => void;
  isNight?: boolean;
  isActive?: boolean;
  hideHeader?: boolean;
};

// Realistic QR data dots for the GO - VEH043 sticker
const QR_DOTS: Array<[number, number]> = [
  [32, 4], [38, 4], [50, 4], [56, 4],
  [32, 10], [50, 10],
  [32, 16], [38, 16], [44, 16], [50, 16], [56, 16],
  [38, 22], [50, 22],
  [32, 28], [44, 28], [56, 28],
  [4, 32], [10, 32], [32, 32], [44, 32], [56, 32], [68, 32], [80, 32],
  [4, 38], [20, 38], [38, 38], [50, 38], [62, 38], [80, 38],
  [4, 44], [10, 44], [26, 44], [44, 44], [56, 44], [62, 44], [74, 44],
  [4, 50], [20, 50], [32, 50], [50, 50], [62, 50], [80, 50],
  [4, 56], [14, 56], [26, 56], [38, 56], [44, 56], [56, 56], [68, 56], [74, 56],
  [32, 62], [44, 62], [56, 62], [62, 62], [74, 62], [80, 62],
  [32, 68], [38, 68], [50, 68], [62, 68], [74, 68],
  [32, 74], [44, 74], [56, 74], [68, 74], [80, 74],
  [32, 80], [44, 80], [56, 80], [68, 80],
];

/**
 * QR Scanner screen with states from Figma:
 *   1. Scanning   : 283x283 viewfinder, 311x311 corner brackets, teal sweep line,
 *                   status pill ("Looking for a vehicle QR code..."), "Try another way" button.
 *   2. Too dark   : Low light overlay (rgba(0,0,0,0.62)), hidden scan line,
 *                   status pill ("Too dark to read. Tap the flash.", amber dot).
 *                   (Flash button intentionally omitted per requirements).
 *   3. Wrong code : 180x180 viewfinder, 208x208 brackets, dark tint + red '!' badge,
 *                   result card ("This looks like a Fuel Pass QR"), "Try another way" link,
 *                   "Scan again" button.
 *   4. Success    : Recognition lock-in, green brackets, green status pill,
 *                   calls onScanSuccess("VEH043").
 *
 * Dev keyboard shortcuts:
 *   o / O / KeyO -> simulate successful QR scan (or tap viewfinder)
 *   p / P / KeyP -> simulate bad QR scan (wrong code)
 *   d / D / t / T -> toggle "Too dark" state
 *   r / R / KeyR -> reset to scanning
 */
export default function GetVehicleCameraAccess({
  onBack,
  onScanSuccess,
  onEnterManualId,
  isNight = false,
  isActive = true,
  hideHeader = false,
}: GetVehicleCameraAccessProps): React.JSX.Element {
  const [scanState, setScanState] = useState<ScanState>("scanning");
  const [animKey, setAnimKey] = useState(0);
  const successTimerRef = useState<{ current: NodeJS.Timeout | null }>({ current: null })[0];

  // Whenever this screen becomes active, reset to scanning, trigger the bouncy QR entry animation, and clear input focus
  useEffect(() => {
    if (isActive) {
      setScanState("scanning");
      setAnimKey((prev) => prev + 1);
      if (
        document.activeElement?.tagName === "INPUT" ||
        document.activeElement?.tagName === "TEXTAREA"
      ) {
        (document.activeElement as HTMLElement)?.blur?.();
      }
    }
  }, [isActive]);

  const handleResetScan = useCallback(() => {
    setScanState("scanning");
    setAnimKey((prev) => prev + 1);
  }, []);

  const handleSuccess = useCallback(() => {
    setScanState("success");
    if (successTimerRef.current) {
      clearTimeout(successTimerRef.current);
    }
    successTimerRef.current = setTimeout(() => {
      onScanSuccess?.("VEH043");
    }, 750);
  }, [onScanSuccess, successTimerRef]);

  useEffect(() => {
    return () => {
      if (successTimerRef.current) {
        clearTimeout(successTimerRef.current);
      }
    };
  }, [successTimerRef]);

  useEffect(() => {
    if (!isActive) return;

    const handleKey = (e: KeyboardEvent) => {
      // Ignore if user is currently typing in an input or textarea
      if (
        document.activeElement?.tagName === "INPUT" ||
        document.activeElement?.tagName === "TEXTAREA"
      ) {
        return;
      }

      const key = e.key.toLowerCase();
      const code = e.code;

      if (key === "o" || code === "KeyO") {
        e.preventDefault();
        handleSuccess();
      } else if (key === "p" || code === "KeyP") {
        e.preventDefault();
        setScanState("wrong-code");
      } else if (key === "d" || code === "KeyD" || key === "t" || code === "KeyT") {
        e.preventDefault();
        setScanState((prev) => (prev === "too-dark" ? "scanning" : "too-dark"));
      } else if (key === "r" || code === "KeyR") {
        e.preventDefault();
        handleResetScan();
      }
    };

    window.addEventListener("keydown", handleKey);
    return () => window.removeEventListener("keydown", handleKey);
  }, [handleSuccess, isActive]);

  const isWrongCode = scanState === "wrong-code";
  const isTooDark = scanState === "too-dark";
  const isSuccess = scanState === "success";

  return (
    <>
      {/* Scanner with 4 corners bouncy zoom animation */}
      <style>{`
        @keyframes waypointScannerBounceZoom {
          0% {
            opacity: 0;
            transform: scale(0.25);
          }
          45% {
            opacity: 1;
            transform: scale(1.08);
          }
          65% {
            transform: scale(0.96);
          }
          82% {
            transform: scale(1.018);
          }
          100% {
            opacity: 1;
            transform: scale(1);
          }
        }

        .waypoint-scanner-bounce {
          transform-origin: center center;
          animation: waypointScannerBounceZoom 0.5s cubic-bezier(0.22, 1, 0.36, 1) both;
          will-change: transform, opacity;
        }

        @keyframes waypointScanSweep {
          0%, 100% { top: 8%; opacity: 0.9; }
          50%      { top: 78%; opacity: 1; }
        }

        .waypoint-scan-line {
          position: absolute;
          left: 20px;
          right: 20px;
          height: 3px;
          border-radius: 2px;
          background: linear-gradient(90deg, rgba(63, 230, 212, 0) 0%, #3FE6D4 50%, rgba(63, 230, 212, 0) 100%);
          filter: drop-shadow(0px 0px 12px rgba(63, 230, 212, 0.8));
          animation: waypointScanSweep 2.4s ease-in-out infinite;
          pointer-events: none;
        }

        @media (prefers-reduced-motion: reduce) {
          .waypoint-scanner-bounce {
            animation: none !important;
            opacity: 1 !important;
            transform: scale(1) !important;
          }
        }
      `}</style>

      <div
        className={cx(
          "relative mx-auto flex h-full max-h-full w-full flex-col font-go select-none transition-colors overflow-hidden",
          isNight ? "bg-[#161616] text-white" : "bg-[#E7F3F2] text-black"
        )}
      >
        {/* ---- Top Header (Back link + Step label) --------------------- */}
        <div className={cx(hideHeader ? "px-[43px] shrink-0" : "pt-[27px] px-[43px] shrink-0")}>
          {!hideHeader && (
            <div className="flex items-center justify-between">
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
              <span
                className={cx(
                  "text-[15px] font-light leading-[19px] text-right",
                  isNight ? "text-white" : "text-black"
                )}
              >
                Get vehicle
              </span>
            </div>
          )}

          {/* Title & Subtitle */}
          {/* Figma: Title top:143px (mt-[39px] below Back row) */}
          <div className="mt-[39px] flex flex-col gap-[2px]">
            <h1
              className={cx(
                "text-[40px] font-medium leading-[50px] tracking-tight transition-all duration-300",
                isNight ? "text-white" : "text-black"
              )}
            >
              {isWrongCode ? "Wrong code" : "Scan vehicle QR"}
            </h1>
            <p
              className={cx(
                "text-[15px] font-light leading-[19px] transition-all duration-300",
                isNight ? "text-white" : "text-black"
              )}
            >
              {isWrongCode
                ? "That QR code isn't a GO vehicle code."
                : "Point the camera at the GO sticker."}
            </p>
          </div>
        </div>

        {/* ---- Viewfinder Area ------------------------------------------ */}
        {/*
          Scanning:    311x311 corner brackets (left:41, right:41), 283x283 inner viewfinder
          Wrong-code:  208x208 corner brackets (centered),         180x180 inner viewfinder
        */}
        <div
          className={cx(
            "mx-auto shrink-0 transition-all duration-500",
            isWrongCode ? "mt-[28px]" : "mt-[64px]"
          )}
        >
          <div
            key={`scanner-box-${animKey}`}
            className={cx(
              "relative transition-all duration-500 waypoint-scanner-bounce",
              isWrongCode ? "w-[208px] h-[208px]" : "w-[311px] h-[311px]"
            )}
          >
            {/* Corner Brackets */}
            {/* Top-Left */}
            <div
              className={cx(
                "absolute top-0 left-0 border-t-[5px] border-l-[5px] rounded-tl-[37px] pointer-events-none transition-all duration-500",
                isWrongCode ? "w-[40px] h-[40px]" : "w-[70px] h-[70px]",
                isSuccess
                  ? "border-[#00BF6A] drop-shadow-[0_0_8px_rgba(0,191,106,0.6)]"
                  : "border-[#A9A9A9]"
              )}
            />
            {/* Top-Right */}
            <div
              className={cx(
                "absolute top-0 right-0 border-t-[5px] border-r-[5px] rounded-tr-[37px] pointer-events-none transition-all duration-500",
                isWrongCode ? "w-[40px] h-[40px]" : "w-[70px] h-[70px]",
                isSuccess
                  ? "border-[#00BF6A] drop-shadow-[0_0_8px_rgba(0,191,106,0.6)]"
                  : "border-[#A9A9A9]"
              )}
            />
            {/* Bottom-Left */}
            <div
              className={cx(
                "absolute bottom-0 left-0 border-b-[5px] border-l-[5px] rounded-bl-[37px] pointer-events-none transition-all duration-500",
                isWrongCode ? "w-[40px] h-[40px]" : "w-[70px] h-[70px]",
                isSuccess
                  ? "border-[#00BF6A] drop-shadow-[0_0_8px_rgba(0,191,106,0.6)]"
                  : "border-[#A9A9A9]"
              )}
            />
            {/* Bottom-Right */}
            <div
              className={cx(
                "absolute bottom-0 right-0 border-b-[5px] border-r-[5px] rounded-br-[37px] pointer-events-none transition-all duration-500",
                isWrongCode ? "w-[40px] h-[40px]" : "w-[70px] h-[70px]",
                isSuccess
                  ? "border-[#00BF6A] drop-shadow-[0_0_8px_rgba(0,191,106,0.6)]"
                  : "border-[#A9A9A9]"
              )}
            />

            {/* Viewfinder Inner Box (14px inset from brackets) */}
            <div
              role="button"
              tabIndex={0}
              title="Click or tap to scan QR code (or press 'o' on keyboard)"
              onClick={handleSuccess}
              onKeyDown={(e) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  handleSuccess();
                }
              }}
              className="absolute bg-[#1E2928] overflow-hidden transition-all duration-500 cursor-pointer"
              style={{
                inset: "14px",
                borderRadius: isWrongCode ? "22px" : "26px",
              }}
            >
              {/* ---- SCANNING / TOO DARK / SUCCESS state ---- */}
              {!isWrongCode && (
                <>
                  {/* Simulated dashboard scene (Figma: -4deg angle) */}
                  <div
                    className="absolute bg-black/35 rounded-[40px] transition-opacity duration-300"
                    style={{
                      left: "-20px",
                      right: "-25px",
                      top: "62%",
                      bottom: "-12%",
                      transform: "rotate(-4deg)",
                    }}
                  />

                  {/* QR sticker card (Figma: 7deg angle, left-of-center) */}
                  <div
                    className={cx(
                      "absolute bg-white/[0.92] rounded-[12px] shadow-md transition-all duration-300",
                      isSuccess && "ring-2 ring-[#00BF6A] shadow-[0_0_20px_rgba(0,191,106,0.5)]"
                    )}
                    style={{
                      width: "48%",
                      height: "56%",
                      top: "18%",
                      left: "18%",
                      transform: "rotate(7deg)",
                    }}
                  >
                    <div className="flex flex-col items-center h-full p-[6px]">
                      <svg viewBox="0 0 90 90" className="w-full flex-1 min-h-0" fill="none">
                        {/* TL finder */}
                        <rect x="4" y="4" width="24" height="24" rx="2" fill="#0B1413" />
                        <rect x="7" y="7" width="18" height="18" rx="1" fill="white" />
                        <rect x="10" y="10" width="12" height="12" rx="1" fill="#0B1413" />
                        {/* TR finder */}
                        <rect x="62" y="4" width="24" height="24" rx="2" fill="#0B1413" />
                        <rect x="65" y="7" width="18" height="18" rx="1" fill="white" />
                        <rect x="68" y="10" width="12" height="12" rx="1" fill="#0B1413" />
                        {/* BL finder */}
                        <rect x="4" y="62" width="24" height="24" rx="2" fill="#0B1413" />
                        <rect x="7" y="65" width="18" height="18" rx="1" fill="white" />
                        <rect x="10" y="68" width="12" height="12" rx="1" fill="#0B1413" />
                        {/* BR alignment */}
                        <rect x="62" y="62" width="14" height="14" rx="1" fill="#0B1413" />
                        <rect x="64" y="64" width="10" height="10" rx="0.5" fill="white" />
                        <rect x="66" y="66" width="6" height="6" rx="0.5" fill="#0B1413" />
                        {/* QR data dots */}
                        {QR_DOTS.map(([x, y], i) => (
                          <rect key={i} x={x} y={y} width="4" height="4" fill="#0B1413" />
                        ))}
                      </svg>
                      <p className="text-[#0B1413] font-semibold text-[7px] leading-[10px] tracking-wider mt-[2px] shrink-0 whitespace-nowrap">
                        GO • VEH043
                      </p>
                    </div>
                  </div>

                  {/* Animated teal scan line (hidden in too-dark or success) */}
                  {!isTooDark && !isSuccess && (
                    <div className="waypoint-scan-line" />
                  )}

                  {/* Success highlight sweep */}
                  {isSuccess && (
                    <div
                      className="absolute inset-0 bg-[#00BF6A]/20 flex items-center justify-center animate-fade-in"
                    >
                      <div className="w-14 h-14 rounded-full bg-[#00BF6A] flex items-center justify-center shadow-lg">
                        <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
                          <path
                            d="M5 13L9 17L19 7"
                            stroke="#FFFFFF"
                            strokeWidth="3.2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          />
                        </svg>
                      </div>
                    </div>
                  )}

                  {/* Too dark low-light overlay (Figma: rgba(0,0,0,0.62)) */}
                  {isTooDark && (
                    <div className="absolute inset-0 bg-black/[0.62] transition-opacity duration-300 pointer-events-none" />
                  )}
                </>
              )}

              {/* ---- WRONG CODE state ---- */}
              {isWrongCode && (
                <>
                  {/* Centered unrotated QR sticker */}
                  <div
                    className="absolute bg-white/[0.92] rounded-[8.64px]"
                    style={{
                      width: "53%",
                      height: "60%",
                      top: "20%",
                      left: "23.5%",
                    }}
                  >
                    <div className="flex flex-col items-center h-full p-[4px]">
                      <svg viewBox="0 0 90 90" className="w-full flex-1 min-h-0" fill="none">
                        <rect x="4" y="4" width="24" height="24" rx="2" fill="#0B1413" />
                        <rect x="7" y="7" width="18" height="18" rx="1" fill="white" />
                        <rect x="10" y="10" width="12" height="12" rx="1" fill="#0B1413" />
                        <rect x="62" y="4" width="24" height="24" rx="2" fill="#0B1413" />
                        <rect x="65" y="7" width="18" height="18" rx="1" fill="white" />
                        <rect x="68" y="10" width="12" height="12" rx="1" fill="#0B1413" />
                        <rect x="4" y="62" width="24" height="24" rx="2" fill="#0B1413" />
                        <rect x="7" y="65" width="18" height="18" rx="1" fill="white" />
                        <rect x="10" y="68" width="12" height="12" rx="1" fill="#0B1413" />
                        <rect x="62" y="62" width="14" height="14" rx="1" fill="#0B1413" />
                        <rect x="64" y="64" width="10" height="10" rx="0.5" fill="white" />
                        <rect x="66" y="66" width="6" height="6" rx="0.5" fill="#0B1413" />
                        <rect x="32" y="4" width="4" height="4" fill="#0B1413" />
                        <rect x="44" y="4" width="4" height="4" fill="#0B1413" />
                        <rect x="38" y="38" width="4" height="4" fill="#0B1413" />
                        <rect x="50" y="38" width="4" height="4" fill="#0B1413" />
                        <rect x="32" y="50" width="4" height="4" fill="#0B1413" />
                        <rect x="80" y="32" width="4" height="4" fill="#0B1413" />
                        <rect x="80" y="44" width="4" height="4" fill="#0B1413" />
                      </svg>
                      <p className="text-[#0B1413] font-semibold text-[5.5px] leading-[8px] tracking-wider mt-[1px] shrink-0 whitespace-nowrap">
                        FUEL PASS
                      </p>
                    </div>
                  </div>

                  {/* Dark tint overlay: rgba(11,20,19,0.55) */}
                  <div className="absolute inset-0 bg-[#0B1413]/55" />

                  {/* Red exclamation mark circle: 64x64, bg #E5484D, radius 32px */}
                  <div
                    className="absolute rounded-full bg-[#E5484D] flex items-center justify-center shadow-lg"
                    style={{
                      width: "64px",
                      height: "64px",
                      top: "58px",
                      left: "calc(50% - 32px)",
                    }}
                  >
                    <svg width="7" height="28" viewBox="0 0 7 28" fill="none">
                      <rect x="0.5" y="0" width="6" height="17" rx="2" fill="white" />
                      <circle cx="3.5" cy="24.5" r="3.5" fill="white" />
                    </svg>
                  </div>
                </>
              )}
            </div>
          </div>
        </div>

        {/* ---- Middle Section: Status pill / Result card ---------------- */}
        {!isWrongCode ? (
          /* Status pill: Figma top:659px, left:65.5px, right:65.5px, h:38px */
          <div className="mt-[36px] mx-[65px] shrink-0">
            <div
              className={cx(
                "flex flex-row items-center rounded-[20px] h-[38px] px-4 gap-2 transition-colors",
                isNight ? "bg-[#232323]" : "bg-white"
              )}
              style={{ boxShadow: "0px 4px 14px rgba(0, 0, 0, 0.06)" }}
            >
              {/* Dot: 8x8 */}
              <div
                className={cx(
                  "w-2 h-2 rounded-full shrink-0 transition-colors",
                  isSuccess
                    ? "bg-[#00BF6A]"
                    : isTooDark
                      ? "bg-[#C98A06]"
                      : "bg-[#0E766D]"
                )}
              />
              {/* Text: 14px/500 */}
              <span
                className={cx(
                  "text-[14px] font-medium leading-[18px] truncate transition-colors",
                  isNight ? "text-white" : "text-black"
                )}
              >
                {isSuccess
                  ? "✓ Vehicle identified: VEH043"
                  : isTooDark
                    ? "Too dark to read. Tap the flash."
                    : "Looking for a vehicle QR code..."}
              </span>
            </div>
          </div>
        ) : (
          /* Wrong code Result card: Figma top:522px, left:25px, right:25px, h:107px, radius:25px */
          <div className="mt-[36px] mx-[25px] shrink-0">
            <div
              className={cx(
                "flex flex-col rounded-[25px] px-[22px] py-[20px] gap-2 transition-colors",
                isNight ? "bg-[#292929]" : "bg-white"
              )}
              style={{
                boxShadow: isNight ? "none" : "0px 5px 20px rgba(0, 0, 0, 0.09)",
              }}
            >
              <h2
                className={cx(
                  "text-[18px] font-semibold leading-[23px] tracking-tight",
                  isNight ? "text-white" : "text-black"
                )}
              >
                This looks like a Fuel Pass QR
              </h2>
              <p
                className={cx(
                  "text-[14px] font-light leading-[18px]",
                  isNight ? "text-[#9CA3AF]" : "text-[#6B7280]"
                )}
              >
                Scan the GO sticker on the dashboard, not the National Fuel Pass code.
              </p>
            </div>
          </div>
        )}

        {/* Flexible spacer */}
        <div className="flex-1" />

        {/* ---- Bottom Controls ----------------------------------------- */}
        {!isWrongCode ? (
          /*
            Scanning & Too-dark: single "Try another way" button
            Figma: left:49px, right:49px, bottom:48px, h:64px, radius:22px
            Light: bg-[#B7F2ED] text-black
            Dark:  bg-[#292929] text-white hover:bg-[#333333]
          */
          <div className="pb-[48px] px-[49px] shrink-0">
            <button
              type="button"
              id="scan-try-another-btn"
              onClick={onEnterManualId}
              className={cx(
                "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99]",
                isNight
                  ? "bg-[#292929] text-white hover:bg-[#333333]"
                  : "bg-[#B7F2ED] text-black hover:bg-[#a8eae4]"
              )}
            >
              Enter vehicle ID
            </button>
          </div>
        ) : (
          /*
            Wrong code:
            - "Enter vehicle ID" underline link at top:672px
            - "Scan again" button at bottom:48px, h:64px
          */
          <div className="shrink-0 flex flex-col items-center pb-[48px]" style={{ gap: "52px" }}>
            <button
              type="button"
              onClick={onEnterManualId}
              className={cx(
                "text-[16px] font-medium leading-[20px] underline underline-offset-2 transition-colors focus-visible:outline-none",
                isNight
                  ? "text-[#00BF6A] hover:text-[#00d878]"
                  : "text-[#0E766D] hover:text-[#0a5750]"
              )}
            >
              Enter vehicle ID
            </button>
            <div className="w-full px-[49px]">
              <button
                type="button"
                id="scan-again-btn"
                onClick={handleResetScan}
                className={cx(
                  "w-full h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99]",
                  isNight
                    ? "bg-[#00BF6A] text-black hover:bg-[#00d878]"
                    : "bg-[#031B08] text-white hover:bg-[#062613]"
                )}
              >
                Scan again
              </button>
            </div>
          </div>
        )}
      </div>
    </>
  );
}