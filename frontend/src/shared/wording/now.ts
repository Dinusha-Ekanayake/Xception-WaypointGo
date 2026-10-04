// The business clock (issue #231): the device's time plus the demo clock's
// offset, so "today", the 16:00 cutoff, a window or a departure countdown on
// screen agrees with the server when demo mode moves the clock. With demo mode
// off the offset is 0 and this is the device's own time.
//
// Elapsed-time measures (a hold-to-confirm, a sign-in lockout, a poll interval)
// stay on Date.now(): they measure the device, not the business day.

const KEY = "waypoint.clock-offset-ms";
const listeners = new Set<() => void>();
let offsetMs = kept();

/** The last offset this device heard, so an offline phone keeps the demo day. */
function kept(): number {
  try {
    const value = Number(globalThis.localStorage?.getItem(KEY) ?? 0);
    return Number.isFinite(value) ? value : 0;
  } catch {
    return 0;
  }
}

/** Sets the demo clock's offset from the server's DemoView; 0 when demo mode is off. */
export function setClockOffset(seconds: number): void {
  const next = Math.round(seconds * 1000);
  if (next === offsetMs) return;
  offsetMs = next;
  try {
    if (next === 0) globalThis.localStorage?.removeItem(KEY);
    else globalThis.localStorage?.setItem(KEY, String(next));
  } catch {
    // Private mode or blocked storage: the offset still holds for this page.
  }
  for (const listener of listeners) listener();
}

/** The offset now in force, in milliseconds. */
export function clockOffsetMs(): number {
  return offsetMs;
}

/** Calls `listener` whenever the offset changes; returns the unsubscribe. */
export function onClockOffset(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Business time now, in epoch milliseconds. */
export function nowMs(): number {
  return Date.now() + offsetMs;
}

/** Business time now. */
export function businessNow(): Date {
  return new Date(nowMs());
}
