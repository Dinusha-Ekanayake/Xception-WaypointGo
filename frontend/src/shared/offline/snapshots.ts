import { getSnapshot, putSnapshot, removeSnapshotKeys, snapshotTimes, type Snapshot } from "./store.ts";
import { contentHash, isOwn, toEvict, unchanged } from "./retention.ts";

// A full-tier role's working set, kept on the device each time it is read so
// the screens still have it when the server cannot be reached. A snapshot is
// what the server said last, never an authority: the screen shows its age.
//
// Roadmap F-04: a screen polls every few seconds, and most answers are the same
// as the last. An unchanged answer is not written again for a minute, saved
// reads older than a week are deleted once per sign-in, and signing out deletes
// them all.

/** What this tab last wrote under each account and key. */
const written = new Map<string, { hash: string; at: number }>();

/** Never throws: a device that cannot store still works online. */
export async function keep<T>(accountId: string, key: string, value: T): Promise<boolean> {
  const slot = `${accountId}\u0000${key}`;
  const hash = contentHash(value);
  const now = Date.now();
  if (unchanged(written.get(slot), hash, now)) return true;
  try {
    await putSnapshot(accountId, key, value);
    written.set(slot, { hash, at: now });
    return true;
  } catch {
    return false;
  }
}

export async function kept<T>(accountId: string, key: string): Promise<Snapshot<T> | null> {
  try {
    return await getSnapshot<T>(accountId, key);
  } catch {
    return null;
  }
}

/** Saved reads that start with this prefix are credentials, such as a vehicle's Fuel Pass QR (FF-06b). */
export const CREDENTIAL_PREFIX = "credential:";

/** Deletes saved reads past their week or beyond the cap. Never throws. */
export async function sweepSaved(accountId: string, now = Date.now()): Promise<number> {
  try {
    const evict = toEvict(await snapshotTimes(accountId), now);
    await removeSnapshotKeys(accountId, evict);
    return evict.length;
  } catch {
    return 0;
  }
}

/**
 * Deletes every saved read of the account, credentials included, so a phone
 * handed to the next person carries nothing of the last one's. Queued writes
 * and uploads stay: they are work that still has to reach the server. Never throws.
 */
export async function forgetSaved(accountId: string): Promise<void> {
  for (const slot of [...written.keys()]) if (slot.startsWith(`${accountId}\u0000`)) written.delete(slot);
  try {
    const saved = await snapshotTimes(accountId);
    await removeSnapshotKeys(accountId, saved.map((m) => m.key).filter((key) => !isOwn(key)));
  } catch {
    // A device that cannot open its store has nothing saved to delete.
  }
}
