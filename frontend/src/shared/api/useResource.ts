"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { request } from "./client.ts";
import { LIVE_DOWN_MS, LIVE_FALLBACK_MS, concerns, listenForChanges } from "./changes.ts";
import { ApiError } from "./problem.ts";
import { businessNow } from "../wording/now.ts";

// One read, its freshness and its failure, for screens that must show when data
// is stale rather than pretend it is live. Polling pauses while the tab is
// hidden or the browser is offline, and the caller says so on screen.

/** Coming back to a screen re-reads it, but not more often than this. */
const RETURN_GAP_MS = 3_000;

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
 * @param live F-07b: re-read when a change to one of these topics commits at one
 *     of these depots (or outlets, written with `outletWatch`), and poll only
 *     every LIVE_FALLBACK_MS while that stream is up; with it down, poll at
 *     `pollMs` as before.
 */
export function useResource<T>(
  load: ((signal: AbortSignal) => Promise<T>) | null,
  key: string,
  pollMs = 0,
  live?: { depots: string[]; topics: string[] },
): Resource<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const [loading, setLoading] = useState(load !== null);
  const [loadedAt, setLoadedAt] = useState<Date | null>(null);
  const [tick, setTick] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;

  const refresh = useCallback(() => setTick((n) => n + 1), []);
  // A poll or a return to the screen reads quietly: the data on screen stays and
  // no loading state flashes every few seconds. A new key or a refresh asked for
  // by the screen shows loading as before.
  const quiet = useRef(false);
  // A read still on its way is left to finish: on a slow line a new poll would
  // otherwise cancel it every time and the screen would never change.
  const inFlight = useRef(false);
  const refreshQuietly = useCallback(() => {
    if (inFlight.current) return;
    quiet.current = true;
    setTick((n) => n + 1);
  }, []);

  useEffect(() => {
    const run = loadRef.current;
    const background = quiet.current;
    quiet.current = false;
    if (!run) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    if (!background) setLoading(true);
    inFlight.current = true;
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
        inFlight.current = false;
      });
    return () => {
      inFlight.current = false;
      controller.abort();
    };
    // `load === null` too: a read switched on under the same key (a tab opened) reads at
    // once, instead of waiting for the next poll.
  }, [key, tick, load === null]);

  // F-07b: listen for the topics this read shows, and slow the poll while the stream is up.
  const [streaming, setStreaming] = useState(false);
  const liveDepots = live?.depots.join(",") ?? "";
  const liveTopics = live?.topics.join(",") ?? "";
  useEffect(() => {
    if (!liveDepots || !liveTopics || load === null) return;
    const topics = liveTopics.split(",");
    return listenForChanges(
      liveDepots.split(","),
      (change) => {
        if (concerns(topics, change)) refreshQuietly();
      },
      setStreaming,
    );
    // `load === null` only switches listening on and off; the loader itself changes every render.
  }, [liveDepots, liveTopics, load === null, refreshQuietly]);
  // A read on the stream polls only as a safety net: every 60 s while the stream is up, every 10 s at
  // most while it is down. A read with no stream keeps its own interval.
  const every = pollMs && liveDepots && liveTopics ? Math.max(pollMs, streaming ? LIVE_FALLBACK_MS : LIVE_DOWN_MS) : pollMs;

  useEffect(() => {
    if (!every) return;
    const id = window.setInterval(() => {
      if (document.visibilityState === "visible" && navigator.onLine) refreshQuietly();
    }, every);
    return () => window.clearInterval(id);
  }, [every, refreshQuietly]);

  // Back on screen, back in focus or back online: read again at once. A phone
  // that slept, or a tab left in the background, otherwise shows old data until
  // someone reloads by hand. At most once every few seconds, as these events
  // often fire together.
  const loadable = load !== null;
  useEffect(() => {
    if (!loadable) return;
    let last = Date.now();
    const again = () => {
      if (document.visibilityState !== "visible" || !navigator.onLine || Date.now() - last < RETURN_GAP_MS) return;
      last = Date.now();
      refreshQuietly();
    };
    document.addEventListener("visibilitychange", again);
    window.addEventListener("focus", again);
    window.addEventListener("online", again);
    return () => {
      document.removeEventListener("visibilitychange", again);
      window.removeEventListener("focus", again);
      window.removeEventListener("online", again);
    };
  }, [loadable, refreshQuietly]);

  return { data, error, loading, loadedAt, refresh };
}

/** A GET through the shared client, for use as a `useResource` loader. */
export function get<T>(path: string): (signal: AbortSignal) => Promise<T> {
  return (signal) => request<T>(path, { signal });
}
