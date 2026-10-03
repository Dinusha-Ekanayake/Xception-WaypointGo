"use client";

import { cx } from "@shared/ui";
import type { MapStatus } from "../data/live.ts";
import type { Tone } from "../data/liveDesk.ts";

// The small pieces every Figma "05 Live" frame shares: the header's segmented
// controls, the status chips and words, the progress bar and the pill buttons.

export const STATUS: Record<MapStatus, { label: string; tone: Tone; bar: string; text: string; dot: string }> = {
  "on-time": { label: "On time", tone: "success", bar: "bg-go-teal", text: "text-go-teal", dot: "bg-go-teal" },
  "at-risk": { label: "At risk", tone: "warning", bar: "bg-[#c08a3e]", text: "text-go-warning-text", dot: "bg-[#c08a3e]" },
  late: { label: "Late", tone: "danger", bar: "bg-go-danger", text: "text-go-danger-strong", dot: "bg-go-danger" },
  returning: { label: "Returning", tone: "info", bar: "bg-go-info", text: "text-go-info", dot: "bg-go-info" },
  offline: { label: "Offline", tone: "muted", bar: "bg-[#a3acaa]", text: "text-go-secondary", dot: "bg-[#a3acaa]" },
};

const CHIP: Record<Tone, string> = {
  danger: "bg-go-danger-tint text-go-danger-strong",
  warning: "bg-go-warning-tint text-go-warning-text",
  muted: "bg-go-surface text-go-secondary",
  success: "bg-go-success-tint text-go-teal",
  info: "bg-go-info-tint text-go-info",
};

export function Chip({ tone, children, className }: { tone: Tone; children: React.ReactNode; className?: string }): React.JSX.Element {
  return <span className={cx("inline-flex shrink-0 items-center rounded-full px-2 py-[3px] text-[11px] leading-none font-medium whitespace-nowrap", CHIP[tone], className)}>{children}</span>;
}

/** A white track with one dark pill selected, as the Figma header groups. */
export function Toggle<T extends string>({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: T;
  onChange: (value: T) => void;
  options: Array<{ value: T; label: string }>;
}): React.JSX.Element {
  return (
    <div role="group" aria-label={label} className="flex shrink-0 items-center rounded-full bg-white p-1 shadow-go-card">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          aria-pressed={o.value === value}
          onClick={() => onChange(o.value)}
          className={cx(
            "rounded-full px-3.5 py-[7px] text-[13px] leading-none font-medium whitespace-nowrap transition-colors",
            o.value === value ? "bg-go-ink text-white" : "text-go-ink hover:bg-go-surface",
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Bar({ share, className, thick }: { share: number; className: string; thick?: boolean }): React.JSX.Element {
  return (
    <span className={cx("block w-full rounded-full bg-go-subtle", thick ? "h-2" : "h-1")}>
      <span className={cx("block rounded-full", thick ? "h-2" : "h-1", className)} style={{ width: `${Math.round(Math.max(0, Math.min(1, share)) * 100)}%` }} />
    </span>
  );
}

const NOT_YET = "Messages to stores and drivers are not available yet";

/**
 * A pill button. One the backend cannot do yet is drawn as the design has it
 * but disabled, with the reason as its description, never as a dead control.
 */
export function Action({
  children,
  primary,
  onClick,
  unavailable,
  className,
  icon,
}: {
  children: React.ReactNode;
  primary?: boolean;
  onClick?: () => void;
  unavailable?: boolean;
  className?: string;
  icon?: React.ReactNode;
}): React.JSX.Element {
  return (
    <button
      type="button"
      onClick={unavailable ? undefined : onClick}
      disabled={unavailable}
      title={unavailable ? NOT_YET : undefined}
      aria-description={unavailable ? NOT_YET : undefined}
      className={cx(
        "inline-flex min-h-[34px] items-center justify-center gap-2 rounded-full px-4 text-[14px] font-medium whitespace-nowrap",
        primary ? "bg-go-ink text-white" : "border border-go-divider bg-white text-go-ink",
        unavailable && "cursor-not-allowed opacity-45",
        className,
      )}
    >
      {icon}
      {children}
    </button>
  );
}

export const NOT_AVAILABLE_NOTE = "Store and driver messages are not available yet.";
