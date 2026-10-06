import { getSnapshot, putSnapshot, removeSnapshots, type Snapshot } from "./store.ts";

// A full-tier role's working set, kept on the device each time it is read so
// the screens still have it when the server cannot be reached. A snapshot is
// what the server said last, never an authority: the screen shows its age.

/** Never throws: a device that cannot store still works online. */
export async function keep<T>(accountId: string, key: string, value: T): Promise<boolean> {
  try {
    await putSnapshot(accountId, key, value);
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

/**
 * Kept reads that are credentials, such as a vehicle's Fuel Pass QR (FF-06b),
 * live under this prefix so signing out can delete them at once: a phone handed
 * to the next driver must not still carry the last one's pass.
 */
export const CREDENTIAL_PREFIX = "credential:";

/** Deletes every credential kept for the account. Never throws: sign-out goes on. */
export async function forgetCredentials(accountId: string): Promise<void> {
  try {
    await removeSnapshots(accountId, CREDENTIAL_PREFIX);
  } catch {
    // A device that cannot open its store has nothing kept to delete.
  }
}
