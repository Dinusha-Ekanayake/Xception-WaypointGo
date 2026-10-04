"use client";

import { useEffect, useState, useCallback, useRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { PRESS, SettingsPanel, Spinner, cx, initialsOf } from "@shared/ui";

/** Waveform bar heights exactly copied from the Figma specification */
export const WAVEFORM_HEIGHTS = [
  6, 10, 16, 12, 20, 14, 8, 18, 24, 16, 10, 14, 20, 12, 8, 16, 22, 14, 10, 6, 12, 18, 14, 8, 10, 16, 12, 6,
] as const;

export type SupportedLang = "en" | "si" | "ta";

/** The driver's initials; tapping them opens Settings as a bottom sheet. Sign out is the last row, in red. */
function DriverProfileButton({
  displayName,
  lang,
  onLang,
  onSignOut,
  isNight,
}: {
  displayName: string;
  lang: SupportedLang;
  onLang?: (l: SupportedLang) => void;
  onSignOut?: () => void;
  isNight: boolean;
}): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);
  // The header scales its controls, and a transformed ancestor traps a fixed
  // sheet inside it, so the sheet renders into the phone frame instead, where
  // it rises from the bottom of the phone like the sign-out sheet.
  const host = open ? (document.getElementById("driver-content") ?? button.current?.closest<HTMLElement>("[data-theme]") ?? document.body) : null;
  return (
    <>
      <button
        ref={button}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`Settings: ${displayName}`}
        onClick={() => setOpen(true)}
        className={cx(
          "w-[42px] h-[42px] rounded-full flex items-center justify-center border text-[14px] font-semibold shadow-[0_5px_20px_rgba(0,0,0,0.05)] active:scale-95 transition-all",
          isNight ? "bg-[#292929] border-[#383838] text-white" : "bg-go-mint border-[#dfe7e6] text-black",
        )}
      >
        {initialsOf(displayName)}
      </button>
      {host &&
        createPortal(
          <SettingsPanel displayName={displayName} roleLabel="Driver" lang={lang} onLang={(l) => onLang?.(l)} placement="frame" showInstall={false} onClose={() => setOpen(false)}>
            {onSignOut && (
              <button
                type="button"
                onClick={() => {
                  setOpen(false);
                  onSignOut();
                }}
                className="flex h-11 shrink-0 items-center gap-2 rounded-full border border-go-danger-strong px-4 text-[15px] font-medium text-go-danger-strong active:scale-95"
              >
                <svg aria-hidden width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                  <polyline points="16 17 21 12 16 7" />
                  <line x1="21" y1="12" x2="9" y2="12" />
                </svg>
                Sign out
              </button>
            )}
          </SettingsPanel>,
          host,
        )}
    </>
  );
}

