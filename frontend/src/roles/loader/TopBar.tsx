import { Icon, cx, formatClock } from "@shared/ui";

// The loader's top bar. Connection state is always on screen: the resilient
// tier keeps working offline, and the loader must know their ticks are waiting.

export default function TopBar({
  displayName,
  online,
  syncedAt,
  waiting,
  sample,
  onBack,
}: {
  displayName: string;
  online: boolean;
  syncedAt: Date | null;
  waiting: number;
  sample: boolean;
  onBack?: () => void;
}): React.JSX.Element {
  const sync = !online
    ? waiting > 0
      ? `Offline · ${waiting} saved on this phone`
      : "Offline · showing last sync"
    : waiting > 0
      ? `Sending ${waiting}…`
      : syncedAt
        ? `Synced ${formatClock(syncedAt)}`
        : "Connecting…";

  return (
    <header className="sticky top-0 z-30 flex flex-col gap-3 bg-go-canvas px-4 pt-4 pb-3.5">
      <div className="flex w-full items-center gap-2">
        {onBack ? (
          <button
            type="button"
            onClick={onBack}
            aria-label="Back to departures"
            className="flex size-12 shrink-0 items-center justify-center rounded-full bg-white drop-shadow-[0_5px_10px_rgba(0,0,0,0.09)]"
          >
            <Icon name="arrow-left" />
          </button>
        ) : (
          <span className="w-[68px] shrink-0 text-[40px] leading-none font-extrabold text-black">GO</span>
        )}
        <div className="flex min-w-0 flex-1 flex-col">
          <span className="text-[18px] font-semibold text-black">Loader</span>
          <span role="status" className={cx("truncate text-[13px]", online ? "text-black/85" : "font-medium text-go-warning-text")}>
            {!online && <span aria-hidden className="mr-1.5 inline-block size-2 rounded-full bg-go-warning" />}
            {sync}
          </span>
        </div>
        {sample && (
          <span className="shrink-0 rounded-full bg-go-warning-tint px-3 py-1.5 text-xs font-medium text-go-warning-text">
            Sample data
          </span>
        )}
      </div>
      <div className="flex w-full items-center">
        <span className="flex h-10 items-center gap-1 rounded-full border border-go-muted pr-2 pl-4 text-[14px] text-go-muted">
          {displayName}
          <span className="ml-1 flex size-8 items-center justify-center" title="Signed in on this device">
            <Icon name="lock" />
          </span>
        </span>
      </div>
    </header>
  );
}
