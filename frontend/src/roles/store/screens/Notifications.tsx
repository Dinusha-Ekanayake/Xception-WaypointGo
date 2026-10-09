"use client";

import { useState } from "react";
import type { NotificationView } from "@shared/domain/types";
import type { Inbox } from "@shared/notifications/useInbox";
import { isUnread, kindOf, noteColor } from "@shared/notifications/inbox";
import { Icon, SkeletonRows, cx } from "@shared/ui";
import { Drawer } from "../ui.tsx";
import { ScrollRevealCard, useRubberBandScroll } from "@shared/ui/scrollReveal";
import { clock, dayLabel, depotToday, businessNow } from "@shared/wording";
import { useT } from "../i18n.tsx";

// The store manager's notifications (issue #118), Figma "14 Store Manager ·
// Desktop" and "15 · Mobile", 10 Notifications (11:117503 drawer, 11:125924
// sheet) and the Home card (11:117599). Each row: the kind in its colour with a
// dot, the time, a bold title, the detail; unread rows are filled, read rows
// outlined. Deferrals and expected arrivals are what the booklet asks the
// store to be told (p6).


/** "03:12" today, "Sat 19:10" before: depot time, whatever the phone's own zone. */
export function when(createdAt: string, now: Date = businessNow()): string {
  const at = new Date(createdAt);
  const day = depotToday(at);
  if (day === depotToday(now)) return clock(at);
  return `${dayLabel(day).split(" ")[0]} ${clock(at)}`;
}

export type OpenSubject = (n: NotificationView) => void;

function Row({ n, onOpen }: { n: NotificationView; onOpen: (n: NotificationView) => void }): React.JSX.Element {
  return (
    <li>
      <RowButton n={n} onOpen={onOpen} />
    </li>
  );
}

function RowButton({ n, onOpen }: { n: NotificationView; onOpen: (n: NotificationView) => void }): React.JSX.Element {
  const t = useT();
  const kind = kindOf(n.eventType);
  const fresh = isUnread(n);
  // One look for every role (Figma): the canvas tint, one accent, red only for an alarm, grey once read.
  const color = noteColor(n);
  return (
    <button
      type="button"
      onClick={() => onOpen(n)}
      aria-label={`${fresh ? t("Unread.") + " " : ""}${t(kind.label)}. ${n.title}. ${n.body}`}
      className="flex w-full flex-col gap-1 rounded-[22px] bg-go-canvas px-[18px] py-4 text-left"
    >
      <span className="flex items-center justify-between gap-2 text-[13px]">
        <span className="flex items-center gap-1.5 font-medium" style={{ color }}>
          <span aria-hidden className="size-2 rounded-full" style={{ background: color }} />
          {kind.label}
        </span>
        <span className="text-go-muted">{when(n.createdAt)}</span>
      </span>
      <span className={cx("text-[16px] text-black", fresh && "font-semibold")}>{n.title}</span>
      <span className="text-[14px] text-go-muted">{n.body}</span>
    </button>
  );
}

