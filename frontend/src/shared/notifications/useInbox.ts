"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { request } from "@shared/api/client";
import { newCommand, send } from "@shared/api/commands";
import { useOnline } from "@shared/api/useResource";
import type { Page } from "@shared/domain/common";
import { NotificationCommandKind, type NotificationView, type UnreadCountView } from "@shared/domain/notification";
import { keep, kept } from "@shared/offline";
import { POLL_MS, appended, isStale, markedAllRead, markedRead } from "./inbox.ts";
import { businessNow } from "../wording/now.ts";

// A role's notifications (issue #118): the live unread count, the list, and
// marking read. Data only; each role draws its own bell and inbox.
//
// The count comes from GET /api/notifications/stream, which the server feeds
// on connect, on every change and every 25 s, and the browser reconnects it by
// itself. Heard nothing for 40 s, the inbox says live updates are paused and
// polls /unread-count instead, until the stream speaks again. The last list is kept
// on the device so an offline screen still shows it, with when it was saved.

const PAGE_SIZE = 20;
/** How often silence is checked; a stale count is polled every POLL_MS. */
const CHECK_MS = 10_000;
const KEPT = "notifications";

export type Inbox = {
  /** Unread notifications; null until the first answer. */
  unread: number | null;
  items: NotificationView[];
  hasMore: boolean;
  loading: boolean;
  error: string | null;
  /** The count is arriving on the stream right now. */
  live: boolean;
  online: boolean;
  /** When the list shown was saved on this device, while offline. */
  savedAt: Date | null;
  refresh: () => void;
  more: () => void;
  markRead: (ids: string[]) => Promise<void>;
  /** Everything up to `upTo` (when the person looked); later arrivals stay unread. */
  markAllRead: (upTo?: Date) => Promise<void>;
};

export function useInbox(accountId: string | null, enabled = true): Inbox {
  const online = useOnline();
  const [unread, setUnread] = useState<number | null>(null);
  const [items, setItems] = useState<NotificationView[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState(false);
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const [tick, setTick] = useState(0);
  const heardAt = useRef<number | null>(null);
  const active = Boolean(accountId) && enabled;

  // The live count, with the polling fallback.
  useEffect(() => {
    if (!active || !online) {
      setLive(false);
      return;
    }
    let closed = false;
    const heard = (count: number) => {
      heardAt.current = Date.now();
      setUnread(count);
    };
    const source = typeof EventSource === "undefined" ? null : new EventSource("/api/notifications/stream");
    source?.addEventListener("unread", (event) => {
      try {
        heard((JSON.parse((event as MessageEvent<string>).data) as UnreadCountView).count);
        setLive(true);
      } catch {
        // A malformed event is ignored; the next one, or the poll, corrects the count.
      }
    });
    // A dropped connection is not yet "paused": EventSource reconnects by
    // itself. Only silence past STALE_AFTER_MS is (the watch below).

    const poll = () =>
      request<UnreadCountView>("/api/notifications/unread-count")
        .then((answer) => !closed && setUnread(answer.count))
        .catch(() => undefined);
    // Ask once now, so the badge does not wait for the stream to open.
    void poll();
    let polledAt = Date.now();
    const watch = window.setInterval(() => {
      const now = Date.now();
      if (!isStale(heardAt.current, now)) return;
      setLive(false);
      if (now - polledAt >= POLL_MS) {
        polledAt = now;
        void poll();
      }
    }, CHECK_MS);
    return () => {
      closed = true;
      source?.close();
      window.clearInterval(watch);
    };
  }, [active, online]);

  // The first page, again whenever the count moves or a refresh is asked for.
  useEffect(() => {
    if (!accountId || !enabled) return;
    let cancelled = false;
    if (!online) {
      void kept<NotificationView[]>(accountId, KEPT).then((snapshot) => {
        if (cancelled || !snapshot) return;
        setItems(snapshot.value);
        setSavedAt(new Date(snapshot.savedAt));
      });
      return () => {
        cancelled = true;
      };
    }
    setLoading(true);
    request<Page<NotificationView>>(`/api/notifications?limit=${PAGE_SIZE}`)
      .then((page) => {
        if (cancelled) return;
        setItems(page.items);
        setCursor(page.nextCursor);
        setError(null);
        setSavedAt(null);
        void keep(accountId, KEPT, page.items);
      })
      .catch((failure: unknown) => !cancelled && setError(failure instanceof Error ? failure.message : "Could not load notifications."))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [accountId, enabled, online, unread, tick]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);

  const more = useCallback(() => {
    if (!cursor || loading) return;
    setLoading(true);
    request<Page<NotificationView>>(`/api/notifications?limit=${PAGE_SIZE}&after=${encodeURIComponent(cursor)}`)
      .then((page) => {
        setItems((current) => appended(current, page.items));
        setCursor(page.nextCursor);
      })
      .catch((failure: unknown) => setError(failure instanceof Error ? failure.message : "Could not load more."))
      .finally(() => setLoading(false));
  }, [cursor, loading]);

  const markRead = useCallback(async (ids: string[]) => {
    const fresh = ids.filter((id) => items.some((n) => n.notificationId === id && n.readAt === null));
    if (fresh.length === 0) return;
    const nowIso = businessNow().toISOString();
    setItems((current) => markedRead(current, new Set(fresh), nowIso));
    setUnread((current) => (current !== null ? Math.max(0, current - fresh.length) : null));
    const ack = await send<{ unread: number }>(newCommand(NotificationCommandKind.markRead, { notificationIds: fresh }));
    if (typeof ack.result?.unread === "number") setUnread(ack.result.unread);
  }, [items]);

  const markAllRead = useCallback(async (upTo: Date = businessNow()) => {
    const at = upTo.toISOString();
    setItems((current) => markedAllRead(current, at));
    setUnread(0);
    const ack = await send<{ unread: number }>(newCommand(NotificationCommandKind.markAllRead, { upTo: at }));
    if (typeof ack.result?.unread === "number") setUnread(ack.result.unread);
  }, []);

  return {
    unread,
    items,
    hasMore: cursor !== null,
    loading,
    error,
    live,
    online,
    savedAt,
    refresh,
    more,
    markRead,
    markAllRead,
  };
}
