import { useEffect, useRef, useState, type ReactNode } from "react";
import { Icon, type IconName } from "./Icon.tsx";
import { PRESS, Spinner } from "./Spinner.tsx";
import { clock } from "../wording/index.ts";

// The GO components every role composes: cards, pills, tiles and buttons, as
// drawn in the Figma style guide. Values are the design's, through theme.css.

function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(" ");
}

export function Card({
  children,
  className,
  label,
}: {
  children: ReactNode;
  className?: string;
  label?: string;
}): React.JSX.Element {
  return (
    <section
      aria-label={label}
      className={cx("flex flex-col gap-3 rounded-go-panel bg-go-card p-5 shadow-go-card", className)}
    >
      {children}
    </section>
  );
}

export function CardHead({
  title,
  meta,
  action,
}: {
  title: string;
  meta?: string;
  action?: ReactNode;
}): React.JSX.Element {
  return (
    <div className="flex w-full items-center gap-2.5">
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <h2 className="truncate text-[17px] font-medium tracking-normal text-go-ink">{title}</h2>
        {meta && <p className="truncate text-xs text-go-secondary">{meta}</p>}
      </div>
      {action}
    </div>
  );
}

/** "Open orders ›": a teal text link with the design's chevron. The hit area reaches 44px tall without growing the visible text. */
export function LinkAction({ children, onClick }: { children: ReactNode; onClick: () => void }): React.JSX.Element {
  return (
    <button type="button" onClick={onClick} className="relative flex shrink-0 items-center gap-0.5 text-[13px] font-medium text-go-teal before:absolute before:-inset-x-2 before:-inset-y-3">
      {children}
      <Icon name="chevron-right" />
    </button>
  );
}

export type Tone = "neutral" | "muted" | "success" | "warning" | "danger" | "info" | "mint";

const PILL_TONE: Record<Tone, string> = {
  neutral: "bg-go-surface text-go-ink",
  muted: "bg-go-surface text-go-secondary",
  success: "bg-go-success-tint text-go-teal",
  warning: "bg-go-warning-tint text-go-warning-text",
  danger: "bg-go-danger-tint text-go-danger",
  info: "bg-go-info-tint text-go-info",
  mint: "bg-go-mint text-go-ink",
};

export function Pill({
  tone = "neutral",
  icon,
  children,
}: {
  tone?: Tone;
  icon?: IconName;
  children: ReactNode;
}): React.JSX.Element {
  return (
    <span className={cx("inline-flex shrink-0 items-center gap-[5px] rounded-full px-[9px] py-[3px] text-[11px] font-medium whitespace-nowrap", PILL_TONE[tone])}>
      {icon && <Icon name={icon} />}
      {children}
    </span>
  );
}

const TILE_TONE: Record<"surface" | "success" | "warning" | "danger", string> = {
  surface: "bg-go-surface",
  success: "bg-go-success-tint",
  warning: "bg-go-warning-tint",
  danger: "bg-go-danger-tint",
};

/** A stat block: small label, large number, one supporting line. */
export function StatTile({
  label,
  value,
  note,
  tone = "surface",
  valueClassName = "text-go-ink",
}: {
  label: string;
  value: ReactNode;
  note?: ReactNode;
  tone?: keyof typeof TILE_TONE;
  valueClassName?: string;
}): React.JSX.Element {
  return (
    <div className={cx("flex min-w-0 flex-1 flex-col gap-1 rounded-go-card px-3.5 py-3.5", TILE_TONE[tone])}>
      <p className="truncate text-xs font-medium text-go-secondary">{label}</p>
      <p className={cx("truncate text-2xl font-medium", valueClassName)}>{value}</p>
      {note && <div className="text-[11px] text-go-secondary">{note}</div>}
    </div>
  );
}

/** White KPI card from the top of the Vehicles screen. */
export function KpiCard({
  label,
  value,
  note,
  valueClassName = "text-go-ink",
}: {
  label: string;
  value: ReactNode;
  note?: ReactNode;
  valueClassName?: string;
}): React.JSX.Element {
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-0.5 rounded-go-card-l bg-go-card px-[18px] py-3.5 shadow-go-card">
      <p className="truncate text-xs text-go-secondary">{label}</p>
      <p className={cx("truncate text-2xl font-medium", valueClassName)}>{value}</p>
      {note && <p className="truncate text-xs text-go-secondary">{note}</p>}
    </div>
  );
}

type ButtonProps = {
  children: ReactNode;
  icon?: IconName;
  onClick?: () => void;
  disabled?: boolean;
  type?: "button" | "submit";
  title?: string;
  /** The command is on its way: disabled, with a spinner before the label. */
  busy?: boolean;
};

export function PrimaryButton({ children, icon, type = "button", busy = false, disabled, ...rest }: ButtonProps): React.JSX.Element {
  return (
    <button
      type={type}
      {...rest}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={cx(
        "inline-flex items-center justify-center gap-1.5 rounded-full bg-go-ink px-[18px] py-3 text-sm font-medium text-go-card disabled:cursor-not-allowed disabled:opacity-40",
        busy && "disabled:cursor-wait disabled:opacity-70",
        PRESS,
      )}
    >
      {busy ? <Spinner /> : icon && <Icon name={icon} />}
      {children}
    </button>
  );
}

