"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { InstallApp } from "./InstallApp.tsx";
import { McpButton } from "./McpConnect.tsx";
import { cx } from "./primitives.tsx";
import { useShell } from "./shell.tsx";

// Settings behind a person's profile picture: the app language and the
// assistant connection (MCP, issue #177). The theme toggle and alerts stay
// where each role already has them. The language is kept per device, so the
// next person on a shared device finds it as it was left. A role that has no
// translation yet says so beside the picker.

export type AppLang = "si" | "ta" | "en";

/** Settings' order, as in Figma "සිං / த / EN"; `short` is the toggle label, `label` its accessible name. */
export const APP_LANGS: Array<{ value: AppLang; label: string; short: string }> = [
  { value: "si", label: "සිංහල", short: "සිං" },
  { value: "ta", label: "தமிழ்", short: "த" },
  { value: "en", label: "English", short: "EN" },
];

const LANG_KEY = "waypoint.lang";

function isLang(value: unknown): value is AppLang {
  return value === "si" || value === "ta" || value === "en";
}

/** The device's chosen language; English when storage is empty or blocked. */
export function useDeviceLang(key: string = LANG_KEY): [AppLang, (lang: AppLang) => void] {
  const [lang, setLangState] = useState<AppLang>("en");
  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(key);
      if (isLang(saved)) setLangState(saved);
    } catch {
      // Storage is a convenience; without it the choice lasts until reload.
    }
  }, [key]);
  const setLang = (next: AppLang) => {
    setLangState(next);
    try {
      window.localStorage.setItem(key, next);
    } catch {
      // As above.
    }
  };
  return [lang, setLang];
}

export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  const first = parts[0]?.[0] ?? "";
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : "";
  return (first + last).toUpperCase();
}

const segment = "flex min-h-11 min-w-12 items-center justify-center rounded-full px-4 text-[15px]";

