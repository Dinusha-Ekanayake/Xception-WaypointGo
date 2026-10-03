"use client";

import { useState, useRef, useEffect } from "react";
import { cx } from "@shared/ui";
import { DriverHeader, VoiceMessagePlayer, type SupportedLang } from "../ui.tsx";
import type { Inbox } from "@shared/notifications/useInbox";
import { kindOf } from "@shared/notifications/inbox";
import { clock } from "@shared/wording";
import SignOutConfirmBottomSheet from "./SignOutConfirmBottomSheet.tsx";

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
  const [bottomFadeSize, setBottomFadeSize] = useState(0);

  // Gesture tracking
  const overshootStartY = useRef<number | null>(null);
  const isMouseDown = useRef(false);
  const rawWheelOvershoot = useRef(0);
  const wheelReleaseTimer = useRef<NodeJS.Timeout | null>(null);

  const updateMask = (el: HTMLElement) => {
    const FADE_DISTANCE = 40;
    // Top fade: strictly 0 at the very top (scrollTop === 0), scaling up to 40px as user scrolls down
    const top = Math.min(FADE_DISTANCE, Math.max(0, el.scrollTop));
    const remaining = el.scrollHeight - el.scrollTop - el.clientHeight;
    const bottom = Math.min(FADE_DISTANCE, Math.max(0, remaining));
    setTopFadeSize((prev) => (prev !== top ? top : prev));
    setBottomFadeSize((prev) => (prev !== bottom ? bottom : prev));
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

    // Pulling down at top
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
    }
    // Pulling up at bottom
    else if (el.scrollTop >= maxScroll - 1) {
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
    }
    // In middle of feed
    else {
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

  // 2. Mouse Drag Handling (for desktop testing & preview)
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

    // Wheeling up at top
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
    }
    // Wheeling down at bottom
    else if (el.scrollTop >= maxScroll - 1 && e.deltaY > 0) {
      rawWheelOvershoot.current += -e.deltaY * 0.85;
      const damped = appleRubberBand(rawWheelOvershoot.current, el.clientHeight, 0.45);
      setIsPulling(true);
      setPullY(damped);

      wheelReleaseTimer.current = setTimeout(() => {
        setIsPulling(false);
        setPullY(0);
        rawWheelOvershoot.current = 0;
      }, 70);
    }
    // User wheeled back into normal territory while in overscroll
    else if (rawWheelOvershoot.current !== 0) {
      rawWheelOvershoot.current = 0;
      setIsPulling(false);
      setPullY(0);
    }
  };

  const topStop = topFadeSize > 0 ? `transparent 0px, black ${topFadeSize}px` : `black 0px`;
  const bottomStop = bottomFadeSize > 0 ? `black calc(100% - ${bottomFadeSize}px), transparent 100%` : `black 100%`;
  const gradient = `linear-gradient(to bottom, ${topStop}, ${bottomStop})`;

  const maskStyle: React.CSSProperties = {
    WebkitMaskImage: gradient,
    maskImage: gradient,
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
      // When resting at the top (y >= 0), it is 100% visible with zero fade
      let topProgress = 1;
      if (y < 0) {
        topProgress = Math.max(0, Math.min(1, (y + 60) / 60));
      }

      // Bottom Exit/Entrance Zone: as card scrolls down past the bottom edge
      let bottomProgress = 1;
      if (y > H - 60) {
        bottomProgress = Math.max(0, Math.min(1, (H - y) / 60));
      }

      if (topProgress < 1) {
        // Exiting top: limit fade minimum to 0.5, keep scale down effect
        const scale = 0.90 + 0.10 * topProgress;
        const opacity = 0.50 + 0.50 * topProgress;
        el.style.opacity = opacity.toFixed(3);
        el.style.transform = `scale3d(${scale.toFixed(3)}, ${scale.toFixed(3)}, 1)`;
        el.style.transformOrigin = "center top";
      } else if (bottomProgress < 1) {
        // Exiting/entering bottom: exact same fade minimum 0.5 and scale down to 0.90
        const scale = 0.90 + 0.10 * bottomProgress;
        const opacity = 0.50 + 0.50 * bottomProgress;
        el.style.opacity = opacity.toFixed(3);
        el.style.transform = `scale3d(${scale.toFixed(3)}, ${scale.toFixed(3)}, 1)`;
        el.style.transformOrigin = "center bottom";
      } else {
        // Fully visible in center
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
      className="w-full snap-start snap-always will-change-[transform,opacity]"
    >
      {children}
    </div>
  );
}

export type DriverHomeProps = {
  driverName?: string;
  driverCode?: string;
  depotName?: string;
  assignedVehicle?: string | null;
  onGetVehicle?: () => void;
  onStartRun?: () => void;
  onStartTrip?: () => void;
  onSignOut?: () => void;
  isNight?: boolean;
  onToggleTheme?: () => void;
  hideHeader?: boolean;
  tripStatus?: "not-started" | "in-progress" | "completed";
  /** The driver's notifications (issue #118). Without it, Figma's sample feed shows. */
  inbox?: Inbox;
};

/**
 * Figma "Driver: Home" notification cards: the label and its colour by what
 * happened. A driver is routed plan published, plan revised and trip released
 * (R-NOT-08); anything else falls back to the shared label, coloured by urgency.
 */
