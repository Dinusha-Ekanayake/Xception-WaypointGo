"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { Icon, PRESS, Spinner, cx, useOverlay, useSheetDrag, type IconName } from "@shared/ui";
import type { SessionStatus, Temperature } from "@shared/domain/types";
import { STATUS_LABEL } from "./data/manifest.ts";
import { useT } from "./i18n.tsx";
import { useTheme } from "./theme.tsx";

// Loader pieces from Figma "08 Loader · Phone". Touch targets are at least 48px
// and the main actions 56px: the dock is worked in gloves.

/** Two letters for an avatar: first and last name, as in the Figma crew list. */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : "")).toUpperCase();
}

export function TempBadge({ temperature }: { temperature: Temperature }): React.JSX.Element {
  const tr = useT();
  return temperature === "chilled" ? (
    <span className="inline-flex shrink-0 items-center gap-[5px] rounded-full border border-go-ink px-2.5 py-1 text-[13px] font-medium tracking-[0.52px] text-go-ink">
      <Icon name="chilled" />
      {tr("CHILLED")}
    </span>
  ) : (
    <span className="inline-flex shrink-0 items-center gap-[5px] rounded-[6px] bg-go-warning-tint py-1 pr-2.5 pl-2 text-[13px] font-medium tracking-[0.52px] text-go-warning-text">
      <Icon name="box" />
      {tr("AMBIENT")}
    </span>
  );
}

const STATUS: Record<SessionStatus, { icon: IconName; text: string }> = {
  NOT_STARTED: { icon: "clock", text: "text-go-muted" },
  IN_PROGRESS: { icon: "loading", text: "text-go-warning-text" },
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

type Tone = "mint" | "danger" | "ink" | "plain" | "muted" | "grey";
const TONE: Record<Tone, string> = {
  mint: "bg-go-soft text-go-on-soft",
  danger: "bg-[#ea2525] text-white",
  ink: "bg-go-action text-go-on-action",
  plain: "border-[1.5px] border-go-rule bg-go-card text-go-ink",
  muted: "bg-go-divider text-go-muted",
  grey: "bg-go-surface text-go-ink",
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
  busy = false,
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
  /** The command is on its way: disabled, with a spinner before the label. */
  busy?: boolean;
}): React.JSX.Element {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={cx(
        "flex items-center justify-center gap-2 px-[18px] font-medium disabled:cursor-not-allowed disabled:opacity-50",
        busy && "disabled:cursor-wait disabled:opacity-70",
        PRESS,
        fit ? "w-auto min-w-[170px] px-7" : "w-full",
        size === "l" ? "min-h-16 rounded-[22px] text-[20px]" : "min-h-12 rounded-full text-[15px] min-[1700px]:text-[17px]",
        TONE[tone],
      )}
    >
      {busy && <Spinner />}
      {children}
      {icon && !busy && <Icon name={icon} />}
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
      <div className="h-2.5 w-full overflow-hidden rounded-[5px] bg-go-divider">
        <div className="h-full rounded-[5px] bg-go-success" style={{ width: `${Math.min(100, Math.max(0, share * 100))}%` }} />
      </div>
    </div>
  );
}

/** The 168px completion ring; 104px on a phone held sideways, where it filled the screen above the load list (#201). */
export function Ring({ percent }: { percent: number }): React.JSX.Element {
  const tr = useT();
  const r = 76;
  const c = 2 * Math.PI * r;
  return (
    <div className="relative size-[168px] short:size-[104px]" role="img" aria-label={tr("{n}% of orders checked", { n: percent })}>
      <svg viewBox="0 0 168 168" className="size-full -rotate-90">
        <circle cx="84" cy="84" r={r} fill="none" stroke="var(--color-go-surface)" strokeWidth="12" />
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
        <span className="text-[36px] font-semibold text-go-ink short:text-[24px]">{percent}%</span>
        <span className="text-[15px] text-go-muted short:text-[12px]">{tr("Completed")}</span>
      </div>
    </div>
  );
}

/**
 * A bottom sheet over a dimmed page on phones, as in the design's release and
 * issue states; a dialog near the top on tablets. Escape closes it, focus moves
 * into it on open, stays inside while it is open, and returns where it was.
 */
