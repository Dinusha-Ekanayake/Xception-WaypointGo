import { cx, formatClock } from "@shared/ui";

// The store's top bar from "02 Home". Connection state is always on screen: the
// resilient tier keeps working offline, and the manager must know an order is
// only on this phone until it is sent.

export default function TopBar({
  online,
  syncedAt,
  waiting,
  sample,
}: {
  online: boolean;
  syncedAt: Date | null;
  waiting: number;
  sample: boolean;
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
    <header className="flex w-full items-center gap-2.5">
      <span className="text-[40px] leading-none font-extrabold text-black">GO</span>
      <span className="rounded-full bg-go-mint px-2.5 py-[5px] text-[13px] font-medium text-black">Store</span>
      <span
        role="status"
        className={cx("min-w-0 flex-1 truncate text-right text-[13px]", online ? "text-go-muted" : "font-medium text-go-warning-text")}
      >
        {!online && <span aria-hidden className="mr-1.5 inline-block size-2 rounded-full bg-go-warning" />}
        {sync}
      </span>
      {sample && <span className="shrink-0 rounded-full bg-go-warning-tint px-3 py-1.5 text-xs font-medium text-go-warning-text">Sample data</span>}
    </header>
  );
}
