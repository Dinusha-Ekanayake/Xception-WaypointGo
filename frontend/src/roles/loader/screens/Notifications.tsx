"use client";

import { useState } from "react";
import { cx, VoiceNotice } from "@shared/ui";
import type { Inbox } from "@shared/notifications/useInbox";
import { ago, isUnread, kindOf, noteColor } from "@shared/notifications/inbox";
import type { NotificationView } from "@shared/domain/types";
import { useT } from "../i18n.tsx";
import { localized } from "../data/messages.ts";
import { SkeletonRows } from "@shared/ui";
import { BigButton, Sheet } from "../ui.tsx";
import { ScrollRevealCard, useRubberBandScroll } from "@shared/ui/scrollReveal";
import { clock, businessNow } from "@shared/wording";

// The loader's notifications (issue #118): plans published or revised for the
// depot, and trips other loaders released. Figma gives the loader a bell with
// an unread dot (08 Loader · Phone, 07 Tablet) but no list, so the list follows
// the store manager's drawer rows (14/15 · 10 Notifications): a dot and label
// with the time, a bold title, the detail. Messages are shown in the loader's
// language, filled from the facts the server kept (data/messages.ts).


export default function Notifications({
  inbox,
  accountId,
  onClose,
}: {
  inbox: Inbox;
  /** Whose saved copies of voice messages play with no signal (R-MSG-11). */
  accountId?: string;
  onClose: () => void;
}): React.JSX.Element {
  const tr = useT();
  const feed = useRubberBandScroll();
  const [lookedAt] = useState(() => businessNow());
  const [error, setError] = useState<string | null>(null);
  const unread = inbox.items.filter(isUnread).length;
  const now = businessNow();

  const act = (work: Promise<void>) => {
    setError(null);
    work.catch((failure: unknown) => setError(failure instanceof Error ? failure.message : tr("Could not do that. Try again.")));
  };
  const open = (n: NotificationView) => {
    if (isUnread(n) && inbox.online) act(inbox.markRead([n.notificationId]));
  };

  return (
    <Sheet label={tr("Notifications")} onClose={onClose}>
      <div className="flex flex-col gap-1">
        <h2 className="text-[24px] font-medium text-go-ink">{tr("Notifications")}</h2>
        <p className="text-[14px] text-go-muted">{unread > 0 ? tr("{n} new", { n: unread }) : tr("All caught up")}</p>
      </div>

      {!inbox.online && (
        <p role="status" className="rounded-[16px] bg-go-warning-tint px-4 py-3 text-[14px] text-go-warning-text">
          {inbox.savedAt
            ? tr("Offline · saved at {time}. Read state updates when you're back online.", { time: clock(inbox.savedAt) })
            : tr("Offline. Notifications show when you're back online.")}
        </p>
      )}
      {inbox.online && !inbox.live && inbox.unread !== null && (
        <p role="status" className="text-[13px] text-go-muted">{tr("Live updates paused. Checking every 30 seconds.")}</p>
      )}
      {(error ?? inbox.error) && (
        <p role="alert" className="text-[14px] text-go-danger-strong">{error ?? inbox.error}</p>
      )}

      {inbox.items.length === 0 ? (
        inbox.loading ? (
          <SkeletonRows rows={3} label={tr("Loading…")} />
        ) : (
          <p className="py-8 text-center text-[15px] text-go-muted">{tr("No notifications yet")}</p>
        )
      ) : (
        // The phones' shared list scroll (rubber band, fades, rows easing out), as on the driver and store.
        <div style={feed.maskStyle} className="relative min-h-0 overflow-hidden">
          <div
            ref={feed.scrollRef}
            {...feed.handlers}
            className="max-h-[55dvh] overflow-y-auto overscroll-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
          >
            <ul
              style={{
                transform: `translate3d(0, ${feed.pullY}px, 0)`,
                transition: feed.isPulling ? "none" : "transform 250ms cubic-bezier(0.18, 1.12, 0.32, 1.0)",
              }}
              className="flex flex-col gap-2.5 will-change-transform"
            >
              {inbox.items.map((n) => {
                const kind = kindOf(n.eventType);
                const fresh = isUnread(n);
                const text = localized(n, tr);
                // One look for every role (Figma): the canvas tint, one accent, red only for an alarm, grey once read.
                const color = noteColor(n);
                // A voice message plays here, with its words (FF-05); a play button cannot sit inside a row button.
                if (n.eventType === "message.posted" && n.facts?.voiceNoteId) {
                  return (
                    <li key={n.notificationId}>
                      <ScrollRevealCard scrollContainerRef={feed.scrollRef} pullY={feed.pullY}>
                        <div onPointerDown={() => open(n)} className="flex w-full flex-col gap-2 rounded-[22px] bg-go-canvas px-[18px] py-4 text-left">
                          <span className="flex items-center justify-between gap-2 text-[13px]">
                            <span className="flex items-center gap-1.5 font-medium" style={{ color }}>
                              <span aria-hidden className="size-2 rounded-full" style={{ background: color }} />
                              {tr(kind.label)}
                            </span>
                            <span className="text-go-muted">{ago(n.createdAt, now)}</span>
                          </span>
                          <VoiceNotice item={n} tr={tr} {...(accountId ? { accountId } : {})} />
                        </div>
                      </ScrollRevealCard>
                    </li>
                  );
                }
                return (
                  <li key={n.notificationId}>
                    <ScrollRevealCard scrollContainerRef={feed.scrollRef} pullY={feed.pullY}>
                      <button
                        type="button"
                        onClick={() => open(n)}
                        aria-label={`${fresh ? tr("Unread") + ". " : ""}${tr(kind.label)}. ${text.title}. ${text.body}`}
                        className="flex w-full flex-col gap-1 rounded-[22px] bg-go-canvas px-[18px] py-4 text-left"
                      >
                        <span className="flex items-center justify-between gap-2 text-[13px]">
                          <span className="flex items-center gap-1.5 font-medium" style={{ color }}>
                            <span aria-hidden className="size-2 rounded-full" style={{ background: color }} />
                            {tr(kind.label)}
                          </span>
                          <span className="text-go-muted">{ago(n.createdAt, now)}</span>
                        </span>
                        <span className={cx("text-[16px] text-go-ink", fresh && "font-semibold")}>{text.title}</span>
                        <span className="text-[14px] text-go-muted">{text.body}</span>
                      </button>
                    </ScrollRevealCard>
                  </li>
                );
              })}
            </ul>
          </div>
        </div>
      )}
      {inbox.hasMore && (
        <button type="button" onClick={inbox.more} disabled={inbox.loading} className="min-h-12 text-[15px] font-medium text-go-teal">
          {tr("Show more")}
        </button>
      )}

      <div className="grid grid-cols-2 gap-3">
        <BigButton tone="grey" onClick={onClose}>
          {tr("Close")}
        </BigButton>
        <BigButton tone="ink" disabled={unread === 0 || !inbox.online} onClick={() => act(inbox.markAllRead(lookedAt))}>
          {tr("Mark all as read")}
        </BigButton>
      </div>
    </Sheet>
  );
}