export function SecondaryButton({ children, icon, type = "button", busy = false, disabled, ...rest }: ButtonProps): React.JSX.Element {
  return (
    <button
      type={type}
      {...rest}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={cx(
        "inline-flex items-center justify-center gap-1.5 rounded-full border border-go-rule bg-go-card px-[18px] py-3 text-sm font-medium text-go-ink disabled:cursor-not-allowed disabled:opacity-40",
        busy && "disabled:cursor-wait disabled:opacity-70",
        PRESS,
      )}
    >
      {busy ? <Spinner /> : icon && <Icon name={icon} />}
      {children}
    </button>
  );
}

/** Filter tabs above a table: the selected one is filled ink. */
export function FilterTabs<T extends string>({
  options,
  value,
  onChange,
  label,
}: {
  options: Array<{ value: T; label: string }>;
  value: T;
  onChange: (value: T) => void;
  label: string;
}): React.JSX.Element {
  return (
    <div role="tablist" aria-label={label} className="flex flex-wrap items-center gap-2">
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={selected}
            onClick={() => onChange(option.value)}
            className={cx(
              "rounded-go-card-s px-3 py-1.5 text-xs font-medium whitespace-nowrap",
              selected ? "bg-go-ink text-go-card" : "bg-go-surface text-go-ink",
            )}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

/**
 * Pill-shaped segmented control. "sm" is the sidebar depot scope; "md" is a
 * screen's own switch in its header (Orders: Current / Upcoming / Past, Issues:
 * Open / In progress / Resolved), with an optional quiet hint after the label.
 */
export function Segmented<T extends string>({
  options,
  value,
  onChange,
  label,
  size = "sm",
}: {
  options: Array<{ value: T; label: string; hint?: string }>;
  value: T;
  onChange: (value: T) => void;
  label: string;
  size?: "sm" | "md";
}): React.JSX.Element {
  const md = size === "md";
  return (
    <div role="radiogroup" aria-label={label} className={cx("flex w-fit items-center rounded-full bg-go-card", md ? "gap-1 p-1" : "gap-0.5 p-[3px]")}>
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={selected}
            onClick={() => onChange(option.value)}
            className={cx(
              "flex items-baseline rounded-full font-medium whitespace-nowrap",
              md ? "gap-2 px-4 py-2 text-[15px]" : "px-[9px] py-[5px] text-[11px]",
              selected ? "bg-go-ink text-go-card" : "text-go-ink hover:bg-go-subtle",
            )}
          >
            {option.label}
            {option.hint && <span className={cx("text-xs font-normal", selected ? "text-go-card/75" : "text-go-secondary")}>{option.hint}</span>}
          </button>
        );
      })}
    </div>
  );
}

/**
 * Announces `text` to screen readers only when it actually changes, not on
 * every render: a visually-hidden companion that a polling clock does not
 * retrigger. Returns the live region to render beside the visible text.
 */
export function useStateAnnouncement(text: string): ReactNode {
  const [announced, setAnnounced] = useState(text);
  const last = useRef(text);
  useEffect(() => {
    if (last.current === text) return;
    last.current = text;
    setAnnounced(text);
  }, [text]);
  return (
    <span role="status" aria-live="polite" className="sr-only">
      {announced}
    </span>
  );
}

/**
 * The sync pill. Online shows when data last arrived; offline turns amber and
 * says what still works, because silent degradation is worse than failure.
 * Only the connection STATE (offline / sending / synced / connecting) is
 * announced to screen readers; the clock inside the visible text is not in a
 * live region, so it does not re-announce on every poll (issue #118 follow-up).
 */
export function ConnectionStatus({
  online,
  lastSyncedAt,
  offlineNote,
  onSync,
  syncing = false,
}: {
  online: boolean;
  lastSyncedAt: Date | null;
  offlineNote: string;
  /** Makes the pill a "sync now" button while online. */
  onSync?: () => void;
  syncing?: boolean;
}): React.JSX.Element {
  const time = lastSyncedAt ? clock(lastSyncedAt) : null;
  const state = !online ? `Offline. ${offlineNote}` : syncing ? "Sending your changes" : time ? "Synced" : "Connecting…";
  const announcement = useStateAnnouncement(state);
  if (!online) {
    return (
      <div className="flex h-12 shrink-0 items-center gap-2 rounded-full border-[1.5px] border-go-warning bg-go-warning-tint pr-3.5 pl-3 drop-shadow-[0_5px_10px_rgba(0,0,0,0.09)]">
        <span aria-hidden className="size-2 rounded-full bg-go-warning" />
        <span className="text-[15px] font-semibold text-go-warning-text">Offline</span>
        <span className="text-sm text-go-warning-text">{time ? `Last synced ${time} · ` : ""}{offlineNote}</span>
        {announcement}
      </div>
    );
  }
  const text = syncing ? "Syncing…" : time ? `Synced ${time}` : "Connecting…";
  const pill = "flex h-12 shrink-0 items-center gap-2 rounded-full bg-go-card pr-3.5 pl-3 drop-shadow-[0_5px_10px_rgba(0,0,0,0.09)]";
  if (onSync) {
    // Tapping the time reads again now (issue #118).
    return (
      <button type="button" onClick={onSync} disabled={syncing} aria-label={`${text}. Sync now`} className={cx(pill, "disabled:cursor-wait")}>
        <Icon name="dot-online" />
        <span className="text-sm text-go-muted">{text}</span>
        {announcement}
      </button>
    );
  }
  return (
    <div className={pill}>
      <Icon name="dot-online" />
      <span className="text-sm text-go-muted">{text}</span>
      {announcement}
    </div>
  );
}

export { cx };
