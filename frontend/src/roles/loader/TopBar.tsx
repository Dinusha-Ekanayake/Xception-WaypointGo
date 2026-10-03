import type { ReactNode } from "react";
import { CountBadge, Icon, cx, useShell } from "@shared/ui";
import { useT } from "./i18n.tsx";
import { GearIcon, LockIcon, MoonIcon, SunIcon, SwapIcon } from "./icons.tsx";
import { useTheme } from "./theme.tsx";
import { initials } from "./ui.tsx";
import { clock } from "@shared/wording";

// The loader's top bar, Figma "08 Loader · Phone" (01 Dock board, 02 Load
// sheet) and the tablet pages. Connection state is always on screen: the
// resilient tier keeps working offline, and the loader must know their ticks
// are waiting.
//
// Phone: the brand or back, then theme and settings; under it, when a loader
// is working, their name (it opens Settings, as the picture does on tablets)
// with the bell and the lock, and Switch user. Tablet: one row, the bell between the name and the actions. The bell carries the unread count
// while anything is unread (issue #118). "Synced 02:23" is a button: it sends
// what waits and reads the board again.

const round = "flex size-12 shrink-0 items-center justify-center rounded-full bg-go-card text-go-ink shadow-go-float";

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
  onSwitch,
  onSettings,
  unread,
  onNotifications,
  onSync,
  syncing,
}: {
  /** The loader working on the device, or null while nobody is. */
  displayName: string | null;
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
  onSwitch?: () => void;
  onSettings: () => void;
  /** Unread notifications; null while unknown. */
  unread: number | null;
  onNotifications?: () => void;
  onSync: () => void;
  syncing: boolean;
}): React.JSX.Element {
  const tr = useT();
  const { theme, setTheme } = useTheme();
  const shell = useShell();
  const sync = !online
    ? waiting > 0
      ? tr("Offline · {n} saved on this device", { n: waiting })
      : tr("Offline · showing last sync")
    : waiting > 0
      ? tr("Sending {n}…", { n: waiting })
      : syncedAt
        ? tr("Synced {time}", { time: clock(syncedAt) })
        : tr("Connecting…");

  const back = onBack && (
    <button type="button" onClick={onBack} aria-label={tr("Back to departures")} className={round}>
      <Icon name="arrow-left" />
    </button>
  );
  const brand = <span className="w-[68px] shrink-0 text-[40px] leading-none font-extrabold text-go-ink">GO</span>;
  const label = syncing && online ? tr("Syncing…") : sync;
  const status = (
    <span role="status" className={cx("truncate", online ? "text-go-ink/85" : "font-medium text-go-warning-text")}>
      {!online && <span aria-hidden className="mr-1.5 inline-block size-2 rounded-full bg-go-warning" />}
      {label}
    </span>
  );
  // Tapping the time syncs now (issue #118). Offline it still checks what waits.
  const syncButton = (className: string) => (
    <button
      type="button"
      onClick={onSync}
      disabled={syncing}
      aria-label={`${label}. ${tr("Sync now")}`}
      className={cx("text-left disabled:cursor-wait", className)}
    >
      {status}
    </button>
  );
  const hasUnread = (unread ?? 0) > 0;
  const bellLabel = hasUnread ? tr("Notifications, {n} unread", { n: unread ?? 0 }) : tr("Notifications");
  const bell = (className: string) =>
    onNotifications && (
      <button type="button" onClick={onNotifications} aria-label={bellLabel} className={cx("relative flex shrink-0 items-center justify-center text-go-ink", className)}>
        <Icon name="bell" />
        <CountBadge count={unread} />
      </button>
    );
  const sampleBadge = sample && (
    <span className="shrink-0 rounded-full bg-go-warning-tint px-3 py-1.5 text-xs font-medium text-go-warning-text">{tr("Sample data")}</span>
  );
  const dark = theme === "dark";
  const actions: ReactNode = (
    <>
      <button
        type="button"
        onClick={() => setTheme(dark ? "light" : "dark")}
        aria-label={tr(dark ? "Use the light theme" : "Use the dark theme")}
        className={round}
      >
        {dark ? <MoonIcon /> : <SunIcon />}
      </button>
      <button type="button" onClick={onSettings} aria-label={tr("Settings")} className={round}>
        <GearIcon />
      </button>
    </>
  );
  const lock = onLock && (
    <button type="button" onClick={onLock} aria-label={tr("Lock loader")} className="flex size-10 shrink-0 items-center justify-center rounded-full text-go-muted">
      <LockIcon />
    </button>
  );
  const swap = onSwitch && (
    <button type="button" onClick={onSwitch} aria-label={tr("Switch user")} className={round}>
      <SwapIcon />
    </button>
  );

  return (
    <header className="sticky top-0 z-30 bg-go-canvas">
      {/* Phone. */}
      <div className="flex flex-col gap-3 px-4 pt-4 pb-3.5 md:hidden">
        <div className="flex w-full items-center gap-2">
          {back ?? brand}
          {back ? (
            <span className="min-w-0 flex-1 truncate text-[13px]">{syncButton("min-h-12 max-w-full")}</span>
          ) : (
            <div className="flex min-w-0 flex-1 flex-col">
              <span className="text-[18px] font-semibold text-go-ink">{tr("Loader")}</span>
              <span className="text-[13px]">{syncButton("min-h-6")}</span>
            </div>
          )}
          {sampleBadge}
          {actions}
        </div>
        {displayName && (
          <div className="flex w-full items-center justify-between gap-2">
            <span className="flex h-12 min-w-0 items-center gap-1 rounded-full border border-go-muted pr-1 pl-5 text-[15px] text-go-muted">
              <button type="button" onClick={onSettings} aria-label={`${tr("Settings")}: ${displayName}`} className="min-h-10 truncate text-left">
                {displayName}
              </button>
              {bell("size-10")}
              {lock}
            </span>
            <div className="flex items-center gap-2">
              {shell?.sync}
              {swap}
            </div>
          </div>
        )}
      </div>

      {/* Tablet and up. */}
      <div className="hidden flex-wrap items-center gap-x-3.5 gap-y-3 px-8 py-6 md:flex lg:px-10 lg:py-[34px]">
        {back ?? brand}
        <div className={cx("flex min-w-[140px] flex-col", back && "max-lg:order-last max-lg:basis-full")}>
          <span className="text-[18px] font-semibold text-go-ink">{title ?? tr("Waypoint · Loader")}</span>
          <span className="text-[14px] text-go-ink/85">{subtitle ?? tr("Depot {depot}", { depot })}</span>
        </div>
        {syncButton("flex min-h-12 shrink-0 items-center gap-2 rounded-full bg-go-card pr-3.5 pl-3 text-[14px] shadow-go-float min-[1700px]:text-[16px]")}
        <span className="flex-1" />
        {sampleBadge}
        {displayName && (
          <span className="flex min-h-12 shrink-0 items-center gap-2 rounded-full bg-go-card py-1 pr-1 pl-1 shadow-go-float lg:pl-1">
            <button type="button" onClick={onSettings} aria-label={`${tr("Settings")}: ${displayName}`} className="flex items-center gap-2">
              <span className="flex size-10 items-center justify-center rounded-full bg-go-soft text-[13px] font-semibold text-go-on-soft">
                {initials(displayName)}
              </span>
              <span className="text-[14px] text-go-muted max-lg:hidden">{displayName}</span>
            </button>
            {lock}
          </span>
        )}
        {displayName && bell("size-12 rounded-full bg-go-card shadow-go-float")}
        {shell?.sync}
        {swap}
        {actions}
      </div>
    </header>
  );
}