/** Marks a row read when it is opened, then shows its subject. */
function useOpen(inbox: Inbox, onSubject: OpenSubject): { open: (n: NotificationView) => void; error: string | null } {
  const t = useT();
  const [error, setError] = useState<string | null>(null);
  const open = (n: NotificationView) => {
    setError(null);
    if (isUnread(n) && inbox.online) {
      inbox.markRead([n.notificationId]).catch((failure: unknown) => setError(failure instanceof Error ? failure.message : t("Could not mark it read.")));
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
  const t = useT();
  const feed = useRubberBandScroll();
  const [readIds, setReadIds] = useState<Set<string>>(() => new Set());
  const [lookedAt] = useState(() => businessNow());
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
      setActionError(failure instanceof Error ? failure.message : t("Could not mark all as read."));
    }
  };

  const error = actionError ?? openError ?? inbox.error;

  return (
    <Drawer label={t("Notifications")} onClose={onClose}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <h2 className="text-[26px] font-medium text-black">{t("Notifications")}</h2>
          <p className="text-[14px] text-go-muted">{unread > 0 ? t("{n} new · today", { n: unread }) : t("All caught up")}</p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={unread === 0 || !inbox.online}
            onClick={() => void handleReadAll()}
            className="flex h-10 items-center justify-center rounded-full border border-go-rule bg-white px-3.5 text-[14px] font-medium text-black hover:bg-go-canvas disabled:cursor-not-allowed disabled:opacity-40"
          >
            {t("Read all")}
          </button>
          <button
            type="button"
            onClick={onClose}
            aria-label={t("Close notifications")}
            className="flex size-10 shrink-0 items-center justify-center rounded-full bg-go-canvas text-black"
          >
            <Icon name="close" />
          </button>
        </div>
      </div>
      {!inbox.online && (
        <p role="status" className="rounded-[14px] bg-go-warning-tint px-3.5 py-2.5 text-[14px] text-go-warning-text">
          {inbox.savedAt
            ? t("Offline · saved at {time}. Read state updates when you\u2019re back online.", { time: clock(inbox.savedAt) })
            : t("Offline. Read state updates when you\u2019re back online.")}
        </p>
      )}
      {inbox.online && !inbox.live && inbox.unread !== null && (
        <p role="status" className="text-[13px] text-go-muted">{t("Live updates paused. Checking every 30 seconds.")}</p>
      )}
      {error && <p role="alert" className="text-[14px] text-go-danger-strong">{error}</p>}
      {visibleItems.length === 0 ? (
        inbox.loading ? (
          <SkeletonRows rows={3} label={t("Loading…")} />
        ) : (
          <p className="py-8 text-center text-[15px] text-go-muted">{t("No notifications yet. Order confirmations, deferrals and deliveries on the way appear here.")}</p>
        )
      ) : (
        // The phones' shared list scroll (rubber band, fades, rows easing out), as on the driver's feed.
        <div style={feed.maskStyle} className="relative min-h-0 overflow-hidden">
          <div
            ref={feed.scrollRef}
            {...feed.handlers}
            className="max-h-[58dvh] overflow-y-auto overscroll-contain lg:max-h-none [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
          >
            <ul
              style={{
                transform: `translate3d(0, ${feed.pullY}px, 0)`,
                transition: feed.isPulling ? "none" : "transform 250ms cubic-bezier(0.18, 1.12, 0.32, 1.0)",
              }}
              className="flex flex-col gap-3 will-change-transform"
            >
              {visibleItems.map((n) => (
                <li key={n.notificationId}>
                  <ScrollRevealCard scrollContainerRef={feed.scrollRef} pullY={feed.pullY}>
                    <RowButton n={n} onOpen={handleOpen} />
                  </ScrollRevealCard>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
      {inbox.hasMore && (
        <button type="button" onClick={inbox.more} disabled={inbox.loading} className="min-h-11 text-[15px] font-medium text-go-teal">
          {t("Show more")}
        </button>
      )}
      <span className="flex-1" />
      <button
        type="button"
        disabled={unread === 0 || !inbox.online}
        onClick={() => void handleReadAll()}
        className="min-h-[52px] shrink-0 rounded-full border border-go-rule bg-white text-[16px] font-medium text-black disabled:opacity-50"
      >
        {t("Mark all as read")}
      </button>
    </Drawer>
  );
}

/** The Home card (11:117599): the bell, the count of new ones, the four newest. */
export function NotificationsCard({ inbox, onSubject, onAll }: { inbox: Inbox; onSubject: OpenSubject; onAll: () => void }): React.JSX.Element {
  const t = useT();
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
          {t("Notifications")}
        </button>
        {unread > 0 && <span className="rounded-full bg-go-mint px-2.5 py-1 text-[13px] font-medium text-black">{unread} new</span>}
      </div>
      {error && <p role="alert" className="text-[13px] text-go-danger-strong">{error}</p>}
      {visibleItems.length === 0 ? (
        inbox.loading ? (
          <SkeletonRows rows={2} label={t("Loading…")} />
        ) : (
          <p className="text-[14px] text-go-muted">{t("No notifications yet. Order confirmations, deferrals and deliveries on the way appear here.")}</p>
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