/** Driver Header with GO logo, Settings (picture, with Sign out inside), and Theme toggle */
export function DriverHeader({
  lang = "en",
  onToggleLang,
  displayName,
  onSignOut,
  onToggleTheme,
  isNight = false,
}: {
  lang?: SupportedLang;
  onToggleLang?: (l: SupportedLang) => void;
  /** The signed-in driver; their picture opens Settings. */
  displayName?: string;
  onSignOut?: () => void;
  onToggleTheme?: () => void;
  isNight?: boolean;
}): React.JSX.Element {
  // Show the other two languages that the user can switch to

  return (
    <header className="driver-header w-full flex items-center justify-between px-6 pt-5 pb-3 shrink-0 select-none transition-all duration-300">
      {/* Big Bold GO wordmark */}
      <span
        className={cx(
          "text-[40px] font-extrabold tracking-tight leading-none font-go transition-colors",
          isNight ? "text-white" : "text-black"
        )}
      >
        GO
      </span>

      {/* Right controls */}
      <div className="flex items-center gap-2">
        {/* The driver's picture opens Settings: language and the assistant connection. */}
        {displayName && <DriverProfileButton displayName={displayName} lang={lang} onLang={onToggleLang} onSignOut={onSignOut} isNight={isNight} />}

        {/* Theme toggle button */}
        <button
          type="button"
          onClick={onToggleTheme}
          className={cx(
            "w-[42px] h-[42px] rounded-[22px] flex items-center justify-center border shadow-[0_5px_20px_rgba(0,0,0,0.09)] active:scale-95 transition-all",
            isNight
              ? "bg-[#292929] border-[#383838] text-white hover:bg-[#333333]"
              : "bg-white border-[#dfe7e6] text-black hover:bg-slate-50"
          )}
          title={isNight ? "Switch to day mode" : "Switch to night mode"}
          aria-label={isNight ? "Switch to day mode" : "Switch to night mode"}
        >
          {isNight ? (
            /* Moon icon */
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />
            </svg>
          ) : (
            /* Sun with rays radiating icon from Figma */
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
      </div>
    </header>
  );
}

/**
 * Driver morphing header shared across Driver Home and Route Next Stop.
 * - The Dark/Light theme toggle stays strictly stationary in place (no slide or scale).
 * - Left slot: GO logo zooms out while < Back button pops up in its place.
 * - Middle-right slot: the Settings picture zooms out while the Synced pill pops up. Sign out lives in Settings.
 * - No slide effect is applied to any of these controls.
 */
export function DriverMorphHeader({
  activeScreen,
  syncLabel = "Connecting",
  onBack,
  lang = "en",
  onToggleLang,
  displayName,
  onSignOut,
  onToggleTheme,
  isNight = false,
}: {
  activeScreen: "home" | "route-next-stop" | "route-map";
  /** Whether this phone is in step with the server, in words. */
  syncLabel?: string;
  onBack?: () => void;
  lang?: SupportedLang;
  onToggleLang?: (l: SupportedLang) => void;
  /** The signed-in driver; their picture opens Settings. */
  displayName?: string;
  onSignOut?: () => void;
  onToggleTheme?: () => void;
  isNight?: boolean;
  /** The trip's thread (issue #136): an icon beside the theme, on every screen with a trip. */
  onMessages?: () => void;
  unreadMessages?: number;
}): React.JSX.Element {
  const isHome = activeScreen === "home";
  const isRoute = activeScreen === "route-next-stop";
  const isMap = activeScreen === "route-map";
  const isRouteOrMap = isRoute || isMap;

  return (
    <header className="driver-morph-header w-full flex items-center justify-between px-6 pt-5 pb-3 shrink-0 select-none pointer-events-none relative z-30 transition-all duration-300">
      {/* 1. Left slot: GO Logo <-> Back button (morphing via zoom out / pop up) */}
      <div className="relative h-[43px] flex items-center min-w-[90px]">
        {/* GO Wordmark */}
        <span
          className={cx(
            "absolute left-0 text-[40px] font-extrabold tracking-tight leading-none font-go transition-all duration-300 transform-gpu origin-left",
            isHome
              ? "scale-100 opacity-100 pointer-events-auto"
              : "scale-0 opacity-0 pointer-events-none",
            isNight ? "text-white" : "text-black"
          )}
        >
          GO
        </span>

        {/* Back Button */}
        <button
          type="button"
          onClick={onBack}
          className={cx(
            "absolute left-0 flex items-center gap-2 h-[43px] px-3.5 rounded-full text-[17px] font-medium leading-none transition-all duration-300 transform-gpu origin-left active:scale-95 pointer-events-auto",
            // The enlarged map draws its own Back on the map, above the tiles.
            isRoute
              ? "scale-100 opacity-100"
              : "scale-0 opacity-0 pointer-events-none",
            isNight
              ? "text-white bg-transparent hover:opacity-80"
              : "text-black bg-transparent hover:opacity-80"
          )}
          aria-label="Go back to home"
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
      </div>

      {/* 2. Right slot: Language+SignOut <-> Synced pill, plus stationary Theme Toggle */}
      <div className="flex items-center gap-2 pointer-events-auto">
        {/* Morphing area: Language + SignOut vs Synced pill */}
        <div className="relative h-[43px] flex items-center justify-end">
          {/* Home Controls: Language Toggle + Sign Out */}
          <div
            className={cx(
              "flex items-center gap-2 transition-all duration-300 transform-gpu origin-right",
              isHome
                ? "scale-100 opacity-100 pointer-events-auto"
                : "scale-0 opacity-0 pointer-events-none absolute right-0"
            )}
          >
            {/* The driver's picture opens Settings: language and the assistant connection. */}
            {displayName && <DriverProfileButton displayName={displayName} lang={lang} onLang={onToggleLang} onSignOut={onSignOut} isNight={isNight} />}
          </div>

          {/* Route & Map Controls: sync pill */}
          <div
            className={cx(
              "w-[125px] h-[43px] rounded-[25px] flex items-center justify-center transition-all duration-300 transform-gpu origin-right",
              isRouteOrMap
                ? "scale-100 opacity-100 pointer-events-auto"
                : "scale-0 opacity-0 pointer-events-none absolute right-0",
              isNight
                ? "bg-[#292929] text-white shadow-[0px_5px_20px_rgba(0,0,0,0.09)]"
                : "bg-white text-black shadow-[0px_4px_16px_rgba(0,0,0,0.08)]"
            )}
          >
            <span className="text-[15px] font-medium leading-[19px] tracking-tight">
              {syncLabel}
            </span>
          </div>
        </div>

        {/* Messages on the trip's thread, with the count of new ones (issue #136). */}
        {/* 3. Theme toggle button - strictly stationary, NO slide, NO scale */}
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
      </div>
    </header>
  );
}

/**
 * "I've arrived" is a slide, not a tap: a touch that does not cross the track
 * springs back, so a bump in the cab does not record an arrival.
 * It records on release, not as the circle passes the mark, so the driver can
 * still slide back.
 */
export function SlideToConfirm({
  label,
  doneLabel,
  done,
  isNight,
  onConfirm,
  id,
}: {
  label: string;
  doneLabel: string;
  done: boolean;
  isNight: boolean;
  onConfirm: () => void;
  id?: string;
}): React.JSX.Element {
  const track = useRef<HTMLDivElement>(null);
  const [x, setX] = useState(0);
  const dragging = useRef(false);
  const sent = useRef(false);
  const limit = () => Math.max(0, (track.current?.clientWidth ?? 0) - 64);

  const release = (at: number) => {
    dragging.current = false;
    const max = limit();
    if (!sent.current && max > 0 && at >= max * 0.8) {
      sent.current = true;
      setX(max);
      onConfirm();
    } else if (!sent.current) {
      setX(0);
    }
  };

  if (done) {
    return (
      <div id={id} className="flex h-[64px] w-full items-center justify-center rounded-full bg-[#0E766D] text-[20px] font-medium text-white">
        {doneLabel}
      </div>
    );
  }

  return (
    <div
      id={id}
      ref={track}
      role="slider"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={limit() === 0 ? 0 : Math.round((x / limit()) * 100)}
      aria-valuetext="Slide across to confirm"
      tabIndex={0}
      onKeyDown={(e) => {
        if ((e.key === "ArrowRight" || e.key === "Enter") && !sent.current) {
          e.preventDefault();
          sent.current = true;
          onConfirm();
        }
      }}
      onPointerDown={(e) => {
        if (sent.current) return;
        dragging.current = true;
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
      }}
      onPointerMove={(e) => {
        if (!dragging.current || sent.current || !track.current) return;
        const rect = track.current.getBoundingClientRect();
        const next = Math.max(0, Math.min(e.clientX - rect.left - 32, limit()));
        setX(next);
      }}
      onPointerUp={(e) => {
        if (!dragging.current) return;
        const rect = track.current?.getBoundingClientRect();
        const at = rect ? Math.max(0, Math.min(e.clientX - rect.left - 32, limit())) : x;
        release(at);
      }}
      onPointerCancel={() => release(0)}
      className={cx(
        "relative h-[64px] w-full touch-none overflow-hidden rounded-full select-none outline-none focus-visible:ring-2 focus-visible:ring-go-signal",
        isNight ? "bg-[#00BF6A]" : "bg-[#031B08]",
      )}
    >
      <span className={cx("pointer-events-none absolute inset-0 flex items-center justify-center pl-10 text-[18px] font-medium", isNight ? "text-black" : "text-white")}>
        {label}
      </span>
      <span
        aria-hidden
        className="absolute top-1 left-1 flex size-14 items-center justify-center rounded-full bg-white text-black shadow"
        style={{ transform: `translateX(${x}px)`, transition: dragging.current ? "none" : "transform 200ms var(--ease-go-out)" }}
      >
        {/* As the problem sheet's rows: three chevrons brighten in turn, to say which way to slide. */}
        <svg width="28" height="18" viewBox="0 0 22 14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <path d="M2.5 2l5 5-5 5" className="animate-chevron motion-reduce:animate-none" />
          <path d="M8.5 2l5 5-5 5" className="animate-chevron motion-reduce:animate-none" style={{ animationDelay: "0.15s" }} />
          <path d="M14.5 2l5 5-5 5" className="animate-chevron motion-reduce:animate-none" style={{ animationDelay: "0.3s" }} />
        </svg>
      </span>
    </div>
  );
}

/** Interactive Voice Message audio player with waveform */
export type VoiceMessagePlayerProps = {
  id?: string;
  duration?: string;
  initialBars?: readonly number[];
  isNight?: boolean;
  text?: string;
  /** Sits on the same row as the play button, under the status. */
  heading?: string;
  /** The short status, such as "Vehicle loaded", on the right of the play button. */
  status?: string;
  statusColor?: string;
  /** The time of the notice, opposite the status. */
  statusTime?: string;
  /** The words under that row, starting at the left edge. */
  message?: string;
  activeAudioId?: string | null;
  onPlayChange?: (id: string | null) => void;
  className?: string;
  /** Bars between play and the length, filling as it plays. Off where a heading takes that row. */
  waveform?: boolean;
};

const WAVE_BARS = 32;

/** Bar heights (0.2-1) drawn from the words, so one message always shows the same shape. */
function waveShape(seed: string): number[] {
  let h = 2166136261;
  for (let i = 0; i < seed.length; i += 1) h = Math.imul(h ^ seed.charCodeAt(i), 16777619);
  return Array.from({ length: WAVE_BARS }, (_, i) => {
    h = Math.imul(h ^ (h >>> 15), 2246822507) ^ i;
    const noise = ((h >>> 0) % 1000) / 1000;
    const swell = Math.sin((i / (WAVE_BARS - 1)) * Math.PI);
    return 0.2 + 0.8 * Math.max(0, Math.min(1, 0.35 * swell + 0.65 * noise));
  });
}

/** Interactive Voice Message audio player with waveform and text-to-speech support */
export function VoiceMessagePlayer({
  id,
  duration = "0:18",
  isNight = false,
  text,
  heading,
  status,
  statusColor,
  statusTime,
  message,
  activeAudioId,
  onPlayChange,
  className,
  waveform = false,
  initialBars,
}: VoiceMessagePlayerProps): React.JSX.Element {
  const [internalIsPlaying, setInternalIsPlaying] = useState(false);
  const isPlaying =
    id !== undefined && activeAudioId !== undefined
      ? activeAudioId === id
      : internalIsPlaying;

  const [progress, setProgress] = useState(0);
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);
  const utteranceRef = useRef<SpeechSynthesisUtterance | null>(null);
  const startedAt = useRef(0);

  // Sync internal state when external activeAudioId changes
  useEffect(() => {
    if (id && activeAudioId !== undefined) {
      if (activeAudioId !== id && internalIsPlaying) {
        setInternalIsPlaying(false);
      }
    }
  }, [id, activeAudioId, internalIsPlaying]);

  // Clean up speech synthesis and timer on unmount
  useEffect(() => {
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      if (typeof window !== "undefined" && "speechSynthesis" in window) {
        if (utteranceRef.current) {
          window.speechSynthesis.cancel();
          utteranceRef.current = null;
        }
      }
    };
  }, []);

  // Parse duration string like "0:18" or "0:06" to seconds
  const parseDurationSec = (durStr: string): number => {
    const parts = durStr.split(":").map(Number);
    if (parts.length === 2 && !isNaN(parts[0]) && !isNaN(parts[1])) {
      return parts[0] * 60 + parts[1];
    }
    return 6;
  };

  const stopPlayback = useCallback(() => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
    if (typeof window !== "undefined" && "speechSynthesis" in window) {
      window.speechSynthesis.cancel();
      utteranceRef.current = null;
    }
    setInternalIsPlaying(false);
    setProgress(0);
    if (id && onPlayChange && activeAudioId === id) {
      onPlayChange(null);
    }
  }, [id, activeAudioId, onPlayChange]);

  const startPlayback = useCallback(() => {
    // Stop any existing speech or timer first
    if (typeof window !== "undefined" && "speechSynthesis" in window) {
      window.speechSynthesis.cancel();
    }
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }

    setInternalIsPlaying(true);
    setProgress(0);
    startedAt.current = Date.now();
    if (id && onPlayChange) {
      onPlayChange(id);
    }

    const durationSeconds = parseDurationSec(duration);

    if (text && typeof window !== "undefined" && "speechSynthesis" in window) {
      try {
        const utterance = new SpeechSynthesisUtterance(text);
        utterance.lang = "en-US";
        utterance.rate = 0.95;
        utterance.pitch = 1.0;

        const voices = window.speechSynthesis.getVoices();
        const preferredVoice =
          voices.find(
            (v) =>
              v.lang.startsWith("en") &&
              (v.name.includes("Natural") ||
                v.name.includes("Google") ||
                v.name.includes("Samantha") ||
                v.name.includes("Daniel") ||
                v.name.includes("Karen") ||
                v.name.includes("Moira"))
          ) || voices.find((v) => v.lang.startsWith("en"));

        if (preferredVoice) {
          utterance.voice = preferredVoice;
        }

        utterance.onend = () => {
          utteranceRef.current = null;
          setInternalIsPlaying(false);
          setProgress(0);
          if (id && onPlayChange) {
            onPlayChange(null);
          }
        };

        utterance.onerror = (e) => {
          console.warn("Speech synthesis notice:", e);
          utteranceRef.current = null;
          setInternalIsPlaying(false);
          setProgress(0);
          if (id && onPlayChange) {
            onPlayChange(null);
          }
        };

        utteranceRef.current = utterance;
        window.speechSynthesis.speak(utterance);
      } catch (err) {
        console.warn("Speech synthesis fallback:", err);
        timeoutRef.current = setTimeout(() => {
          stopPlayback();
        }, durationSeconds * 1000);
      }
    } else {
      timeoutRef.current = setTimeout(() => {
        stopPlayback();
      }, durationSeconds * 1000);
    }
  }, [id, duration, text, onPlayChange, stopPlayback]);

  // The ring around play fills with the message, so the old waveform row can show the words.
  useEffect(() => {
    if (!isPlaying) return;
    const ms = parseDurationSec(duration) * 1000;
    let frame = 0;
    const tick = () => {
      setProgress(Math.min(1, (Date.now() - startedAt.current) / ms));
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [isPlaying, duration]);

  const handleToggle = () => {
    if (isPlaying) {
      stopPlayback();
    } else {
      startPlayback();
    }
  };

  const ring = isNight ? "#00BF6A" : "#031A0C";
  const words = message ?? (!heading ? text : undefined);
  const bars = waveform && !heading && !status ? (initialBars ? [...initialBars] : waveShape(text ?? duration)) : null;
  return (
    <div className={cx("flex w-full flex-col gap-1 pt-1", className)}>
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleToggle}
          className="relative flex size-11 shrink-0 items-center justify-center active:scale-95"
          aria-label={isPlaying ? "Pause message audio" : "Listen to message"}
          title={isPlaying ? "Pause message" : "Listen to message"}
        >
          <span
            aria-hidden
            className="absolute inset-0 rounded-full"
            style={{ background: `conic-gradient(${ring} ${Math.round(progress * 360)}deg, ${isNight ? "rgba(255,255,255,0.2)" : "rgba(0,0,0,0.12)"} 0deg)` }}
          />
          <span className={cx("relative flex size-8 items-center justify-center rounded-full", isNight ? "bg-[#00BF6A] text-black" : "bg-[#031A0C] text-white")}>
            {isPlaying ? (
              <span className="flex items-center gap-1">
                <span className="h-3.5 w-1 rounded-full bg-current" />
                <span className="h-3.5 w-1 rounded-full bg-current" />
              </span>
            ) : (
              <svg width="12" height="15" viewBox="0 0 12 15" fill="none" className="translate-x-[1px]">
                <path d="M11 7.5L1 1.7V13.3L11 7.5Z" fill="currentColor" />
              </svg>
            )}
          </span>
        </button>
        {bars && (
          <span aria-hidden className="flex h-7 min-w-0 flex-1 items-center justify-between px-1">
            {bars.map((height, i) => (
              <span
                key={i}
                className="w-[3px] shrink-0 rounded-full transition-colors duration-150"
                style={{
                  height: `${Math.round(height * 100)}%`,
                  background: (i + 0.5) / bars.length <= progress ? ring : isNight ? "rgba(255,255,255,0.28)" : "rgba(0,0,0,0.18)",
                }}
              />
            ))}
          </span>
        )}
        <span className={cx("flex min-w-0 flex-col gap-0.5", bars ? "hidden" : "flex-1")}>
          {status && (
            <span className="flex items-center justify-between gap-2">
              <span className="truncate text-[12px] font-medium" style={{ color: statusColor ?? (isNight ? "#fff" : "#000") }}>{status}</span>
              {statusTime && <span className="shrink-0 text-[12px] font-light text-[#A9A9A9]">{statusTime}</span>}
            </span>
          )}
          {heading && (
            <span className="flex items-center justify-between gap-2">
              <span className={cx("min-w-0 flex-1 text-left text-[15px] font-medium leading-5", isNight ? "text-white" : "text-black")}>{heading}</span>
              <span className="shrink-0 font-go text-[13px] font-normal text-[#A9A9A9]">{duration}</span>
            </span>
          )}
        </span>
        {!heading && <span className="shrink-0 font-go text-[13px] font-normal text-[#A9A9A9]">{duration}</span>}
      </div>
      {words && <p className={cx("text-left text-[13px] font-light leading-4", isNight ? "text-white" : "text-black")}>{words}</p>}
    </div>
  );
}


