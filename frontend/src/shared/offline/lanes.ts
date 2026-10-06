import type { StoredEntry } from "./store.ts";
import { inRecordedOrder, readyToSend } from "./review.ts";

// Two lanes in one queue (roadmap F-03).
//
// A driver's phone records a position every few seconds, so after a long stretch
// with no signal it holds thousands of them. Sent in recorded order they would
// go first and a delivery, receipt or issue recorded later would wait behind
// them for many passes. Positions are append-only and independent of every
// other write (no expectedVersion, and the server neither orders them across
// batches nor refuses an older point), so they go after the business writes.
// Business writes keep their strict recorded order and wait for their uploads,
// exactly as before. scripts/sw-drain.mjs sends in the same order.

/** The position command (ExecutionCommandKind.recordPositions); the worker matches the same string. */
export const POSITION_KIND = "delivery:RecordPositions";

export function isPosition(entry: Pick<StoredEntry, "kind">): boolean {
  return entry.kind === POSITION_KIND;
}

/**
 * What a pass may send, in the order to send it: business writes in recorded
 * order up to the first one still waiting for an upload, then positions in
 * recorded order. Positions never wait for uploads, so a held-back photo does
 * not stop the map either.
 */
export function inSendOrder(entries: StoredEntry[], waitingUploads: Set<string>): StoredEntry[] {
  const business = readyToSend(inRecordedOrder(entries.filter((e) => !isPosition(e))), waitingUploads);
  const positions = inRecordedOrder(entries.filter(isPosition));
  return [...business, ...positions];
}
