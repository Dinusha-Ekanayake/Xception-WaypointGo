"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { request } from "./client.ts";
import { ApiError } from "./problem.ts";
import { businessNow } from "../wording/now.ts";

// One read, its freshness and its failure, for screens that must show when data
// is stale rather than pretend it is live. Polling pauses while the tab is
// hidden or the browser is offline, and the caller says so on screen.

export type Resource<T> = {
  data: T | null;
  error: ApiError | Error | null;
  loading: boolean;
  /** When data last arrived. Kept across failures so a screen can say how old it is. */
  loadedAt: Date | null;
  refresh: () => void;
};

export function useOnline(): boolean {
  const [online, setOnline] = useState(true);
  useEffect(() => {
    const update = () => setOnline(navigator.onLine);
    update();
    window.addEventListener("online", update);
    window.addEventListener("offline", update);
    return () => {
      window.removeEventListener("online", update);
      window.removeEventListener("offline", update);
    };
  }, []);
  return online;
}

/**
 * @param load the read to run; `null` skips it, for a read that is not possible yet.
 * @param key reruns the read when it changes, such as a depot or a date.
 * @param pollMs refetch interval while visible and online; 0 disables polling.
 */
export function useResource<T>(
  load: ((signal: AbortSignal) => Promise<T>) | null,
  key: string,
  pollMs = 0,
): Resource<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const [loading, setLoading] = useState(load !== null);
  const [loadedAt, setLoadedAt] = useState<Date | null>(null);
  const [tick, setTick] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;

  const refresh = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    const run = loadRef.current;
    if (!run) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    run(controller.signal)
      .then((result) => {
        setData(result);
        setError(null);
        setLoadedAt(businessNow());
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted) return;
        setError(failure instanceof Error ? failure : new Error(String(failure)));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [key, tick]);

  useEffect(() => {
    if (!pollMs) return;
    const id = window.setInterval(() => {
      if (document.visibilityState === "visible" && navigator.onLine) refresh();
    }, pollMs);
    return () => window.clearInterval(id);
  }, [pollMs, refresh]);

  return { data, error, loading, loadedAt, refresh };
}

/** A GET through the shared client, for use as a `useResource` loader. */
export function get<T>(path: string): (signal: AbortSignal) => Promise<T> {
  return (signal) => request<T>(path, { signal });
}
