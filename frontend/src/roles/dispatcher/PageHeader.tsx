"use client";

import type { ReactNode } from "react";
import { ConnectionStatus, Icon, Notice, formatClock } from "@shared/ui";

// Title, sync pill and notifications bell, as on every dispatcher screen. The
// dispatcher is online only (src/shared/offline/tiers.ts), so going offline
// turns the page read only and the banner says so rather than failing a click.

export default function PageHeader({
  title,
  subtitle,
  online,
  lastSyncedAt,
  tools,
}: {
  title: string;
  subtitle: string;
  online: boolean;
  lastSyncedAt: Date | null;
  tools?: ReactNode;
}): React.JSX.Element {
  return (
    <>
      <header className="flex w-full items-center gap-3">
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <h1 className="truncate text-[30px] font-medium tracking-normal text-go-ink">{title}</h1>
          <p className="truncate text-sm text-go-secondary">{subtitle}</p>
        </div>
        {tools}
        <ConnectionStatus online={online} lastSyncedAt={lastSyncedAt} offlineNote="read only" />
        <button
          type="button"
          disabled
          title="Notifications arrive with the Notification module (#14)"
          aria-label="Notifications (not available yet)"
          className="flex rounded-[21px] bg-white p-[11px] disabled:cursor-not-allowed"
        >
          <Icon name="bell" />
        </button>
      </header>
      {!online && (
        <Notice tone="warning" title="You are offline. The dispatcher screens are read only." live>
          {lastSyncedAt ? `What you see was loaded at ${formatClock(lastSyncedAt)}. ` : ""}
          Changes are turned off until the connection returns, and live updates are paused.
        </Notice>
      )}
    </>
  );
}
