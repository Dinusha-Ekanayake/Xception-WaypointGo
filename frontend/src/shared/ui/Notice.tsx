import type { ReactNode } from "react";
import { cx } from "./primitives.tsx";

// An inline alert, drawn like the "Short by 2 refrigerated vehicles" block on the
// Vehicles screen: a tinted panel, a strong first line, a plain second line.
// Used for every degraded state, so a missing dependency is said on screen and
// never shown as an empty list.

const TONE = {
  danger: { box: "bg-go-danger-tint", title: "text-go-danger-strong" },
  warning: { box: "bg-go-warning-tint", title: "text-go-warning-text" },
  info: { box: "bg-go-info-tint", title: "text-go-info" },
  neutral: { box: "bg-go-surface", title: "text-go-ink" },
} as const;

export function Notice({
  tone = "warning",
  title,
  children,
  action,
  live = false,
}: {
  tone?: keyof typeof TONE;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
  /** Announce it: for states that arrive after the page, such as going offline. */
  live?: boolean;
}): React.JSX.Element {
  return (
    <div
      role={live ? (tone === "danger" ? "alert" : "status") : undefined}
      className={cx("flex w-full items-center gap-3 rounded-go-card-s px-3 py-2.5", TONE[tone].box)}
    >
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <p className={cx("text-[13px] font-medium", TONE[tone].title)}>{title}</p>
        {children && <div className="text-xs text-go-ink">{children}</div>}
      </div>
      {action}
    </div>
  );
}

/**
 * A card body for data whose backend module is not built yet. Decision D-D rules
 * out mock data, so the screen names what it is waiting for instead.
 */
/**
 * Something the design shows that the system cannot fill yet. `waitingOn` is
 * said to the person reading the screen, in their words: what is missing, or
 * where to look meanwhile. Never a module, issue or table name.
 */
export function Pending({ what, waitingOn }: { what: string; waitingOn: string }): React.JSX.Element {
  return (
    <Notice tone="neutral" title={`Not available yet: ${what}`}>
      {waitingOn}
    </Notice>
  );
}
