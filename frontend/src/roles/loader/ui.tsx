"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { Icon, cx, type IconName } from "@shared/ui";
import type { SessionStatus, Temperature } from "@shared/domain/types";
import { STATUS_LABEL } from "./data/manifest.ts";
import { useT } from "./i18n.tsx";

// Loader pieces from Figma "08 Loader · Phone". Touch targets are at least 48px
// and the main actions 56px: the dock is worked in gloves.

export function TempBadge({ temperature }: { temperature: Temperature }): React.JSX.Element {
  const tr = useT();
  return temperature === "chilled" ? (
    <span className="inline-flex shrink-0 items-center gap-[5px] rounded-full border border-black px-2.5 py-1 text-[13px] font-medium tracking-[0.52px] text-black">
      <Icon name="chilled" />
      {tr("CHILLED")}
    </span>
  ) : (
    <span className="inline-flex shrink-0 items-center gap-[5px] rounded-[6px] bg-[#f7eddf] py-1 pr-2.5 pl-2 text-[13px] font-medium tracking-[0.52px] text-[#8a5a1c]">
      <Icon name="box" />
      {tr("AMBIENT")}
    </span>
  );
}

const STATUS: Record<SessionStatus, { icon: IconName; text: string }> = {
  NOT_STARTED: { icon: "clock", text: "text-go-muted" },
  IN_PROGRESS: { icon: "loading", text: "text-[#b45309]" },
  BLOCKED: { icon: "triangle", text: "text-go-danger-strong" },
  READY: { icon: "check", text: "text-go-success" },
  COMPLETED: { icon: "check", text: "text-go-success" },
};

export function StatusChip({ status }: { status: SessionStatus }): React.JSX.Element {
  const tr = useT();
  const s = STATUS[status];
  return (
    <span className={cx("inline-flex items-center gap-1.5 py-1.5 text-[15px] font-medium", s.text)}>
      <Icon name={s.icon} />
      {tr(STATUS_LABEL[status])}
    </span>
  );
}

type Tone = "mint" | "danger" | "ink" | "plain" | "muted";
const TONE: Record<Tone, string> = {
  mint: "bg-go-mint text-go-ink",
  danger: "bg-[#ea2525] text-white",
  ink: "bg-[#0b2a1a] text-white",
  plain: "border-[1.5px] border-[#dfe3e8] bg-white text-go-ink",
  muted: "bg-[#e5e7eb] text-go-muted",
};

export function BigButton({
  children,
  tone = "mint",
  icon,
  onClick,
  disabled,
  size = "m",
  fit = false,
  type = "button",
}: {
  children: ReactNode;
  tone?: Tone;
  icon?: IconName;
  onClick?: () => void;
  disabled?: boolean;
  size?: "m" | "l";
  /** Sized to its label, as the side-by-side actions in the designs are. */
  fit?: boolean;
  type?: "button" | "submit";
}): React.JSX.Element {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      className={cx(
        "flex items-center justify-center gap-2 px-[18px] font-medium disabled:cursor-not-allowed disabled:opacity-50",
        fit ? "w-auto min-w-[170px] px-7" : "w-full",
        size === "l" ? "min-h-16 rounded-[22px] text-[20px]" : "min-h-12 rounded-full text-[15px]",
        TONE[tone],
      )}
    >
      {children}
      {icon && <Icon name={icon} />}
    </button>
  );
}

export function Bar({ label, value, share }: { label: string; value: string; share: number }): React.JSX.Element {
  const tr = useT();
  return (
    <div className="flex w-full flex-col gap-1.5">
      <div className="flex w-full text-[13px] font-medium">
        <span className="flex-1 text-go-muted">{tr(label)}</span>
        <span className="text-go-ink">{value}</span>
      </div>
      <div className="h-2.5 w-full overflow-hidden rounded-[5px] bg-[#e5e7eb]">
        <div className="h-full rounded-[5px] bg-go-success" style={{ width: `${Math.min(100, Math.max(0, share * 100))}%` }} />
      </div>
    </div>
  );
}

