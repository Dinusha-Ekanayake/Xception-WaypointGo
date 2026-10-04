import { cx } from "@shared/ui";
import type { Status } from "../../data/runs.ts";
import { Button, Chip } from "../../ui.tsx";
import { useT } from "../../i18n.tsx";

// One row of "05a Deliveries": the time box, what is coming, where it stands and
// what the store can do. On a phone the chip and the buttons wrap below.

export type RowAction = { label: string; tone?: "ink" | "plain"; onClick: () => void };

export default function Row({
  time,
  title,
  tag,
  badge,
  line,
  sub,
  status,
  actions,
  highlight,
  onClick,
}: {
  /** "ETA 05:44", "Window 05:00", "Sat 26 05:48". */
  time: { label: string; value: string; tone?: "warm" | "mint" | "plain" };
  title: string;
  /** "Refrigerated vehicle", "Chilled". */
  tag?: string;
  /** "2 orders". */
  badge?: string;
  line?: string;
  sub?: string;
  status: Status;
  actions: RowAction[];
  /** Waiting for the store: outlined, as the row to act on. */
  highlight?: boolean;
  onClick?: () => void;
}): React.JSX.Element {
  // Labels arrive in English from the data layer; a label with no translation stays as it is.
  const t = useT();
  return (
    <article
      aria-label={title}
      onClick={onClick}
      className={cx(
        "flex flex-col gap-3 rounded-[20px] bg-white p-3 lg:flex-row lg:items-center lg:gap-4 lg:pr-4",
        highlight && "outline-2 outline-[#0f766e]",
        onClick && "cursor-pointer transition-colors hover:bg-go-surface/60",
      )}
    >
      <div className="flex min-w-0 flex-1 items-center gap-4">
        <span
          className={cx(
            "flex w-[84px] shrink-0 flex-col items-center rounded-[14px] py-2",
            time.tone === "warm" ? "bg-[#fbf1e1]" : time.tone === "mint" ? "bg-go-mint" : "bg-go-canvas",
          )}
        >
          <span className="text-[11px] text-go-muted">{t(time.label)}</span>
          <span className="text-[20px] leading-tight font-semibold text-black">{time.value}</span>
        </span>
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="flex flex-wrap items-center gap-2">
            <span className="text-[17px] font-medium text-black">{title}</span>
            {tag && <Chip outline>{t(tag)}</Chip>}
            {badge && <Chip tone="muted">{badge}</Chip>}
          </span>
          {line && <span className="text-[14px] text-black">{line}</span>}
          {sub && <span className="text-[13px] text-go-muted">{sub}</span>}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-2 lg:flex-nowrap" onClick={(e) => e.stopPropagation()}>
        <Chip tone={status.tone}>{t(status.label)}</Chip>
        {actions.map((a) => (
          <span key={a.label} className="min-w-[112px] flex-1 lg:flex-none">
            <Button tone={a.tone ?? "plain"} onClick={a.onClick}>
              {t(a.label)}
            </Button>
          </span>
        ))}
      </div>
    </article>
  );
}
