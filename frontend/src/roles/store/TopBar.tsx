import { useState } from "react";
import type { OutletView } from "@shared/domain/types";
import { CountBadge, Icon, ShellActions, cx, useStateAnnouncement } from "@shared/ui";
import AccountMenu from "./AccountMenu.tsx";
import { initials } from "./ui.tsx";
import { clock } from "@shared/wording";
import { useKeptSince } from "@shared/offline";

// The store's top bar from "02 Home" (mobile and desktop). Connection state is always on screen: the
// resilient tier keeps working offline, and the manager must know an order is
// only on this phone until it is sent. "Synced 02:23 AM" is a button that sends
// what waits and reads again, and the bell opens the notifications (issue #118).

export default function TopBar({
  online,
  syncedAt,
  waiting,
  sample,
  displayName,
  outlet,
  onEditProfile,
  onEditStore,
  onSync,
  syncing,
  unread,
  onNotifications,
}: {
  online: boolean;
  syncedAt: Date | null;
  waiting: number;
  sample: boolean;
  displayName: string;
  outlet: OutletView | null;
  onEditProfile: () => void;
  onEditStore: () => void;
  onSync: () => void;
  syncing: boolean;
  /** Unread notifications; null while unknown. */
  unread: number | null;
  onNotifications?: () => void;
}): React.JSX.Element {
  // Phones have no sidebar, so the account menu opens from here as a bottom sheet.
  const [account, setAccount] = useState(false);
  // When a screen shows what this device kept rather than the server's answer, say from when (#201).
  const since = useKeptSince();
  const sync = !online
    ? waiting > 0
      ? `Offline · ${waiting} saved on this phone`
      : since
        ? `Offline · showing ${clock(since)}`
        : "Offline · showing last sync"
    : since
      ? `Server unreachable · showing ${clock(since)}`
      : waiting > 0
      ? `Sending ${waiting}…`
      : syncing
        ? "Syncing…"
        : syncedAt
          ? `Synced ${clock(syncedAt)}`
          : "Connecting…";
  // On a narrow phone the pill keeps "Synced 10:49" but drops the longer words
  // of the other states; the full sentence stays its accessible name. The round
  // buttons step down to 40 px there to make room (UX plan U9).
  const short = !online
    ? waiting > 0
      ? `Offline · ${waiting}`
      : "Offline"
    : since
      ? "Unreachable"
      : waiting > 0
        ? `Sending ${waiting}`
        : syncing
          ? "Syncing…"
          : syncedAt
            ? `Synced ${clock(syncedAt)}`
            : "…";
  const synced = online && !since && waiting === 0 && !syncing && syncedAt !== null;
  const hasUnread = (unread ?? 0) > 0;
  // Only the connection STATE is announced to screen readers, not the clock inside
  // the visible text, so it does not re-announce on every poll (issue #118 follow-up).
  const state = !online ? (waiting > 0 ? `Offline, ${waiting} saved on this phone` : "Offline") : since ? "Server unreachable" : waiting > 0 ? "Sending" : syncing ? "Syncing" : "Synced";
  const announcement = useStateAnnouncement(state);

  return (
    <header className="flex w-full items-center gap-2.5 lg:absolute lg:top-8 lg:right-10 lg:w-auto">
      {/* On desktops the brand is in the sidebar; the sync pill stays top right, as in "02 Home". */}
      <span className="text-[40px] leading-none font-extrabold text-black lg:hidden">GO</span>
      {/* Below 440 px the bar cannot hold the chip and a readable sync time; GO stays (UX plan U9). */}
      <span className="rounded-full bg-go-mint px-2.5 py-[5px] text-[13px] font-medium text-black max-[439px]:hidden lg:hidden">Store</span>
      <span className="flex-1" />
      {sample && <span className="shrink-0 rounded-full bg-go-warning-tint px-3 py-1.5 text-xs font-medium text-go-warning-text">Sample data</span>}
      <button
        type="button"
        onClick={onSync}
        disabled={syncing}
        aria-label={`${sync}. Sync now`}
        className={cx(
          "min-h-12 min-w-0 truncate text-right text-[13px] max-[439px]:shrink-0 disabled:cursor-wait lg:flex lg:items-center lg:rounded-full lg:bg-white lg:px-4 lg:text-[14px] lg:shadow-[0_5px_20px_rgba(0,0,0,0.09)]",
          online ? "text-go-muted lg:text-black" : "font-medium text-go-warning-text",
        )}
      >
        <span>
          {!online && <span aria-hidden className="mr-1.5 inline-block size-2 rounded-full bg-go-warning" />}
          <span className="max-[439px]:hidden">{sync}</span>
          <span aria-hidden className="min-[440px]:hidden">
            {synced && <span className="mr-1.5 inline-block size-2 rounded-full bg-go-success" />}
            {short}
          </span>
        </span>
        {announcement}
      </button>
      {onNotifications && (
        <button
          type="button"
          onClick={onNotifications}
          aria-label={hasUnread ? `Notifications, ${unread} unread` : "Notifications"}
          className="relative flex size-12 shrink-0 items-center justify-center rounded-full bg-white text-black shadow-[0_5px_20px_rgba(0,0,0,0.09)] max-[439px]:size-10"
        >
          <Icon name="bell" />
          <CountBadge count={unread} />
        </button>
      )}
      <span className="lg:hidden max-[439px]:[&_button]:size-10 max-[439px]:[&_button]:min-h-10">
        <ShellActions compact />
      </span>
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={account}
        aria-label={`Account: ${displayName}`}
        onClick={() => setAccount(true)}
        className="flex size-12 shrink-0 items-center justify-center rounded-full bg-go-mint text-[14px] font-semibold text-black lg:hidden max-[439px]:size-10 max-[439px]:text-[13px]"
      >
        {initials(displayName)}
      </button>
      {account && (
        <AccountMenu
          placement="sheet"
          displayName={displayName}
          initials={initials(displayName)}
          outlet={outlet}
          onEditProfile={onEditProfile}
          onEditStore={onEditStore}
          onClose={() => setAccount(false)}
        />
      )}
    </header>
  );
}
