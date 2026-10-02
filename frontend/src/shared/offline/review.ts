import type { Command } from "@shared/api/commands";
import { SyncCommandKind, type DiscardOperation, type ResolveOperation } from "../domain/sync.ts";
import type { StoredEntry } from "./store.ts";

// What a person decides about a write the server held (decision D-O: only its
// owner, on their own device). Pure, so the order and the ids can be tested
// without a browser. The queue stores and sends what these return.

const newId = (): string => globalThis.crypto?.randomUUID?.() ?? `cmd-${Date.now()}-${Math.random()}`;

/** The order the server applies a device's writes in: when each was recorded. */
export function inRecordedOrder<T extends Pick<StoredEntry, "enqueuedAt" | "commandId">>(entries: T[]): T[] {
  return [...entries].sort(
    (a, b) => (Date.parse(a.enqueuedAt) || 0) - (Date.parse(b.enqueuedAt) || 0) || a.commandId.localeCompare(b.commandId),
  );
}

/**
 * Drop a held write. When the server knows its version, the server records the
 * drop and the reason too; a write held before versions were kept is only
 * dropped here.
 */
export function discardCommand(entry: StoredEntry, reason: string, now: Date): Command<DiscardOperation> | null {
  if (entry.serverVersion === undefined) return null;
  return {
    commandId: newId(),
    kind: SyncCommandKind.discard,
    expectedVersion: entry.serverVersion,
    payload: { operationId: entry.commandId, reason },
    clientRecordedAt: now.toISOString(),
  };
}

export type Redo = {
  /** The same write on the version the device now sees, under a new id. */
  redo: Command;
  /** Settles the held write as replaced by the redo; null when the server's version is unknown. */
  resolve: Command<ResolveOperation> | null;
};

/**
 * Redo a held conflict on the current version. The redo is recorded first and
 * the resolve one millisecond later, so the server applies them in that order
 * (it orders a device's writes by recording time). Nothing is merged: the redo
 * is checked against the current record like any new write.
 */
export function redoCommands(
  entry: StoredEntry,
  expectedVersion: number | null,
  actingUserId: string | undefined,
  now: Date,
): Redo {
  const original = entry.payload as Command;
  const actor = actingUserId ?? original.actingUserId;
  const redo: Command = {
    commandId: newId(),
    kind: original.kind,
    expectedVersion,
    payload: original.payload,
    clientRecordedAt: now.toISOString(),
    ...(actor ? { actingUserId: actor } : {}),
  };
  const resolve: Command<ResolveOperation> | null =
    entry.serverVersion === undefined
      ? null
      : {
          commandId: newId(),
          kind: SyncCommandKind.resolve,
          expectedVersion: entry.serverVersion,
          payload: { operationId: entry.commandId, replacedBy: redo.commandId },
          clientRecordedAt: new Date(now.getTime() + 1).toISOString(),
        };
  return { redo, resolve };
}