export function SettingsPanel({
  displayName,
  roleLabel,
  lang,
  onLang,
  translated = true,
  placement = "sheet",
  showInstall = true,
  showAssistant = true,
  onClose,
  children,
}: {
  displayName: string;
  roleLabel: string;
  lang: AppLang;
  onLang: (lang: AppLang) => void;
  /** False while this role's screens are still English only. */
  translated?: boolean;
  /** "frame" is a bottom sheet inside the nearest positioned ancestor (the driver's phone frame), with the sign-out sheet's look. */
  placement?: "popover" | "sheet" | "frame";
  /** False where the role has its own way to install, or none. */
  showInstall?: boolean;
  /** False where the role does not offer the AI assistant connection. */
  showAssistant?: boolean;
  onClose: () => void;
  /** Rows a role adds below the language and the assistant connection. */
  children?: ReactNode;
}): React.JSX.Element {
  const shell = useShell();
  const panel = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  useEffect(() => {
    close.current = onClose;
  });
  // A frame sheet slides away before it unmounts; the others close at once.
  const [leaving, setLeaving] = useState(false);
  const leaveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const requestClose = useRef(() => {});
  requestClose.current = () => {
    if (placement !== "frame") {
      close.current();
      return;
    }
    if (leaveTimer.current) return;
    setLeaving(true);
    const still = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    leaveTimer.current = setTimeout(() => close.current(), still ? 0 : 200);
  };
  useEffect(() => {
    // Without preventScroll, focusing a sheet still below its frame scrolls the frame.
    panel.current?.focus({ preventScroll: true });
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") requestClose.current();
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      if (leaveTimer.current) clearTimeout(leaveTimer.current);
    };
  }, []);

  // In the driver's frame the header is the home card's: its avatar, its name size, the
  // role's action (sign out) beside the name, and the language buttons beside their label.
  const framed = placement === "frame";
  const body = (
    <>
      <div className={cx("flex items-center", framed ? "gap-3.5" : "gap-3")}>
        <span
          aria-hidden
          className={cx(
            "flex shrink-0 items-center justify-center rounded-full text-black",
            framed ? "size-[52px] bg-[#B7F2ED] text-[18px] font-medium in-[.go-dark]:bg-[#00BF6A]" : "size-10 bg-go-mint text-[14px] font-semibold",
          )}
        >
          {initialsOf(displayName)}
        </span>
        <span className="flex min-w-0 flex-1 flex-col">
          <span className={cx("truncate font-medium", framed ? "text-[26px] leading-[33px] tracking-tight" : "text-[15px]")}>{displayName}</span>
          <span className={cx(framed ? "pt-0.5 text-[13px] font-light leading-none text-[#A9A9A9]" : "text-[12px] text-go-muted")}>{roleLabel}</span>
        </span>
        {framed && children}
      </div>
      <h2 className="text-[18px] font-medium">Settings</h2>
      <fieldset className={cx("flex gap-2", framed ? "flex-row flex-wrap items-center gap-x-4" : "flex-col rounded-[14px] bg-go-canvas p-3")}>
        <legend className="sr-only">Language</legend>
        <span className="text-[14px] font-medium">Language</span>
        <div className="flex gap-1">
          {APP_LANGS.map((l) => (
            <button
              key={l.value}
              type="button"
              lang={l.value}
              aria-label={l.label}
              aria-pressed={lang === l.value}
              onClick={() => onLang(l.value)}
              className={cx(segment, lang === l.value ? "bg-go-card font-medium text-go-ink shadow-go-float" : "text-go-muted")}
            >
              {l.short}
            </button>
          ))}
        </div>
        {!translated && <span className="w-full text-[12px] text-go-muted">These screens are in English for now; your choice is kept on this device.</span>}
      </fieldset>
      {showAssistant && (
        <McpButton
          url={shell?.mcpUrl ?? null}
          className="flex min-h-12 w-full items-center justify-center gap-2 rounded-[14px] bg-go-surface text-[15px] font-medium text-go-ink"
        />
      )}
      {showInstall && (
        <InstallApp className="flex min-h-12 w-full items-center justify-center gap-2 rounded-[14px] bg-go-surface text-[15px] font-medium text-go-ink" />
      )}
      {!framed && children}
    </>
  );

  if (placement === "frame") {
    return (
      <div className="absolute inset-0 z-50 flex flex-col justify-end overflow-hidden">
        <button
          type="button"
          aria-label="Close settings"
          tabIndex={-1}
          onClick={() => requestClose.current()}
          className={cx("absolute inset-0 cursor-default bg-black/35 backdrop-blur-[6px]", leaving ? "animate-fade-out" : "animate-fade-in")}
        />
        <div
          ref={panel}
          tabIndex={-1}
          role="dialog"
          aria-modal="true"
          aria-label="Settings"
          data-full-frame
          className={cx(
            "relative flex max-h-[92%] w-full flex-col gap-4 overflow-y-auto rounded-t-[40px] bg-go-card px-5 pb-9 pt-5 text-go-ink shadow-2xl outline-none",
            leaving ? "animate-sheet-down" : "animate-sheet-up",
          )}
        >
          {body}
        </div>
      </div>
    );
  }

  return (
    <>
      <button
        type="button"
        aria-label="Close settings"
        tabIndex={-1}
        onClick={onClose}
        className={cx("fixed inset-0 z-40 cursor-default", placement === "sheet" && "animate-fade-in bg-black/20 backdrop-blur-[6px]")}
      />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-label="Settings"
        className={cx(
          "z-50 flex flex-col gap-4 bg-go-card p-4 text-go-ink shadow-[0_10px_30px_rgba(0,0,0,0.18)] outline-none",
          placement === "sheet"
            ? "fixed inset-x-0 bottom-0 animate-sheet-up rounded-t-[28px] pb-[max(2rem,env(safe-area-inset-bottom))] md:inset-x-auto md:left-1/2 md:w-[420px] md:-translate-x-1/2"
            : "fixed bottom-5 left-5 w-[300px] animate-rise-in rounded-[20px]",
        )}
      >
        {body}
      </div>
    </>
  );
}
