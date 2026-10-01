"use client";

import type { ButtonHTMLAttributes, ReactNode } from "react";
import { cx } from "@shared/ui";

// The driver's building blocks, from Figma "12 · Driver · Mobile". Everything is
// drawn from theme tokens, so the dark theme is the same markup. Touch targets
// are at least 56px: the phone is used one-handed, when safely stopped.

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { children: ReactNode };

const big = "flex min-h-16 w-full items-center justify-center gap-2 rounded-[22px] px-5 text-[19px] font-medium disabled:opacity-50";

export function ActionButton({ children, className, type = "button", ...rest }: ButtonProps): React.JSX.Element {
  return (
    <button type={type} {...rest} className={cx(big, "bg-go-action text-go-on-action", className)}>
      {children}
    </button>
  );
}

export function SoftButton({ children, className, type = "button", ...rest }: ButtonProps): React.JSX.Element {
  return (
    <button type={type} {...rest} className={cx(big, "bg-go-soft text-go-on-soft", className)}>
      {children}
    </button>
  );
}

export function OutlineButton({ children, className, type = "button", ...rest }: ButtonProps): React.JSX.Element {
  return (
    <button type={type} {...rest} className={cx(big, "border border-go-ink bg-transparent text-go-ink", className)}>
      {children}
    </button>
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

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }): React.JSX.Element {
  return (
    <label className="flex flex-col gap-1.5 text-[15px] text-go-ink">
      <span className="font-medium">{label}</span>
      {children}
      {hint && <span className="text-[13px] text-go-muted">{hint}</span>}
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
