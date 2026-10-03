import { badgeText } from "../notifications/inbox.ts";
import { cx } from "./primitives.tsx";

/**
 * The unread count on a bell: a red bubble on the button's top right corner,
 * ringed in white so it reads against any icon. Nothing at zero. Decorative to
 * a screen reader: the button's own label carries the count.
 */
export function CountBadge({ count, className }: { count: number | null | undefined; className?: string }): React.JSX.Element | null {
  const text = badgeText(count);
  if (text === null) return null;
  return (
    <span
      aria-hidden
      className={cx(
        "pointer-events-none absolute -top-1 -right-1 flex h-[18px] min-w-[18px] items-center justify-center rounded-full bg-go-danger px-[5px] text-[11px] leading-none font-semibold text-white tabular-nums ring-2 ring-white",
        className,
      )}
    >
      {text}
    </span>
  );
}
