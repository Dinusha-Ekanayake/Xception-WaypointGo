"use client";

import { useCallback, useEffect, useState } from "react";
import { DRAIN_MESSAGE, QUEUED_EVENT, discard, drain, heldForReview, pendingCount, pendingEntries, redo } from "./queue.ts";
import { resolverFor } from "./resolvers.ts";
import type { StoredEntry } from "./store.ts";

// The sync engine's schedule. Queued writes are sent when the connection
// returns, when the app comes back to the foreground, right after something is
// queued, on a slow interval as a safety net, and when the service worker
// passes on a Background Sync. drain() is shared per account, so multiple role
// shells never send the same write twice.

const INTERVAL_MS = 30_000;

export type SyncState = {
  /** Writes still on this device, including held ones. */
  pending: number;
  /** Refused by the server; a person decides what happens to them. */
  held: StoredEntry[];
  lastSyncedAt: Date | null;
  syncing: boolean;
  syncNow: () => void;
  /** Drop a held write, with the person's reason (sync:Discard). */
  discard: (commandId: string, reason: string) => Promise<void>;
  /** Whether the role that made this held write can redo it on the current version. */
  canRedo: (entry: StoredEntry) => boolean;
  /**
   * Redo a held conflict on the current version (sync:Resolve). Rejects with the
   * role's reason when it cannot, such as no connection to read the version.
   */
  redo: (entry: StoredEntry) => Promise<void>;
};

export function useSync(accountId: string | null): SyncState {
  const [pending, setPending] = useState(0);
  const [held, setHeld] = useState<StoredEntry[]>([]);
  const [lastSyncedAt, setLastSyncedAt] = useState<Date | null>(null);
  const [syncing, setSyncing] = useState(false);

  const read = useCallback(async () => {
    if (!accountId) return;
    setPending(await pendingCount(accountId));
    setHeld(await heldForReview(accountId));
  }, [accountId]);

  const syncNow = useCallback(() => {
    if (!accountId) return;
    if (typeof navigator !== "undefined" && !navigator.onLine) {
      void read();
      return;
    }
    setSyncing(true);
    drain(accountId)
      .then((report) => {
        if (report.remaining === report.heldForReview) setLastSyncedAt(new Date());
      })
      .catch(() => undefined)
      .finally(() => {
        setSyncing(false);
        void read();
      });
  }, [accountId, read]);

  useEffect(() => {
    if (!accountId) return;
    syncNow();
    const onVisible = () => document.visibilityState === "visible" && syncNow();
    window.addEventListener("online", syncNow);
    window.addEventListener("focus", syncNow);
    window.addEventListener(QUEUED_EVENT, syncNow);
    document.addEventListener("visibilitychange", onVisible);
    const timer = setInterval(syncNow, INTERVAL_MS);
    const onWorker = (event: MessageEvent) => {
      if ((event.data as { type?: unknown } | null)?.type === DRAIN_MESSAGE) syncNow();
    };
    const worker = typeof navigator !== "undefined" && "serviceWorker" in navigator ? navigator.serviceWorker : null;
    worker?.addEventListener("message", onWorker);
    return () => {
      worker?.removeEventListener("message", onWorker);
      window.removeEventListener("online", syncNow);
      window.removeEventListener("focus", syncNow);
      window.removeEventListener(QUEUED_EVENT, syncNow);
      document.removeEventListener("visibilitychange", onVisible);
      clearInterval(timer);
    };
  }, [accountId, syncNow]);

  return {
    pending,
    held,
    lastSyncedAt,
    syncing,
    syncNow,
    discard: async (commandId, reason) => {
      if (!accountId) return;
      await discard(accountId, commandId, reason);
      await read();
    },
    canRedo: (entry) => entry.problemCode === "VERSION_CONFLICT" && resolverFor(entry.kind) !== null,
    redo: async (entry) => {
      if (!accountId) return;
      const resolver = resolverFor(entry.kind);
      if (!resolver) throw new Error("This change can only be discarded.");
      const waiting = (await pendingEntries(accountId)).filter((e) => !e.needsReview);
      const basis = await resolver(entry, waiting);
      if (!basis) throw new Error("Sign in on this device to redo this change.");
      await redo(accountId, entry, basis);
      await read();
    },
  };
}