export function Sheet({
  label,
  onClose,
  children,
  placement = "bottom",
}: {
  label: string;
  onClose: () => void;
  children: ReactNode;
  /** "center" for the short confirmations Figma draws as a card over the page. */
  placement?: "bottom" | "center";
}): React.JSX.Element {
  const tr = useT();
  const panel = useRef<HTMLDivElement>(null);
  const { closing, requestClose } = useOverlay(panel, onClose);
  useSheetDrag(panel, onClose, placement === "bottom");

  return (
    <div
      data-closing={closing || undefined}
      className={cx(
        "go-overlay fixed inset-0 z-40 flex justify-center",
        placement === "center" ? "items-center px-4" : "items-end md:items-start md:p-6 md:pt-10",
      )}
      role="presentation"
    >
      <button type="button" tabIndex={-1} aria-label={tr("Close")} onClick={requestClose} className="go-backdrop absolute inset-0 bg-black/25 backdrop-blur-[6px]" />
      <div
        ref={panel}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className={cx(
          "go-panel relative flex w-full flex-col gap-4 overflow-y-auto bg-go-card text-go-ink outline-none",
          placement === "center" ? "go-panel-dialog" : "go-panel-sheet",
          placement === "center"
            ? "max-h-[90dvh] max-w-[400px] rounded-[32px] px-6 py-6"
            : "max-h-[92dvh] max-w-[560px] rounded-t-[32px] px-5 pt-6 pb-8 md:max-h-[calc(100dvh-64px)] md:rounded-[32px] md:px-7 md:pb-7",
        )}
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
      className="relative flex min-h-16 w-full items-center justify-center overflow-hidden rounded-full bg-go-action px-[18px] text-[17px] font-medium text-go-on-action select-none disabled:cursor-not-allowed disabled:opacity-50"
    >
      <span aria-hidden className="absolute inset-y-0 left-0 bg-go-signal" style={{ width: `${progress * 100}%` }} />
      <span className="relative">{children}</span>
    </button>
  );
}

/**
 * Swipe to release vehicle: swipe from left end to right end.
 * Uses theme colors (go-action track, go-signal gradient fill, crisp handle).
 */
