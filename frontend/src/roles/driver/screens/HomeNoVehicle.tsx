"use client";

import { useState, useRef, useEffect } from "react";
import { type ReportedVehicleStatus, type VehicleView } from "@shared/domain/types";
import { isUnread, kindOf, toneOf, TONE_STYLE } from "@shared/notifications/inbox";
import type { Inbox } from "@shared/notifications/useInbox";
import { cx, useDeviceLang, useScrollMemory, VoiceNote } from "@shared/ui";
import { ScrollRevealCard, useRubberBandScroll } from "@shared/ui/scrollReveal";
import { voiceUrl } from "@shared/messaging/useThread";
import { clock, countdown, dayLabel, hhmm } from "../../../shared/wording/index.ts";
import type { TripStatus } from "../data/stopView.ts";
import type { NextRun } from "../data/run.ts";
import type { FuelPassState } from "../data/fuelPass.ts";
import { DriverHeader, VoiceMessagePlayer } from "../ui.tsx";
import FuelPassSheet from "./FuelPassSheet.tsx";
import { useCappedScroll } from "./cappedScroll.ts";


export type DriverHomeProps = {
  driverName: string;
  depotName: string;
  /** The vehicle on today's run sheet; dispatch assigns it, the driver does not claim one. */
  vehicle: VehicleView | null;
  tripStatus: TripStatus;
  stopCount: number;
  /** With nothing released today: the next trip, released or still at the dock. */
  nextRun?: NextRun | null;
  /** Today's trip waits for the loader: the driver says they are at the depot (R-EXE-24). */
  depotCheckIn?: { vehicleId: string; done: boolean; report: () => void } | null;
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
  /** Opens the trip's thread, for a message notification (issue #136). */
  onOpenMessages?: () => void;
  /** Reads the vehicle's Fuel Pass, or the copy kept on this phone (FF-06b). */
  loadFuelPass: (vehicleId: string) => Promise<FuelPassState>;
};


/**
 * The colour of a notification's kind, the same palette every role's inbox uses
 * (TONE_STYLE), as a theme variable so the night shades follow the dark theme.
 */
