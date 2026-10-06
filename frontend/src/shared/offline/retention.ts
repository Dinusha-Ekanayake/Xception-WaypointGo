// Roadmap F-04: what the phone keeps of the reads it saved, and for how long.
// Pure: the saved keys and their times go in, what to delete comes out.
//
// A saved read is a copy of what the server last said, for a screen with no
// signal; after a week it helps nobody and only fills the phone.

/** Saved reads older than this are deleted. */
export const KEEP_DAYS = 7;
/** At most this many saved reads per account; the oldest go first. */
export const KEEP_MAX = 400;
/** An unchanged read is written again at most this often, so its "saved at" time stays honest. */
export const REWRITE_MS = 60_000;

export type SavedMeta = { key: string; savedAt: string };

/**
 * Keys the app itself keeps beside the reads, such as the device id the service
 * worker sends under (queue.ts). Never evicted and never cleared on sign-out.
 */
export const OWN_PREFIX = "waypoint.";

export const isOwn = (key: string): boolean => key.startsWith(OWN_PREFIX);

/** The keys to delete: everything older than {@link KEEP_DAYS}, then the oldest beyond {@link KEEP_MAX}. */
export function toEvict(all: SavedMeta[], now: number, keepDays = KEEP_DAYS, keepMax = KEEP_MAX): string[] {
  const saved = all.filter((m) => !isOwn(m.key));
  const cutoff = now - keepDays * 86_400_000;
  const at = (m: SavedMeta) => Date.parse(m.savedAt) || 0;
  const old = saved.filter((m) => at(m) < cutoff);
  const fresh = saved.filter((m) => at(m) >= cutoff).sort((a, b) => at(b) - at(a) || a.key.localeCompare(b.key));
  return [...old, ...fresh.slice(keepMax)].map((m) => m.key);
}

/** A short fingerprint of a value, to tell whether a read changed (FNV-1a over its JSON). */
export function contentHash(value: unknown): string {
  const text = JSON.stringify(value) ?? "undefined";
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return `${(hash >>> 0).toString(16)}:${text.length}`;
}

/** Whether a write can be skipped: the same content was written within {@link REWRITE_MS}. */
export function unchanged(last: { hash: string; at: number } | undefined, hash: string, now: number): boolean {
  return last !== undefined && last.hash === hash && now - last.at < REWRITE_MS;
}

/** A storage failure in words a person can act on; the browser's own text otherwise. */
export function storageFailure(error: unknown): string {
  const name = typeof error === "object" && error !== null && "name" in error ? String(error.name) : "";
  if (name === "QuotaExceededError") return "This phone is out of storage space. Free some space, then save again.";
  return String(error);
}
