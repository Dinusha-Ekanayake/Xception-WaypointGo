import { expect, test } from "@playwright/test";

// Issue #28, A-39: a Background Sync that fires with no page open. The real
// service worker (public/sw.js, with scripts/sw-drain.mjs inlined) drains a
// queue the page left in IndexedDB, under the device id the page kept.

const ACCOUNT = "driver-user";
const SESSION = { userId: ACCOUNT, displayName: "Nimal Perera", roles: ["driver"], scope: ["depot:KDY"] };

test("with no page to ask, the service worker sends the queue itself, in order, and clears what applied", async ({ context, page }) => {
  const sent: Array<{ deviceId: string; operations: Array<{ sequence: number; command: { commandId: string; kind: string } }> }> = [];
  await page.route("**/api/**", (route) =>
    new URL(route.request().url()).pathname === "/api/session" ? route.fulfill({ json: SESSION }) : route.fulfill({ status: 404, json: {} }));

  await page.goto("/");
  // Installed first: Playwright aborts a worker's own requests while any context route is set,
  // which would fail its precache. The shell registers it too; registering again is harmless.
  await page.evaluate(() => navigator.serviceWorker.register("/sw.js").then(() => navigator.serviceWorker.ready).then(() => undefined));
  const worker = context.serviceWorkers()[0] ?? (await context.waitForEvent("serviceworker"));

  // Now the worker's own /api/sync request, which only a context route sees.
  await context.route("**/api/sync", async (route) => {
    const body = route.request().postDataJSON() as (typeof sent)[number];
    sent.push(body);
    return route.fulfill({ json: { results: body.operations.map((o, i) => ({
      operationId: o.command.commandId, sequence: i + 1, status: "APPLIED", problemCode: null, detail: null, replayed: false, rowVersion: 2,
    })) } });
  });

  // What the page's queue leaves behind: two writes and the device id (src/shared/offline/store.ts layout).
  await page.evaluate(async (account) => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const r = indexedDB.open(`waypoint-${account}`, 2);
      r.onupgradeneeded = () => {
        r.result.createObjectStore("outbox", { keyPath: "commandId" });
        r.result.createObjectStore("snapshots", { keyPath: "key" });
        r.result.createObjectStore("uploads", { keyPath: "id" });
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
    const write = (id: string, at: string) => ({
      commandId: id, kind: "delivery:RecordArrival", enqueuedAt: at, attempts: 0,
      payload: { commandId: id, kind: "delivery:RecordArrival", expectedVersion: 1, payload: {}, clientRecordedAt: at },
    });
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(["outbox", "snapshots"], "readwrite");
      tx.objectStore("outbox").put(write("second", "2026-10-03T01:00:02.000Z"));
      tx.objectStore("outbox").put(write("first", "2026-10-03T01:00:01.000Z"));
      tx.objectStore("snapshots").put({ key: "waypoint.deviceId", value: "device-kept-by-page", savedAt: "2026-10-03T01:00:00.000Z" });
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
  }, ACCOUNT);

  // The worker's 'sync' handler takes this path when matchAll finds no window.
  await worker.evaluate(() => (self as unknown as { drainWithoutPage: (idb: IDBFactory, f: typeof fetch) => Promise<void> })
    .drainWithoutPage(self.indexedDB, self.fetch.bind(self)));

  expect(sent).toHaveLength(1);
  expect(sent[0]!.deviceId).toBe("device-kept-by-page");
  expect(sent[0]!.operations.map((o) => o.command.commandId)).toEqual(["first", "second"]);

  const left = await page.evaluate(async (account) => {
    const db = await new Promise<IDBDatabase>((resolve, reject) => {
      const r = indexedDB.open(`waypoint-${account}`);
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
    const all = await new Promise<unknown[]>((resolve) => {
      const r = db.transaction("outbox").objectStore("outbox").getAll();
      r.onsuccess = () => resolve(r.result);
    });
    db.close();
    return all.length;
  }, ACCOUNT);
  expect(left).toBe(0);
});
