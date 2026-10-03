"use client";

import { useState, useEffect, useRef } from "react";
import { cx } from "@shared/ui";
import ReportProblemBottomSheet from "./ReportProblemBottomSheet.tsx";
import CallOptionsBottomSheet from "./CallOptionsBottomSheet.tsx";
import { ROUTE_STOPS, RouteStop } from "./routeData.ts";

export type RouteNextStopProps = {
  onBack: () => void;
  onOpenMap?: () => void;
  onArrived?: () => void;
  isNight?: boolean;
  onToggleTheme?: () => void;
  stopIndex?: number;
  onSelectStop?: (index: number) => void;
  hideHeader?: boolean;
};

/**
 * Apple UIScrollView rubber-band resistance formula:
 * f(x) = (x * dimension * c) / (dimension + c * x)
 * where c is Apple's standard rubber band coefficient (0.55).
 */
function appleRubberBand(offset: number, dimension = 600, coefficient = 0.55): number {
  const abs = Math.abs(offset);
  const damped = (abs * dimension * coefficient) / (dimension + coefficient * abs);
  return offset < 0 ? -damped : damped;
}

function useRubberBandScroll() {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const [pullY, setPullY] = useState(0);
  const [isPulling, setIsPulling] = useState(false);
  const [topFadeSize, setTopFadeSize] = useState(0);

  // Gesture tracking
  const overshootStartY = useRef<number | null>(null);
  const isMouseDown = useRef(false);
  const rawWheelOvershoot = useRef(0);
  const wheelReleaseTimer = useRef<NodeJS.Timeout | null>(null);

  const updateMask = (el: HTMLElement) => {
    const FADE_DISTANCE = 40;
    const top = Math.min(FADE_DISTANCE, Math.max(0, el.scrollTop));
    setTopFadeSize((prev) => (prev !== top ? top : prev));
  };

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    updateMask(el);

    const ro = new ResizeObserver(() => {
      updateMask(el);
    });
    ro.observe(el);
    return () => {
      ro.disconnect();
      if (wheelReleaseTimer.current) clearTimeout(wheelReleaseTimer.current);
    };
  }, []);

  const handleScroll = (e: React.UIEvent<HTMLDivElement>) => {
    updateMask(e.currentTarget);
  };

  // 1. Touch Gesture Handling
  const handleTouchStart = () => {
    overshootStartY.current = null;
    setIsPulling(false);
  };

  const handleTouchMove = (e: React.TouchEvent<HTMLDivElement>) => {
    const el = scrollRef.current;
    if (!el || !e.touches[0]) return;
    const currentY = e.touches[0].clientY;
    const maxScroll = Math.max(0, el.scrollHeight - el.clientHeight);

    if (el.scrollTop <= 0) {
      if (overshootStartY.current === null) {
        overshootStartY.current = currentY;
      }
      const rawOvershoot = currentY - overshootStartY.current;
      if (rawOvershoot > 0) {
        const damped = appleRubberBand(rawOvershoot, el.clientHeight, 0.55);
        setIsPulling(true);
        setPullY(damped);
        return;
      } else if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    } else if (el.scrollTop >= maxScroll - 1) {
      if (overshootStartY.current === null) {
        overshootStartY.current = currentY;
      }
      const rawOvershoot = currentY - overshootStartY.current;
      if (rawOvershoot < 0) {
        const damped = appleRubberBand(rawOvershoot, el.clientHeight, 0.55);
        setIsPulling(true);
        setPullY(damped);
        return;
      } else if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    } else {
      overshootStartY.current = null;
      if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    }
  };

  const handleTouchEnd = () => {
    overshootStartY.current = null;
    setIsPulling(false);
    setPullY(0);
  };

  // 2. Mouse Drag Handling
  const handleMouseDown = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    isMouseDown.current = true;
    overshootStartY.current = null;
    setIsPulling(false);
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!isMouseDown.current) return;
    const el = scrollRef.current;
    if (!el) return;
    const currentY = e.clientY;
    const maxScroll = Math.max(0, el.scrollHeight - el.clientHeight);

    if (el.scrollTop <= 0) {
      if (overshootStartY.current === null) {
        overshootStartY.current = currentY;
      }
      const rawOvershoot = currentY - overshootStartY.current;
      if (rawOvershoot > 0) {
        const damped = appleRubberBand(rawOvershoot, el.clientHeight, 0.55);
        setIsPulling(true);
        setPullY(damped);
        return;
      } else if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    } else if (el.scrollTop >= maxScroll - 1) {
      if (overshootStartY.current === null) {
        overshootStartY.current = currentY;
      }
      const rawOvershoot = currentY - overshootStartY.current;
      if (rawOvershoot < 0) {
        const damped = appleRubberBand(rawOvershoot, el.clientHeight, 0.55);
        setIsPulling(true);
        setPullY(damped);
        return;
      } else if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    } else {
      overshootStartY.current = null;
      if (pullY !== 0) {
        setPullY(0);
        setIsPulling(false);
      }
    }
  };

  const handleMouseUp = () => {
    if (!isMouseDown.current) return;
    isMouseDown.current = false;
    overshootStartY.current = null;
    setIsPulling(false);
    setPullY(0);
  };

  // 3. Mouse Wheel / Trackpad Handling
  const handleWheel = (e: React.WheelEvent<HTMLDivElement>) => {
    const el = scrollRef.current;
    if (!el) return;
    const maxScroll = Math.max(0, el.scrollHeight - el.clientHeight);

    if (wheelReleaseTimer.current) {
      clearTimeout(wheelReleaseTimer.current);
    }

    if (el.scrollTop <= 0 && e.deltaY < 0) {
      rawWheelOvershoot.current += -e.deltaY * 0.85;
      const damped = appleRubberBand(rawWheelOvershoot.current, el.clientHeight, 0.45);
      setIsPulling(true);
      setPullY(damped);

      wheelReleaseTimer.current = setTimeout(() => {
        setIsPulling(false);
        setPullY(0);
        rawWheelOvershoot.current = 0;
      }, 70);
    } else if (el.scrollTop >= maxScroll - 1 && e.deltaY > 0) {
      rawWheelOvershoot.current += -e.deltaY * 0.85;
      const damped = appleRubberBand(rawWheelOvershoot.current, el.clientHeight, 0.45);
      setIsPulling(true);
      setPullY(damped);

      wheelReleaseTimer.current = setTimeout(() => {
        setIsPulling(false);
        setPullY(0);
        rawWheelOvershoot.current = 0;
      }, 70);
    } else if (rawWheelOvershoot.current !== 0) {
      rawWheelOvershoot.current = 0;
      setIsPulling(false);
      setPullY(0);
    }
  };

  const topStop = topFadeSize > 0 ? `transparent 0px, black ${topFadeSize}px` : `black 0px`;
  const maskStyle: React.CSSProperties = {
    WebkitMaskImage: `linear-gradient(to bottom, ${topStop}, black 100%)`,
    maskImage: `linear-gradient(to bottom, ${topStop}, black 100%)`,
  };

  return {
    scrollRef,
    pullY,
    isPulling,
    maskStyle,
    handlers: {
      onScroll: handleScroll,
      onTouchStart: handleTouchStart,
      onTouchMove: handleTouchMove,
      onTouchEnd: handleTouchEnd,
      onTouchCancel: handleTouchEnd,
      onMouseDown: handleMouseDown,
      onMouseMove: handleMouseMove,
      onMouseUp: handleMouseUp,
      onMouseLeave: handleMouseUp,
      onWheel: handleWheel,
    },
  };
}

