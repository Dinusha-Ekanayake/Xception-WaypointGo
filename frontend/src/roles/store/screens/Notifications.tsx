"use client";

import { useState } from "react";
import type { NotificationView } from "@shared/domain/types";
import type { Inbox } from "@shared/notifications/useInbox";
import { isUnread, kindOf, type Tone } from "@shared/notifications/inbox";
import { Icon, cx } from "@shared/ui";
import { Drawer } from "../ui.tsx";
import { clock, dayLabel, depotToday } from "@shared/wording";

// The store manager's notifications (issue #118), Figma "14 Store Manager ·
// Desktop" and "15 · Mobile", 10 Notifications (11:117503 drawer, 11:125924
// sheet) and the Home card (11:117599). Each row: the kind in its colour with a
// dot, the time, a bold title, the detail; unread rows are filled, read rows
// outlined. Deferrals and expected arrivals are what the booklet asks the
// store to be told (p6).

const LABEL: Record<Tone, string> = {
  urgent: "text-go-danger-strong",
  warning: "text-go-warning-text",
  good: "text-go-success",
  info: "text-go-teal",
};
const DOT: Record<Tone, string> = {
  urgent: "bg-go-danger-strong",
  warning: "bg-go-warning",
  good: "bg-go-success",
  info: "bg-go-teal",
};

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
  return (
    <li>
      <button
        type="button"
        onClick={() => onOpen(n)}
        aria-label={`${fresh ? "Unread. " : ""}${kind.label}. ${n.title}. ${n.body}`}
        className={cx(
          "flex w-full flex-col gap-1 rounded-[16px] px-3.5 py-3 text-left",
          fresh ? "bg-go-surface" : "border border-go-rule bg-white",
        )}
      >
        <span className="flex items-center justify-between gap-2 text-[13px]">
          <span className={cx("flex items-center gap-1.5 font-medium", LABEL[kind.tone])}>
            <span aria-hidden className={cx("size-2 rounded-full", DOT[kind.tone])} />
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
  const [lookedAt] = useState(() => new Date());
  const { open, error } = useOpen(inbox, (n) => {
    onClose();
    onSubject(n);
  });
  const unread = inbox.items.filter(isUnread).length;

  return (
    <Drawer label="Notifications" onClose={onClose}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <h2 className="text-[26px] font-medium text-black">Notifications</h2>
          <p className="text-[14px] text-go-muted">{unread > 0 ? `${unread} new · today` : "All caught up"}</p>
        </div>
        <button type="button" onClick={onClose} aria-label="Close notifications" className="flex size-10 shrink-0 items-center justify-center rounded-full bg-go-canvas text-black">
          <Icon name="close" />
        </button>
      </div>
      {!inbox.online && (
        <p role="status" className="rounded-[14px] bg-go-warning-tint px-3.5 py-2.5 text-[14px] text-go-warning-text">
          Offline{inbox.savedAt ? ` · saved at ${clock(inbox.savedAt)}` : ""}. Read state updates when you&rsquo;re back online.
        </p>
      )}
      {inbox.online && !inbox.live && inbox.unread !== null && (
        <p role="status" className="text-[13px] text-go-muted">Live updates paused. Checking every 30 seconds.</p>
      )}
      {(error ?? inbox.error) && <p role="alert" className="text-[14px] text-go-danger-strong">{error ?? inbox.error}</p>}
      {inbox.items.length === 0 ? (
        <p className="py-8 text-center text-[15px] text-go-muted">{inbox.loading ? "Loading…" : "No notifications yet."}</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {inbox.items.map((n) => (
            <Row key={n.notificationId} n={n} onOpen={open} />
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
        onClick={() => void inbox.markAllRead(lookedAt).catch(() => undefined)}
        className="min-h-[52px] shrink-0 rounded-full border border-go-rule bg-white text-[16px] font-medium text-black disabled:opacity-50"
      >
        Mark all as read
      </button>
    </Drawer>
  );
}

/** The Home card (11:117599): the bell, the count of new ones, the four newest. */
export function NotificationsCard({ inbox, onSubject, onAll }: { inbox: Inbox; onSubject: OpenSubject; onAll: () => void }): React.JSX.Element {
  const { open, error } = useOpen(inbox, onSubject);
  const unread = inbox.items.filter(isUnread).length;
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
      {inbox.items.length === 0 ? (
        <p className="text-[14px] text-go-muted">{inbox.loading ? "Loading…" : "No notifications yet."}</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {inbox.items.slice(0, 4).map((n) => (
            <Row key={n.notificationId} n={n} onOpen={open} />
          ))}
        </ul>
      )}
    </>
  );
}
