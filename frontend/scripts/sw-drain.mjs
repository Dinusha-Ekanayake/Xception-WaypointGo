// The service worker's own drain, for a Background Sync that fires with no page
// open (issue #28, A-39). scripts/build-sw.mjs inlines this file into
// public/sw.js; tests/sw-drain.test.ts imports it. It follows the page's queue
// (src/shared/offline/queue.ts) rule for rule:
//
//   - the same device id, which the page keeps in the snapshot store because a
//     worker cannot read localStorage; with none kept, it waits for a page;
//   - recorded order, at most 100 per batch;
//   - applied, discarded or resolved leaves the device; a conflict or refusal
//     is held for a person with the server's version; anything else stops;
//   - a 401 keeps everything for the next sign-in.
//
// A queue holding loader work waits for a page: a shared loader device replays
// its offline operator switches first (setBeforeDrain), and that log is in
// localStorage. Sending the checks before the switches would refuse them.

export const DEVICE_KEY = "waypoint.deviceId";
const BATCH = 100;

export function outcomeOf(status) {
  if (status === "APPLIED") return "sent";
  if (status === "DISCARDED" || status === "RESOLVED") return "dropped";
  if (status === "CONFLICT" || status === "REJECTED") return "review";
  return "wait";
}

/** As recordedOrder in src/shared/offline/review.ts: the device's write order, else its recording time. */
function recordedOrder(entry) {
  return entry.order ?? (Date.parse(entry.enqueuedAt) || 0) * 1000;
}

/**
 * One account's queue. `io` reaches storage and the network:
 * entries(), deviceId(), post(body) -> {status, json}, put(entry), remove(id).
 * Answers "empty", "sent", "kept" (signed out), "needs-page" or "no-device";
 * throws when the server could not be reached, so the browser tries again.
 */
export async function drainAccount(io) {
  const entries = await io.entries();
  const ready = entries
    .filter((e) => !e.needsReview)
    .sort((a, b) => recordedOrder(a) - recordedOrder(b) || a.commandId.localeCompare(b.commandId))
    .slice(0, BATCH);
  if (ready.length === 0) return "empty";
  if (ready.some((e) => typeof e.kind === "string" && e.kind.startsWith("loading:"))) return "needs-page";
  const deviceId = await io.deviceId();
  if (!deviceId) return "no-device";

  const answer = await io.post({
    deviceId,
    operations: ready.map((e, i) => ({ sequence: recordedOrder(e) || i, command: e.payload })),
  });
  if (answer.status === 401) return "kept";
  if (answer.status < 200 || answer.status >= 300) throw new Error("sync answered " + answer.status);

  const byId = new Map(ready.map((e) => [e.commandId, e]));
  for (const result of answer.json.results || []) {
    const entry = byId.get(result.operationId);
    if (!entry) continue;
    const action = outcomeOf(result.status);
    if (action === "sent" || action === "dropped") {
      await io.remove(entry.commandId);
    } else if (action === "review") {
      await io.put({
        ...entry,
        needsReview: true,
        lastError: result.detail || result.problemCode || result.status,
        ...(result.problemCode ? { problemCode: result.problemCode } : {}),
        ...(typeof result.rowVersion === "number" ? { serverVersion: result.rowVersion } : {}),
      });
    } else {
      await io.put({ ...entry, attempts: (entry.attempts || 0) + 1, lastError: result.detail || "Not applied yet" });
      break;
    }
  }
  return "sent";
}

// ---- IndexedDB, as src/shared/offline/store.ts lays it out -------------------

function request(r) {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

/** Every account's queue on this browser: the waypoint-<account> databases. */
export async function drainWithoutPage(idb, fetchFn) {
  const names = (await idb.databases()).map((d) => d.name).filter((n) => typeof n === "string" && n.startsWith("waypoint-"));
  let waiting = false;
  for (const name of names) {
    // Opened without a version: the worker never creates or upgrades a store.
    const db = await request(idb.open(name));
    try {
      if (!db.objectStoreNames.contains("outbox") || !db.objectStoreNames.contains("snapshots")) continue;
      const store = (s, mode) => db.transaction(s, mode).objectStore(s);
      const result = await drainAccount({
        entries: () => request(store("outbox", "readonly").getAll()),
        deviceId: async () => (await request(store("snapshots", "readonly").get(DEVICE_KEY)))?.value ?? null,
        post: async (body) => {
          const res = await fetchFn("/api/sync", {
            method: "POST",
            credentials: "same-origin",
            headers: { "content-type": "application/json", accept: "application/json, application/problem+json", "x-correlation-id": crypto.randomUUID() },
            body: JSON.stringify(body),
          });
          return { status: res.status, json: res.ok ? await res.json() : null };
        },
        put: (entry) => request(store("outbox", "readwrite").put(entry)),
        remove: (id) => request(store("outbox", "readwrite").delete(id)),
      });
      if (result === "needs-page" || result === "no-device") waiting = true;
    } finally {
      db.close();
    }
  }
  // Rejecting keeps the sync registered, so the browser tries again; the queue
  // also drains when the app is next opened.
  if (waiting) throw new Error("some queues wait for the app to open");
}
