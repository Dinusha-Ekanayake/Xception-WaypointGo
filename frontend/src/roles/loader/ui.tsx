import type { ReactNode } from "react";
import { Icon, cx, type IconName } from "@shared/ui";
import type { SessionStatus, Temperature } from "@shared/domain/types";
import { STATUS_LABEL } from "./data/manifest.ts";

// Loader pieces from Figma "08 Loader · Phone". Touch targets are at least 48px
// and the main actions 56px: the dock is worked in gloves.

export function TempBadge({ temperature }: { temperature: Temperature }): React.JSX.Element {
  return temperature === "chilled" ? (
    <span className="inline-flex shrink-0 items-center gap-[5px] rounded-full border border-black px-2.5 py-1 text-[13px] font-medium tracking-[0.52px] text-black">
      <Icon name="chilled" />
      CHILLED
    </span>
  ) : (
    <span className="inline-flex shrink-0 items-center gap-[5px] rounded-[6px] bg-[#f7eddf] py-1 pr-2.5 pl-2 text-[13px] font-medium tracking-[0.52px] text-[#8a5a1c]">
      <Icon name="box" />
      AMBIENT
    </span>
  );
}

const STATUS: Record<SessionStatus, { icon: IconName; text: string }> = {
  NOT_STARTED: { icon: "clock", text: "text-go-muted" },
  IN_PROGRESS: { icon: "loading", text: "text-[#b45309]" },
  BLOCKED: { icon: "triangle", text: "text-go-danger-strong" },
  COMPLETED: { icon: "check", text: "text-go-success" },
};

export function StatusChip({ status }: { status: SessionStatus }): React.JSX.Element {
  const s = STATUS[status];
  return (
    <span className={cx("inline-flex items-center gap-1.5 py-1.5 text-[15px] font-medium", s.text)}>
      <Icon name={s.icon} />
      {STATUS_LABEL[status]}
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
        size === "l" ? "min-h-14 rounded-[22px] text-[20px]" : "min-h-12 rounded-full text-[15px]",
        TONE[tone],
      )}
    >
      {children}
      {icon && <Icon name={icon} />}
    </button>
  );
}

export function Bar({ label, value, share }: { label: string; value: string; share: number }): React.JSX.Element {
  return (
    <div className="flex w-full flex-col gap-1.5">
      <div className="flex w-full text-[13px] font-medium">
        <span className="flex-1 text-go-muted">{label}</span>
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

/** A bottom sheet over a dimmed page on phones, as in the design's release and issue states; a dialog near the top on tablets. */
export function Sheet({ label, onClose, children }: { label: string; onClose: () => void; children: ReactNode }): React.JSX.Element {
  return (
    <div className="fixed inset-0 z-40 flex items-end justify-center md:items-start md:p-6 md:pt-10" role="presentation">
      <button type="button" aria-label="Close" onClick={onClose} className="absolute inset-0 bg-black/25 backdrop-blur-[6px]" />
      <div
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className="relative flex max-h-[92dvh] w-full max-w-[560px] flex-col gap-4 overflow-y-auto rounded-t-[32px] bg-white px-5 pt-6 pb-8 md:max-h-[calc(100dvh-64px)] md:rounded-[32px] md:px-7 md:pb-7"
      >
        {children}
      </div>
    </div>
  );
}
