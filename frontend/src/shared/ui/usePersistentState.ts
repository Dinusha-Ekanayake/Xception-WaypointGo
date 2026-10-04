"use client";

import { useCallback, useState } from "react";

/**
 * A screen's own choices (a tab, a filter, a search) kept while the person
 * moves around the app and back, for this browser tab only. Scoped to the
 * signed-in account, so a shared dock terminal never shows the last person's
 * filter. Storage that is blocked or full is ignored: the screen then simply
 * starts from its default, as before.
 */

let scope = "anon";

/** Called by the app shell when the signed-in account changes. */
export function setStateScope(userId: string | null): void {
  scope = userId ?? "anon";
}

export function storageKey(key: string, owner: string = scope): string {
  return `wp:${owner}:${key}`;
}

export function readKept<T>(key: string, initial: T, store: Pick<Storage, "getItem"> | null = safeStore()): T {
  try {
    const raw = store?.getItem(storageKey(key));
    return raw === null || raw === undefined ? initial : (JSON.parse(raw) as T);
  } catch {
    return initial;
  }
}

export function writeKept<T>(key: string, value: T, store: Pick<Storage, "setItem"> | null = safeStore()): void {
  try {
    store?.setItem(storageKey(key), JSON.stringify(value));
  } catch {
    // Blocked or full: the choice lasts only while the screen is open.
  }
}

function safeStore(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

export function usePersistentState<T>(key: string, initial: T): [T, (value: T | ((prev: T) => T)) => void] {
  // Role screens render only in the browser, after the session is read, so the
  // kept value is there on the first render with no flash of the default.
  const [value, setValue] = useState<T>(() => readKept(key, initial));
  const set = useCallback(
    (next: T | ((prev: T) => T)) =>
      setValue((prev) => {
        const resolved = typeof next === "function" ? (next as (p: T) => T)(prev) : next;
        writeKept(key, resolved);
        return resolved;
      }),
    [key],
  );
  return [value, set];
}
