"use client";

import { cx } from "./primitives.tsx";

// The round sun and moon button the sign-in and the field roles use to change
// the theme. An icon, never a "Day" or "Night" label, so every screen offers
// the same control.

/** Where the screens outside a role (sign-in, error pages) keep the reader's choice. */
export const SHELL_THEME_KEY = "waypoint.signin.theme";

export function storedShellDark(): boolean {
  try {
    return window.localStorage.getItem(SHELL_THEME_KEY) === "dark";
  } catch {
    return false;
  }
}

export function keepShellTheme(dark: boolean): void {
  try {
    window.localStorage.setItem(SHELL_THEME_KEY, dark ? "dark" : "light");
  } catch {
    // Private mode or blocked storage: the choice lasts until reload.
  }
}

export function ThemeToggle({ dark, onToggle, className }: { dark: boolean; onToggle: () => void; className?: string }): React.JSX.Element {
  return (
    <button
      type="button"
      onClick={onToggle}
      className={cx(
        "flex h-[42px] w-[42px] items-center justify-center rounded-[22px] border border-go-rule bg-go-card text-go-ink shadow-[0_5px_20px_rgba(0,0,0,0.09)] transition-all hover:bg-go-surface active:scale-95",
        className,
      )}
      title={dark ? "Use the light theme" : "Use the dark theme"}
      aria-label={dark ? "Use the light theme" : "Use the dark theme"}
    >
      {dark ? (
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" />
        </svg>
      ) : (
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
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
  );
}
