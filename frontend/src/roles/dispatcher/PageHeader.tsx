"use client";

import type { ReactNode } from "react";
import { ConnectionStatus, Icon, McpButton, Notice, useShell } from "@shared/ui";
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
}: {
  title: string;
  subtitle: string;
  online: boolean;
  lastSyncedAt: Date | null;
  tools?: ReactNode;
  /** Reads this screen again; the pill becomes a button. */
  onSync?: () => void;
  syncing?: boolean;
}): React.JSX.Element {
  const shell = useShell();
  const inbox = useDispatcherInbox();
  const unread = inbox?.inbox.unread ?? 0;
  return (
    <>
      <header className="flex w-full flex-wrap items-center gap-3">
        <div className="flex min-w-[240px] flex-1 flex-col gap-0.5">
          <h1 className="text-[26px] md:truncate md:text-[30px] font-medium tracking-normal text-go-ink">{title}</h1>
          <p className="truncate text-sm text-go-secondary">{subtitle}</p>
        </div>
        {tools}
        <ConnectionStatus online={online} lastSyncedAt={lastSyncedAt} offlineNote="read only" onSync={onSync} syncing={syncing} />
        <McpButton url={shell?.mcpUrl ?? null} className="flex min-h-[42px] items-center gap-2 rounded-[21px] bg-white px-3.5 text-sm font-medium text-go-ink" />
        <button
          type="button"
          disabled={!inbox}
          onClick={() => inbox?.setOpen(true)}
          aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"}
          className="flex rounded-[21px] bg-go-card p-[11px] disabled:cursor-not-allowed"
        >
          <Icon name="bell" />
        </button>
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