function driverKind(eventType: string, isNight: boolean): { label: string; color: string } {
  const teal = isNight ? "#00BF6A" : "#0E766D";
  switch (eventType) {
    case "plan.published":
      return { label: "Run published", color: "#A9A9A9" };
    case "plan.revised":
      return { label: "Route updated", color: teal };
    case "trip.released":
      return { label: "Vehicle loaded", color: teal };
    default: {
      const kind = kindOf(eventType);
      const color = kind.tone === "urgent" ? "#E5484D" : kind.tone === "warning" ? "#B7791F" : kind.tone === "good" ? teal : isNight ? "#7FB3E6" : "#16324F";
      return { label: kind.tone === "urgent" ? "Action needed" : kind.label, color };
    }
  }
}

/** About how long the message takes to read aloud, for the player's time ("0:12"). */
function spokenLength(text: string): string {
  const seconds = Math.max(2, Math.round(text.split(/\s+/).length / 2.5));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

const FUEL_QR_ROWS: number[][] = [
  // 0:
  [0, 1, 2, 3, 4, 5, 6, 8, 10, 11, 14, 18, 19, 20, 21, 22, 23, 24],
  // 1:
  [0, 6, 12, 13, 16, 18, 24],
  // 2:
  [0, 2, 3, 4, 6, 9, 12, 18, 20, 21, 22, 24],
  // 3:
  [0, 2, 3, 4, 6, 11, 12, 14, 15, 16, 18, 20, 21, 22, 24],
  // 4:
  [0, 2, 3, 4, 6, 8, 9, 14, 15, 18, 20, 21, 22, 24],
  // 5:
  [0, 6, 9, 10, 12, 13, 14, 18, 24],
  // 6:
  [0, 1, 2, 3, 4, 5, 6, 9, 10, 11, 12, 13, 14, 18, 19, 20, 21, 22, 23, 24],
  // 7:
  [8, 10],
  // 8:
  [1, 3, 5, 6, 7, 10, 12, 13, 14, 15, 17, 19, 20, 22],
  // 9:
  [4, 5, 6, 7, 8, 10, 12, 14, 16, 17, 19, 20, 21],
  // 10:
  [0, 2, 6, 7, 8, 10, 14, 15, 16, 18, 21],
  // 11:
  [2, 3, 6, 7, 8, 11, 14, 16, 17, 18, 24],
  // 12:
  [1, 2, 6, 7, 8, 9, 15, 17, 18, 20, 22, 23],
  // 13:
  [0, 1, 8, 13, 14, 15, 16, 17, 18, 21, 22, 23],
  // 14:
  [0, 1, 3, 6, 7, 8, 9, 12, 15, 16, 18, 19, 20, 23],
  // 15:
  [0, 1, 2, 3, 4, 5, 6, 7, 11, 17, 18, 19, 20],
  // 16:
  [4, 5, 8, 9, 10, 16, 17, 19],
  // 17:
  [8, 12, 14, 15, 16, 18, 21, 24],
  // 18:
  [0, 1, 2, 3, 4, 5, 6, 8, 10, 11, 12, 13, 16, 17, 20, 21, 23],
  // 19:
  [0, 6, 10, 11, 20, 21, 23],
  // 20:
  [0, 2, 3, 4, 6, 8, 9, 13, 15, 17, 21],
  // 21:
  [0, 2, 3, 4, 6, 8, 14, 16, 17, 20, 21, 22],
  // 22:
  [0, 2, 3, 4, 6, 9, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 22],
  // 23:
  [0, 6, 10, 18, 19, 22, 24],
  // 24:
  [0, 1, 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 16, 17, 18, 21, 23, 24],
];

export default function HomeNoVehicle({
  driverName = "Rashmika Dilshan",
  driverCode = "DRV-00021",
  depotName = "Kandy depot",
  assignedVehicle,
  onGetVehicle,
  onStartRun,
  onStartTrip,
  onSignOut,
  isNight: propIsNight,
  onToggleTheme,
  hideHeader = false,
  tripStatus,
  inbox,
}: DriverHomeProps): React.JSX.Element {
  const [lang, setLang] = useState<SupportedLang>("en");
  const [internalIsNight, setInternalIsNight] = useState(false);
  const isNight = propIsNight ?? internalIsNight;
  const [statusText, setStatusText] = useState<string | null>(null);
  const [isAvailable, setIsAvailable] = useState(true);
  const [showFuelQrModal, setShowFuelQrModal] = useState(false);
  const [showSignOutConfirm, setShowSignOutConfirm] = useState(false);
  const [internalTripStarted, setInternalTripStarted] = useState(false);
  const isTripActive = tripStatus ? tripStatus === "in-progress" : internalTripStarted;
  const handleStartTrip = onStartTrip || onStartRun;
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const [activeAudioId, setActiveAudioId] = useState<string | null>(null);
  const { scrollRef, pullY, isPulling, maskStyle, handlers } = useRubberBandScroll();

  useEffect(() => {
    return () => {
      if (typeof window !== "undefined" && "speechSynthesis" in window) {
        window.speechSynthesis.cancel();
      }
    };
  }, []);

  const showToast = (msg: string) => {
    setToastMessage(msg);
    setTimeout(() => setToastMessage(null), 3000);
  };

  const handleToggleTheme = () => {
    if (onToggleTheme) {
      onToggleTheme();
    } else {
      setInternalIsNight((prev) => !prev);
    }
  };

  // Compute initials (e.g. Rashmika Dilshan -> RD)
  const initials = driverName
    .split(" ")
    .filter(Boolean)
    .map((w) => w[0]?.toUpperCase())
    .slice(0, 2)
    .join("") || "RD";

  const handleGetVehicle = () => {
    if (typeof window !== "undefined" && "speechSynthesis" in window) {
      window.speechSynthesis.cancel();
    }
    setActiveAudioId(null);
    if (onGetVehicle) {
      onGetVehicle();
    } else {
      setStatusText("Vehicle request registered with dispatch. Waiting for assignment.");
      setTimeout(() => setStatusText(null), 5000);
    }
  };

  return (
    <div
      className={cx(
        "relative mx-auto flex h-full max-h-full w-full flex-col overflow-hidden font-go select-none transition-colors",
        isNight ? "bg-[#161616] text-white" : "bg-[#E7F3F2] text-[#000000]"
      )}
    >
      {/* STATIC TOP SECTION (Never scrolls): Header + Driver Card + Notifications Header */}
      <div className="shrink-0 flex flex-col z-20">
        {/* 1. Header: GO + Language Toggle + Sign out + Theme Toggle */}
        {hideHeader ? (
          <div className="w-full h-[74px] shrink-0 pointer-events-none" />
        ) : (
          <DriverHeader
            lang={lang}
            onToggleLang={setLang}
            onSignOut={onSignOut ? () => setShowSignOutConfirm(true) : undefined}
            onToggleTheme={handleToggleTheme}
            isNight={isNight}
          />
        )}

        {/* 2. Driver Identity Card (Figma "Driver card") */}
        <div className="px-6 pt-1 shrink-0">
          <div
            className={cx(
              "w-full rounded-[38px] p-5 sm:p-6 shadow-[0px_5px_20px_rgba(0,0,0,0.05)] flex flex-col gap-3.5 transition-colors",
              isNight ? "bg-[#292929]" : "bg-white"
            )}
          >
            {/* Identity info */}
            <div className="flex items-center gap-3.5">
              {/* Avatar Circle: #00BF6A in dark mode, #B7F2ED in light mode */}
              <div
                className={cx(
                  "size-[52px] rounded-full flex items-center justify-center shrink-0 transition-colors",
                  isNight ? "bg-[#00BF6A]" : "bg-[#B7F2ED]"
                )}
              >
                <span className="text-[18px] font-medium text-black">{initials}</span>
              </div>

              {/* Name + Details */}
              <div className="flex flex-col min-w-0">
                <h1
                  className={cx(
                    "text-[26px] font-medium leading-[33px] truncate tracking-tight transition-colors",
                    isNight ? "text-white" : "text-black"
                  )}
                >
                  {driverName}
                </h1>
                <p className="text-[13px] font-light text-[#A9A9A9] leading-none pt-0.5">
                  Driver • {driverCode} • {depotName}
                </p>
              </div>
            </div>

            {/* If NO vehicle assigned yet: "Get vehicle" button */}
            {!assignedVehicle ? (
              <>
                <button
                  type="button"
                  onClick={handleGetVehicle}
                  className={cx(
                    "w-full h-[64px] rounded-[22px] text-[20px] font-medium flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
                    isNight
                      ? "bg-[#00BF6A] text-black hover:bg-[#00d878]"
                      : "bg-[#031B08] text-white hover:bg-[#062613]"
                  )}
                >
                  Get vehicle
                </button>

                {/* Temporary status feedback if triggered */}
                {statusText && (
                  <p
                    className={cx(
                      "text-center text-xs font-medium animate-fade-in",
                      isNight ? "text-[#00BF6A]" : "text-[#0E766D]"
                    )}
                  >
                    {statusText}
                  </p>
                )}
              </>
            ) : (
              /* Vehicle Assigned State (Figma: Driver: Home: 03 Kadugannawa) */
              <>
                {/* Vehicle tile (height: 128px, rounded: 24px) */}
                <div
                  className={cx(
                    "w-full rounded-[24px] p-[14px_14px_14px_20px] flex flex-col justify-between transition-colors",
                    isNight ? "bg-[#121212]" : "bg-[#E7F3F2]"
                  )}
                >
                  {/* Top row: Vehicle ID + Type chip */}
                  <div className="flex items-start justify-between w-full">
                    <div className="flex flex-col">
                      <span
                        className={cx(
                          "text-[12px] font-light leading-[15px]",
                          isNight ? "text-white" : "text-black"
                        )}
                      >
                        Vehicle
                      </span>
                      <span
                        className={cx(
                          "text-[28px] font-semibold leading-[35px] tracking-tight",
                          isNight ? "text-white" : "text-black"
                        )}
                      >
                        {assignedVehicle}
                      </span>
                    </div>

                    {/* Vehicle type chip: Refrigerated vehicle */}
                    <div
                      className={cx(
                        "px-3 py-1.5 rounded-[34px] flex items-center justify-center shrink-0 transition-colors",
                        isNight ? "bg-[#292929] text-white" : "bg-white text-black"
                      )}
                    >
                      <span className="text-[13px] font-light leading-[16px]">
                        Refrigerated vehicle
                      </span>
                    </div>
                  </div>

                  {/* Bottom row: Availability toggle + Fuel QR button */}
                  <div className="flex items-center justify-between w-full pt-2">
                    {/* Availability toggle */}
                    <div className="flex items-center gap-2.5">
                      <button
                        type="button"
                        role="switch"
                        aria-checked={isAvailable}
                        onClick={() => setIsAvailable((prev) => !prev)}
                        className={cx(
                          "relative w-[44px] h-[26px] rounded-[13px] transition-colors focus-visible:outline-none shrink-0",
                          isAvailable ? "bg-[#00BF6A]" : "bg-[#767676]"
                        )}
                        aria-label="Toggle vehicle availability"
                      >
                        <span
                          className={cx(
                            "absolute top-[3px] w-[20px] h-[20px] rounded-full bg-white shadow-[0px_1px_3px_rgba(0,0,0,0.15)] transition-all duration-200 ease-out",
                            isAvailable ? "left-[21px]" : "left-[3px]"
                          )}
                        />
                      </button>
                      <span
                        className={cx(
                          "text-[14px] font-medium leading-[18px]",
                          isNight ? "text-white" : "text-black"
                        )}
                      >
                        {isAvailable ? "Available" : "Unavailable"}
                      </span>
                    </div>

                    {/* Fuel QR button */}
                    <button
                      type="button"
                      onClick={() => setShowFuelQrModal(true)}
                      className={cx(
                        "h-[36px] px-3.5 flex items-center gap-2 rounded-[34px] transition-all active:scale-[0.98]",
                        isNight
                          ? "bg-[#292929] text-white hover:bg-[#333333]"
                          : "bg-white text-black hover:bg-slate-50"
                      )}
                      aria-label="Show Fuel QR code"
                    >
                      <svg width="18" height="18" viewBox="0 0 18 18" fill="none" className="shrink-0">
                        <path d="M2 2H7V7H2V2Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
                        <rect x="3.8" y="3.8" width="1.4" height="1.4" fill="currentColor" />
                        <path d="M11 2H16V7H11V2Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
                        <rect x="12.8" y="3.8" width="1.4" height="1.4" fill="currentColor" />
                        <path d="M2 11H7V16H2V11Z" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
                        <rect x="3.8" y="12.8" width="1.4" height="1.4" fill="currentColor" />
                        <path d="M11 11H13V13H11V11Z" fill="currentColor" />
                        <path d="M14 11H16V13H14V11Z" fill="currentColor" />
                        <path d="M11 14H13V16H11V14Z" fill="currentColor" />
                        <path d="M14 14H16V16H14V14Z" fill="currentColor" />
                      </svg>
                      <span className="text-[14px] font-medium leading-[18px]">Fuel QR</span>
                    </button>
                  </div>
                </div>

                {/* Primary Action Button: "Start trip" / "Continue trip" */}
                <button
                  type="button"
                  id="start-trip-btn"
                  onClick={() => {
                    if (typeof window !== "undefined" && "speechSynthesis" in window) {
                      window.speechSynthesis.cancel();
                    }
                    setActiveAudioId(null);
                    if (handleStartTrip) {
                      handleStartTrip();
                    } else {
                      if (!isTripActive) {
                        setInternalTripStarted(true);
                        setStatusText("Trip started • 7 stops to Ambepussa");
                      } else {
                        setStatusText("Continuing trip • 7 stops to Ambepussa");
                      }
                      setTimeout(() => setStatusText(null), 4000);
                    }
                  }}
                  className={cx(
                    "w-full h-[64px] rounded-[22px] text-[20px] font-medium flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
                    isNight
                      ? "bg-[#00BF6A] text-black hover:bg-[#00d878]"
                      : "bg-[#031B08] text-white hover:bg-[#062613]"
                  )}
                >
                  {isTripActive ? "Continue trip" : "Start trip"}
                </button>

                {/* Temporary status feedback if triggered */}
                {statusText && (
                  <p
                    className={cx(
                      "text-center text-xs font-medium animate-fade-in",
                      isNight ? "text-[#00BF6A]" : "text-[#0E766D]"
                    )}
                  >
                    {statusText}
                  </p>
                )}
              </>
            )}
          </div>
        </div>

        {/* 3. Notifications Header (Bell icon, "Notifications" text, "3 new" badge) */}
        <div className="flex items-center justify-between px-8 py-5 shrink-0">
          <div className="flex items-center gap-2">
            {/* Bell icon */}
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none" className={isNight ? "text-white" : "text-black"}>
              <path d="M10 2.1V3.4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
              <path
                d="M4.4 14.2H15.6C16.2 14.2 16.5 13.5 16.1 12.9L14.7 8.7V8.2C14.7 5.6 12.6 3.5 10 3.5C7.4 3.5 5.3 5.6 5.3 8.2V8.7L3.9 12.9C3.5 13.5 3.8 14.2 4.4 14.2Z"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              <path d="M8.3 16.2C8.3 17.1 9.1 17.8 10 17.8C10.9 17.8 11.7 17.1 11.7 16.2" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <h2 className={cx("text-[16px] font-medium leading-none", isNight ? "text-white" : "text-black")}>
              Notifications
            </h2>
          </div>

          {/* New count badge: the real unread count once signed in (issue #118). */}
          {(!inbox || (inbox.unread ?? 0) > 0) && (
            <span
              className={cx(
                "text-[12px] font-medium px-2.5 py-0.5 rounded-[34px] transition-colors",
                isNight ? "bg-[#00BF6A] text-black" : "bg-[#B7F2ED] text-black"
              )}
            >
              {inbox ? `${inbox.unread} new` : "3 new"}
            </span>
          )}
        </div>
      </div>

      {/* 4. Scrollable Container with dynamic mask fade (ONLY this feed scrolls) */}
      <div
        style={maskStyle}
        className="relative flex-1 min-h-0 px-6 overflow-hidden"
      >

        {/* Scrollable feed list with discrete snap-to-card paging & rubber band bounce */}
        <div
          ref={scrollRef}
          {...handlers}
          className="h-full overflow-y-auto overscroll-contain pb-6 no-scrollbar [scrollbar-width:none] [&::-webkit-scrollbar]:hidden snap-y snap-mandatory scroll-smooth"
        >
          <div
            style={{
              transform: `translate3d(0, ${pullY}px, 0)`,
              transition: isPulling ? "none" : "transform 500ms cubic-bezier(0.18, 1.12, 0.32, 1.0)",
            }}
            className="flex flex-col gap-2.5 will-change-transform"
          >
            {inbox ? (
              inbox.items.length === 0 ? (
                <p className={cx("px-2 py-6 text-center text-[13px] font-light", isNight ? "text-white/70" : "text-black/60")}>
                  {inbox.loading ? "Loading…" : inbox.online ? "No notifications yet." : "Offline. Notifications show when you're back online."}
                </p>
              ) : (
                inbox.items.map((n) => {
                  const kind = driverKind(n.eventType, isNight);
                  const fresh = n.readAt === null;
                  const id = `n-${n.notificationId}`;
                  return (
                    <ScrollRevealCard key={n.notificationId} scrollContainerRef={scrollRef} pullY={pullY}>
                      <div
                        className={cx(
                          "rounded-[20px] p-[14px_20px] flex flex-col gap-1 transition-all duration-200",
                          isNight ? "bg-[#292929]" : "bg-white",
                          activeAudioId === id &&
                            (isNight ? "ring-1 ring-[#00BF6A]/60 shadow-[0_0_15px_rgba(0,191,106,0.15)]" : "ring-1 ring-[#0E766D]/50 shadow-[0_0_15px_rgba(14,118,109,0.12)]"),
                        )}
                      >
                      <button
                        type="button"
                        onClick={() => fresh && inbox.online && void inbox.markRead([n.notificationId]).catch(() => undefined)}
                        aria-label={`${fresh ? "Unread. " : ""}${kind.label}. ${n.title}. ${n.body}`}
                        className={cx("flex w-full flex-col gap-1 text-left", !fresh && "opacity-70")}
                      >
                        <span className="flex items-center justify-between">
                          <span className="flex items-center gap-1.5">
                            <span className="size-2 rounded-full" style={{ backgroundColor: kind.color }} />
                            <span className="text-[12px] font-medium" style={{ color: kind.color }}>{kind.label}</span>
                          </span>
                          <span className="text-[12px] font-light text-[#A9A9A9]">{clock(n.createdAt)}</span>
                        </span>
                        <span className={cx("text-[15px] font-medium leading-[19px] pt-0.5", isNight ? "text-white" : "text-black")}>{n.title}</span>
                        <span className={cx("text-[13px] font-light leading-4 pt-0.5", isNight ? "text-white" : "text-black")}>{n.body}</span>
                      </button>
                      <VoiceMessagePlayer
                        id={id}
                        duration={spokenLength(`${n.title}. ${n.body}`)}
                        text={`${kind.label}. ${n.title}. ${n.body}`}
                        isNight={isNight}
                        activeAudioId={activeAudioId}
                        onPlayChange={setActiveAudioId}
                      />
                      </div>
                    </ScrollRevealCard>
                  );
                })
              )
            ) : (
              <>
            {/* 1. Voice Message */}
            <ScrollRevealCard scrollContainerRef={scrollRef} pullY={pullY}>
              <div
                className={cx(
                  "rounded-[20px] p-[14px_20px] flex flex-col gap-2 transition-all duration-200",
                  isNight ? "bg-[#292929]" : "bg-white",
                  activeAudioId === "msg-voice-1" &&
                    (isNight ? "ring-1 ring-[#00BF6A]/60 shadow-[0_0_15px_rgba(0,191,106,0.15)]" : "ring-1 ring-[#0E766D]/50 shadow-[0_0_15px_rgba(14,118,109,0.12)]")
                )}
              >
                <div className="flex items-center justify-between">
                  <span className={cx("text-[15px] font-medium", isNight ? "text-white" : "text-black")}>
                    Priya S. • Dispatch
                  </span>
                  <span className="text-[12px] font-light text-[#A9A9A9]">03:12</span>
                </div>
                <VoiceMessagePlayer
                  id="msg-voice-1"
                  duration="0:18"
                  text="Hi Rashmika, Priya here from dispatch. We noticed a delay on the Kandy corridor. Proceed with caution and let us know if you need dock assistance."
                  isNight={isNight}
                  activeAudioId={activeAudioId}
                  onPlayChange={setActiveAudioId}
                />
              </div>
            </ScrollRevealCard>

            {/* 2. Action Needed: Loading shortfall at Dock 2 */}
            <ScrollRevealCard scrollContainerRef={scrollRef} pullY={pullY}>
              <div
                className={cx(
                  "rounded-[20px] p-[14px_20px] flex flex-col gap-1 transition-all duration-200",
                  isNight ? "bg-[#292929]" : "bg-white",
                  activeAudioId === "msg-shortfall" &&
                    (isNight ? "ring-1 ring-[#00BF6A]/60 shadow-[0_0_15px_rgba(0,191,106,0.15)]" : "ring-1 ring-[#0E766D]/50 shadow-[0_0_15px_rgba(14,118,109,0.12)]")
                )}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5">
                    <span className="size-2 rounded-full bg-[#E5484D]" />
                    <span className="text-[12px] font-medium text-[#E5484D]">Action needed</span>
                  </div>
                  <span className="text-[12px] font-light text-[#A9A9A9]">03:10</span>
                </div>
                <h3 className={cx("text-[15px] font-medium leading-[19px] pt-0.5", isNight ? "text-white" : "text-black")}>
                  Loading shortfall at Dock 2
                </h3>
                <p className={cx("text-[13px] font-light leading-4 pt-0.5", isNight ? "text-white" : "text-black")}>
                  Loader flagged 1 chilled crate missing for stop 03, Kadugannawa. Check with the loader before you depart.
                </p>
                <VoiceMessagePlayer
                  id="msg-shortfall"
                  duration="0:08"
                  text="Action needed. Loading shortfall at Dock 2. Loader flagged 1 chilled crate missing for stop 03, Kadugannawa. Check with the loader before you depart."
                  isNight={isNight}
                  activeAudioId={activeAudioId}
                  onPlayChange={setActiveAudioId}
                />
              </div>
            </ScrollRevealCard>

            {/* 3. Route Updated: Kadugannawa added as stop 03 */}
            <ScrollRevealCard scrollContainerRef={scrollRef} pullY={pullY}>
              <div
                className={cx(
                  "rounded-[20px] p-[14px_20px] flex flex-col gap-1 transition-all duration-200",
                  isNight ? "bg-[#292929]" : "bg-white",
                  activeAudioId === "msg-route-updated" &&
                    (isNight ? "ring-1 ring-[#00BF6A]/60 shadow-[0_0_15px_rgba(0,191,106,0.15)]" : "ring-1 ring-[#0E766D]/50 shadow-[0_0_15px_rgba(14,118,109,0.12)]")
                )}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5">
                    <span className={cx("size-2 rounded-full", isNight ? "bg-[#00BF6A]" : "bg-[#0E766D]")} />
                    <span
                      className={cx(
                        "text-[12px] font-medium",
                        isNight ? "text-[#00BF6A]" : "text-[#0E766D]"
                      )}
                    >
                      Route updated
                    </span>
                  </div>
                  <span className="text-[12px] font-light text-[#A9A9A9]">03:02</span>
                </div>
                <h3 className={cx("text-[15px] font-medium leading-[19px] pt-0.5", isNight ? "text-white" : "text-black")}>
                  Kadugannawa added as stop 03
                </h3>
                <p className={cx("text-[13px] font-light leading-4 pt-0.5", isNight ? "text-white" : "text-black")}>
                  Dispatch moved this order onto your run. Your run is now 3 stops.
                </p>
                <VoiceMessagePlayer
                  id="msg-route-updated"
                  duration="0:06"
                  text="Route updated. Kadugannawa added as stop 03. Dispatch moved this order onto your run. Your run is now 3 stops."
                  isNight={isNight}
                  activeAudioId={activeAudioId}
                  onPlayChange={setActiveAudioId}
                />
              </div>
            </ScrollRevealCard>

            {/* 4. Road Alert: Roadworks near Kadugannawa */}
            <ScrollRevealCard scrollContainerRef={scrollRef} pullY={pullY}>
              <div
                className={cx(
                  "rounded-[20px] p-[14px_20px] flex flex-col gap-1 transition-all duration-200",
                  isNight ? "bg-[#292929]" : "bg-white",
                  activeAudioId === "msg-roadworks" &&
                    (isNight ? "ring-1 ring-[#00BF6A]/60 shadow-[0_0_15px_rgba(0,191,106,0.15)]" : "ring-1 ring-[#0E766D]/50 shadow-[0_0_15px_rgba(14,118,109,0.12)]")
                )}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5">
                    <span className="size-2 rounded-full bg-[#B7791F]" />
                    <span className="text-[12px] font-medium text-[#B7791F]">Road alert</span>
                  </div>
                  <span className="text-[12px] font-light text-[#A9A9A9]">02:55</span>
                </div>
                <h3 className={cx("text-[15px] font-medium leading-[19px] pt-0.5", isNight ? "text-white" : "text-black")}>
                  Roadworks near Kadugannawa
                </h3>
                <p className={cx("text-[13px] font-light leading-4 pt-0.5", isNight ? "text-white" : "text-black")}>
                  Colombo-Kandy rd, single lane. Allow about 10 extra minutes.
                </p>
                <VoiceMessagePlayer
                  id="msg-roadworks"
                  duration="0:06"
                  text="Road alert. Roadworks near Kadugannawa. Colombo-Kandy road, single lane. Allow about 10 extra minutes."
                  isNight={isNight}
                  activeAudioId={activeAudioId}
                  onPlayChange={setActiveAudioId}
                />
              </div>
            </ScrollRevealCard>

            {/* 5. Voice Message (Secondary) */}
            <ScrollRevealCard scrollContainerRef={scrollRef} pullY={pullY}>
              <div
                className={cx(
                  "rounded-[20px] p-[14px_20px] flex flex-col gap-2 transition-all duration-200",
                  isNight ? "bg-[#292929]" : "bg-white",
                  activeAudioId === "msg-voice-2" &&
                    (isNight ? "ring-1 ring-[#00BF6A]/60 shadow-[0_0_15px_rgba(0,191,106,0.15)]" : "ring-1 ring-[#0E766D]/50 shadow-[0_0_15px_rgba(14,118,109,0.12)]")
                )}
              >
                <div className="flex items-center justify-between">
                  <span className={cx("text-[15px] font-medium", isNight ? "text-white" : "text-black")}>
                    Priya S. • Dispatch
                  </span>
                  <span className="text-[12px] font-light text-[#A9A9A9]">03:12</span>
                </div>
                <VoiceMessagePlayer
                  id="msg-voice-2"
                  duration="0:27"
                  text="Hey Rashmika, dispatch here. Kadugannawa dock confirmed they have your chilled pallets ready."
                  isNight={isNight}
                  activeAudioId={activeAudioId}
                  onPlayChange={setActiveAudioId}
                />
              </div>
            </ScrollRevealCard>

            {/* 6. Store Update: Gampola store confirmed */}
            <ScrollRevealCard scrollContainerRef={scrollRef} pullY={pullY}>
              <div
                className={cx(
                  "rounded-[20px] p-[14px_20px] flex flex-col gap-1 transition-all duration-200",
                  isNight ? "bg-[#292929]" : "bg-white",
                  activeAudioId === "msg-gampola" &&
                    (isNight ? "ring-1 ring-[#00BF6A]/60 shadow-[0_0_15px_rgba(0,191,106,0.15)]" : "ring-1 ring-[#0E766D]/50 shadow-[0_0_15px_rgba(14,118,109,0.12)]")
                )}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5">
                    <span className={cx("size-2 rounded-full", isNight ? "bg-[#7FB3E6]" : "bg-[#16324F]")} />
                    <span
                      className={cx(
                        "text-[12px] font-medium",
                        isNight ? "text-[#7FB3E6]" : "text-[#16324F]"
                      )}
                    >
                      Store update
                    </span>
                  </div>
                  <span className="text-[12px] font-light text-[#A9A9A9]">02:48</span>
                </div>
                <h3 className={cx("text-[15px] font-medium leading-[19px] pt-0.5", isNight ? "text-white" : "text-black")}>
                  Gampola store confirmed
                </h3>
                <p className={cx("text-[13px] font-light leading-4 pt-0.5", isNight ? "text-white" : "text-black")}>
                  Receiver on site from 05:45. Unload at the rear dock.
                </p>
                <VoiceMessagePlayer
                  id="msg-gampola"
                  duration="0:05"
                  text="Store update. Gampola store confirmed. Receiver on site from 05:45. Unload at the rear dock."
                  isNight={isNight}
                  activeAudioId={activeAudioId}
                  onPlayChange={setActiveAudioId}
                />
              </div>
            </ScrollRevealCard>

            {/* 7. Synced: Yesterday's records uploaded */}
            <ScrollRevealCard scrollContainerRef={scrollRef} pullY={pullY}>
              <div
                className={cx(
                  "rounded-[20px] p-[14px_20px] flex flex-col gap-1 transition-all duration-200",
                  isNight ? "bg-[#292929]" : "bg-white",
                  activeAudioId === "msg-synced" &&
                    (isNight ? "ring-1 ring-[#00BF6A]/60 shadow-[0_0_15px_rgba(0,191,106,0.15)]" : "ring-1 ring-[#0E766D]/50 shadow-[0_0_15px_rgba(14,118,109,0.12)]")
                )}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5">
                    <span className={cx("size-2 rounded-full", isNight ? "bg-[#00BF6A]" : "bg-[#0E766D]")} />
                    <span
                      className={cx(
                        "text-[12px] font-medium",
                        isNight ? "text-[#00BF6A]" : "text-[#0E766D]"
                      )}
                    >
                      Synced
                    </span>
                  </div>
                  <span className="text-[12px] font-light text-[#A9A9A9]">Yesterday 18:20</span>
                </div>
                <h3 className={cx("text-[15px] font-medium leading-[19px] pt-0.5", isNight ? "text-white" : "text-black")}>
                  Yesterday&apos;s records uploaded
                </h3>
                <p className={cx("text-[13px] font-light leading-4 pt-0.5", isNight ? "text-white" : "text-black")}>
                  6 proofs of delivery saved offline reached dispatch when coverage returned.
                </p>
                <VoiceMessagePlayer
                  id="msg-synced"
                  duration="0:06"
                  text="Synced. Yesterday's records uploaded. 6 proofs of delivery saved offline reached dispatch when coverage returned."
                  isNight={isNight}
                  activeAudioId={activeAudioId}
                  onPlayChange={setActiveAudioId}
                />
              </div>
            </ScrollRevealCard>

            {/* 8. Run Published: Tomorrow's run is ready */}
            <ScrollRevealCard scrollContainerRef={scrollRef} pullY={pullY}>
              <div
                className={cx(
                  "rounded-[20px] p-[14px_20px] flex flex-col gap-1 transition-all duration-200",
                  isNight ? "bg-[#292929]" : "bg-white",
                  activeAudioId === "msg-run-ready" &&
                    (isNight ? "ring-1 ring-[#00BF6A]/60 shadow-[0_0_15px_rgba(0,191,106,0.15)]" : "ring-1 ring-[#0E766D]/50 shadow-[0_0_15px_rgba(14,118,109,0.12)]")
                )}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5">
                    <span className="size-2 rounded-full bg-[#A9A9A9]" />
                    <span className="text-[12px] font-medium text-[#A9A9A9]">Run published</span>
                  </div>
                  <span className="text-[12px] font-light text-[#A9A9A9]">Yesterday 16:40</span>
                </div>
                <h3 className={cx("text-[15px] font-medium leading-[19px] pt-0.5", isNight ? "text-white" : "text-black")}>
                  Tomorrow&apos;s run is ready
                </h3>
                <p className={cx("text-[13px] font-light leading-4 pt-0.5", isNight ? "text-white" : "text-black")}>
                  6 Fresh stops from Kandy depot. Report to Dock 2 by 03:30.
                </p>
                <VoiceMessagePlayer
                  id="msg-run-ready"
                  duration="0:06"
                  text="Run published. Tomorrow's run is ready. 6 Fresh stops from Kandy depot. Report to Dock 2 by 03:30."
                  isNight={isNight}
                  activeAudioId={activeAudioId}
                  onPlayChange={setActiveAudioId}
                />
              </div>
            </ScrollRevealCard>
              </>
            )}
          </div>
        </div>
      </div>

      {/* Fuel Pass QR Modal (Figma: Fuel QR popup) */}
      {showFuelQrModal && (
        <div
          role="dialog"
          aria-modal="true"
          className="absolute inset-0 z-50 flex items-center justify-center p-6 animate-fade-in"
        >
          {/* Blur scrim: background: rgba(11, 31, 30, 0.18); backdrop-filter: blur(7px); */}
          <div
            className="absolute inset-0 bg-[#0B1F1E]/20 backdrop-blur-[7px] transition-opacity"
            onClick={() => setShowFuelQrModal(false)}
          />

          {/* QR card (Figma: auto layout, p: 24px 20px 22px, gap: 16px, rounded: 38px, w: 343px) */}
          <div
            onClick={(e) => e.stopPropagation()}
            className={cx(
              "relative z-10 w-full max-w-[343px] rounded-[38px] p-[24px_20px_22px] flex flex-col items-center gap-[16px] shadow-[0px_10px_30px_rgba(0,0,0,0.12)] transition-colors select-none",
              isNight ? "bg-[#292929] text-white" : "bg-white text-black"
            )}
          >
            {/* QR heading block */}
            <div className="flex flex-col items-center gap-1 text-center">
              <h3
                className={cx(
                  "text-[20px] font-medium leading-[25px]",
                  isNight ? "text-white" : "text-black"
                )}
              >
                National Fuel Pass
              </h3>
              <p
                className={cx(
                  "text-[13px] font-light leading-[16px]",
                  isNight ? "text-[#A9A9A9]" : "text-[#6B7280]"
                )}
              >
                {assignedVehicle || "VEH043"} • CP LK-4821 • Diesel
              </p>
            </div>

            {/* QR code box: enlarged to 276px x 276px with 252px SVG */}
            <div className="w-[276px] h-[276px] rounded-[26px] p-[12px] bg-white border-[1.5px] border-[#B7F2ED] flex items-center justify-center shrink-0 shadow-inner">
              <svg viewBox="0 0 25 25" className="w-[252px] h-[252px]" fill="none">
                <rect width="25" height="25" fill="#FFFFFF" />
                {FUEL_QR_ROWS.map((cols, y) =>
                  cols.map((x) => (
                    <rect key={`${x}-${y}`} x={x} y={y} width="1" height="1" fill="#000000" />
                  ))
                )}
              </svg>
            </div>

            {/* Log fill-up button: w: 276px matching QR box, h: 62px, rounded: 22px */}
            <button
              type="button"
              onClick={() => {
                setShowFuelQrModal(false);
                showToast("Fuel fill-up logged successfully");
              }}
              className={cx(
                "w-[276px] h-[62px] rounded-[22px] text-[19px] font-medium leading-[24px] flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
                isNight
                  ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90"
                  : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
              )}
            >
              Log fill-up
            </button>
          </div>
        </div>
      )}

      {/* Sign Out Confirmation Bottom Sheet (Figma: Sign out: confirm) */}
      <SignOutConfirmBottomSheet
        isOpen={showSignOutConfirm}
        onClose={() => setShowSignOutConfirm(false)}
        onConfirm={() => {
          setShowSignOutConfirm(false);
          onSignOut?.();
        }}
        isNight={isNight}
      />

      {/* Toast Notification */}
      {toastMessage && (
        <div className="absolute top-[80px] left-6 right-6 z-50 flex justify-center pointer-events-none animate-fade-in">
          <div className="bg-[#031B08] text-white text-[13px] font-medium px-4 py-2.5 rounded-full shadow-lg border border-white/10">
            {toastMessage}
          </div>
        </div>
      )}
    </div>
  );
}
