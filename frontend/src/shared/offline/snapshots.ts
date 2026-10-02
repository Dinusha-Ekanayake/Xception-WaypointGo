import { getSnapshot, putSnapshot, type Snapshot } from "./store.ts";

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
