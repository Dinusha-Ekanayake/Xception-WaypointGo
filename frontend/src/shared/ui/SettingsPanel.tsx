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
  onClose,
  children,
}: {
  displayName: string;
  roleLabel: string;
  lang: AppLang;
  onLang: (lang: AppLang) => void;
  /** False while this role's screens are still English only. */
  translated?: boolean;
  placement?: "popover" | "sheet";
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
  useEffect(() => {
    panel.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") close.current();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <>
      <button
        type="button"
        aria-label="Close settings"
        tabIndex={-1}
        onClick={onClose}
        className={cx("fixed inset-0 z-40 cursor-default", placement === "sheet" && "bg-black/20 backdrop-blur-[6px]")}
      />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-label="Settings"
        className={cx(
          "z-50 flex flex-col gap-4 bg-go-card p-4 text-go-ink shadow-[0_10px_30px_rgba(0,0,0,0.18)] outline-none",
          placement === "sheet"
            ? "fixed inset-x-0 bottom-0 rounded-t-[28px] pb-[max(2rem,env(safe-area-inset-bottom))] md:inset-x-auto md:left-1/2 md:w-[420px] md:-translate-x-1/2"
            : "fixed bottom-5 left-5 w-[300px] rounded-[20px]",
        )}
      >
        <div className="flex items-center gap-3">
          <span aria-hidden className="flex size-10 shrink-0 items-center justify-center rounded-full bg-go-mint text-[14px] font-semibold text-black">
            {initialsOf(displayName)}
          </span>
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="truncate text-[15px] font-medium">{displayName}</span>
            <span className="text-[12px] text-go-muted">{roleLabel}</span>
          </span>
        </div>
        <h2 className="text-[18px] font-medium">Settings</h2>
        <fieldset className="flex flex-col gap-2 rounded-[14px] bg-go-canvas p-3">
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
          {!translated && <span className="text-[12px] text-go-muted">These screens are in English for now; your choice is kept on this device.</span>}
        </fieldset>
        <McpButton
          url={shell?.mcpUrl ?? null}
          className="flex min-h-12 w-full items-center justify-center gap-2 rounded-[14px] bg-go-surface text-[15px] font-medium text-go-ink"
        />
        <InstallApp className="flex min-h-12 w-full items-center justify-center gap-2 rounded-[14px] bg-go-surface text-[15px] font-medium text-go-ink" />
        {children}
      </div>
    </>
  );
}
