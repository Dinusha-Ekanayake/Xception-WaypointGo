import { request } from "@shared/api/client";
import type { Command } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import type { SubmitBatch, SyncAck } from "@shared/domain/sync";
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
const beforeDrain = new Map<string, () => Promise<void>>();

/**
 * Work every pass must finish before it sends anything, such as the operator
 * switches a shared loader device made offline. A failure stops the pass; the
 * queue is kept and tried again. Returns the unregister function.
 */
export function setBeforeDrain(accountId: string, step: () => Promise<void>): () => void {
  beforeDrain.set(accountId, step);
  return () => {
    if (beforeDrain.get(accountId) === step) beforeDrain.delete(accountId);
  };
}

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

const DEVICE_KEY = "waypoint.deviceId";

/**
 * This browser's id, minted once. The server orders a device's operations by
 * it, so it outlives sign-in and sign-out. Blocked storage gets a fresh id per
 * page load, which only costs ordering across reloads, never a write.
 */
function deviceId(): string {
  try {
    const known = localStorage.getItem(DEVICE_KEY);
    if (known) return known;
    const minted = crypto.randomUUID();
    localStorage.setItem(DEVICE_KEY, minted);
    return minted;
  } catch {
    return crypto.randomUUID();
  }
}

/** The server takes at most this many per batch (SubmitBatchHandler.MAX_BATCH). */
const BATCH = 100;

async function drainOnce(accountId: string): Promise<DrainReport> {
  await beforeDrain.get(accountId)?.();
  const entries = await all(accountId);
  const ready = entries.filter((e) => !e.needsReview).slice(0, BATCH);
  let sent = 0;
  let heldForReview = entries.length - entries.filter((e) => !e.needsReview).length;
  if (ready.length === 0) return { sent, heldForReview, remaining: entries.length };

  const batch: SubmitBatch = {
    deviceId: deviceId(),
    // The recording time is the sequence: stable across retries, and in the
    // order the person did things.
    operations: ready.map((e, i) => ({ sequence: Date.parse(e.enqueuedAt) || i, command: e.payload as Command })),
  };

  let ack: SyncAck;
  try {
    ack = await request<SyncAck>("/api/sync", { method: "POST", body: batch });
  } catch (error) {
    // 401: the session expired and the writes are still good; keep them until
    // the person signs in again. 429 and outages: try later. Either way nothing
    // is marked for review, because nothing was decided.
    const first = ready[0]!;
    if (!(error instanceof ApiError && error.status === 401)) {
      await put(accountId, { ...first, attempts: first.attempts + 1, lastError: String(error) });
    }
    return { sent, heldForReview, remaining: entries.length };
  }

  const byId = new Map(ready.map((e) => [e.commandId, e]));
  for (const result of ack.results) {
    const entry = byId.get(result.operationId);
    if (!entry) continue;
    if (result.status === "APPLIED") {
      // Only the server's confirmation lets a write leave the device.
      await remove(accountId, entry.commandId);
      sent++;
    } else if (result.status === "CONFLICT" || result.status === "REJECTED") {
      // A conflict is never auto-merged, and a refusal cannot be fixed by
      // resending. Discarding silently would lose the person's work.
      await put(accountId, { ...entry, needsReview: true, lastError: result.detail ?? result.problemCode ?? result.status });
      heldForReview++;
    } else {
      // Recorded but not decided. The server stopped here to keep the order.
      await put(accountId, { ...entry, attempts: entry.attempts + 1, lastError: result.detail ?? "Not applied yet" });
      break;
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

/**
 * Every write still on this device, in the order it was recorded. A full-tier
 * screen applies these to what the server last said, so it shows the work as
 * the person left it.
 */
export async function pendingEntries(accountId: string): Promise<StoredEntry[]> {
  try {
    return await all(accountId);
  } catch {
    return [];
  }
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
