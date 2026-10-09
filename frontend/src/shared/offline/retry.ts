// When the write queue may try again after a failed send (roadmap F-01).
//
// Without jitter, every phone that lost coverage in the same area retries at
// the same moment when it comes back, and the recovery becomes the outage. So
// a failed send sets a "not before" time on the first waiting write: a
// jittered, growing delay, or the server's Retry-After if that is later. Every
// trigger (online, focus, the interval, Background Sync) respects it.
//
// The base delay is capped at 20 s, so with jitter a phone tries again within
// 30 s, inside the 30 s p95 for "sync after reconnect" (SYSTEM-ARCHITECTURE
// 6.7). A server that asks for longer through Retry-After gets longer.
//
// Pure: no clock reads and no randomness of its own, so it is tested directly.
// scripts/sw-drain.mjs reads the same nextAttemptAt field.

export const BACKOFF_BASE_MS = 1_000;
export const BACKOFF_CAP_MS = 20_000;
export const BACKOFF_FLOOR_MS = 250;

/** The delay before attempt number `attempt` (0 for the first retry), with plus or minus 50% jitter. */
export function backoffMs(attempt: number, random: () => number = Math.random): number {
  const base = Math.min(BACKOFF_BASE_MS * 2 ** Math.max(0, attempt), BACKOFF_CAP_MS);
  const jitter = base * 0.5 * (random() * 2 - 1);
  return Math.max(BACKOFF_FLOOR_MS, Math.round(base + jitter));
}

/**
 * The earliest time, in epoch milliseconds, to send again after a failure.
 * `attempts` is the count after this failure, so the first failure waits about
 * a second.
 */
export function nextAttemptAt(now: number, attempts: number, retryAfterSeconds?: number, random: () => number = Math.random): number {
  const backoff = backoffMs(attempts - 1, random);
  const asked = retryAfterSeconds !== undefined && retryAfterSeconds > 0 ? retryAfterSeconds * 1_000 : 0;
  return now + Math.max(backoff, asked);
}

/** True while a write must not be sent yet. */
export function waiting(entry: { nextAttemptAt?: number }, now: number): boolean {
  return entry.nextAttemptAt !== undefined && entry.nextAttemptAt > now;
}

/** The server's Retry-After, as the API client records it on a problem, if any. */
export function retryAfterOf(error: unknown): number | undefined {
  const extensions = (error as { problem?: { extensions?: Record<string, unknown> } } | null)?.problem?.extensions;
  const seconds = extensions?.retryAfterSeconds;
  return typeof seconds === "number" && seconds > 0 ? seconds : undefined;
}