function ScrollRevealCard({
  scrollContainerRef,
  pullY = 0,
  children,
}: {
  scrollContainerRef: React.RefObject<HTMLDivElement | null>;
  pullY?: number;
  children: React.ReactNode;
}) {
  const cardRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const el = cardRef.current;
    const root = scrollContainerRef.current;
    if (!el || !root) return;

    let rafId: number | null = null;

    const update = () => {
      const rootRect = root.getBoundingClientRect();
      const elRect = el.getBoundingClientRect();
      const y = elRect.top - rootRect.top;
      const H = rootRect.height;

      // Top Exit Zone: only as card scrolls up past the top edge (y < 0)
      let topProgress = 1;
      if (y < 0) {
        topProgress = Math.max(0, Math.min(1, (y + 50) / 50));
      }

      // Bottom Exit/Entrance Zone: as item scrolls near the bottom edge
      let bottomProgress = 1;
      if (y > H - 80) {
        bottomProgress = Math.max(0, Math.min(1, (H - y) / 80));
      }

      if (topProgress < 1) {
        const scale = 0.90 + 0.10 * topProgress;
        const opacity = 0.50 + 0.50 * topProgress;
        el.style.opacity = opacity.toFixed(3);
        el.style.transform = `scale3d(${scale.toFixed(3)}, ${scale.toFixed(3)}, 1)`;
        el.style.transformOrigin = "center top";
      } else if (bottomProgress < 1) {
        const scale = 0.90 + 0.10 * bottomProgress;
        const opacity = 0.50 + 0.50 * bottomProgress;
        el.style.opacity = opacity.toFixed(3);
        el.style.transform = `scale3d(${scale.toFixed(3)}, ${scale.toFixed(3)}, 1)`;
        el.style.transformOrigin = "center bottom";
      } else {
        el.style.opacity = "1";
        el.style.transform = "scale3d(1, 1, 1)";
        el.style.transformOrigin = "center center";
      }
    };

    const onScroll = () => {
      if (rafId) cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(update);
    };

    update();
    root.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll, { passive: true });

    return () => {
      root.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onScroll);
      if (rafId) cancelAnimationFrame(rafId);
    };
  }, [scrollContainerRef, pullY]);

  return (
    <div
      ref={cardRef}
      className="w-full will-change-[transform,opacity]"
    >
      {children}
    </div>
  );
}