const TONE_COLOR: Record<ReturnType<typeof toneOf>, string> = {
  urgent: "var(--color-go-danger-strong)",
  warning: "var(--color-go-warning-text)",
  good: "var(--color-go-teal)",
  info: "var(--color-go-info)",
};

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
  nextRun = null,
  depotCheckIn = null,
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
  onOpenMessages,
  loadFuelPass,
}: DriverHomeProps): React.JSX.Element {
  const [lang, setLang] = useDeviceLang();
  const [activeAudioId, setActiveAudioId] = useState<string | null>(null);
  const [fuelQr, setFuelQr] = useState(false);
  const [markingRead, setMarkingRead] = useState(false);
  const { scrollRef, pullY, isPulling, compact, maskStyle, handlers } = useRubberBandScroll();
  useScrollMemory("driver:home", scrollRef, { page: false });
  useCappedScroll(scrollRef);

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

        {/* Driver Identity Card (Figma "Driver card"). Scrolling the voices folds it up. */}
        <div className="shrink-0 px-6 pt-1">
          <div className={cx("w-full shadow-[0px_5px_20px_rgba(0,0,0,0.05)] flex flex-col transition-all duration-300", card, "rounded-[38px] p-5 sm:p-6", compact ? "gap-0" : "gap-3.5")}>
            <div className="flex items-center gap-3.5">
              <div
                className={cx(
                  "rounded-full flex items-center justify-center shrink-0 transition-all duration-300",
                  "size-[52px]",
                  isNight ? "bg-[#00BF6A]" : "bg-[#B7F2ED]"
                )}
              >
                <span className="text-[18px] font-medium text-black">{initials}</span>
              </div>
              <div className="flex flex-col min-w-0 flex-1">
                <h1 className={cx("truncate text-[26px] font-medium leading-[33px] tracking-tight", ink)}>{driverName}</h1>
                <p className="truncate pt-0.5 text-[13px] font-light leading-none text-[#A9A9A9]">
                  Driver{depotName ? ` · ${depotName} depot` : ""}
                </p>
              </div>
              {compact && stopCount > 0 && (
                <button
                  type="button"
                  onClick={startTrip}
                  aria-label={startLabel}
                  className={cx(
                    "flex size-11 shrink-0 animate-pop-in items-center justify-center rounded-full active:scale-[0.96] motion-reduce:animate-none",
                    isNight ? "bg-[#00BF6A] text-black" : "bg-[#031B08] text-white"
                  )}
                >
                  {tripStatus === "completed" ? (
                    <svg aria-hidden width="20" height="20" viewBox="0 0 20 20" fill="none">
                      <path d="M4 10h11M11 5l5 5-5 5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  ) : (
                    <svg aria-hidden width="20" height="20" viewBox="0 0 20 20" fill="currentColor">
                      <path d="M6.5 4.2v11.6a.8.8 0 0 0 1.2.7l9-5.8a.8.8 0 0 0 0-1.4l-9-5.8a.8.8 0 0 0-1.2.7Z" />
                    </svg>
                  )}
                </button>
              )}
            </div>

            <div
              aria-hidden={compact}
              inert={compact}
              className={cx(
                "min-h-0 overflow-hidden transition-[max-height,opacity] duration-300",
                compact ? "max-h-0 opacity-0" : "max-h-[640px] opacity-100"
              )}
            >
            <div className="flex flex-col gap-3.5">
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
                    <button
                      type="button"
                      onClick={onProblem}
                      className={cx(
                        "flex h-9 shrink-0 items-center justify-center rounded-full px-4 text-[13px] font-medium active:scale-[0.98]",
                        isNight ? "bg-[#3A3A3A] text-white" : "bg-[#B7F2ED] text-black"
                      )}
                    >
                      Report problem
                    </button>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <button
                      type="button"
                      role="switch"
                      aria-checked={vehicleStatus !== "at_workshop" && vehicleStatus !== "fault"}
                      aria-label="Available"
                      onClick={() => onVehicleStatus((vehicleStatus === "at_workshop" || vehicleStatus === "fault" ? "available" : "at_workshop") as ReportedVehicleStatus)}
                      className="flex items-center gap-2"
                    >
                      <span
                        aria-hidden
                        className={cx(
                          "flex h-7 w-12 items-center rounded-full p-0.5 transition-colors",
                          vehicleStatus === "at_workshop" || vehicleStatus === "fault" ? "justify-start bg-[#d5d5d5]" : "justify-end bg-[#22c55e]",
                        )}
                      >
                        <span className="size-6 rounded-full bg-white shadow" />
                      </span>
                      <span className={cx("text-[15px] font-medium", ink)}>Available</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => setFuelQr(true)}
                      className={cx(
                        "flex h-10 shrink-0 items-center gap-2 rounded-full px-3 text-[14px] font-medium active:scale-[0.98]",
                        isNight ? "bg-[#292929] text-white" : "bg-white text-black",
                      )}
                    >
                      <svg aria-hidden width="18" height="18" viewBox="0 0 18 18" fill="currentColor">
                        <rect x="1" y="1" width="6" height="6" rx="1.2" />
                        <rect x="11" y="1" width="6" height="6" rx="1.2" />
                        <rect x="1" y="11" width="6" height="6" rx="1.2" />
                        <rect x="11" y="11" width="2.4" height="2.4" rx="0.4" />
                        <rect x="14.6" y="11" width="2.4" height="2.4" rx="0.4" />
                        <rect x="11" y="14.6" width="2.4" height="2.4" rx="0.4" />
                        <rect x="14.6" y="14.6" width="2.4" height="2.4" rx="0.4" />
                      </svg>
                      Fuel QR
                    </button>
                  </div>
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
                ) : depotCheckIn && !depotCheckIn.done ? (
                  // R-EXE-24: today's trip waits for the loader. Collect the vehicle,
                  // say so here, and a loader takes the trip to load it.
                  <>
                    <p role="status" className={cx("text-[14px] leading-[18px]", ink)}>
                      {nextRun?.departure
                        ? `Today's trip on ${depotCheckIn.vehicleId} departs ${hhmm(nextRun.departure)}. Collect the vehicle and tell the loader you are at the depot.`
                        : `Today's trip on ${depotCheckIn.vehicleId} is planned. Collect the vehicle and tell the loader you are at the depot.`}
                    </p>
                    <button
                      type="button"
                      onClick={depotCheckIn.report}
                      className={cx(
                        "w-full h-[64px] rounded-[22px] text-[20px] font-medium flex items-center justify-center transition-all active:scale-[0.99] shadow-sm",
                        isNight ? "bg-[#00BF6A] text-black hover:bg-[#00d878]" : "bg-[#031B08] text-white hover:bg-[#062613]"
                      )}
                    >
                      I&apos;m at the depot
                    </button>
                  </>
                ) : (
                  <p role="status" className={cx("text-[14px] leading-[18px]", ink)}>
                    {depotCheckIn?.done
                      ? `At the depot with ${depotCheckIn.vehicleId}. The loader is loading it; your stops appear here when it is released.`
                      : nextRun && !nextRun.released && nextRun.vehicleId && nextRun.departure
                        ? `Next trip ${dayLabel(nextRun.date)} · ${nextRun.vehicleId} · departs ${hhmm(nextRun.departure)}. It is planned and waiting for the loader to release it; your stops appear here then.`
                        : `No trip planned for ${vehicle.vehicleId} today. Your stops appear here when the loader releases the vehicle.`}
                  </p>
                )}
              </>
            )}
            </div>
            </div>
          </div>
        </div>

        {/* Notifications Header (Bell icon, "Notifications" text, unread badge) */}
        <div className={cx("flex items-center justify-between px-8 shrink-0 transition-all duration-300", compact ? "py-2" : "py-5")}>
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
              disabled={markingRead}
              onClick={() => {
                setMarkingRead(true);
                void inbox.markAllRead(now).finally(() => setMarkingRead(false));
              }}
              aria-label={markingRead ? "Marking all read" : `${unread} new. Mark all read`}
              aria-busy={markingRead || undefined}
              className={cx(
                "text-[12px] font-medium px-2.5 py-0.5 rounded-[34px] transition-colors disabled:cursor-wait disabled:opacity-70",
                isNight ? "bg-[#00BF6A] text-black" : "bg-[#B7F2ED] text-black"
              )}
            >
              {markingRead ? "Marking…" : `${unread > 99 ? "99+" : unread} new`}
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
          className="h-full touch-none overflow-y-auto overscroll-contain pb-6 short:h-auto short:touch-auto short:overflow-visible no-scrollbar [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
        >
          <div
            style={{
              transform: `translate3d(0, ${pullY}px, 0)`,
              transition: isPulling ? "none" : "transform 250ms cubic-bezier(0.18, 1.12, 0.32, 1.0)",
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
              // One palette and one wording for every role's inbox (shared/notifications/inbox.ts).
              const tone = toneOf(item);
              const kind = { label: kindOf(item.eventType).label, color: TONE_COLOR[tone] };
              const id = `msg-${item.notificationId}`;
              return (
                <ScrollRevealCard key={item.notificationId} scrollContainerRef={scrollRef} pullY={pullY}>
                  <div
                      className={cx(
                      "rounded-[20px] p-[14px_20px] flex flex-col gap-1 transition-all duration-200 border-l-4",
                      TONE_STYLE[tone].edge,
                      isUnread(item) ? TONE_STYLE[tone].tint : card
                    )}
                  >
                    {item.eventType === "message.posted" && item.facts?.voiceNoteId && item.subjectId ? (
                      // A voice message plays the person's own voice, never text to speech (issue #136).
                      <div className={cx("mt-1 rounded-[16px] px-2 py-1", isNight ? "bg-[#1f1f1f]" : "bg-[#F1F6F5]")}>
                        <VoiceNote
                          src={voiceUrl(item.subjectId, item.facts.voiceNoteId)}
                          durationMs={item.facts.voiceDurationMs ? Number(item.facts.voiceDurationMs) : null}
                          {...(item.facts.voicePeaks ? { peaks: item.facts.voicePeaks.split(",").map((p) => Number(p) / 100) } : {})}
                          seed={item.facts.voiceNoteId}
                          tone="theirs"
                          label={`Voice message from ${item.title}`}
                          heading={item.title}
                          caption={item.body}
                        />
                      </div>
                    ) : (
                      <VoiceMessagePlayer
                        id={id}
                        duration={readingTime(`${item.title} ${item.body}`)}
                        text={`${kind.label}. ${item.title}. ${item.body}`}
                        heading={item.title}
                        status={kind.label}
                        statusColor={kind.color}
                        statusTime={clock(item.createdAt)}
                        message={item.body}
                        isNight={isNight}
                        activeAudioId={activeAudioId}
                        onPlayChange={setActiveAudioId}
                      />
                    )}
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
      {fuelQr && vehicle && (
        <FuelPassSheet vehicleId={vehicle.vehicleId} kind={vehicle.van ? "Van" : "Truck"} load={loadFuelPass} onClose={() => setFuelQr(false)} />
      )}
    </div>
  );
}
