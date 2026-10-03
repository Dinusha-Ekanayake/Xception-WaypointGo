import { useEffect, useState } from "react";
import { ApiError } from "../api/problem.ts";
import { keep, kept } from "./snapshots.ts";

// Reads that survive an offline reload (issue #201). A resilient role's reads
// pass through here: each answer is kept on the device, per account, and when
// the server cannot be reached the kept answer stands in for it. Only an
// outage falls back. A refusal (403, 404, a rule) is the server's answer and
// passes through, so kept data never shows what the server now refuses.
//
// What is shown from the device is never shown as live: `useKeptSince` gives
// the time of the oldest kept answer on screen, and the role's top bar says it.

export const KEPT_EVENT = "waypoint:kept-reads";

/** Read keys served from the device right now, with when each was saved. */
const showing = new Map<string, string>();

const changed = () => typeof window !== "undefined" && window.dispatchEvent(new Event(KEPT_EVENT));

/**
 * Any answer from the server shows it is reachable again, so the marks of
 * every kept read go, not only this one: a screen left while it showed kept
 * data would otherwise keep the top bar saying so. Screens still open read
 * again on their next refresh.
 */
function live(): void {
  if (showing.size === 0) return;
  showing.clear();
  changed();
}

function fromDevice(key: string, savedAt: string): void {
  if (showing.get(key) === savedAt) return;
  showing.set(key, savedAt);
  changed();
}

/** True for a failure the kept answer may stand in for: no network, a timeout, a server error. */
export function isOutage(error: unknown): boolean {
  if (error instanceof DOMException && error.name === "AbortError") return false;
  if (error instanceof ApiError) return error.isRetryable;
  return true;
}

/** Reads from the server and keeps the answer; in an outage, answers with what was kept. */
export async function readThrough<T>(accountId: string, key: string, read: () => Promise<T>): Promise<T> {
  try {
    const value = await read();
    live();
    void keep(accountId, key, value);
    return value;
  } catch (error) {
    if (!isOutage(error)) throw error;
    const snapshot = await kept<T>(accountId, key);
    if (!snapshot) throw error;
    fromDevice(key, snapshot.savedAt);
    return snapshot.value;
  }
}

/** When the oldest answer on screen from the device was saved; null while everything is live. */
export function keptSince(): string | null {
  let oldest: string | null = null;
  for (const at of showing.values()) if (oldest === null || at < oldest) oldest = at;
  return oldest;
}

export function useKeptSince(): string | null {
  const [since, setSince] = useState<string | null>(null);
  useEffect(() => {
    const update = () => setSince(keptSince());
    update();
    window.addEventListener(KEPT_EVENT, update);
    return () => window.removeEventListener(KEPT_EVENT, update);
  }, []);
  return since;
}
