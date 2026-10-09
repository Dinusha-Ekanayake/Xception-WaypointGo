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

/** The server takes at most this many points in one batch (R-EXE-18). */
export const MAX_POINTS = 100;

type PositionCommand = { payload: { vehicleId: string; tripId?: string; points: unknown[] } };

/**
 * Roadmap F-08: with no signal, a new batch of fixes is added to the last batch
 * still waiting, instead of queuing one command every 10 s, so the backlog sent
 * when the signal returns is a few large batches, each one receipt and one audit
 * row. Only a batch that was never tried is extended: one that may have reached
 * the server keeps its exact payload. Returns the extended entry, or null to
 * queue the new batch on its own.
 */
export function extendBacklog(entries: StoredEntry[], next: { kind: string; payload: unknown }): StoredEntry | null {
  if (next.kind !== POSITION_KIND) return null;
  const incoming = (next.payload as PositionCommand).payload;
  const last = inRecordedOrder(entries.filter(isPosition)).at(-1);
  if (!last || last.attempts > 0 || last.needsReview) return null;
  const held = (last.payload as PositionCommand).payload;
  if (held.vehicleId !== incoming.vehicleId || held.tripId !== incoming.tripId) return null;
  if (held.points.length + incoming.points.length > MAX_POINTS) return null;
  const command = last.payload as Record<string, unknown>;
  return { ...last, payload: { ...command, payload: { ...held, points: [...held.points, ...incoming.points] } } };
}