// The driver's building blocks, from Figma "12 · Driver · Mobile". Everything is
// drawn from theme tokens, so the dark theme is the same markup. Touch targets
// are at least 56px: the phone is used one-handed, when safely stopped.

/** `busy`: the command is on its way, so the button is disabled with a spinner before the label. */
type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { children: ReactNode; busy?: boolean };

const big = cx("flex min-h-16 w-full items-center justify-center gap-2 rounded-[22px] px-5 text-[19px] font-medium disabled:opacity-50", PRESS);

function DriverButton({ children, className, type = "button", busy = false, disabled, look, ...rest }: ButtonProps & { look: string }): React.JSX.Element {
  return (
    <button type={type} {...rest} disabled={disabled || busy} aria-busy={busy || undefined} className={cx(big, look, busy && "disabled:opacity-70", className)}>
      {busy && <Spinner />}
      {children}
    </button>
  );
}

export function ActionButton(props: ButtonProps): React.JSX.Element {
  return <DriverButton {...props} look="bg-go-action text-go-on-action" />;
}

export function SoftButton(props: ButtonProps): React.JSX.Element {
  return <DriverButton {...props} look="bg-go-soft text-go-on-soft" />;
}

export function OutlineButton(props: ButtonProps): React.JSX.Element {
  return <DriverButton {...props} look="border border-go-ink bg-transparent text-go-ink" />;
}

