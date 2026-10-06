import type { Command } from "../../../shared/api/commands.ts";
import type { StoredEntry } from "../../../shared/offline/store.ts";

// Roadmap F-06: a driver's write held because the stop moved on (another device,
// a revised plan) can be redone on the stop's current version, not only
// discarded. Pure: the held write, the stop's version on the server and the
// writes still waiting go in, the version to redo against comes out.

/** The stop a held write is about; null for a write with no stop, such as a vehicle status. */
export function deliveryOf(entry: Pick<StoredEntry, "payload">): string | null {
  const payload = (entry.payload as Command | null)?.payload as { deliveryId?: unknown } | null | undefined;
  return typeof payload?.deliveryId === "string" ? payload.deliveryId : null;
}

/**
 * The version a redo is checked against: the stop's version now, plus one for
 * each write on the same stop still waiting on the phone, since each of those
 * moves the stop on before the redo is sent.
 */
export function redoVersion(deliveryId: string, current: number, waiting: Array<Pick<StoredEntry, "kind" | "payload">>): number {
  return current + waiting.filter((e) => e.kind.startsWith("delivery:") && deliveryOf(e) === deliveryId).length;
}
