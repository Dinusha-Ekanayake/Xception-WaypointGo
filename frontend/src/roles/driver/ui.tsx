"use client";

import { useEffect, useState, useCallback, useRef, type ReactNode } from "react";
import { cx } from "@shared/ui";

/** Waveform bar heights exactly copied from the Figma specification */
export const WAVEFORM_HEIGHTS = [
  6, 10, 16, 12, 20, 14, 8, 18, 24, 16, 10, 14, 20, 12, 8, 16, 22, 14, 10, 6, 12, 18, 14, 8, 10, 16, 12, 6,
] as const;

export type SupportedLang = "en" | "si" | "ta";

const LANG_CONFIG: Record<
  SupportedLang,
  { label: string; title: string; style?: React.CSSProperties; className?: string }
> = {
  en: {
    label: "EN",
    title: "English",
    className: "text-[13px] font-medium tracking-tight",
  },
  si: {
    label: "සිං",
    title: "Sinhala",
    style: { fontFamily: "var(--font-sinhala), 'UN-Malithi', sans-serif", fontWeight: 400 },
    className: "text-[14px] font-normal",
  },
  ta: {
    label: "த",
    title: "Tamil",
    style: { fontFamily: "'Anek Tamil', sans-serif", fontWeight: 400 },
    className: "text-[15px] font-normal pb-0.5",
  },
};

/** Driver Header with GO logo, Language toggle, Sign out, and Theme toggle */
export function DriverHeader({
  lang = "en",
  onToggleLang,
  onSignOut,
  onToggleTheme,
  isNight = false,
}: {
  lang?: SupportedLang;
  onToggleLang?: (l: SupportedLang) => void;
  onSignOut?: () => void;
  onToggleTheme?: () => void;
  isNight?: boolean;
}): React.JSX.Element {
  // Show the other two languages that the user can switch to
  const availableLangs = (["si", "ta", "en"] as const).filter((l) => l !== lang);

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
        {/* Language toggle: shows the two alternative languages to switch to */}
        <div
          role="group"
          aria-label="Language selection"
          className={cx(
            "flex items-center rounded-full p-[4px] gap-[2px] shadow-[0_5px_20px_2px_rgba(0,0,0,0.05)] border transition-colors",
            isNight ? "bg-[#292929] border-[#383838]" : "bg-white border-[#dfe7e6]"
          )}
        >
          {availableLangs.map((itemLang) => {
            const config = LANG_CONFIG[itemLang];
            return (
              <button
                key={itemLang}
                type="button"
                onClick={() => onToggleLang?.(itemLang)}
                style={config.style}
                className={cx(
                  "w-[36px] h-[34px] rounded-full flex items-center justify-center transition-all active:scale-95",
                  config.className,
                  isNight
                    ? "text-[#A3A3A3] hover:text-white hover:bg-white/10"
                    : "text-[#6B6B6B] hover:text-black hover:bg-black/5"
                )}
                title={config.title}
              >
                {config.label}
              </button>
            );
          })}
        </div>

        {/* Sign out button */}
        {onSignOut && (
          <button
            type="button"
            onClick={onSignOut}
            className={cx(
              "w-[42px] h-[42px] rounded-full flex items-center justify-center border shadow-[0_5px_20px_rgba(0,0,0,0.05)] active:scale-95 transition-all",
              isNight
                ? "bg-[#292929] border-[#383838] text-white hover:bg-[#333333]"
                : "bg-white border-[#dfe7e6] text-black hover:bg-slate-50"
            )}
            title="Sign out"
            aria-label="Sign out"
          >
            {/* Door / right-from-bracket icon */}
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
          </button>
        )}

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
 * - Middle-right slot: Language toggle + Sign out zoom out while the Synced pill pops up.
 * - No slide effect is applied to any of these controls.
 */
export function DriverMorphHeader({
  activeScreen,
  onBack,
  lang = "en",
  onToggleLang,
  onSignOut,
  onToggleTheme,
  isNight = false,
}: {
  activeScreen: "home" | "route-next-stop" | "route-map";
  onBack?: () => void;
  lang?: SupportedLang;
  onToggleLang?: (l: SupportedLang) => void;
  onSignOut?: () => void;
  onToggleTheme?: () => void;
  isNight?: boolean;
}): React.JSX.Element {
  const availableLangs = (["si", "ta", "en"] as const).filter((l) => l !== lang);
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
            isRouteOrMap
              ? "scale-100 opacity-100"
              : "scale-0 opacity-0 pointer-events-none",
            isMap
              ? isNight
                ? "bg-[#292929] text-white shadow-md hover:bg-[#333333]"
                : "bg-white text-black shadow-md hover:bg-slate-50"
              : isNight
                ? "text-white bg-transparent hover:opacity-80"
                : "text-black bg-transparent hover:opacity-80"
          )}
          aria-label={isMap ? "Back to route" : "Go back to home"}
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
            {/* Language toggle */}
            <div
              role="group"
              aria-label="Language selection"
              className={cx(
                "flex items-center rounded-full p-[4px] gap-[2px] shadow-[0_5px_20px_2px_rgba(0,0,0,0.05)] border transition-colors",
                isNight ? "bg-[#292929] border-[#383838]" : "bg-white border-[#dfe7e6]"
              )}
            >
              {availableLangs.map((itemLang) => {
                const config = LANG_CONFIG[itemLang];
                return (
                  <button
                    key={itemLang}
                    type="button"
                    onClick={() => onToggleLang?.(itemLang)}
                    style={config.style}
                    className={cx(
                      "w-[36px] h-[34px] rounded-full flex items-center justify-center transition-all active:scale-95",
                      config.className,
                      isNight
                        ? "text-[#A3A3A3] hover:text-white hover:bg-white/10"
                        : "text-[#6B6B6B] hover:text-black hover:bg-black/5"
                    )}
                    title={config.title}
                  >
                    {config.label}
                  </button>
                );
              })}
            </div>

            {/* Sign out button */}
            {onSignOut && (
              <button
                type="button"
                onClick={onSignOut}
                className={cx(
                  "w-[42px] h-[42px] rounded-full flex items-center justify-center border shadow-[0_5px_20px_rgba(0,0,0,0.05)] active:scale-95 transition-all",
                  isNight
                    ? "bg-[#292929] border-[#383838] text-white hover:bg-[#333333]"
                    : "bg-white border-[#dfe7e6] text-black hover:bg-slate-50"
                )}
                title="Sign out"
                aria-label="Sign out"
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
                  <polyline points="16 17 21 12 16 7" />
                  <line x1="21" y1="12" x2="9" y2="12" />
                </svg>
              </button>
            )}
          </div>

          {/* Route & Map Controls: Synced 05:31 Pill */}
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
              Synced 05:31
            </span>
          </div>
        </div>

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

