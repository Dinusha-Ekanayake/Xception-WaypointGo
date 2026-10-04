"use client";

import { useState } from "react";
import type { NotificationView } from "@shared/domain/types";
import type { Inbox } from "@shared/notifications/useInbox";
import { isUnread, kindOf, toneOf, TONE_STYLE } from "@shared/notifications/inbox";
import { Icon, SkeletonRows, cx } from "@shared/ui";
import { Drawer } from "../ui.tsx";
import { clock, dayLabel, depotToday } from "@shared/wording";

// The store manager's notifications (issue #118), Figma "14 Store Manager ·
// Desktop" and "15 · Mobile", 10 Notifications (11:117503 drawer, 11:125924
// sheet) and the Home card (11:117599). Each row: the kind in its colour with a
// dot, the time, a bold title, the detail; unread rows are filled, read rows
// outlined. Deferrals and expected arrivals are what the booklet asks the
// store to be told (p6).


/** "03:12" today, "Sat 19:10" before: depot time, whatever the phone's own zone. */
export function when(createdAt: string, now: Date = new Date()): string {
  const at = new Date(createdAt);
  const day = depotToday(at);
  if (day === depotToday(now)) return clock(at);
  return `${dayLabel(day).split(" ")[0]} ${clock(at)}`;
}

export type OpenSubject = (n: NotificationView) => void;

function Row({ n, onOpen }: { n: NotificationView; onOpen: (n: NotificationView) => void }): React.JSX.Element {
  const kind = kindOf(n.eventType);
  const fresh = isUnread(n);
  const tone = TONE_STYLE[toneOf(n)];
  return (
    <li>
      <button
        type="button"
        onClick={() => onOpen(n)}
        aria-label={`${fresh ? "Unread. " : ""}${kind.label}. ${n.title}. ${n.body}`}
        className={cx(
          "flex w-full flex-col gap-1 rounded-[16px] border-l-4 px-3.5 py-3 text-left",
          tone.edge,
          fresh ? tone.tint : "border-y border-r border-y-go-rule border-r-go-rule bg-white",
        )}
      >
        <span className="flex items-center justify-between gap-2 text-[13px]">
          <span className={cx("flex items-center gap-1.5 font-medium", tone.label)}>
            <span aria-hidden className={cx("size-2 rounded-full", tone.dot)} />
            {kind.label}
          </span>
          <span className="text-go-muted">{when(n.createdAt)}</span>
        </span>
        <span className="text-[16px] font-semibold text-black">{n.title}</span>
        <span className="text-[14px] text-go-muted">{n.body}</span>
      </button>
    </li>
  );
}

/** Marks a row read when it is opened, then shows its subject. */
function useOpen(inbox: Inbox, onSubject: OpenSubject): { open: (n: NotificationView) => void; error: string | null } {
  const [error, setError] = useState<string | null>(null);
  const open = (n: NotificationView) => {
    setError(null);
    if (isUnread(n) && inbox.online) {
      inbox.markRead([n.notificationId]).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : "Could not mark it read."));
    }
    onSubject(n);
  };
  return { open, error };
}