export default function RouteNextStop({
  onBack,
  onOpenMap,
  onArrived,
  isNight = false,
  onToggleTheme,
  stopIndex = 0,
  onSelectStop,
  hideHeader = false,
}: RouteNextStopProps): React.JSX.Element {
  const [hasArrived, setHasArrived] = useState(false);
  const [showProblemModal, setShowProblemModal] = useState(false);
  const [showCallPrompt, setShowCallPrompt] = useState(false);
  const [toastMessage, setToastMessage] = useState<string | null>(null);

  // Dynamic top fade on scroll & rubber band bounce (matching HomeNoVehicle message feed)
  const { scrollRef, pullY, isPulling, maskStyle, handlers } = useRubberBandScroll();

  // Active stop, completed stops, upcoming stops
  const safeIndex = Math.min(Math.max(0, stopIndex), ROUTE_STOPS.length - 1);
  const activeStop: RouteStop = ROUTE_STOPS[safeIndex] ?? ROUTE_STOPS[0];
  const completedStops = ROUTE_STOPS.slice(0, safeIndex);
  const upcomingStops = ROUTE_STOPS.slice(safeIndex + 1);

  // Reset arrived state when stop changes
  useEffect(() => {
    setHasArrived(false);
  }, [stopIndex]);

  // Dev keyboard shortcuts: 1, 2, 3 to switch stops directly
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) {
        return;
      }
      if (e.key === "1" && onSelectStop) {
        onSelectStop(0);
      } else if (e.key === "2" && onSelectStop) {
        onSelectStop(1);
      } else if (e.key === "3" && onSelectStop) {
        onSelectStop(2);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onSelectStop]);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 3500);
  };

  const handleOpenMap = () => {
    if (onOpenMap) {
      onOpenMap();
    } else {
      const query = encodeURIComponent(`${activeStop.name}, Sri Lanka`);
      window.open(`https://www.google.com/maps/search/?api=1&query=${query}`, "_blank");
    }
  };

  const handleCallDispatch = () => {
    setShowCallPrompt(true);
  };

  return (
    <div
      className={cx(
        "relative mx-auto flex h-full max-h-full w-full flex-col font-go select-none transition-colors overflow-hidden",
        isNight ? "bg-[#161616] text-white" : "bg-[#E7F3F2] text-black"
      )}
    >
      {/* ---- Top Header (Back + Sync pill + Theme toggle) ---------------- */}
      {hideHeader ? (
        <div className="w-full h-[74px] shrink-0 pointer-events-none" />
      ) : (
        <div className="pt-[20px] px-[25px] sm:px-[33px] flex items-center justify-between shrink-0 z-20">
          {/* Back button */}
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

          {/* Right header controls: Sync pill + Theme toggle */}
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
                Synced 05:31
              </span>
            </div>

            {/* Theme toggle button (Figma: w:43px, h:43px, radius:25px) */}
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
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
                </svg>
              ) : (
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
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
      )}

      {/* ---- Scrollable Area (Stops scroll) with dynamic top fade on scroll ----------------------------- */}
      <div style={maskStyle} className="relative flex-1 min-h-0 overflow-hidden mt-4">
        {/* Scroll content container with rubber band bounce & scroll gestures */}
        <div
          ref={scrollRef}
          {...handlers}
          className="h-full overflow-y-auto overscroll-contain px-[25px] pb-[110px] no-scrollbar [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
        >
          <div
            style={{
              transform: `translate3d(0, ${pullY}px, 0)`,
              transition: isPulling ? "none" : "transform 500ms cubic-bezier(0.18, 1.12, 0.32, 1.0)",
            }}
            className="flex flex-col will-change-transform"
          >
            {/* ---- Completed Stops Section (Above Primary Card) ------------ */}
            {completedStops.length > 0 && (
              <div className="flex flex-col gap-2.5 mb-3 px-2 animate-fade-in">
                {completedStops.map((stop) => (
                  <ScrollRevealCard key={stop.id} scrollContainerRef={scrollRef} pullY={pullY}>
                    <div
                      onClick={() => onSelectStop?.(stop.stopIndex)}
                      className="flex items-center justify-between text-[14px] font-light leading-[18px] cursor-pointer active:opacity-70 transition-opacity"
                    >
                      <div className="flex items-center gap-3">
                        <span className={cx(isNight ? "text-white" : "text-black")}>{stop.stopNumber}</span>
                        <span className={cx(isNight ? "text-white" : "text-black")}>{stop.name}</span>
                      </div>
                      {/* Green Checkmark Badge */}
                      <div className="w-[22px] h-[22px] rounded-full bg-[#00BF6A] flex items-center justify-center shrink-0 shadow-sm">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" strokeWidth="3.4" strokeLinecap="round" strokeLinejoin="round">
                          <polyline points="20 6 9 17 4 12" />
                        </svg>
                      </div>
                    </div>
                  </ScrollRevealCard>
                ))}
              </div>
            )}

          {/* ---- Primary Stop Card (Rectangle 1) - with same shadow as Call button --- */}
          <div
            className={cx(
              "w-full rounded-[25px] p-[24px] flex flex-col justify-between transition-colors",
              isNight
                ? "bg-[#292929] shadow-[0px_4px_16px_rgba(0,0,0,0.35)]"
                : "bg-white shadow-[0px_4px_16px_rgba(0,0,0,0.12)]"
            )}
          >
            {/* 1. Header: Next label + Dock tag */}
            <div className="flex items-center justify-between">
              <span
                className={cx(
                  "text-[15px] font-light leading-[19px]",
                  isNight ? "text-[#7C7583]" : "text-[#A9A9A9]"
                )}
              >
                Next • stop {activeStop.stopNumber} of {String(activeStop.totalStops || ROUTE_STOPS.length).padStart(2, "0")}
              </span>

              {/* Dock tag */}
              <div
                className={cx(
                  "h-[25.5px] px-[12px] flex items-center justify-center rounded-full transition-colors",
                  isNight
                    ? "border border-white text-white"
                    : "bg-[#D9D9D9] text-black"
                )}
              >
                <span className="text-[14px] font-light leading-none">{activeStop.dockTag}</span>
              </div>
            </div>

            {/* 2. Destination name & Address */}
            <div className="mt-2.5">
              <h1
                className={cx(
                  "text-[40px] font-medium leading-[50px] tracking-tight",
                  isNight ? "text-white" : "text-black"
                )}
              >
                {activeStop.name}
              </h1>
              <p
                className={cx(
                  "text-[15px] font-light leading-[19px] mt-0.5",
                  isNight ? "text-white" : "text-black"
                )}
              >
                {activeStop.address}
              </p>
            </div>

            {/* 3. Time Tiles (ETA + Window) */}
            <div className="grid grid-cols-2 gap-3 mt-4">
              {/* Tile 1: ETA (Filled tile: bg #E7F3F2 light / #121212 dark) */}
              <div
                className={cx(
                  "h-[105px] rounded-[24px] flex flex-col items-center justify-center p-2 transition-colors",
                  isNight ? "bg-[#121212]" : "bg-[#E7F3F2]"
                )}
              >
                <span className={cx("text-[12px] font-light leading-[15px]", isNight ? "text-white" : "text-black")}>
                  Expected
                </span>
                <span className={cx("text-[36px] font-semibold leading-[45px] tracking-tight", isNight ? "text-white" : "text-black")}>
                  {activeStop.eta}
                </span>
                <span className={cx("text-[12px] font-light leading-[15px]", isNight ? "text-white" : "text-black")}>
                  {activeStop.etaDistanceTime}
                </span>
              </div>

              {/* Tile 2: Window (Outlined tile: border #B7F2ED) */}
              <div
                className="h-[105px] rounded-[24px] border border-[#B7F2ED] flex flex-col items-center justify-center p-2"
              >
                <span className={cx("text-[12px] font-light leading-[15px]", isNight ? "text-white" : "text-black")}>
                  Window
                </span>
                <span className={cx("text-[36px] font-semibold leading-[45px] tracking-tight", isNight ? "text-white" : "text-black")}>
                  {activeStop.window}
                </span>
                <span className={cx("text-[12px] font-light leading-[15px]", isNight ? "text-white" : "text-black")}>
                  {activeStop.windowStatus}
                </span>
              </div>
            </div>

            {/* 4. Divider Line */}
            <div
              className={cx(
                "w-full h-0 border-t my-4",
                isNight ? "border-[#7C7583]" : "border-black/20"
              )}
            />

            {/* 5. Cargo Info */}
            <p className={cx("text-[15px] font-light leading-[19px]", isNight ? "text-white" : "text-black")}>
              {activeStop.cargoText}
            </p>

            {/* 6. Action Buttons */}
            {activeStop.mapButtonType === "wide" ? (
              /* Stop 03 Kadugannawa Layout: Full-width Open map button */
              <div className="flex flex-col gap-3.5 mt-3.5">
                <button
                  type="button"
                  onClick={handleOpenMap}
                  className={cx(
                    "w-full h-[52px] rounded-[22px] flex items-center justify-center gap-2.5 transition-all active:scale-[0.98]",
                    isNight
                      ? "bg-[#3A3A3A] text-white hover:bg-[#444444]"
                      : "bg-[#B7F2ED] text-black hover:bg-[#a8eae4]"
                  )}
                >
                  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                    <polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6" />
                    <line x1="8" y1="2" x2="8" y2="18" />
                    <line x1="16" y1="6" x2="16" y2="22" />
                  </svg>
                  <span className="text-[18px] font-medium leading-[23px]">Open map</span>
                </button>

                <button
                  type="button"
                  id="arrived-btn"
                  onClick={() => {
                    setHasArrived(true);
                    if (onArrived) {
                      onArrived();
                    }
                  }}
                  className={cx(
                    "w-full h-[64px] rounded-[22px] text-[20px] font-medium flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
                    hasArrived
                      ? "bg-[#0E766D] text-white"
                      : isNight
                        ? "bg-[#00BF6A] text-black hover:bg-[#00d878]"
                        : "bg-[#031B08] text-white hover:bg-[#062613]"
                  )}
                >
                  {hasArrived ? "Arrived • Unloading" : "I’ve arrived"}
                </button>
              </div>
            ) : (
              /* Stop 01 & 02 Layout: Map + Report problem split buttons, then I've arrived */
              <>
                <div className="flex items-center gap-2.5 mt-3.5">
                  {/* Map button */}
                  <button
                    type="button"
                    onClick={handleOpenMap}
                    className={cx(
                      "h-[52px] px-5 rounded-[22px] flex items-center justify-center gap-2.5 transition-all active:scale-[0.98]",
                      isNight
                        ? "bg-[#292929] border border-[#A9A9A9] text-white hover:bg-[#333333]"
                        : "bg-white border border-[#6B6B6B] text-black hover:bg-slate-50"
                    )}
                  >
                    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                      <polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6" />
                      <line x1="8" y1="2" x2="8" y2="18" />
                      <line x1="16" y1="6" x2="16" y2="22" />
                    </svg>
                    <span className="text-[18px] font-medium leading-[23px]">Map</span>
                  </button>

                  {/* Report Problem button */}
                  <button
                    type="button"
                    onClick={() => setShowProblemModal(true)}
                    className={cx(
                      "flex-1 h-[52px] px-4 rounded-[22px] flex items-center justify-center transition-all active:scale-[0.98]",
                      isNight
                        ? "bg-[#3A3A3A] text-white hover:bg-[#444444]"
                        : "bg-[#B7F2ED] text-black hover:bg-[#a8eae4]"
                    )}
                  >
                    <span className="text-[18px] font-medium leading-[23px] truncate">Report problem</span>
                  </button>
                </div>

                {/* Primary Action Button: "I've arrived" */}
                <button
                  type="button"
                  id="arrived-btn"
                  onClick={() => {
                    setHasArrived(true);
                    if (onArrived) {
                      onArrived();
                    }
                  }}
                  className={cx(
                    "w-full h-[64px] rounded-[22px] text-[20px] font-medium flex items-center justify-center mt-3.5 transition-all active:scale-[0.99] shadow-sm",
                    hasArrived
                      ? "bg-[#0E766D] text-white"
                      : isNight
                        ? "bg-[#00BF6A] text-black hover:bg-[#00d878]"
                        : "bg-[#031B08] text-white hover:bg-[#062613]"
                  )}
                >
                  {hasArrived ? "Arrived • Unloading" : "I’ve arrived"}
                </button>
              </>
            )}

            {/* 7. Delivery Instructions text */}
            <p className={cx("text-[15px] font-light leading-[19px] mt-3.5", isNight ? "text-white" : "text-black")}>
              {activeStop.instructions}
            </p>
          </div>

          {/* ---- Upcoming Stops Section (Below Primary Card) ---------- */}
          {upcomingStops.length > 0 && (
            <div className="mt-7 px-2 flex flex-col gap-3">
              {upcomingStops.map((stop, i) => (
                <ScrollRevealCard key={stop.id} scrollContainerRef={scrollRef} pullY={pullY}>
                  <div
                    onClick={() => onSelectStop?.(stop.stopIndex)}
                    className="flex flex-col gap-3 cursor-pointer active:opacity-70 transition-opacity"
                  >
                    <div className="flex items-center justify-between text-[14px] font-light leading-[18px]">
                      <div className="flex items-center gap-3">
                        <span className={cx(isNight ? "text-white" : "text-black")}>{stop.stopNumber}</span>
                        <span className={cx(isNight ? "text-white" : "text-black")}>{stop.name}</span>
                      </div>
                      <span className={cx(isNight ? "text-white" : "text-black")}>{stop.eta}</span>
                    </div>

                    {i < upcomingStops.length - 1 && (
                      <div
                        className={cx(
                          "w-full h-0 border-t",
                          isNight ? "border-white/20" : "border-black/20"
                        )}
                      />
                    )}
                  </div>
                </ScrollRevealCard>
              ))}
            </div>
          )}
          </div>
        </div>

        {/* Bottom gradient fade (height: 110px) */}
        <div
          className={cx(
            "absolute bottom-0 left-0 right-0 h-[110px] pointer-events-none z-10 transition-colors",
            isNight
              ? "bg-gradient-to-t from-[#161616] via-[#161616]/70 to-transparent"
              : "bg-gradient-to-t from-[#E7F3F2] via-[#E7F3F2]/70 to-transparent"
          )}
        />
      </div>

      {/* ---- Floating "Call" Button (Call Dispatch button) ------------- */}
      <div className="absolute bottom-[40px] left-0 right-0 flex justify-center z-30 pointer-events-none">
        <button
          type="button"
          onClick={handleCallDispatch}
          className={cx(
            "pointer-events-auto h-[52px] w-[124px] rounded-[54px] flex items-center justify-center gap-2.5 transition-all active:scale-95",
            isNight
              ? "bg-[#00BF6A] text-black shadow-[0px_4px_16px_rgba(0,191,106,0.3)] hover:bg-[#00d878]"
              : "bg-white text-black shadow-[0px_4px_16px_rgba(0,0,0,0.12)] hover:bg-slate-50"
          )}
          aria-label="Call Dispatch"
        >
          {/* Phone icon */}
          <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
            <path d="M6.62 10.79a15.053 15.053 0 006.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z" />
          </svg>
          <span className="text-[20px] font-medium leading-[25px]">Call</span>
        </button>
      </div>

      {/* ---- Problem Report Bottom Sheet ----------------------------- */}
      <ReportProblemBottomSheet
        isOpen={showProblemModal}
        onClose={() => setShowProblemModal(false)}
        onSubmit={(reason) => {
          showToast(`Reported to dispatch: "${reason}"`);
        }}
        isNight={isNight}
        stopContext={`Stop ${activeStop.stopNumber} · ${activeStop.name} · Expected ${activeStop.eta}`}
      />

      {/* ---- Call Options Bottom Sheet -------------------------------- */}
      <CallOptionsBottomSheet
        isOpen={showCallPrompt}
        onClose={() => setShowCallPrompt(false)}
        isNight={isNight}
        onCall={(contact) => {
          showToast(`Calling ${contact.name}...`);
        }}
      />

      {/* ---- Toast Notification ---------------------------------------- */}
      {toastMessage && (
        <div className="absolute top-[80px] left-6 right-6 z-50 flex justify-center pointer-events-none animate-fade-in">
          <div className="bg-[#031B08] text-white text-[13px] font-medium px-4 py-2.5 rounded-full shadow-lg">
            {toastMessage}
          </div>
        </div>
      )}
    </div>
  );
}

