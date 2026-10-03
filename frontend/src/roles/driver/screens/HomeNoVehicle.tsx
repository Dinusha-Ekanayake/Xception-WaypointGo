"use client";

import { useState, useRef, useEffect } from "react";
import { VehicleStatuses, type ReportedVehicleStatus, type VehicleView } from "@shared/domain/types";
import { isUnread, kindOf } from "@shared/notifications/inbox";
import type { Inbox } from "@shared/notifications/useInbox";
import { cx, useDeviceLang } from "@shared/ui";
import { clock, countdown, stops as stopsText } from "../../../shared/wording/index.ts";
import type { TripStatus } from "../data/stopView.ts";
import { DriverHeader, VoiceMessagePlayer } from "../ui.tsx";

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
  driverName: string;
  depotName: string;
  /** The vehicle on today's run sheet; dispatch assigns it, the driver does not claim one. */
  vehicle: VehicleView | null;
  tripStatus: TripStatus;
  stopCount: number;
  /** The server could not be asked and this phone holds no copy of today. */
  unavailable: boolean;
  online: boolean;
  vehicleStatus: ReportedVehicleStatus | null;
  onVehicleStatus: (status: ReportedVehicleStatus) => void;
  onStartTrip: () => void;
  /** The road or the vehicle, when no stop is open. */
  onProblem: () => void;
  onSignOut?: () => void;
  /** The driver's notifications (issue #118): plan published or revised, trip released. */
  inbox: Inbox;
  now: Date;
  isNight?: boolean;
  onToggleTheme?: () => void;
  hideHeader?: boolean;
};

const STATUS_LABEL: Record<ReportedVehicleStatus, string> = {
  available: "Available",
  on_trip: "On trip",
  at_workshop: "At workshop",
  fault: "Fault",
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
      return { label: "Run updated", color: teal };
    case "trip.released":
      return { label: "Vehicle loaded", color: teal };
    default: {
      const kind = kindOf(eventType);
      const color = kind.tone === "urgent" ? "#E5484D" : kind.tone === "warning" ? "#B7791F" : kind.tone === "good" ? teal : isNight ? "#7FB3E6" : "#16324F";
      return { label: kind.tone === "urgent" ? "Action needed" : kind.label, color };
    }
  }
}

function vehicleKind(vehicle: VehicleView): string {
  if (vehicle.refrigerated) return "Refrigerated vehicle";
  return vehicle.van ? "Van" : "Truck";
}

/** About how long the phone takes to read a notification aloud. */
function readingTime(text: string): string {
  return countdown(Math.max(2, text.split(/\s+/).length / 2.5));
}

