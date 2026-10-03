import type { OrderView, StatusChangeView } from "@shared/domain/types";

// "09 Order deferred": where an order moved from and to, and why. The plan
// records its reason on the order's timeline as "deferred by plan <id> (<rule>):
// <reason>" (R-PLN-19: the binding constraint, never a generic message); the
// cutoff job records a code. A deferred order is placed first on the next run
// (R-PLN-21).

export type Deferral = {
  /** The day it was due before it moved, or the day asked for when that is all that is known. */
  was: { label: "Was" | "Asked for"; date: string };
  now: string;
  why: string;
  /** "the dispatcher", "the plan", "the cutoff". */
  by: string;
  at: string | null;
};

const CODES: Record<string, string> = {
  stock_unresolved: "The warehouse had not confirmed the stock by the cutoff",
};

/** The reason in words: the part after the rule, a known code spelled out, the first letter capital. */
export function whyOf(reason: string | null): string {
  if (!reason?.trim()) return "No reason was recorded";
  const marker = reason.indexOf("): ");
  const text = (marker >= 0 ? reason.slice(marker + 3) : reason).trim();
  const known = CODES[text];
  if (known) return known;
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * The latest deferral of an order. `firstDate` is the day it was first due: the
 * requested day, or the day that rolled to when the requested one was closed;
 * null when that could not be read. Only a first deferral moved from that day,
 * so a later one says what was asked for instead.
 */
export function deferralOf(order: OrderView, timeline: StatusChangeView[], firstDate: string | null): Deferral {
  const entry = [...timeline].reverse().find((h) => h.to === "DEFERRED") ?? null;
  const reason = entry?.reason ?? null;
  const known = order.deferralCount <= 1 && firstDate !== null;
  return {
    was: known ? { label: "Was", date: firstDate } : { label: "Asked for", date: order.requestedDate },
    now: order.deliveryDate,
    why: whyOf(reason),
    by: reason && CODES[reason.trim()] ? "the cutoff" : entry?.actorId ? "the dispatcher" : "the plan",
    at: entry?.at ?? null,
  };
}

// Which deferrals this manager has read ("Got it"). A per-device convenience:
// nothing here reaches Waypoint, and with storage blocked a deferral shows again.

const key = (outletId: string) => `waypoint.store.deferrals-read.${outletId}`;

function readAll(outletId: string): Record<string, number> {
  try {
    const raw = window.localStorage.getItem(key(outletId));
    const parsed = raw ? (JSON.parse(raw) as unknown) : null;
    return parsed && typeof parsed === "object" ? (parsed as Record<string, number>) : {};
  } catch {
    return {};
  }
}

/** Read already: this order's latest deferral, counted by `deferralCount`, so a second deferral shows again. */
export function deferralRead(outletId: string, order: OrderView): boolean {
  return readAll(outletId)[order.orderId] === order.deferralCount;
}

export function markDeferralRead(outletId: string, order: OrderView): void {
  try {
    window.localStorage.setItem(key(outletId), JSON.stringify({ ...readAll(outletId), [order.orderId]: order.deferralCount }));
  } catch {
    // Storage is unavailable: the notice shows again, which is safe.
  }
}