/**
 * The primary action of a long form, pinned to the bottom of the scrolling
 * layer on a phone so it is always in reach; the form scrolls under it. From
 * the tablet column up it sits in the flow as drawn. `on` is the surface it
 * sits on, the page or a panel, so the content passing under it is hidden.
 */
export function PinnedAction({ on, className, children }: { on: "canvas" | "card"; className?: string; children: ReactNode }): React.JSX.Element {
  return (
    <div
      className={cx(
        "max-md:sticky max-md:bottom-0 max-md:z-10 max-md:pt-3 max-md:pb-[max(1rem,env(safe-area-inset-bottom))] max-md:shadow-[0_-8px_16px_rgba(0,0,0,0.04)]",
        on === "canvas" ? "max-md:-mx-5 max-md:bg-go-canvas max-md:px-5" : "max-md:-mx-6 max-md:bg-go-card max-md:px-6",
        className,
      )}
    >
      {children}
    </div>
  );
}

export function Panel({ children, className, label }: { children: ReactNode; className?: string; label?: string }): React.JSX.Element {
  return (
    <section aria-label={label} className={cx("rounded-[28px] bg-go-card p-6 shadow-go-card", className)}>
      {children}
    </section>
  );
}

/** A round control in the top bar: 48px, with a name for assistive technology. */
export function RoundButton({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }): React.JSX.Element {
  return (
    <button
      type="button"
      aria-label={label}
      onClick={onClick}
      className="flex size-12 shrink-0 items-center justify-center rounded-full bg-go-card text-go-ink shadow-go-float"
    >
      {children}
    </button>
  );
}

