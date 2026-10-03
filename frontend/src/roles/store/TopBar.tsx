import { useState } from "react";
import type { OutletView } from "@shared/domain/types";
import { McpButton, ShellActions, cx, formatClock, useShell } from "@shared/ui";
import AccountMenu from "./AccountMenu.tsx";
import { initials } from "./ui.tsx";

// The store's top bar from "02 Home" (mobile and desktop). Connection state is always on screen: the
// resilient tier keeps working offline, and the manager must know an order is
// only on this phone until it is sent.

export default function TopBar({
  online,
  syncedAt,
  waiting,
  sample,
  displayName,
  outlet,
  onEditProfile,
  onEditStore,
}: {
  online: boolean;
  syncedAt: Date | null;
  waiting: number;
  sample: boolean;
  displayName: string;
  outlet: OutletView | null;
  onEditProfile: () => void;
  onEditStore: () => void;
}): React.JSX.Element {
  const shell = useShell();
  // Phones have no sidebar, so the account menu opens from here as a bottom sheet.
  const [account, setAccount] = useState(false);
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
    <header className="flex w-full items-center gap-2.5 lg:absolute lg:top-8 lg:right-10 lg:w-auto">
      {/* On desktops the brand is in the sidebar; the sync pill stays top right, as in "02 Home". */}
      <span className="text-[40px] leading-none font-extrabold text-black lg:hidden">GO</span>
      <span className="rounded-full bg-go-mint px-2.5 py-[5px] text-[13px] font-medium text-black lg:hidden">Store</span>
      <span className="flex-1" />
      {sample && <span className="shrink-0 rounded-full bg-go-warning-tint px-3 py-1.5 text-xs font-medium text-go-warning-text">Sample data</span>}
      <span
        role="status"
        className={cx(
          "min-w-0 truncate text-right text-[13px] lg:flex lg:min-h-12 lg:items-center lg:rounded-full lg:bg-white lg:px-4 lg:text-[14px] lg:shadow-[0_5px_20px_rgba(0,0,0,0.09)]",
          online ? "text-go-muted lg:text-black" : "font-medium text-go-warning-text",
        )}
      >
        {!online && <span aria-hidden className="mr-1.5 inline-block size-2 rounded-full bg-go-warning" />}
        {sync}
      </span>
      <McpButton url={shell?.mcpUrl ?? null} compact />
      <span className="lg:hidden">
        <ShellActions compact />
      </span>
      <button
        type="button"
        aria-haspopup="dialog"
        aria-expanded={account}
        aria-label={`Account: ${displayName}`}
        onClick={() => setAccount(true)}
        className="flex size-12 shrink-0 items-center justify-center rounded-full bg-go-mint text-[14px] font-semibold text-black lg:hidden"
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
