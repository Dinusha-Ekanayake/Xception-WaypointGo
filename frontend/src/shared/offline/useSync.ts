"use client";

import { useCallback, useEffect, useState } from "react";
import { QUEUED_EVENT, discard, drain, heldForReview, pendingCount, retry } from "./queue.ts";
import type { StoredEntry } from "./store.ts";

// The sync engine's schedule. Queued writes are sent when the connection
// returns, when the app comes back to the foreground, right after something is
// queued, and on a slow interval as a safety net. drain() is shared per
// account, so this running beside a role's own flush never sends twice.

const INTERVAL_MS = 30_000;

export type SyncState = {
  /** Writes still on this device, including held ones. */
  pending: number;
  /** Refused by the server; a person decides what happens to them. */
  held: StoredEntry[];
  lastSyncedAt: Date | null;
  syncing: boolean;
  syncNow: () => void;
  discard: (commandId: string) => Promise<void>;
  retry: (entry: StoredEntry) => Promise<void>;
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
    return () => {
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
    discard: async (commandId) => {
      if (!accountId) return;
      await discard(accountId, commandId);
      await read();
    },
    retry: async (entry) => {
      if (!accountId) return;
      await retry(accountId, entry);
      syncNow();
    },
  };
}