const TONES = {
  neutral: "bg-go-surface text-go-ink",
  good: "bg-go-success-tint text-go-success",
  warn: "bg-go-warning-tint text-go-warning-text",
  bad: "bg-go-danger-tint text-go-danger-strong",
} as const;

export function Tag({ tone = "neutral", children }: { tone?: keyof typeof TONES; children: ReactNode }): React.JSX.Element {
  return <span className={cx("inline-flex min-h-7 shrink-0 items-center whitespace-nowrap rounded-full px-3 text-[13px] font-medium", TONES[tone])}>{children}</span>;
}

export function Banner({ tone, title, children, live = false }: { tone: keyof typeof TONES; title: string; children?: ReactNode; live?: boolean }): React.JSX.Element {
  return (
    <div role={live ? "status" : undefined} aria-live={live ? "polite" : undefined} className={cx("rounded-[18px] px-4 py-3 text-[15px]", TONES[tone])}>
      <p className="font-medium">{title}</p>
      {children && <p className="mt-0.5 opacity-90">{children}</p>}
    </div>
  );
}

export function Field({
  label,
  hint,
  error,
  errorId,
  children,
}: {
  label: string;
  hint?: string;
  /** Shown in red under the field; pair with `errorId` and the input's own `aria-describedby`. */
  error?: string;
  /** The id the error text is given, so the input can point `aria-describedby` at it. */
  errorId?: string;
  children: ReactNode;
}): React.JSX.Element {
  return (
    <label className="flex flex-col gap-1.5 text-[15px] text-go-ink">
      <span className="font-medium">{label}</span>
      {children}
      {hint && <span className="text-[13px] text-go-muted">{hint}</span>}
      {error && (
        <span id={errorId} role="alert" className="text-[13px] text-go-danger-strong">
          {error}
        </span>
      )}
    </label>
  );
}

