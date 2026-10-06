// Roadmap F-07: the driver's run is read every 2 s, but the vehicle and its
// outlets almost never change during a day. Reading them on every pass sent
// one request per outlet each time, about 450 a minute on a 12-stop run. They
// are read again only once their copy is older than its time to live; the run
// sheet itself is still read every pass.

/** Outlet and vehicle details: a dock or window edited mid-day shows within ten minutes. */
export const DETAILS_TTL_MS = 10 * 60_000;
/** Which vehicles the driver is assigned for the day: a new assignment shows within a minute. */
export const ASSIGNED_TTL_MS = 60_000;

export type ReadCache = {
  /** The value kept for `key` while younger than `ttlMs`, else read again. A failed read is not kept. */
  get<T>(key: string, ttlMs: number, read: () => Promise<T>): Promise<T>;
};

export function readCache(now: () => number = Date.now): ReadCache {
  const kept = new Map<string, { at: number; value: Promise<unknown> }>();
  return {
    get<T>(key: string, ttlMs: number, read: () => Promise<T>): Promise<T> {
      const hit = kept.get(key);
      if (hit && now() - hit.at < ttlMs) return hit.value as Promise<T>;
      const value = read();
      kept.set(key, { at: now(), value });
      value.catch(() => {
        if (kept.get(key)?.value === value) kept.delete(key);
      });
      return value;
    },
  };
}
