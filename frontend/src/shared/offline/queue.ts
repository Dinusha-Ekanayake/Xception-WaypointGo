import { request } from "@shared/api/client";
import type { Command } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import type { OperationView, SubmitBatch, SyncAck } from "@shared/domain/sync";
import { outcomeAction } from "./outcome.ts";
import { discardCommand, inRecordedOrder, nextOrder, recordedOrder, redoCommands } from "./review.ts";
import type { RedoBasis } from "./resolvers.ts";
import { all, put, putSnapshot, remove, type StoredEntry } from "./store.ts";
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
    await keepCommand(accountId, command);
    queued();
    return { durable: true };
  } catch (error) {
    // Private windows, cleared site data and blocked storage all land here. The
    // user must be told, because the promise of a durable save was not kept.
    return { durable: false, reason: String(error) };
  }
}

/** Tell the sync engine there is something to send, now and, where the browser can, once the connection is back. */
function queued(): void {
  globalThis.dispatchEvent?.(new Event(QUEUED_EVENT));
  requestBackgroundSync();
}

/** The tag the service worker answers by asking an open page to drain (scripts/build-sw.mjs). */
export const DRAIN_TAG = "waypoint-drain";
/** The message the service worker posts to a page. */
export const DRAIN_MESSAGE = "waypoint:drain";

/**
 * Background Sync, where the browser has it (Chromium). The browser fires the
 * tag once a connection is back, even with the page in the background. It is a
 * hint only: the online event, focus and the interval drain the queue anyway,
 * and a browser without it loses nothing.
 */
function requestBackgroundSync(): void {
  try {
    if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
    if (typeof ServiceWorkerRegistration === "undefined" || !("sync" in ServiceWorkerRegistration.prototype)) return;
    void navigator.serviceWorker.ready
      .then((registration) => (registration as ServiceWorkerRegistration & { sync: { register(tag: string): Promise<void> } }).sync.register(DRAIN_TAG))
      .catch(() => undefined);
  } catch {
    // No service worker, or a browser that refuses: the other triggers still drain.
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
export function deviceId(): string {
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

/** Where the service worker finds this browser's device id (scripts/sw-drain.mjs). */
const WORKER_DEVICE_KEY = "waypoint.deviceId";
const devicesKept = new Set<string>();

/**
 * Keep the device id where the service worker can read it, once per account per
 * page load, so a Background Sync with no page open sends under the same device.
 */
async function keepDeviceForWorker(accountId: string, id: string): Promise<void> {
  if (devicesKept.has(accountId)) return;
  try {
    await putSnapshot(accountId, WORKER_DEVICE_KEY, id);
    devicesKept.add(accountId);
  } catch {
    // Blocked storage: the worker waits for a page instead.
  }
}

async function drainOnce(accountId: string): Promise<DrainReport> {
  await beforeDrain.get(accountId)?.();
  const entries = await all(accountId);
  // In the order they were recorded, so a batch never sends a later write
  // without an earlier one (storage returns them by id).
  const ready = inRecordedOrder(entries.filter((e) => !e.needsReview)).slice(0, BATCH);
  let sent = 0;
  let heldForReview = entries.length - entries.filter((e) => !e.needsReview).length;
  if (ready.length === 0) return { sent, heldForReview, remaining: entries.length };

  const device = deviceId();
  await keepDeviceForWorker(accountId, device);
  const batch: SubmitBatch = {
    deviceId: device,
    // The recorded order is the sequence: stable across retries, strictly
    // increasing, and in the order the person did things.
    operations: ready.map((e, i) => ({ sequence: recordedOrder(e) || i, command: e.payload as Command })),
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
    const action = outcomeAction(result.status);
    if (action === "sent") {
      await remove(accountId, entry.commandId);
      sent++;
    } else if (action === "dropped") {
      await remove(accountId, entry.commandId);
    } else if (action === "review") {
      await put(accountId, {
        ...entry,
        needsReview: true,
        lastError: result.detail ?? result.problemCode ?? result.status,
        ...(result.problemCode ? { problemCode: result.problemCode } : {}),
        ...(typeof result.rowVersion === "number" ? { serverVersion: result.rowVersion } : {}),
      });
      heldForReview++;
    } else {
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

/**
 * Drop a held write, with the person's reason. Only a person does this, never
 * the engine. The server records the drop as well when it knows the write's
 * version (sync:Discard); the drop is queued, so it works offline too.
 */
export async function discard(accountId: string, commandId: string, reason: string): Promise<void> {
  const stored = (await all(accountId)).find((e) => e.commandId === commandId);
  if (!stored) return;
  const entry = await withServerVersion(stored);
  const command = discardCommand(entry, reason, new Date());
  if (command) await keepCommand(accountId, command);
  await remove(accountId, commandId);
  if (command) queued();
}

/**
 * Redo a held conflict on the version the device now sees: the same write under
 * a new id, then a sync:Resolve naming it, so the server keeps the trail. A
 * resend of the held write itself could never work: the server answers a
 * replayed id with the answer it gave the first time.
 */
export async function redo(accountId: string, stored: StoredEntry, basis: RedoBasis): Promise<void> {
  const entry = await withServerVersion(stored, true);
  const { redo: again, resolve } = redoCommands(entry, basis.expectedVersion, basis.actingUserId, new Date());
  await keepCommand(accountId, again);
  if (resolve) await keepCommand(accountId, resolve);
  await remove(accountId, entry.commandId);
  queued();
}

/**
 * A write held before sync answers carried the operation's version has none on
 * the device. Ask the server for it (GET /api/sync/{id}, the owner's own rows
 * only), so the discard or redo is recorded there too. With no connection the
 * entry is used as it is: a discard then drops it here only, and a redo goes
 * without the resolve. A redo of something the server holds as other than a
 * conflict is refused, because it would be refused again.
 */
async function withServerVersion(entry: StoredEntry, forRedo = false): Promise<StoredEntry> {
  if (entry.serverVersion !== undefined) return entry;
  let view: OperationView;
  try {
    view = await request<OperationView>(`/api/sync/${encodeURIComponent(entry.commandId)}`);
  } catch {
    return entry;
  }
  if (forRedo && view.status !== "CONFLICT") {
    throw new Error("Only a change held because the record moved on can be redone. Discard this one.");
  }
  return { ...entry, serverVersion: view.rowVersion, ...(view.problemCode ? { problemCode: view.problemCode } : {}) };
}

/** The last order given on this page, so two writes queued at once never share one. */
let lastOrder = 0;

async function keepCommand(accountId: string, command: Command): Promise<void> {
  const order = nextOrder(command.clientRecordedAt, await all(accountId), lastOrder);
  lastOrder = order;
  await put(accountId, {
    commandId: command.commandId,
    kind: command.kind,
    payload: command,
    enqueuedAt: command.clientRecordedAt,
    order,
    attempts: 0,
  });
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