export const input =
  "min-h-14 w-full rounded-[14px] border border-go-rule bg-go-subtle px-4 text-[17px] text-go-ink placeholder:text-go-placeholder focus:border-go-teal focus:outline-none";

// ---- icons: drawn inline so they follow the theme's text colour ----

const stroke = { fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round" } as const;

export function SunIcon(): React.JSX.Element {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <circle cx="12" cy="12" r="4.5" />
      <path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4" />
    </svg>
  );
}

export function MoonIcon(): React.JSX.Element {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z" />
    </svg>
  );
}

export function SignOutIcon(): React.JSX.Element {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M15 8l4 4-4 4M19 12H9" />
    </svg>
  );
}

export function BackIcon(): React.JSX.Element {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <path d="M15 5l-7 7 7 7" />
    </svg>
  );
}

export function CheckIcon({ size = 36 }: { size?: number }): React.JSX.Element {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" {...stroke} strokeWidth={2.4}>
      <path d="M5 12.5l4.5 4.5L19 7.5" />
    </svg>
  );
}

export function CalendarOffIcon(): React.JSX.Element {
  return (
    <svg width="30" height="30" viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      <rect x="4" y="5.5" width="16" height="15" rx="3" />
      <path d="M8 3.5v4M16 3.5v4M4 10h16M10 13.5l4 4M14 13.5l-4 4" />
    </svg>
  );
}
