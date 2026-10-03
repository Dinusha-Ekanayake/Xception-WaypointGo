"use client";

import { useState } from "react";
import { cx } from "@shared/ui";
import { ago, isUnread, kindOf, toneOf, TONE_STYLE } from "@shared/notifications/inbox";
import type { NotificationView } from "@shared/domain/types";
import { useDispatcherInbox } from "./inbox.tsx";
import type { ViewId } from "./navigation.ts";
import { clock } from "@shared/wording";

// Figma "05 Dispatcher Desktop": the notifications panel (189:23606) and the
// Overview card (189:10739). Each row: a small grey line naming the kind and
// when, the message, then "Mark as read". Figma also has "Reply"; there is no
// messaging between roles to reply through, so it is left out rather than
// shown doing nothing.

export function NotificationRows({
  items,
  onNavigate,
  limit,
}: {
  items: NotificationView[];
  onNavigate: (view: ViewId) => void;
  limit?: number;
}): React.JSX.Element {
  const ctx = useDispatcherInbox();
  const [error, setError] = useState<string | null>(null);
  if (!ctx) return <></>;
  const { inbox, setOpen, viewOf } = ctx;
  const now = new Date();
  const shown = limit ? items.slice(0, limit) : items;
  const markRead = (n: NotificationView) => {
    setError(null);
    inbox.markRead([n.notificationId]).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : "Could not mark it read."));
  };

  return (
    <>
      {error && <p role="alert" className="text-[13px] text-go-danger-strong">{error}</p>}
      <ul className="flex flex-col gap-3">
        {shown.map((n) => {
          const fresh = isUnread(n);
          const view = viewOf(n);
          const tone = TONE_STYLE[toneOf(n)];
          return (
            <li
              key={n.notificationId}
              className={cx(
                "flex flex-col gap-1 rounded-go-card-s border-l-4 px-3.5 py-3",
                tone.edge,
                fresh ? tone.tint : "border-y border-r border-y-go-divider border-r-go-divider bg-go-card",
              )}
            >
              <span className="flex items-center justify-between gap-2 text-xs">
                <span className={cx("flex items-center gap-1.5 font-medium", tone.label)}>
                  <span aria-hidden className={cx("size-2 rounded-full", tone.dot)} />
                  {kindOf(n.eventType).label}
                  {fresh && <span className="sr-only">, unread</span>}
                </span>
                <span className="text-go-secondary">{ago(n.createdAt, now)}</span>
              </span>
              <span className={cx("text-[15px] text-go-ink", fresh && "font-medium")}>{n.title}</span>
              <span className="text-[13px] text-go-secondary">{n.body}</span>
              <span className="flex gap-1 pt-1">
                {view && (
                  <button
                    type="button"
                    onClick={() => {
                      if (fresh) markRead(n);
                      setOpen(false);
                      onNavigate(view);
                    }}
                    className="min-h-9 rounded-full bg-go-soft px-3.5 text-[13px] font-medium text-go-on-soft"
                  >
                    Open
                  </button>
                )}
                {fresh && (
                  <button type="button" onClick={() => markRead(n)} disabled={!inbox.online} className="min-h-9 rounded-full px-3.5 text-[13px] font-medium text-go-teal disabled:opacity-50">
                    Mark as read
                  </button>
                )}
              </span>
            </li>
          );
        })}
      </ul>
    </>
  );
}

export default function NotificationsPanel({ onNavigate }: { onNavigate: (view: ViewId) => void }): React.JSX.Element | null {
  const ctx = useDispatcherInbox();
  if (!ctx?.open) return null;
  const { inbox, setOpen } = ctx;
  const unread = inbox.items.filter(isUnread).length;

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-end p-4 md:pt-24 md:pr-9" role="presentation">
      <button type="button" aria-label="Close notifications" onClick={() => setOpen(false)} className="absolute inset-0 bg-black/20" />
      <section
        role="dialog"
        aria-modal="true"
        aria-label="Notifications"
        className="relative flex max-h-[80dvh] w-full max-w-[520px] flex-col gap-3 overflow-y-auto rounded-go-panel bg-go-card p-6 shadow-go-card"
      >
        <header className="flex items-center justify-between gap-3">
          <h2 className="text-[22px] font-medium text-go-ink">Notifications</h2>
          <button type="button" onClick={() => setOpen(false)} className="min-h-9 rounded-full bg-go-surface px-3.5 text-sm font-medium text-go-teal">
            Close
          </button>
        </header>
        <p className="text-sm text-go-secondary">
          {unread > 0 ? `${unread} new · newest first` : "Recent messages · newest first"}
          {!inbox.live && inbox.online && inbox.unread !== null ? " · live updates paused, checking every 30 seconds" : ""}
        </p>
        {!inbox.online && (
          <p role="status" className="rounded-go-card-s bg-go-warning-tint px-3.5 py-2.5 text-sm text-go-warning-text">
            Offline{inbox.savedAt ? ` · saved at ${clock(inbox.savedAt)}` : ""}. Read state updates when you are back online.
          </p>
        )}
        {inbox.error && <p role="alert" className="text-sm text-go-danger-strong">{inbox.error}</p>}
        {inbox.items.length === 0 ? (
          <p className="py-6 text-center text-sm text-go-secondary">{inbox.loading ? "Loading…" : "No notifications yet."}</p>
        ) : (
          <NotificationRows items={inbox.items} onNavigate={onNavigate} />
        )}
        <span className="flex items-center justify-between gap-2 pt-1">
          {inbox.hasMore ? (
            <button type="button" onClick={inbox.more} disabled={inbox.loading} className="min-h-9 text-sm font-medium text-go-teal">
              Show more
            </button>
          ) : (
            <span />
          )}
          {unread > 0 && (
            <button
              type="button"
              disabled={!inbox.online}
              onClick={() => void inbox.markAllRead().catch(() => undefined)}
              className="min-h-9 rounded-full bg-go-ink px-4 text-sm font-medium text-go-card disabled:opacity-50"
            >
              Mark all as read
            </button>
          )}
        </span>
      </section>
    </div>
  );
}
