import { Icon, ShellActions, cx, formatClock } from "@shared/ui";
import { LanguagePicker, useT } from "./i18n.tsx";

// The loader's top bar, Figma "02 Dock board" (tablet) and "01 Dock board"
// (phone). Connection state is always on screen: the resilient tier keeps
// working offline, and the loader must know their ticks are waiting.
//
// Phone: two rows, the name pill under the brand. Tablet (md and up): one row
// with the sync pill and the name as an avatar pill; labels on the actions
// from lg, icon buttons below that, as in the portrait designs.

const pill = "flex min-h-12 shrink-0 items-center rounded-full bg-white shadow-[0_5px_20px_rgba(0,0,0,0.09)]";

function initials(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? "") : "")).toUpperCase();
}

export default function TopBar({
  displayName,
  depot,
  title,
  subtitle,
  online,
  syncedAt,
  waiting,
  sample,
  onBack,
  onLock,
  lockDisabled = false,
}: {
  displayName: string;
  depot: string;
  /** The open trip, replacing the brand on tablets. */
  title?: string;
  subtitle?: string;
  online: boolean;
  syncedAt: Date | null;
  waiting: number;
  sample: boolean;
  onBack?: () => void;
  onLock?: () => void;
  lockDisabled?: boolean;
}): React.JSX.Element {
  const tr = useT();
  const sync = !online
    ? waiting > 0
      ? tr("Offline · {n} saved on this device", { n: waiting })
      : tr("Offline · showing last sync")
    : waiting > 0
      ? tr("Sending {n}…", { n: waiting })
      : syncedAt
        ? tr("Synced {time}", { time: formatClock(syncedAt) })
        : tr("Connecting…");

  const back = onBack && (
    <button
      type="button"
      onClick={onBack}
      aria-label={tr("Back to departures")}
      className="flex size-12 shrink-0 items-center justify-center rounded-full bg-white drop-shadow-[0_5px_10px_rgba(0,0,0,0.09)]"
    >
      <Icon name="arrow-left" />
    </button>
  );
  const brand = <span className="w-[68px] shrink-0 text-[40px] leading-none font-extrabold text-black">GO</span>;
  const status = (
    <span role="status" className={cx("truncate", online ? "text-black/85" : "font-medium text-go-warning-text")}>
      {!online && <span aria-hidden className="mr-1.5 inline-block size-2 rounded-full bg-go-warning" />}
      {sync}
    </span>
  );
  const sampleBadge = sample && (
    <span className="shrink-0 rounded-full bg-go-warning-tint px-3 py-1.5 text-xs font-medium text-go-warning-text">{tr("Sample data")}</span>
  );

  return (
    <header className="sticky top-0 z-30 bg-go-canvas">
      {/* Phone. */}
      <div className="flex flex-col gap-3 px-4 pt-4 pb-3.5 md:hidden">
        <div className="flex w-full items-center gap-2">
          {back ?? brand}
          {back ? (
            <span className="min-w-0 flex-1 truncate text-[13px]">{status}</span>
          ) : (
            <div className="flex min-w-0 flex-1 flex-col">
              <span className="text-[18px] font-semibold text-black">{tr("Loader")}</span>
              <span className="text-[13px]">{status}</span>
            </div>
          )}
          {sampleBadge}
        </div>
        <div className="flex w-full items-center justify-between gap-2">
          <span className="flex h-10 min-w-0 items-center gap-1 rounded-full border border-go-muted pr-2 pl-4 text-[14px] text-go-muted">
            <span className="truncate">{displayName}</span>
            <span className="ml-1 flex size-8 shrink-0 items-center justify-center" title={tr("Signed in on this device")}>
              <Icon name="lock" />
            </span>
          </span>
          <div className="flex items-center gap-1">
            {onLock && <button type="button" onClick={onLock} disabled={lockDisabled} aria-label={tr("Lock loader")} title={tr(lockDisabled ? "Sync saved work before locking" : "Lock loader")} className="flex size-12 items-center justify-center rounded-full bg-white disabled:opacity-50"><Icon name="lock" /></button>}
            <LanguagePicker compact />
            <ShellActions compact />
          </div>
        </div>
      </div>

      {/* Tablet and up. */}
      <div className="hidden flex-wrap items-center gap-x-3.5 gap-y-3 px-8 py-6 md:flex lg:px-10 lg:py-[34px]">
        {back ?? brand}
        <div className={cx("flex min-w-[140px] flex-col", back && "max-lg:order-last max-lg:basis-full")}>
          <span className="text-[18px] font-semibold text-black">{title ?? tr("Waypoint · Loader")}</span>
          <span className="text-[14px] text-black/85">{subtitle ?? tr("Depot {depot}", { depot })}</span>
        </div>
        <span className={cx(pill, "gap-2 pr-3.5 pl-3 text-[14px]")}>{status}</span>
        <span className="flex-1" />
        {sampleBadge}
        <span className={cx(pill, "gap-2 py-2 pr-2 pl-2 lg:pr-4")} title={tr("Signed in on this device")}>
          <span className="flex size-8 items-center justify-center rounded-[16px] bg-go-mint text-[12px] font-semibold text-black">
            {initials(displayName)}
          </span>
          <span className="text-[14px] text-go-muted max-lg:hidden">{displayName}</span>
        </span>
        {onLock && <button type="button" onClick={onLock} disabled={lockDisabled} title={tr(lockDisabled ? "Sync saved work before locking" : "Lock loader")} className="flex min-h-12 items-center gap-2 rounded-full bg-white px-4 text-[14px] font-medium disabled:opacity-50"><Icon name="lock" /> {tr("Lock")}</button>}
        <LanguagePicker />
        <ShellActions />
      </div>
    </header>
  );
}
