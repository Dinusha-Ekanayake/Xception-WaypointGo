"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { readTripTrailSince, type TrailPoint } from "@shared/ui/map";

// One trip's run path, from its first recorded point to now (R-EXE-23). Read
// whole once when the trip is chosen, then only the points after the newest
// one held, so a long day at one fix every five seconds is never re-read.

export type TripTrail = {
  /** Null until the first read answers. */
  points: TrailPoint[] | null;
  error: Error | null;
  refresh: () => void;
};

export function useTripTrail(tripId: string | null, pollMs: number): TripTrail {
  const [points, setPoints] = useState<TrailPoint[] | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [tick, setTick] = useState(0);
  const last = useRef<string | null>(null);
  const busy = useRef(false);

  useEffect(() => {
    setPoints(null);
    setError(null);
    last.current = null;
  }, [tripId]);

  useEffect(() => {
    if (!tripId) return;
    const controller = new AbortController();
    const read = () => {
      if (busy.current || document.visibilityState !== "visible" || !navigator.onLine) return;
      busy.current = true;
      const since = last.current;
      readTripTrailSince(tripId, since, controller.signal)
        .then((page) => {
          if (controller.signal.aborted) return;
          last.current = page.last ?? since;
          setPoints((held) => (since === null || held === null ? page.points : [...held, ...page.points]));
          setError(null);
        })
        .catch((failure: unknown) => {
          if (!controller.signal.aborted) setError(failure instanceof Error ? failure : new Error(String(failure)));
        })
        .finally(() => {
          busy.current = false;
        });
    };
    read();
    const timer = pollMs > 0 ? window.setInterval(read, pollMs) : null;
    return () => {
      controller.abort();
      busy.current = false;
      if (timer !== null) window.clearInterval(timer);
    };
  }, [tripId, pollMs, tick]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);
  return { points, error, refresh };
}
