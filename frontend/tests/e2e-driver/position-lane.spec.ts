import { expect, test } from "@playwright/test";
import { SESSION, serve, startTrip } from "./mocks.ts";

// Roadmap F-03: a long stretch with no signal leaves thousands of positions on
// the phone. A delivery write recorded after them still reaches the server
// first: positions have their own lane, behind the business writes.

test("a delivery write lands before a backlog of 2,000 queued positions", async ({ page, context }) => {
  const server = await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await server.goOffline(context);

  // 20 position batches of 100 fixes each, recorded an hour before the delivery write.
  // Wait for the app to create its queue first: opening it here before then would make an empty database.
  await expect.poll(() => page.evaluate(async (accountId) =>
    (await indexedDB.databases()).some((d) => d.name === `waypoint-${accountId}`), SESSION.userId), { timeout: 15_000 }).toBe(true);
  await page.evaluate(async (accountId) => {
    const db: IDBDatabase = await new Promise((resolve, reject) => {
      const r = indexedDB.open(`waypoint-${accountId}`);
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
    if (!db.objectStoreNames.contains("outbox")) throw new Error("the app's queue has no outbox yet");
    const hourAgo = Date.now() - 3_600_000;
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction("outbox", "readwrite");
      for (let b = 0; b < 20; b++) {
        const points = Array.from({ length: 100 }, (_, i) => ({
          recordedAt: new Date(hourAgo + (b * 100 + i) * 1000).toISOString(),
          latitude: "7.290000", longitude: "80.630000", accuracyM: "8",
        }));
        tx.objectStore("outbox").put({
          commandId: `position-${b}`, kind: "delivery:RecordPositions",
          payload: { commandId: `position-${b}`, kind: "delivery:RecordPositions", expectedVersion: null,
            clientRecordedAt: new Date(hourAgo + b * 100_000).toISOString(), payload: { vehicleId: "VEH043", points } },
          enqueuedAt: new Date(hourAgo + b * 100_000).toISOString(), order: (hourAgo + b * 100_000) * 1000, attempts: 0,
        });
      }
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
  }, SESSION.userId);

  await startTrip(page);
  // The start is on the phone, behind the backlog, before the signal returns.
  await expect.poll(() => page.evaluate(async (accountId) => {
    const db: IDBDatabase = await new Promise((resolve, reject) => {
      const r = indexedDB.open(`waypoint-${accountId}`);
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
    const all: Array<{ kind: string }> = await new Promise((resolve, reject) => {
      const r = db.transaction("outbox", "readonly").objectStore("outbox").getAll();
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
    db.close();
    return all.some((e) => e.kind === "delivery:Start");
  }, SESSION.userId), { timeout: 15_000 }).toBe(true);
  await server.goOnline(context);

  // Both arrive: the delivery write, then the whole backlog after it.
  await expect.poll(() => server.commands.filter((c) => c.kind === "delivery:RecordPositions")
    .reduce((n, c) => n + ((c.payload as { points?: unknown[] }).points?.length ?? 0), 0), { timeout: 30_000 }).toBeGreaterThanOrEqual(2000);
  const kinds = server.commands.map((c) => c.kind);
  const start = kinds.indexOf("delivery:Start");
  const firstPosition = kinds.indexOf("delivery:RecordPositions");
  expect(start).toBeGreaterThanOrEqual(0);
  expect(start < firstPosition, `the start went before every position: ${kinds.slice(0, 5)}`).toBe(true);
});
