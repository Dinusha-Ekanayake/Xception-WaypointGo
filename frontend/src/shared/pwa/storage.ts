"use client";

// A browser may clear a site's storage when the device runs short of space,
// and for a role that queues writes that would lose recorded work (issue
// #201). Asking for persistent storage stops that where the browser agrees:
// Chrome grants it to an installed app, Firefox asks the person, Safari keeps
// the storage of a home screen app. The answer is kept for the settings row.

/** Whether this device keeps offline work through low space; null where the browser cannot say. */
export type Persistence = boolean | null;

const KEY = "waypoint.persisted";

/** Asks once per sign-in; harmless when already granted. Never throws. */
export async function keepStorage(): Promise<Persistence> {
  let result: Persistence = null;
  try {
    const storage = navigator.storage;
    if (storage?.persist) result = (await storage.persisted()) || (await storage.persist());
  } catch {
    result = null;
  }
  try {
    window.localStorage.setItem(KEY, String(result));
  } catch {
    // The answer is only shown; without storage it is asked again next time.
  }
  return result;
}

/** The last answer, for settings. */
export function lastPersistence(): Persistence {
  try {
    const saved = window.localStorage.getItem(KEY);
    return saved === "true" ? true : saved === "false" ? false : null;
  } catch {
    return null;
  }
}