/** Interactive Voice Message audio player with waveform */
export type VoiceMessagePlayerProps = {
  id?: string;
  duration?: string;
  initialBars?: readonly number[];
  isNight?: boolean;
  text?: string;
  activeAudioId?: string | null;
  onPlayChange?: (id: string | null) => void;
  className?: string;
};

/** Interactive Voice Message audio player with waveform and text-to-speech support */
export function VoiceMessagePlayer({
  id,
  duration = "0:18",
  initialBars = WAVEFORM_HEIGHTS,
  isNight = false,
  text,
  activeAudioId,
  onPlayChange,
  className,
}: VoiceMessagePlayerProps): React.JSX.Element {
  const [internalIsPlaying, setInternalIsPlaying] = useState(false);
  const isPlaying =
    id !== undefined && activeAudioId !== undefined
      ? activeAudioId === id
      : internalIsPlaying;

  const [activeStep, setActiveStep] = useState(0);
  const timeoutRef = useRef<NodeJS.Timeout | null>(null);
  const utteranceRef = useRef<SpeechSynthesisUtterance | null>(null);

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
    setActiveStep(0);
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
    setActiveStep(0);
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
          setActiveStep(0);
          if (id && onPlayChange) {
            onPlayChange(null);
          }
        };

        utterance.onerror = (e) => {
          console.warn("Speech synthesis notice:", e);
          utteranceRef.current = null;
          setInternalIsPlaying(false);
          setActiveStep(0);
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

  // Waveform progress animation while playing
  useEffect(() => {
    if (!isPlaying) {
      setActiveStep(0);
      return;
    }
    const interval = setInterval(() => {
      setActiveStep((prev) => (prev + 1) % initialBars.length);
    }, 120);
    return () => clearInterval(interval);
  }, [isPlaying, initialBars.length]);

  const handleToggle = () => {
    if (isPlaying) {
      stopPlayback();
    } else {
      startPlayback();
    }
  };

  return (
    <div className={cx("flex items-center gap-3 pt-1 w-full", className)}>
      {/* Play/Pause Button */}
      <button
        type="button"
        onClick={handleToggle}
        className={cx(
          "size-9 rounded-full active:scale-95 flex items-center justify-center shrink-0 transition-all shadow-xs cursor-pointer",
          isNight
            ? "bg-[#00BF6A] hover:bg-[#00d878] text-black"
            : "bg-[#031A0C] hover:bg-[#062613] text-white"
        )}
        aria-label={isPlaying ? "Pause message audio" : "Listen to message"}
        title={isPlaying ? "Pause message" : "Listen to message"}
      >
        {isPlaying ? (
          /* Pause bars */
          <div className="flex items-center gap-1">
            <span className="w-1 h-3.5 bg-current rounded-full" />
            <span className="w-1 h-3.5 bg-current rounded-full" />
          </div>
        ) : (
          /* Play triangle */
          <svg width="12" height="15" viewBox="0 0 12 15" fill="none" className="translate-x-[1px]">
            <path d="M11 7.5L1 1.7V13.3L11 7.5Z" fill="currentColor" />
          </svg>
        )}
      </button>

      {/* Waveform bars */}
      <div className="flex items-center gap-[3px] flex-1 h-6 overflow-hidden">
        {initialBars.map((height, idx) => {
          const isActive = isPlaying && idx <= activeStep;
          return (
            <span
              key={idx}
              style={{ height: `${height}px` }}
              className={cx(
                "w-[3px] rounded-full transition-colors duration-150 shrink-0",
                isActive
                  ? isNight
                    ? "bg-[#00BF6A]"
                    : "bg-[#0E766D]"
                  : isNight
                  ? "bg-white/35"
                  : "bg-black/35"
              )}
            />
          );
        })}
      </div>

      {/* Duration */}
      <span className="text-[13px] font-normal text-[#A9A9A9] shrink-0 font-go">
        {duration}
      </span>
    </div>
  );
}
