// An unsent order, kept on this device so a stock check spread across a busy
// shift is not lost (Figma "03 Place order", "Save draft"). It is a convenience,
// never the record: nothing here has reached Waypoint until it is submitted.
// Storage can be missing or blocked, so every call is guarded and the screen
// works without it.

export type Draft = { quantities: Record<string, number>; savedAt: string };

const key = (outletId: string) => `waypoint.store.draft.${outletId}`;

export function loadDraft(outletId: string): Draft | null {
  try {
    const raw = window.localStorage.getItem(key(outletId));
    if (!raw) return null;
    const draft = JSON.parse(raw) as Draft;
    return draft && typeof draft.quantities === "object" && typeof draft.savedAt === "string" ? draft : null;
  } catch {
    return null;
  }
}

/** True when the draft was written; false when this device cannot keep it. */
export function saveDraft(outletId: string, quantities: Record<string, number>): boolean {
  const kept = Object.fromEntries(Object.entries(quantities).filter(([, n]) => n > 0));
  try {
    if (Object.keys(kept).length === 0) window.localStorage.removeItem(key(outletId));
    else window.localStorage.setItem(key(outletId), JSON.stringify({ quantities: kept, savedAt: new Date().toISOString() } satisfies Draft));
    return true;
  } catch {
    return false;
  }
}

export function clearDraft(outletId: string): void {
  try {
    window.localStorage.removeItem(key(outletId));
  } catch {
    // Nothing to clear if storage is unavailable.
  }
}