/** The 168px completion ring. */
export function Ring({ percent }: { percent: number }): React.JSX.Element {
  const r = 76;
  const c = 2 * Math.PI * r;
  return (
    <div className="relative size-[168px]" role="img" aria-label={`${percent}% of orders checked`}>
      <svg viewBox="0 0 168 168" className="size-full -rotate-90">
        <circle cx="84" cy="84" r={r} fill="none" stroke="#e7f3f2" strokeWidth="12" />
        <circle
          cx="84"
          cy="84"
          r={r}
          fill="none"
          stroke="#00bf6a"
          strokeWidth="12"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - percent / 100)}
          className="transition-[stroke-dashoffset] duration-500"
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="text-[36px] font-semibold text-black">{percent}%</span>
        <span className="text-[15px] text-go-muted">Completed</span>
      </div>
    </div>
  );
}

/**
 * A bottom sheet over a dimmed page on phones, as in the design's release and
 * issue states; a dialog near the top on tablets. Escape closes it, focus moves
 * into it on open, stays inside while it is open, and returns where it was.
 */
export function Sheet({ label, onClose, children }: { label: string; onClose: () => void; children: ReactNode }): React.JSX.Element {
  const panel = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const focusable = () =>
      Array.from(
        panel.current?.querySelectorAll<HTMLElement>("button, [href], input, select, textarea, [tabindex]:not([tabindex='-1'])") ?? [],
      ).filter((el) => !el.hasAttribute("disabled"));
    (focusable()[0] ?? panel.current)?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        close.current();
        return;
      }
      if (event.key !== "Tab") return;
      const items = focusable();
      if (items.length === 0) return;
      const first = items[0]!;
      const last = items[items.length - 1]!;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      previous?.focus();
    };
  }, []);

  return (
    <div className="fixed inset-0 z-40 flex items-end justify-center md:items-start md:p-6 md:pt-10" role="presentation">
      <button type="button" tabIndex={-1} aria-label="Close" onClick={onClose} className="absolute inset-0 bg-black/25 backdrop-blur-[6px]" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className="relative flex max-h-[92dvh] w-full max-w-[560px] flex-col gap-4 overflow-y-auto rounded-t-[32px] bg-white px-5 pt-6 pb-8 outline-none md:max-h-[calc(100dvh-64px)] md:rounded-[32px] md:px-7 md:pb-7"
      >
        {children}
      </div>
    </div>
  );
}

/**
 * Figma 04 "Hold to release vehicle": a deliberate press, so a brushed tap in a
 * glove never releases a truck. Keyboard users hold Space or Enter the same way.
 */
export function HoldButton({
  children,
  onHeld,
  disabled,
  holdMs = 1200,
}: {
  children: ReactNode;
  onHeld: () => void;
  disabled?: boolean;
  holdMs?: number;
}): React.JSX.Element {
  const [progress, setProgress] = useState(0);
  const timer = useRef<number | null>(null);

  const stop = () => {
    if (timer.current !== null) window.clearInterval(timer.current);
    timer.current = null;
    setProgress(0);
  };
  const start = () => {
    if (disabled || timer.current !== null) return;
    const began = Date.now();
    timer.current = window.setInterval(() => {
      const share = Math.min(1, (Date.now() - began) / holdMs);
      setProgress(share);
      if (share >= 1) {
        stop();
        onHeld();
      }
    }, 30);
  };
  useEffect(() => stop, []);

  return (
    <button
      type="button"
      disabled={disabled}
      onPointerDown={start}
      onPointerUp={stop}
      onPointerLeave={stop}
      onPointerCancel={stop}
      onKeyDown={(e) => (e.key === " " || e.key === "Enter") && (e.preventDefault(), start())}
      onKeyUp={stop}
      aria-label="Hold to release vehicle"
      className="relative flex min-h-16 w-full items-center justify-center overflow-hidden rounded-full bg-[#0b2a1a] px-[18px] text-[17px] font-medium text-white select-none disabled:cursor-not-allowed disabled:opacity-50"
    >
      <span aria-hidden className="absolute inset-y-0 left-0 bg-go-signal" style={{ width: `${progress * 100}%` }} />
      <span className="relative">{children}</span>
    </button>
  );
}