export function NotificationsDrawer({
  inbox,
  onSubject,
  onClose,
}: {
  inbox: Inbox;
  onSubject: OpenSubject;
  onClose: () => void;
}): React.JSX.Element {
  const [readIds, setReadIds] = useState<Set<string>>(() => new Set());
  const [lookedAt] = useState(() => new Date());
  const [actionError, setActionError] = useState<string | null>(null);

  const { open, error: openError } = useOpen(inbox, (n) => {
    onClose();
    onSubject(n);
  });

  const visibleItems = inbox.items.filter((n) => isUnread(n) && !readIds.has(n.notificationId));
  const unread = visibleItems.length;

  const handleOpen = (n: NotificationView) => {
    setReadIds((prev) => new Set(prev).add(n.notificationId));
    open(n);
  };

  const handleReadAll = async () => {
    setActionError(null);
    setReadIds((prev) => {
      const next = new Set(prev);
      for (const item of inbox.items) {
        next.add(item.notificationId);
      }
      return next;
    });
    try {
      await inbox.markAllRead(lookedAt);
    } catch (failure: unknown) {
      setActionError(failure instanceof Error ? failure.message : "Could not mark all as read.");
    }
  };

  const error = actionError ?? openError ?? inbox.error;

  return (
    <Drawer label="Notifications" onClose={onClose}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <h2 className="text-[26px] font-medium text-black">Notifications</h2>
          <p className="text-[14px] text-go-muted">{unread > 0 ? `${unread} new · today` : "All caught up"}</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={unread === 0 || !inbox.online}
            onClick={() => void handleReadAll()}
            className="flex h-10 items-center justify-center rounded-full border border-go-rule bg-white px-3.5 text-[14px] font-medium text-black hover:bg-go-canvas disabled:cursor-not-allowed disabled:opacity-40"
          >
            Read all
          </button>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close notifications"
            className="flex size-10 shrink-0 items-center justify-center rounded-full bg-go-canvas text-black"
          >
            <Icon name="close" />
          </button>
        </div>
      </div>
      {!inbox.online && (
        <p role="status" className="rounded-[14px] bg-go-warning-tint px-3.5 py-2.5 text-[14px] text-go-warning-text">
          Offline{inbox.savedAt ? ` · saved at ${clock(inbox.savedAt)}` : ""}. Read state updates when you&rsquo;re back online.
        </p>
      )}
      {inbox.online && !inbox.live && inbox.unread !== null && (
        <p role="status" className="text-[13px] text-go-muted">Live updates paused. Checking every 30 seconds.</p>
      )}
      {error && <p role="alert" className="text-[14px] text-go-danger-strong">{error}</p>}
      {visibleItems.length === 0 ? (
        inbox.loading ? (
          <SkeletonRows rows={3} label="Loading…" />
        ) : (
          <p className="py-8 text-center text-[15px] text-go-muted">No notifications yet. Order confirmations, deferrals and deliveries on the way appear here.</p>
        )
      ) : (
        <ul className="flex flex-col gap-3">
          {visibleItems.map((n) => (
            <Row key={n.notificationId} n={n} onOpen={handleOpen} />
          ))}
        </ul>
      )}
      {inbox.hasMore && (
        <button type="button" onClick={inbox.more} disabled={inbox.loading} className="min-h-11 text-[15px] font-medium text-go-teal">
          Show more
        </button>
      )}
      <span className="flex-1" />
      <button
        type="button"
        disabled={unread === 0 || !inbox.online}
        onClick={() => void handleReadAll()}
        className="min-h-[52px] shrink-0 rounded-full border border-go-rule bg-white text-[16px] font-medium text-black disabled:opacity-50"
      >
        Mark all as read
      </button>
    </Drawer>
  );
}

/** The Home card (11:117599): the bell, the count of new ones, the four newest. */
export function NotificationsCard({ inbox, onSubject, onAll }: { inbox: Inbox; onSubject: OpenSubject; onAll: () => void }): React.JSX.Element {
  const [readIds, setReadIds] = useState<Set<string>>(() => new Set());
  const { open, error } = useOpen(inbox, onSubject);
  const visibleItems = inbox.items.filter((n) => isUnread(n) && !readIds.has(n.notificationId));
  const unread = visibleItems.length;

  const handleOpen = (n: NotificationView) => {
    setReadIds((prev) => new Set(prev).add(n.notificationId));
    open(n);
  };

  return (
    <>
      <div className="flex items-center justify-between gap-2">
        <button type="button" onClick={onAll} className="flex items-center gap-2 text-[18px] font-medium text-black">
          <Icon name="bell" />
          Notifications
        </button>
        {unread > 0 && <span className="rounded-full bg-go-mint px-2.5 py-1 text-[13px] font-medium text-black">{unread} new</span>}
      </div>
      {error && <p role="alert" className="text-[13px] text-go-danger-strong">{error}</p>}
      {visibleItems.length === 0 ? (
        inbox.loading ? (
          <SkeletonRows rows={2} label="Loading…" />
        ) : (
          <p className="text-[14px] text-go-muted">No notifications yet. Order confirmations, deferrals and deliveries on the way appear here.</p>
        )
      ) : (
        <ul className="flex flex-col gap-3">
          {visibleItems.slice(0, 4).map((n) => (
            <Row key={n.notificationId} n={n} onOpen={handleOpen} />
          ))}
        </ul>
      )}
    </>
  );
}
