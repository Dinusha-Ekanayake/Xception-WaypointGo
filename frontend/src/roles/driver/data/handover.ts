import type { StoreAnswerWaiverReason } from "@shared/domain/types";
import type { Stop } from "./run.ts";

// Issue #21, store-led handover: the rules the stop screen follows, kept apart
// from the screens so they are tested without a browser.

/**
 * Where a stop stands in the handover: at the door, handed over and waiting for
 * the store, the store's report in, accepted with the PIN, or left before the
 * store answered.
 */
export type HandoverPhase = "arrived" | "waiting" | "answered" | "accepted" | "left";

/** The reasons the server keeps, in the words the driver reads. */
export const LEAVE_REASONS: Array<{ reason: StoreAnswerWaiverReason; label: string }> = [
  { reason: "store_absent", label: "Store manager not available" },
  { reason: "no_signal", label: "No signal to see the store's report" },
  { reason: "disagree", label: "I disagree with the store's report" },
];

export function leftBecause(reason: StoreAnswerWaiverReason | null): string | null {
  return LEAVE_REASONS.find((r) => r.reason === reason)?.label ?? null;
}

/** Where the stop stands; the store's answer, once in, decides the rest. Leaving is final. */
export function handoverPhase(stop: Pick<Stop, "outcome" | "storeAnswerWaived">, answered: boolean, accepted: boolean): HandoverPhase {
  if (stop.outcome === "ARRIVED") return "arrived";
  if (stop.storeAnswerWaived) return "left";
  if (accepted) return "accepted";
  return answered ? "answered" : "waiting";
}