export default function HomeNoVehicle({
  driverName,
  depotName,
  vehicle,
  tripStatus,
  stopCount,
  unavailable,
  online,
  vehicleStatus,
  onVehicleStatus,
  onStartTrip,
  onProblem,
  onSignOut,
  inbox,
  now,
  isNight = false,
  onToggleTheme,
  hideHeader = false,
}: DriverHomeProps): React.JSX.Element {
  const [lang, setLang] = useDeviceLang();
  const [activeAudioId, setActiveAudioId] = useState<string | null>(null);
  const { scrollRef, pullY, isPulling, maskStyle, handlers } = useRubberBandScroll();

  useEffect(() => {
    return () => {
      if (typeof window !== "undefined" && "speechSynthesis" in window) {
        window.speechSynthesis.cancel();
      }
    };
  }, []);

  const initials = driverName
    .split(" ")
    .filter(Boolean)
    .map((w) => w[0]?.toUpperCase())
    .slice(0, 2)
    .join("") || "D";

  const startLabel = tripStatus === "completed" ? "Trip summary" : tripStatus === "in-progress" ? "Continue trip" : "Start trip";
  const card = isNight ? "bg-[#292929]" : "bg-white";
  const ink = isNight ? "text-white" : "text-black";
  const unread = inbox.unread ?? inbox.items.filter(isUnread).length;

  const startTrip = () => {
    if (typeof window !== "undefined" && "speechSynthesis" in window) {
      window.speechSynthesis.cancel();
    }
    setActiveAudioId(null);
    onStartTrip();
  };

  return (
    <div
      className={cx(
        "relative mx-auto flex h-full max-h-full w-full flex-col overflow-hidden font-go select-none transition-colors short:h-auto short:min-h-full short:max-h-none short:overflow-visible",
        isNight ? "bg-[#161616] text-white" : "bg-[#E7F3F2] text-[#000000]"
      )}
    >
      {/* STATIC TOP SECTION (Never scrolls): Header + Driver Card + Notifications Header */}
      <div className="shrink-0 flex flex-col z-20">
        {hideHeader ? (
          <div className="w-full h-[74px] shrink-0 pointer-events-none" />
        ) : (
          <DriverHeader displayName={driverName} lang={lang} onToggleLang={setLang} onSignOut={onSignOut} onToggleTheme={onToggleTheme} isNight={isNight} />
        )}

        {/* Driver Identity Card (Figma "Driver card") */}
        <div className="px-6 pt-1 shrink-0">
          <div className={cx("w-full rounded-[38px] p-5 sm:p-6 shadow-[0px_5px_20px_rgba(0,0,0,0.05)] flex flex-col gap-3.5 transition-colors", card)}>
            <div className="flex items-center gap-3.5">
              <div
                className={cx(
                  "size-[52px] rounded-full flex items-center justify-center shrink-0 transition-colors",
                  isNight ? "bg-[#00BF6A]" : "bg-[#B7F2ED]"
                )}
              >
                <span className="text-[18px] font-medium text-black">{initials}</span>
              </div>
              <div className="flex flex-col min-w-0">
                <h1 className={cx("text-[26px] font-medium leading-[33px] truncate tracking-tight transition-colors", ink)}>{driverName}</h1>
                <p className="text-[13px] font-light text-[#A9A9A9] leading-none pt-0.5">
                  Driver{depotName ? ` · ${depotName} depot` : ""}
                </p>
              </div>
            </div>

            {unavailable ? (
              <p role="status" className={cx("text-[14px] leading-[18px]", ink)}>
                {online ? "Waypoint is not answering." : "This phone is offline."} Today's trip has not been downloaded to this phone
                yet. It appears here as soon as the connection is back.
              </p>
            ) : !vehicle ? (
              <p role="status" className={cx("text-[14px] leading-[18px]", ink)}>
                No vehicle assigned today. Dispatch assigns your vehicle; if you expected one, ask them.
              </p>
            ) : (
              <>
                {/* Vehicle tile (Figma: Driver: Home: 03) */}
                <div
                  className={cx(
                    "w-full rounded-[24px] p-[14px_14px_14px_20px] flex flex-col justify-between gap-2 transition-colors",
                    isNight ? "bg-[#121212]" : "bg-[#E7F3F2]"
                  )}
                >
                  <div className="flex items-start justify-between w-full">
                    <div className="flex flex-col">
                      <span className={cx("text-[12px] font-light leading-[15px]", ink)}>Vehicle</span>
                      <span className={cx("text-[28px] font-semibold leading-[35px] tracking-tight", ink)}>{vehicle.vehicleId}</span>
                    </div>
                    <div
                      className={cx(
                        "px-3 py-1.5 rounded-[34px] flex items-center justify-center shrink-0 transition-colors",
                        isNight ? "bg-[#292929] text-white" : "bg-white text-black"
                      )}
                    >
                      <span className="text-[13px] font-light leading-[16px]">{vehicleKind(vehicle)}</span>
                    </div>
                  </div>
                  <label className={cx("flex items-center justify-between gap-3 text-[14px] font-medium", ink)}>
                    Vehicle status
                    <select
                      value={vehicleStatus ?? ""}
                      onChange={(event) => onVehicleStatus(event.target.value as ReportedVehicleStatus)}
                      className={cx(
                        "h-[36px] rounded-[34px] px-3 text-[14px] font-medium",
                        isNight ? "bg-[#292929] text-white" : "bg-white text-black"
                      )}
                    >
                      <option value="" disabled>
                        Choose
                      </option>
                      {VehicleStatuses.filter((status) => status !== "fault").map((status) => (
                        <option key={status} value={status}>
                          {STATUS_LABEL[status]}
                        </option>
                      ))}
                    </select>
                  </label>
                </div>

                {stopCount > 0 ? (
                  <button
                    type="button"
                    id="start-trip-btn"
                    onClick={startTrip}
                    className={cx(
                      "w-full h-[64px] rounded-[22px] text-[20px] font-medium flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
                      isNight ? "bg-[#00BF6A] text-black hover:bg-[#00d878]" : "bg-[#031B08] text-white hover:bg-[#062613]"
                    )}
                  >
                    {startLabel}
                  </button>
                ) : (
                  <p role="status" className={cx("text-[14px] leading-[18px]", ink)}>
                    No trip planned for {vehicle.vehicleId} today. Your stops appear here when the loader releases the vehicle.
                  </p>
                )}
                {stopCount > 0 && tripStatus !== "completed" && (
                  <p className="text-center text-[13px] font-light text-[#A9A9A9]">{stopsText(stopCount)} on today's run sheet</p>
                )}
                <button type="button" onClick={onProblem} className={cx("text-[15px] font-medium underline", ink)}>
                  Report problem
                </button>
              </>
            )}
          </div>
        </div>

        {/* Notifications Header (Bell icon, "Notifications" text, unread badge) */}
        <div className="flex items-center justify-between px-8 py-5 shrink-0">
          <div className="flex items-center gap-2">
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none" className={ink}>
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
            <h2 className={cx("text-[16px] font-medium leading-none", ink)}>Notifications</h2>
          </div>
          {unread > 0 && (
            <button
              type="button"
              onClick={() => void inbox.markAllRead(now)}
              aria-label={`${unread} new. Mark all read`}
              className={cx(
                "text-[12px] font-medium px-2.5 py-0.5 rounded-[34px] transition-colors",
                isNight ? "bg-[#00BF6A] text-black" : "bg-[#B7F2ED] text-black"
              )}
            >
              {unread > 99 ? "99+" : unread} new
            </button>
          )}
        </div>
      </div>

      {/* Scrollable Container with dynamic mask fade (ONLY this feed scrolls) */}
      <div style={maskStyle} className="relative flex-1 min-h-0 px-6 overflow-hidden short:flex-none short:overflow-visible">
        <div
          ref={scrollRef}
          {...handlers}
          aria-label="Notifications"
          className="h-full overflow-y-auto overscroll-contain pb-6 short:h-auto short:overflow-visible no-scrollbar [scrollbar-width:none] [&::-webkit-scrollbar]:hidden snap-y snap-mandatory scroll-smooth"
        >
          <div
            style={{
              transform: `translate3d(0, ${pullY}px, 0)`,
              transition: isPulling ? "none" : "transform 500ms cubic-bezier(0.18, 1.12, 0.32, 1.0)",
            }}
            className="flex flex-col gap-2.5 will-change-transform"
          >
            {inbox.savedAt && (
              <p role="status" className="px-2 text-[13px] font-light text-[#A9A9A9]">
                Offline · showing notifications saved on this phone
              </p>
            )}
            {!inbox.savedAt && !inbox.live && inbox.online && inbox.unread !== null && (
              <p role="status" className="px-2 text-[13px] font-light text-[#A9A9A9]">
                Live updates paused · checking every 30 s
              </p>
            )}
            {inbox.error && (
              <p role="alert" className={cx("px-2 text-[13px]", ink)}>
                {inbox.error}
              </p>
            )}
            {inbox.loading && inbox.items.length === 0 && (
              <p role="status" className="px-2 text-[14px] font-light text-[#A9A9A9]">
                Loading notifications…
              </p>
            )}
            {!inbox.loading && !inbox.error && inbox.items.length === 0 && (
              <p className="px-2 text-[14px] font-light text-[#A9A9A9]">
                Nothing yet. Dispatch's messages about your trip appear here.
              </p>
            )}
            {inbox.items.map((item) => {
              const kind = driverKind(item.eventType, isNight);
              const id = `msg-${item.notificationId}`;
              const fresh = isUnread(item);
              return (
                <ScrollRevealCard key={item.notificationId} scrollContainerRef={scrollRef} pullY={pullY}>
                  <div
                    className={cx(
                      "rounded-[20px] p-[14px_20px] flex flex-col gap-1 transition-all duration-200",
                      card,
                      activeAudioId === id &&
                        (isNight ? "ring-1 ring-[#00BF6A]/60 shadow-[0_0_15px_rgba(0,191,106,0.15)]" : "ring-1 ring-[#0E766D]/50 shadow-[0_0_15px_rgba(14,118,109,0.12)]")
                    )}
                  >
                    <button
                      type="button"
                      onClick={() => fresh && inbox.online && void inbox.markRead([item.notificationId]).catch(() => undefined)}
                      aria-label={`${fresh ? "Unread. " : ""}${kind.label}. ${item.title}. ${item.body}`}
                      className={cx("flex w-full flex-col gap-1 text-left", !fresh && "opacity-70")}
                    >
                      <span className="flex items-center justify-between">
                        <span className="flex items-center gap-1.5">
                          <span className="size-2 rounded-full" style={{ backgroundColor: kind.color }} />
                          <span className="text-[12px] font-medium" style={{ color: kind.color }}>
                            {kind.label}
                          </span>
                        </span>
                        <span className="text-[12px] font-light text-[#A9A9A9]">{clock(item.createdAt)}</span>
                      </span>
                      <span className={cx("text-[15px] font-medium leading-[19px] pt-0.5", ink)}>{item.title}</span>
                      <span className={cx("text-[13px] font-light leading-4 pt-0.5", ink)}>{item.body}</span>
                    </button>
                    <VoiceMessagePlayer
                      id={id}
                      duration={readingTime(`${item.title} ${item.body}`)}
                      text={`${kind.label}. ${item.title}. ${item.body}`}
                      isNight={isNight}
                      activeAudioId={activeAudioId}
                      onPlayChange={setActiveAudioId}
                    />
                  </div>
                </ScrollRevealCard>
              );
            })}
            {inbox.hasMore && (
              <button type="button" onClick={inbox.more} className={cx("py-2 text-[14px] font-medium underline", ink)}>
                Show older
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