export function SwipeButton({
  onSwiped,
  disabled = false,
  busy = false,
  label = "Swipe to release vehicle",
  busyLabel = "Releasing…",
}: {
  onSwiped: () => void;
  disabled?: boolean;
  busy?: boolean;
  label?: string;
  busyLabel?: string;
}): React.JSX.Element {
  const { theme } = useTheme();
  const isDark = theme === "dark";
  const trackRef = useRef<HTMLDivElement>(null);
  const [dragX, setDragX] = useState(0);
  const [isDragging, setIsDragging] = useState(false);
  const [completed, setCompleted] = useState(false);
  const startXRef = useRef(0);
  const pointerIdRef = useRef<number | null>(null);

  useEffect(() => {
    if (!busy && !disabled && completed) {
      const timeout = setTimeout(() => {
        setCompleted(false);
        setDragX(0);
      }, 1000);
      return () => clearTimeout(timeout);
    }
  }, [busy, disabled, completed]);

  const getMaxDrag = () => {
    if (!trackRef.current) return 0;
    return Math.max(0, trackRef.current.clientWidth - 52 - 12);
  };

  const handlePointerDown = (e: React.PointerEvent) => {
    if (disabled || busy || completed) return;
    setIsDragging(true);
    startXRef.current = e.clientX - dragX;
    pointerIdRef.current = e.pointerId;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    if (!isDragging || disabled || busy || completed) return;
    const maxDrag = getMaxDrag();
    const currentX = e.clientX - startXRef.current;
    const clamped = Math.max(0, Math.min(maxDrag, currentX));
    setDragX(clamped);
  };

  const handlePointerUp = (e: React.PointerEvent) => {
    if (!isDragging) return;
    setIsDragging(false);
    if (pointerIdRef.current !== null) {
      try {
        (e.currentTarget as HTMLElement).releasePointerCapture(pointerIdRef.current);
      } catch {
        // Ignored if capture already lost
      }
      pointerIdRef.current = null;
    }

    const maxDrag = getMaxDrag();
    const threshold = maxDrag * 0.78; // 78% threshold to trigger

    if (dragX >= threshold && maxDrag > 0) {
      setDragX(maxDrag);
      setCompleted(true);
      onSwiped();
    } else {
      setDragX(0);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (disabled || busy || completed) return;
    if (e.key === "ArrowRight" || e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      const maxDrag = getMaxDrag();
      setDragX(maxDrag);
      setCompleted(true);
      onSwiped();
    }
  };

  const maxDrag = getMaxDrag();
  const progressRatio = maxDrag > 0 ? Math.min(1, dragX / maxDrag) : 0;
  const isDone = completed || busy;

  return (
    <div
      ref={trackRef}
      role="slider"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(progressRatio * 100)}
      aria-disabled={disabled || busy}
      tabIndex={disabled || busy ? -1 : 0}
      onKeyDown={handleKeyDown}
      className={cx(
        "relative flex min-h-[64px] h-16 w-full items-center select-none overflow-hidden rounded-full p-[6px] transition-colors duration-300",
        disabled
          ? "bg-[#031a0c]/40 cursor-not-allowed opacity-50"
          : isDone
            ? "bg-[#0b8a3a] shadow-[0_4px_16px_rgba(11,138,58,0.35)]"
            : isDark
              ? "bg-[#081510] shadow-[inset_0_2px_5px_rgba(0,0,0,0.7),0_4px_14px_rgba(0,0,0,0.3)] ring-1 ring-go-signal/20"
              : "bg-[#031a0c] shadow-[inset_0_2px_4px_rgba(0,0,0,0.5),0_4px_14px_rgba(3,26,12,0.25)] ring-1 ring-white/10",
      )}
    >
      {/* Background dynamic progress fill */}
      <div
        aria-hidden="true"
        className={cx(
          "absolute inset-y-0 left-0 rounded-full bg-gradient-to-r from-go-success to-go-signal transition-all pointer-events-none",
          !isDragging && "duration-300 ease-out",
        )}
        style={{
          width: isDone ? "100%" : `${dragX + 52 + 6}px`,
          opacity: dragX > 0 || isDone ? 1 : 0,
        }}
      />

      {/* Centered prompt text & animated chevrons */}
      <div
        className={cx(
          "absolute inset-0 flex items-center justify-center gap-2 px-14 text-[16px] font-medium transition-all pointer-events-none",
          isDone ? "text-white" : isDark ? "text-white/95 font-semibold" : "text-white/90",
          !isDragging && "duration-200",
        )}
        style={{
          opacity: isDone ? 1 : Math.max(0, 1 - progressRatio * 1.6),
          transform: `translateX(${dragX * 0.12}px)`,
        }}
      >
        <span>{isDone ? busyLabel : label}</span>
        {!isDone && !disabled && (
          <span className="flex items-center text-go-signal font-bold tracking-tighter opacity-80 animate-pulse">
            <svg className="size-4 -mr-1" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6"/></svg>
            <svg className="size-4 -mr-1" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6"/></svg>
            <svg className="size-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><path d="m9 18 6-6-6-6"/></svg>
          </span>
        )}
      </div>

      {/* Swiper Handle (Thumb) */}
      <div
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
        className={cx(
          "relative z-10 flex size-[52px] shrink-0 items-center justify-center rounded-full transition-transform touch-none cursor-grab active:cursor-grabbing",
          isDark
            ? "bg-go-signal text-[#031a0c] shadow-[0_4px_16px_rgba(0,191,106,0.45)]"
            : "bg-white text-go-ink shadow-[0_4px_12px_rgba(0,0,0,0.3)]",
          !isDragging && "duration-300 ease-out",
          disabled && "cursor-not-allowed opacity-60",
          isDone && (isDark ? "bg-white text-go-success" : "bg-white text-go-success"),
        )}
        style={{
          transform: `translateX(${isDone ? maxDrag : dragX}px)`,
        }}
      >
        {isDone ? (
          <svg className="size-6 text-go-success" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="20 6 9 17 4 12" />
          </svg>
        ) : (
          <svg className={cx("size-6 transition-transform", isDark ? "text-[#031a0c]" : "text-go-ink")} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <path d="M5 12h14" />
            <path d="m12 5 7 7-7 7" />
          </svg>
        )}
      </div>
    </div>
  );
}
