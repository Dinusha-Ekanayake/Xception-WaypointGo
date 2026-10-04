"use client";

import type { ReactNode } from "react";
import { ConnectionStatus, CountBadge, Icon, Notice } from "@shared/ui";
import { DepotSwitch } from "./depotScope.tsx";
import { GlobalSearchSlot } from "./GlobalSearch.tsx";
import { useDispatcherInbox } from "./inbox.tsx";
import { clock } from "@shared/wording";

// Title, sync pill and notifications bell, as on every dispatcher screen. The
// dispatcher is online only (src/shared/offline/tiers.ts), so going offline
// turns the page read only and the banner says so rather than failing a click.
// "Synced 4:12 PM" reads the screen again when tapped (issue #118); the bell
// opens the notifications panel, plain as Figma draws it, with the count read
// out to a screen reader.

export default function PageHeader({
  title,
  subtitle,
  online,
  lastSyncedAt,
  tools,
  onSync,
  syncing,
  quiet = false,
  hideDepots = false,
}: {
  title: string;
  subtitle: string;
  online: boolean;
  lastSyncedAt: Date | null;
  tools?: ReactNode;
  /** Reads this screen again; the pill becomes a button. */
  onSync?: () => void;
  syncing?: boolean;
  /** Leave out the sync pill and the bell, as the Plan screen is drawn. Offline still says so. */
  quiet?: boolean;
  /** A screen that is not by depot (Forecast reads every depot's model) leaves the switch out. */
  hideDepots?: boolean;
}): React.JSX.Element {
  const inbox = useDispatcherInbox();
  const unread = inbox?.inbox.unread ?? 0;
  return (
    <>
      <header className="flex w-full flex-wrap items-center gap-3">
        <div className="flex min-w-[240px] flex-1 flex-col gap-0.5">
          <h1 className="text-[26px] font-medium tracking-normal text-go-ink md:truncate md:text-[30px]">{title}</h1>
          <p className="truncate text-sm text-go-secondary">{subtitle}</p>
        </div>
        <div className="flex flex-wrap items-center justify-end gap-3">
          {!hideDepots && <DepotSwitch />}
          {tools}
          {/* The search box is always here, even on a quiet header (Plan): it is a way to get
              somewhere else, not status chrome, so it stays however the rest of the cluster is drawn. */}
          <GlobalSearchSlot />
          {!quiet && (
            // The status cluster stays together: the bell never wraps away from the sync pill.
            <span className="flex shrink-0 items-center gap-3">
              <ConnectionStatus online={online} lastSyncedAt={lastSyncedAt} offlineNote="read only" onSync={onSync} syncing={syncing} />
              <button
                type="button"
                disabled={!inbox}
                onClick={() => inbox?.setOpen(true)}
                aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"}
                className="relative flex rounded-[21px] bg-go-card p-[11px] disabled:cursor-not-allowed"
              >
                <Icon name="bell" />
                <CountBadge count={unread} />
              </button>
            </span>
          )}
        </div>
      </header>
      {!online && (
        <Notice tone="warning" title="You are offline. The dispatcher screens are read only." live>
          {lastSyncedAt ? `What you see was loaded at ${clock(lastSyncedAt)}. ` : ""}
          Changes are turned off until the connection returns, and live updates are paused.
        </Notice>
      )}
    </>
  );
}
