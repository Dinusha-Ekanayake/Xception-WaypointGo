import { send, type Command } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import { all, put, remove, type StoredEntry } from "./store.ts";
import { queuesWrites, type Role } from "./tiers.ts";

// The write queue.
//
// Two rules decide everything here:
//
//   1. "Saved" means durable on this device. The UI acknowledges only after the
//      local write resolves, never when the request is merely sent.
//   2. A conflict is never auto-merged. It is held for a person to look at
//      against the current server record.
//
// Draining uses exponential backoff with jitter. Without jitter a regional
// reconnect arrives in lockstep and the recovery becomes the outage.

export const QUEUED_EVENT = "waypoint:queued";

export type EnqueueResult = { durable: true } | { durable: false; reason: string };

export async function enqueue(
  accountId: string,
  role: Role,
  command: Command,
): Promise<EnqueueResult> {
  if (!queuesWrites(role)) {
    return { durable: false, reason: "This role works online; the write was not queued." };
  }
  try {
    await put(accountId, {
      commandId: command.commandId,
      kind: command.kind,
      payload: command,
      enqueuedAt: command.clientRecordedAt,
      attempts: 0,
    });
    // Tell the sync engine there is something to send.
    globalThis.dispatchEvent?.(new Event(QUEUED_EVENT));
    return { durable: true };
  } catch (error) {
    // Private windows, cleared site data and blocked storage all land here. The
    // user must be told, because the promise of a durable save was not kept.
    return { durable: false, reason: String(error) };
  }
}

export function backoffMs(attempt: number): number {
  const base = Math.min(1000 * 2 ** attempt, 60_000);
  const jitter = base * 0.5 * (Math.random() * 2 - 1);
  return Math.max(250, Math.round(base + jitter));
}

export type DrainReport = { sent: number; heldForReview: number; remaining: number };

const running = new Map<string, Promise<DrainReport>>();

/**
 * Sends what it can, once. Callers schedule it on reconnect and on visibility.
 * Concurrent calls for one account share a single pass, so two schedulers never
 * send the same write twice or out of order.
 */
export function drain(accountId: string): Promise<DrainReport> {
  const current = running.get(accountId);
  if (current) return current;
  const pass = drainOnce(accountId).finally(() => running.delete(accountId));
  running.set(accountId, pass);
  return pass;
}

async function drainOnce(accountId: string): Promise<DrainReport> {
  const entries = await all(accountId);
  let sent = 0;
  let heldForReview = 0;

  for (const entry of entries) {
    if (entry.needsReview) {
      heldForReview++;
      continue;
    }
    try {
      await send(entry.payload as Command);
      await remove(accountId, entry.commandId);
      sent++;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        // The session expired. The write is still good: keep it, untouched,
        // until the person signs in again.
        break;
      }
      if (error instanceof ApiError && error.isVersionConflict) {
        await put(accountId, { ...entry, needsReview: true, lastError: error.message });
        heldForReview++;
        continue;
      }
      if (error instanceof ApiError && !error.isRetryable) {
        // The server rejected it on the rules. Retrying cannot help, and
        // discarding silently would lose the driver's work.
        await put(accountId, { ...entry, needsReview: true, lastError: error.message });
        heldForReview++;
        continue;
      }
      await put(accountId, {
        ...entry,
        attempts: entry.attempts + 1,
        lastError: String(error),
      });
      break; // Preserve submission order per account.
    }
  }

  const left = await all(accountId);
  return { sent, heldForReview, remaining: left.length };
}

/** Writes the server refused, waiting for a person to decide. */
export async function heldForReview(accountId: string): Promise<StoredEntry[]> {
  try {
    return (await all(accountId)).filter((e) => e.needsReview);
  } catch {
    return [];
  }
}

/** Drop a held write. Only a person does this, never the engine. */
export function discard(accountId: string, commandId: string): Promise<void> {
  return remove(accountId, commandId);
}

/** Put a held write back in line, for when the cause was fixed elsewhere. */
export async function retry(accountId: string, entry: StoredEntry): Promise<void> {
  await put(accountId, { ...entry, needsReview: false });
}

/** How many writes this account still has on the device, sent or not. */
export async function pendingCount(accountId: string): Promise<number> {
  try {
    return (await all(accountId)).length;
  } catch {
    return 0;
  }
}

export type { StoredEntry };
