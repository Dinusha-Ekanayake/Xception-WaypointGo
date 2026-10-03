"use client";

import { useState } from "react";
import { cx } from "@shared/ui";
import type { Inbox } from "@shared/notifications/useInbox";
import { ago, isUnread, kindOf, toneOf, TONE_STYLE } from "@shared/notifications/inbox";
import type { NotificationView } from "@shared/domain/types";
import { useT } from "../i18n.tsx";
import { BigButton, Sheet } from "../ui.tsx";
import { clock } from "@shared/wording";

// The loader's notifications (issue #118): plans published or revised for the
// depot, and trips other loaders released. Figma gives the loader a bell with
// an unread dot (08 Loader · Phone, 07 Tablet) but no list, so the list follows
// the store manager's drawer rows (14/15 · 10 Notifications): a dot and label
// with the time, a bold title, the detail. The messages come from the server in
// English; the frame around them is translated.


export default function Notifications({
  inbox,
  onClose,
}: {
  inbox: Inbox;
  onClose: () => void;
}): React.JSX.Element {
  const tr = useT();
  const [lookedAt] = useState(() => new Date());
  const [error, setError] = useState<string | null>(null);
  const unread = inbox.items.filter(isUnread).length;
  const now = new Date();

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
        <p className="py-8 text-center text-[15px] text-go-muted">{inbox.loading ? tr("Loading…") : tr("No notifications yet")}</p>
      ) : (
        <ul className="flex max-h-[55dvh] flex-col gap-2.5 overflow-y-auto">
          {inbox.items.map((n) => {
            const kind = kindOf(n.eventType);
            const fresh = isUnread(n);
            const tone = TONE_STYLE[toneOf(n)];
            return (
              <li key={n.notificationId}>
                <button
                  type="button"
                  onClick={() => open(n)}
                  aria-label={`${fresh ? tr("Unread") + ". " : ""}${tr(kind.label)}. ${n.title}. ${n.body}`}
                  className={cx(
                    "flex w-full flex-col gap-1 rounded-[20px] border-l-4 px-4 py-3.5 text-left",
                    tone.edge,
                    fresh ? tone.tint : "border-y border-r border-y-go-rule border-r-go-rule bg-go-card",
                  )}
                >
                  <span className="flex items-center justify-between gap-2 text-[13px]">
                    <span className={cx("flex items-center gap-1.5 font-medium", tone.label)}>
                      <span aria-hidden className={cx("size-2 rounded-full", tone.dot)} />
                      {tr(kind.label)}
                    </span>
                    <span className="text-go-muted">{ago(n.createdAt, now)}</span>
                  </span>
                  <span className={cx("text-[16px] text-go-ink", fresh && "font-semibold")}>{n.title}</span>
                  <span className="text-[14px] text-go-muted">{n.body}</span>
                </button>
              </li>
            );
          })}
        </ul>
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
